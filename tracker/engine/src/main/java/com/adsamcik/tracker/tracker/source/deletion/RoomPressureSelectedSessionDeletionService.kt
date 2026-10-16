package com.adsamcik.tracker.tracker.source.deletion

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.PressureFactLineageProof
import com.adsamcik.tracker.shared.base.database.PressureFactProofAuthenticator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryLockedDays
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.MAX_PRESSURE_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletion
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact, source-local selected-session deletion for the new-v28 Pressure fact writer. */
@Singleton
@Suppress("LargeClass", "TooManyFunctions")
internal class RoomPressureSelectedSessionDeletionService internal constructor(
	private val database: AppDatabase,
	private val dirtyTracker: MetricDirtyTracker,
	private val wallTimeMsProvider: () -> Long,
	private val requestPressureDrain: () -> Unit,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog = ExecutableSourceLaneCatalog(),
	private val afterDayLocksAcquired: suspend () -> Unit = {},
	private val beforeMutation: suspend () -> Unit = {},
	private val afterFenceInstalled: suspend () -> Unit = {},
	private val afterFactBatchDeleted: suspend (deletedCount: Long) -> Unit = {},
	private val afterFactPageScanned: suspend (
		pageCount: Int,
		factCount: Long,
		residentLineageRevisionCount: Int,
	) -> Unit = { _, _, _ -> },
) : PressureSessionDeletion {
	@Inject
	constructor(
		database: AppDatabase,
		dirtyTracker: MetricDirtyTracker,
		sourcePipelineRecovery: Provider<SourcePipelineRecovery>,
		executableLaneCatalog: ExecutableSourceLaneCatalog,
	) : this(
		database = database,
		dirtyTracker = dirtyTracker,
		wallTimeMsProvider = System::currentTimeMillis,
		requestPressureDrain = { sourcePipelineRecovery.get().requestPressureSessionFactDrain() },
		executableLaneCatalog = executableLaneCatalog,
	)

	override suspend fun deleteSelectedSession(sessionSegmentId: Long): PressureSessionDeletionResult =
		deleteSelectedSessionInternal(sessionSegmentId, null)

	override suspend fun deleteSelectedSession(
		sessionSegmentId: Long,
		expectedCollectedDataEpoch: Long,
	): PressureSessionDeletionResult =
		deleteSelectedSessionInternal(sessionSegmentId, expectedCollectedDataEpoch)

	private suspend fun deleteSelectedSessionInternal(
		sessionSegmentId: Long,
		expectedEpoch: Long?,
	): PressureSessionDeletionResult {
		if (sessionSegmentId <= 0L) {
			return unsupported(PressureSessionDeletionUnsupportedReason.INVALID_SEGMENT_ID)
		}
		val deletedAtMs = wallTimeMsProvider().coerceAtLeast(0L)
		val result = try {
			deleteSelectedScope(sessionSegmentId, expectedEpoch, deletedAtMs)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: ConcurrentPressureDeletionStateException) {
			PressureSessionDeletionResult.RetryableFailure(
				PressureSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			PressureSessionDeletionResult.RetryableFailure(
				PressureSessionDeletionRetryableReason.DATABASE_UNAVAILABLE,
			)
		}

		if (result == PressureSessionDeletionResult.Deleted) {
			dirtyTracker.markDirty(
				setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
			)
			requestPressureDrain()
		}
		return result
	}

	private suspend fun deleteSelectedScope(
		sessionSegmentId: Long,
		expectedEpoch: Long?,
		deletedAtMs: Long,
	): PressureSessionDeletionResult {
		if (expectedEpoch != null &&
			database.sourceEvidenceStateDao().get()?.collectedDataEpoch != expectedEpoch
		) {
			return unsupported(PressureSessionDeletionUnsupportedReason.STALE_COLLECTED_DATA_EPOCH)
		}
		val selected = database.sessionSegmentDao().getById(sessionSegmentId)
			?: return PressureSessionDeletionResult.NotFound
		if (preReadHasActiveOwner(selected)) {
			return PressureSessionDeletionResult.BlockedActive
		}
		return when (val preflight = selectedScope(selected)) {
			is SelectedScopePreflight.Ready ->
				deletePreparedScope(sessionSegmentId, expectedEpoch, deletedAtMs, preflight.scope)
			is SelectedScopePreflight.Unsupported -> unsupported(preflight.reason)
			SelectedScopePreflight.LegacyUnverifiable -> PressureSessionDeletionResult.LegacyUnverifiable
		}
	}

	private suspend fun deletePreparedScope(
		sessionSegmentId: Long,
		expectedEpoch: Long?,
		deletedAtMs: Long,
		expected: SelectedScope,
	): PressureSessionDeletionResult = DailySummaryAggregator(
		dailySummaryDao = database.dailySummaryDao(),
		sessionSegmentDao = database.sessionSegmentDao(),
		zoneId = expected.zoneId,
	).withDayLocks(expected.affectedDays) { lockedDays ->
		afterDayLocksAcquired()
		database.withTransaction {
			if (expectedEpoch != null &&
				database.sourceEvidenceStateDao().get()?.collectedDataEpoch != expectedEpoch
			) {
				return@withTransaction unsupported(PressureSessionDeletionUnsupportedReason.STALE_COLLECTED_DATA_EPOCH)
			}
			val actual = readCurrentSelectedScope(sessionSegmentId)
			if (actual != expected) {
				throw ConcurrentPressureDeletionStateException()
			}
			deleteInTransaction(actual, deletedAtMs, lockedDays)
		}
	}

	private suspend fun readCurrentSelectedScope(sessionSegmentId: Long): SelectedScope {
		val selected = database.sessionSegmentDao().getById(sessionSegmentId)
			?: throw ConcurrentPressureDeletionStateException()
		return (selectedScope(selected) as? SelectedScopePreflight.Ready)?.scope
			?: throw ConcurrentPressureDeletionStateException()
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ThrowsCount")
	private suspend fun deleteInTransaction(
		scope: SelectedScope,
		deletedAtMs: Long,
		lockedDays: DailySummaryLockedDays,
	): PressureSessionDeletionResult {
		val segment = scope.segment
		val logicalTrackingId = scope.logicalTrackingId
		val serviceRunId = scope.serviceRun.serviceRunId
		val sessionDao = database.sourceSessionDao()
		if (logicalSessionIsActive(
				scope.logicalSessionState,
				scope.currentServiceRunId,
				scope.serviceRun,
			) ||
			runHasLiveDemand(logicalTrackingId, serviceRunId) ||
			sessionDao.hasNonterminalLatestLifecycleAction(logicalTrackingId, serviceRunId)
		) {
			return PressureSessionDeletionResult.BlockedActive
		}

		val repair = when (
			val preflight = StepsDailySummaryRepairComposer(database).compose(
				epochDays = scope.affectedDays,
				excludedSegmentId = segment.id,
				dailySummaries = scope.dailySummaries,
			)
		) {
			is StepsDayRepairPreflight.Ready -> preflight.plans
			is StepsDayRepairPreflight.Unsupported -> return unsupported(
				PressureSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
			StepsDayRepairPreflight.Materializing -> return PressureSessionDeletionResult.RetryableFailure(
				PressureSessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING,
			)
		}
		beforeMutation()
		val fenceDao = database.sourceDeletionFenceDao()
		val scopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		val existingFence = fenceDao.get(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = scopeDigest,
		)
		val proposedFence = existingFence ?: SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			fenceGeneration = FIRST_PRESSURE_DELETION_GENERATION,
			collectedDataEpoch = scope.collectedDataEpoch,
			deletedAtMs = deletedAtMs,
		)
		if (existingFence == null) {
			fenceDao.insertIfAbsent(proposedFence)
		}
		val fence = fenceDao.get(
			sourceKind = proposedFence.sourceKind,
			purpose = proposedFence.purpose,
			scopeKind = proposedFence.scopeKind,
			scopeIdentityDigest = proposedFence.scopeIdentityDigest,
		) ?: throw ConcurrentPressureDeletionStateException()
		if (!fence.matchesPressureDeletionScope(scopeDigest, scope.collectedDataEpoch, deletedAtMs)) {
			throw ConcurrentPressureDeletionStateException()
		}
		afterFenceInstalled()

		val deletedFacts = deleteValidatedFactsInBatches(logicalTrackingId, serviceRunId)
		if (deletedFacts != scope.factCount) {
			throw ConcurrentPressureDeletionStateException()
		}
		if (database.pressureFactRetentionRevisionAuthorityDao().deleteForExactRun(
				logicalTrackingId,
				serviceRunId,
			).toLong() != scope.retentionRevisionAuthorityCount ||
			database.pressureFactRetentionAuthorityDao().deleteExactRun(
				logicalTrackingId,
				serviceRunId,
			).toLong() != scope.retentionAuthorityCount ||
			database.pressureFactRetentionRevisionAuthorityDao().countForExactRun(
				logicalTrackingId,
				serviceRunId,
			) != 0L ||
			database.pressureFactRetentionAuthorityDao().countForExactRun(
				logicalTrackingId,
				serviceRunId,
			) != 0L
		) {
			throw ConcurrentPressureDeletionStateException()
		}
		database.skiRunSegmentDao().deleteBySession(segment.id)
		if (database.sessionSegmentDao().deleteExact(segment.id, logicalTrackingId, serviceRunId) != 1) {
			throw ConcurrentPressureDeletionStateException()
		}
		if (database.sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1) {
			throw ConcurrentPressureDeletionStateException()
		}

		repair.forEach { plan ->
			DailySummaryAggregator(
				dailySummaryDao = database.dailySummaryDao(),
				sessionSegmentDao = database.sessionSegmentDao(),
				zoneId = plan.zoneId,
			).repairDayFromSourceTotalsWhileLocked(
				epochDay = plan.epochDay,
				lockedDays = lockedDays,
				totals = plan.totals,
			)
		}
		return PressureSessionDeletionResult.Deleted
	}

	private fun SourceDeletionFenceEntity.matchesPressureDeletionScope(
		expectedScopeDigest: String,
		expectedEpoch: Long,
		requestedDeletionTimeMs: Long,
	): Boolean {
		val sameScope = sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
			purpose == PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE &&
			scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN && scopeIdentityDigest == expectedScopeDigest
		return sameScope && fenceGeneration >= FIRST_PRESSURE_DELETION_GENERATION &&
			collectedDataEpoch == expectedEpoch && deletedAtMs <= requestedDeletionTimeMs
	}

	private suspend fun deleteValidatedFactsInBatches(
		logicalTrackingId: String,
		serviceRunId: String,
	): Long {
		val factDao = database.pressureFactRevisionDao()
		var deletedCount = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = factDao.firstExactServiceRunPage(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				limit = FACT_PAGE_SIZE,
			)
			if (page.isEmpty()) {
				break
			}
			val through = page.last()
			if (factDao.deleteExactServiceRunPageThrough(
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
					writerProjectionVersion =
						SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
					throughLogicalFactId = through.logicalFactId,
					throughSemanticRevision = through.semanticRevision,
				) != page.size
			) {
				throw ConcurrentPressureDeletionStateException()
			}
			deletedCount = try {
				Math.addExact(deletedCount, page.size.toLong())
			} catch (_: ArithmeticException) {
				throw ConcurrentPressureDeletionStateException()
			}
			afterFactBatchDeleted(deletedCount)
			if (page.size < FACT_PAGE_SIZE) {
				break
			}
		}
		return deletedCount
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun selectedScope(segment: SessionSegment): SelectedScopePreflight {
		val logicalTrackingId = segment.logicalTrackingId
		val serviceRunId = segment.serviceRunId
		if (logicalTrackingId == null && serviceRunId == null) {
			return SelectedScopePreflight.LegacyUnverifiable
		}
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			return unsupportedScope(PressureSessionDeletionUnsupportedReason.INCOMPLETE_ATTRIBUTION)
		}
		val sessionDao = database.sourceSessionDao()
		val serviceRun = sessionDao.serviceRun(serviceRunId)
			?: return unsupportedScope(PressureSessionDeletionUnsupportedReason.SERVICE_RUN_MISSING)
		if (serviceRun.presentationAcknowledgement ==
			SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
		) {
			return SelectedScopePreflight.LegacyUnverifiable
		}
		if (serviceRun.logicalTrackingId != logicalTrackingId || serviceRun.sessionSegmentId != segment.id) {
			return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.SERVICE_RUN_BINDING_MISMATCH,
			)
		}
		val logicalSession = sessionDao.session(logicalTrackingId)
			?: return unsupportedScope(PressureSessionDeletionUnsupportedReason.LOGICAL_SESSION_MISSING)
		val manifests = sessionDao.manifestsForServiceRun(
			serviceRunId = serviceRunId,
			limit = MAX_SELECTED_MANIFESTS + 1,
		).sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (manifests.isEmpty()) {
			return unsupportedScope(PressureSessionDeletionUnsupportedReason.MANIFEST_MISSING)
		}
		if (manifests.size > MAX_SELECTED_MANIFESTS) {
			return unsupportedScope(PressureSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED)
		}
		val manifestSlices = manifestFactSlices(logicalSession, serviceRun, manifests)
			?: return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED,
			)
		val readDao = database.trackingHistoryReadDao()
		val manifestSources = readDao.manifestSources(
			serviceRunIds = listOf(serviceRunId),
			limit = MAX_SELECTED_MANIFEST_SOURCES + 1,
		)
		if (manifestSources.size > MAX_SELECTED_MANIFEST_SOURCES) {
			return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)
		}
		val sourcesByManifest = manifestSources.groupBy(SessionManifestSourceEntity::manifestRevision)
		val captureBindings = mutableListOf<SessionManifestSourceEntity>()
		val zonesByManifest = linkedMapOf<Long, ZoneId>()
		for (manifest in manifests) {
			if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId) {
				return unsupportedScope(
					PressureSessionDeletionUnsupportedReason.MANIFEST_MEMBERSHIP_MISMATCH,
				)
			}
			val sources = sourcesByManifest[manifest.manifestRevision].orEmpty()
			if (sources.any { source -> source.logicalTrackingId != logicalTrackingId }) {
				return unsupportedScope(
					PressureSessionDeletionUnsupportedReason.MANIFEST_MEMBERSHIP_MISMATCH,
				)
			}
			if (!SessionManifestIntegrity.verify(manifest, sources)) {
				return unsupportedScope(PressureSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED)
			}
			if (sources.any { source ->
				source.purpose !in SessionManifestPurposeCode.ALL ||
					source.sourceKind !in KNOWN_SOURCE_KINDS
			}) {
				return unsupportedScope(
					PressureSessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
				)
			}
			val exactCapture = sources.filter { source ->
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
			}
			if (exactCapture.size != 1 ||
				exactCapture.single().sourceKind != SourceDestinationOwnerEntity.SOURCE_PRESSURE ||
				!exactCapture.single().persistenceEligible
			) {
				return unsupportedScope(
					PressureSessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
				)
			}
			captureBindings += exactCapture.single()
			zonesByManifest[manifest.manifestRevision] = try {
				ZoneId.of(manifest.zoneId)
			} catch (_: DateTimeException) {
				return unsupportedScope(
					PressureSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
			}
		}
		if (captureBindings.any { source ->
			source.writerOwner == SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
		}) {
			return unsupportedScope(PressureSessionDeletionUnsupportedReason.LEGACY_WRITER)
		}
		val executableBindings = manifests.zip(captureBindings).mapNotNull { (manifest, source) ->
			exactCandidateBinding(source, manifest.sessionMode)
		}
		val exactBinding = executableBindings.distinct().singleOrNull()
		if (executableBindings.size != captureBindings.size || exactBinding == null ||
			captureBindings.map(SessionManifestSourceEntity::writerOwnerGeneration).distinct().size != 1
		) {
			return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.CANDIDATE_WRITER_BINDING_INVALID,
			)
		}
		val distinctZones = zonesByManifest.values.distinct()
		if (distinctZones.size != 1) {
			return unsupportedScope(PressureSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE)
		}

		val policies = readDao.policiesForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			serviceRunIds = listOf(serviceRunId),
		)
		val policiesByRevision = policies.associateBy(SourcePolicyEntity::policyRevision)
		if (policiesByRevision.size != policies.size) {
			return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
			)
		}
		val requestedConsentEpochs = captureBindings.map(SessionManifestSourceEntity::consentEpoch)
			.distinct()
		if (requestedConsentEpochs.isEmpty() || requestedConsentEpochs.size > MAX_SELECTED_MANIFESTS) {
			return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
			)
		}
		val consents = database.sourcePolicyDao().consentEpochs(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			epochs = requestedConsentEpochs,
		)
		val consentsByEpoch = consents.associateBy(SourceConsentEpochEntity::epoch)
		if (consents.size != requestedConsentEpochs.size || consentsByEpoch.size != consents.size ||
			consentsByEpoch.keys != requestedConsentEpochs.toSet()
		) {
			return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
			)
		}
		for ((manifest, binding) in manifests.zip(captureBindings)) {
			val policy = policiesByRevision[manifest.sourcePolicyRevision]
			if (policy == null || policy.sourceKind != SourceDestinationOwnerEntity.SOURCE_PRESSURE ||
				!policy.enabled || !policy.capturePersistenceEligible ||
				policy.captureConsentEpoch != binding.consentEpoch || policy.qosCode != binding.qosCode ||
				binding.qosCode !in MIN_CAPTURE_QOS_CODE..MAX_CAPTURE_QOS_CODE
			) {
				return unsupportedScope(
					PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
				)
			}
			val consent = consentsByEpoch[binding.consentEpoch]
			if (consent == null || !consent.eligible || !consent.persistenceEligible ||
				consent.policyRevision > manifest.sourcePolicyRevision
			) {
				return unsupportedScope(
					PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
				)
			}
		}

		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.SOURCE_EVIDENCE_STATE_MISSING,
			)
		val manifestsByRevision = manifests.associateBy(SessionManifestVersionEntity::manifestRevision)
		val bindingsByRevision = captureBindings.associateBy(SessionManifestSourceEntity::manifestRevision)
		val affectedDays = sortedSetOf<Long>()
		val segmentZone = distinctZones.single()
		affectedEpochDays(segment.startTimeMs, segment.endTimeMs, segmentZone)?.let(affectedDays::addAll)
			?: return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
			)
		plausiblyPersistedEpochDays(segment.startTimeMs, segment.endTimeMs)
			?.let(affectedDays::addAll)
			?: return unsupportedScope(
				PressureSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
			)
		val factValidation = validateFactPages(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			exactBinding = exactBinding,
			manifestsByRevision = manifestsByRevision,
			bindingsByRevision = bindingsByRevision,
			manifestSlices = manifestSlices,
			zonesByManifest = zonesByManifest,
			collectedDataEpoch = evidenceState.collectedDataEpoch,
			affectedDays = affectedDays,
		)
		val factCount = when (factValidation) {
			is FactScopeValidation.Ready -> factValidation.factCount
			is FactScopeValidation.Unsupported -> return unsupportedScope(factValidation.reason)
		}
		val retainedValidation = validateRetainedAuthority(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			collectedDataEpoch = evidenceState.collectedDataEpoch,
			retainedFromMs = evidenceState.retainedFromMs,
			allowedPolicyConsentPairs = manifests.zip(captureBindings)
				.mapTo(hashSetOf()) { (manifest, binding) ->
					manifest.sourcePolicyRevision to binding.consentEpoch
				},
			zonesByManifest = zonesByManifest,
			affectedDays = affectedDays,
		)
		val retainedCounts = when (retainedValidation) {
			is RetainedScopeValidation.Ready -> retainedValidation
			is RetainedScopeValidation.Unsupported ->
				return unsupportedScope(retainedValidation.reason)
		}
		if (factCount == 0L && retainedCounts.parentCount == 0L) {
			return unsupportedScope(PressureSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH)
		}
		val affectedDaySet = affectedDays.toSet()
		val dailySummaries = database.dailySummaryDao()
			.getBetween(affectedDays.first(), affectedDays.last())
			.filter { summary -> summary.dateEpochDay in affectedDaySet }

		return SelectedScopePreflight.Ready(
			SelectedScope(
				segment = segment,
				logicalTrackingId = logicalTrackingId,
				logicalSessionState = logicalSession.state,
				currentServiceRunId = logicalSession.currentServiceRunId,
				serviceRun = serviceRun,
				factCount = factCount,
				retentionAuthorityCount = retainedCounts.parentCount,
				retentionRevisionAuthorityCount = retainedCounts.revisionCount,
				collectedDataEpoch = evidenceState.collectedDataEpoch,
				zoneId = segmentZone,
				affectedDays = affectedDays.toList(),
				dailySummaries = dailySummaries,
			),
		)
	}

	private fun unsupportedScope(
		reason: PressureSessionDeletionUnsupportedReason,
	) = SelectedScopePreflight.Unsupported(reason)

	@Suppress("ReturnCount")
	private suspend fun preReadHasActiveOwner(segment: SessionSegment): Boolean {
		val logicalTrackingId = segment.logicalTrackingId?.takeIf(String::isNotBlank) ?: return false
		val serviceRunId = segment.serviceRunId?.takeIf(String::isNotBlank) ?: return false
		val sessionDao = database.sourceSessionDao()
		val serviceRun = sessionDao.serviceRun(serviceRunId) ?: return false
		if (serviceRun.logicalTrackingId != logicalTrackingId || serviceRun.sessionSegmentId != segment.id) {
			return false
		}
		val logicalSession = sessionDao.session(logicalTrackingId) ?: return false
		return logicalSessionIsActive(
			logicalSession.state,
			logicalSession.currentServiceRunId,
			serviceRun,
		) || runHasLiveDemand(logicalTrackingId, serviceRunId) ||
			sessionDao.hasNonterminalLatestLifecycleAction(logicalTrackingId, serviceRunId)
	}

	private suspend fun runHasLiveDemand(logicalTrackingId: String, serviceRunId: String): Boolean =
		database.sourceBrokerDao().currentDemands("session:$logicalTrackingId")
			.any { demand -> demand.serviceRunId == serviceRunId }

	private fun logicalSessionIsActive(
		logicalSessionState: String,
		currentServiceRunId: String?,
		serviceRun: SourceServiceRunEntity,
	): Boolean = logicalSessionState !in TERMINAL_SESSION_STATES ||
		currentServiceRunId == serviceRun.serviceRunId || serviceRun.state !in TERMINAL_RUN_STATES ||
		serviceRun.completedAtMs == null

	private fun exactCandidateBinding(
		source: SessionManifestSourceEntity,
		sessionMode: String,
	): ExecutableSourceLaneBinding? {
		if (source.outputDestination != SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE ||
			source.writerOwner != SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ||
			source.writerOwnerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION ||
			sessionMode != "MANUAL"
		) {
			return null
		}
		val generation = source.writerBindingGeneration ?: return null
		val binding = executableLaneCatalog.bindingFor(
			source = SourceKind.PRESSURE,
			bindingGeneration = generation,
			projectionId = source.writerProjectionId,
			projectionVersion = source.writerProjectionVersion,
		)
		return binding?.takeIf { executable ->
			executable == ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS &&
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE in executable.captureModes
		}
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun manifestFactSlices(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		ordered: List<SessionManifestVersionEntity>,
	): Map<Long, ManifestFactSlice>? {
		val completedAtMs = run.completedAtMs ?: return null
		val logicalCompletedAtMs = logicalSession.completedAtMs ?: return null
		val retainedManifestRevision = logicalSession.currentManifestRevision ?: return null
		if (ordered.isEmpty() || run.preparedManifestRevision <= 0L ||
			ordered.map(SessionManifestVersionEntity::manifestRevision).distinct().size != ordered.size ||
			ordered.first().manifestRevision != run.preparedManifestRevision ||
			ordered.first().effectiveWallTimeMs != run.startedAtMs ||
			ordered.first().effectiveElapsedRealtimeNanos != run.startedElapsedNanos ||
			ordered.first().effectiveBootId != run.bootId || completedAtMs < run.startedAtMs ||
			logicalSession.rolloutRevision != run.rolloutRevision ||
			logicalSession.sessionMode != ordered.first().sessionMode ||
			logicalSession.startedAtMs > run.startedAtMs || logicalCompletedAtMs < completedAtMs ||
			run.startedAtMs < 0L || run.startedElapsedNanos < 0L || run.desiredPlanRevision <= 0L ||
			run.rolloutRevision <= 0L || run.startOrigin !in SESSION_MODE_BY_START_ORIGIN ||
			SESSION_MODE_BY_START_ORIGIN[run.startOrigin] != ordered.first().sessionMode ||
			retainedManifestRevision < ordered.last().manifestRevision ||
			ordered.last().acquisitionPlanRevision != run.desiredPlanRevision ||
			(retainedManifestRevision == ordered.last().manifestRevision &&
				logicalSession.desiredPlanRevision != run.desiredPlanRevision)
		) {
			return null
		}
		val isInitialRun = logicalSession.startedAtMs == run.startedAtMs &&
			logicalSession.startedElapsedNanos == run.startedElapsedNanos
		if (isInitialRun && (logicalSession.startOrigin != run.startOrigin ||
				logicalSession.clockDomainId != run.bootId)
		) {
			return null
		}
		val slices = linkedMapOf<Long, ManifestFactSlice>()
		for ((index, manifest) in ordered.withIndex()) {
			val previous = ordered.getOrNull(index - 1)
			val next = ordered.getOrNull(index + 1)
			val expectedOrigin = if (index == 0) {
				run.startOrigin
			} else {
				POLICY_RECONCILIATION_ORIGIN
			}
			if (manifest.logicalTrackingId != logicalSession.logicalTrackingId ||
				manifest.serviceRunId != run.serviceRunId ||
				manifest.rolloutRevision != run.rolloutRevision ||
				manifest.sessionMode != logicalSession.sessionMode ||
				manifest.startOrigin != expectedOrigin || manifest.effectiveBootId != run.bootId ||
				manifest.effectiveElapsedRealtimeNanos < run.startedElapsedNanos ||
				manifest.effectiveWallTimeMs !in run.startedAtMs..completedAtMs ||
				manifest.acquisitionPlanRevision <= 0L
			) {
				return null
			}
			if (previous != null) {
				val expectedRevision = try {
					Math.addExact(previous.manifestRevision, 1L)
				} catch (_: ArithmeticException) {
					return null
				}
				if (manifest.manifestRevision != expectedRevision ||
					manifest.effectiveWallTimeMs < previous.effectiveWallTimeMs ||
					manifest.effectiveElapsedRealtimeNanos <
					previous.effectiveElapsedRealtimeNanos
				) {
					return null
				}
			}
			val sliceEndMs = next?.effectiveWallTimeMs ?: completedAtMs
			if (sliceEndMs < manifest.effectiveWallTimeMs) {
				return null
			}
			slices[manifest.manifestRevision] = ManifestFactSlice(
				startTimeMs = manifest.effectiveWallTimeMs,
				endTimeMs = sliceEndMs,
				startElapsedRealtimeNanos = manifest.effectiveElapsedRealtimeNanos,
				endElapsedRealtimeNanos = next?.effectiveElapsedRealtimeNanos,
			)
		}
		return slices
	}

	@Suppress(
		"ComplexCondition",
		"CyclomaticComplexMethod",
		"LongMethod",
		"LongParameterList",
		"ReturnCount",
	)
	private suspend fun validateFactPages(
		logicalTrackingId: String,
		serviceRunId: String,
		exactBinding: ExecutableSourceLaneBinding,
		manifestsByRevision: Map<Long, SessionManifestVersionEntity>,
		bindingsByRevision: Map<Long, SessionManifestSourceEntity>,
		manifestSlices: Map<Long, ManifestFactSlice>,
		zonesByManifest: Map<Long, ZoneId>,
		collectedDataEpoch: Long,
		affectedDays: MutableSet<Long>,
	): FactScopeValidation {
		val factDao = database.pressureFactRevisionDao()
		if (factDao.hasServiceRunScopeMismatch(logicalTrackingId, serviceRunId)) {
			return FactScopeValidation.Unsupported(
				PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
			)
		}
		var currentLineageKey: PressureFactLineageKey? = null
		val currentLineage = mutableListOf<PressureFactRevisionEntity>()
		val currentLineageWalByEventId = linkedMapOf<String, SourceEventWalEntity>()
		var cursor: PressureFactCursor? = null
		var factCount = 0L
		var factPageCount = 0
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = cursor?.let { after ->
				factDao.exactServiceRunPageAfter(
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					afterWriterId = after.writerProjectionId,
					afterWriterVersion = after.writerProjectionVersion,
					afterLogicalFactId = after.logicalFactId,
					afterSemanticRevision = after.semanticRevision,
					limit = FACT_PAGE_SIZE,
				)
			} ?: factDao.firstExactServiceRunPage(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				limit = FACT_PAGE_SIZE,
			)
			if (page.isEmpty()) {
				break
			}
			factPageCount = try {
				Math.addExact(factPageCount, 1)
			} catch (_: ArithmeticException) {
				return FactScopeValidation.Unsupported(
					PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				)
			}
			if (factPageCount > MAX_SELECTED_FACT_PAGES) {
				return FactScopeValidation.Unsupported(
					PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				)
			}
			val pageWalByEventId = loadPressureWalCarriers(page)
				?: return FactScopeValidation.Unsupported(
					PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				)
			for (fact in page) {
				val nextCursor = PressureFactCursor(fact)
				if (cursor?.let { previous -> nextCursor <= previous } == true) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
					)
				}
				cursor = nextCursor
				val lineageKey = PressureFactLineageKey(fact)
				val startsLineage = lineageKey != currentLineageKey
				if (startsLineage) {
					if (!authenticateCompletedPressureLineage(
							currentLineage,
							currentLineageWalByEventId,
							bindingsByRevision,
						)
					) {
						return FactScopeValidation.Unsupported(
							PressureSessionDeletionUnsupportedReason
								.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
						)
					}
					currentLineage.clear()
					currentLineageWalByEventId.clear()
					currentLineageKey = lineageKey
				}
				val manifest = manifestsByRevision[fact.manifestRevision]
				val binding = bindingsByRevision[fact.manifestRevision]
				val slice = manifestSlices[fact.manifestRevision]
				if (manifest == null || binding == null || slice == null ||
					fact.writerProjectionId != exactBinding.projectionId ||
					fact.writerProjectionVersion != exactBinding.projectionVersion ||
					fact.writerBindingGeneration != exactBinding.bindingGeneration ||
					fact.logicalTrackingId != logicalTrackingId || fact.serviceRunId != serviceRunId ||
					fact.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
					fact.sourcePolicyRevision != manifest.sourcePolicyRevision ||
					fact.captureConsentEpoch != binding.consentEpoch ||
					fact.clockDomainId != manifest.effectiveBootId ||
					fact.intervalStartTimeMs < slice.startTimeMs ||
					fact.intervalEndTimeMs > slice.endTimeMs ||
					fact.windowStartElapsedRealtimeNanos < slice.startElapsedRealtimeNanos ||
					slice.endElapsedRealtimeNanos?.let { end ->
						fact.windowEndElapsedRealtimeNanos > end
					} == true
				) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
					)
				}
				if (!factHasExactIdentityAndChecksum(fact, binding, startsLineage)) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.FACT_INTEGRITY_FAILED,
					)
				}
				if (fact.collectedDataEpoch != collectedDataEpoch) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.STALE_COLLECTED_DATA_EPOCH,
					)
				}
				val zoneId = zonesByManifest[fact.manifestRevision]
					?: return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
					)
				affectedEpochDays(fact.intervalStartTimeMs, fact.intervalEndTimeMs, zoneId)
					?.let(affectedDays::addAll)
					?: return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
					)
				plausiblyPersistedEpochDays(fact.intervalStartTimeMs, fact.intervalEndTimeMs)
					?.let(affectedDays::addAll)
					?: return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
					)
				if (affectedDays.size.toLong() > MAX_AFFECTED_DAYS) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
					)
				}
				if (currentLineage.size >= MAX_PRESSURE_LINEAGE_REVISIONS) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason
							.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
					)
				}
				currentLineage += fact
				val wal = pageWalByEventId[fact.sourceEventId]
					?: return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason
							.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
					)
				if (currentLineageWalByEventId.put(fact.sourceEventId, wal) != null) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason
							.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
					)
				}
				factCount = try {
					Math.addExact(factCount, 1L)
				} catch (_: ArithmeticException) {
					return FactScopeValidation.Unsupported(
						PressureSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
					)
				}
			}
			afterFactPageScanned(factPageCount, factCount, currentLineage.size)
			if (page.size < FACT_PAGE_SIZE) {
				break
			}
		}
		if (factCount > 0L && factDao.hasCrossScopeRevisions(logicalTrackingId, serviceRunId)) {
			return FactScopeValidation.Unsupported(
				PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
			)
		}
		if (!authenticateCompletedPressureLineage(
				currentLineage,
				currentLineageWalByEventId,
				bindingsByRevision,
			)
		) {
			return FactScopeValidation.Unsupported(
				PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
			)
		}
		return if (factCount > 0L) {
			FactScopeValidation.Ready(factCount)
		} else {
			FactScopeValidation.Ready(0L)
		}
	}

	@Suppress("LongParameterList")
	private suspend fun validateRetainedAuthority(
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		retainedFromMs: Long?,
		allowedPolicyConsentPairs: Set<Pair<Long, Long>>,
		zonesByManifest: Map<Long, ZoneId>,
		affectedDays: MutableSet<Long>,
	): RetainedScopeValidation {
		val parentDao = database.pressureFactRetentionAuthorityDao()
		val revisionDao = database.pressureFactRetentionRevisionAuthorityDao()
		val parents = parentDao.forExactRun(
			logicalTrackingId,
			serviceRunId,
			MAX_SELECTED_RETENTION_AUTHORITIES + 1,
		)
		if (parents.size > MAX_SELECTED_RETENTION_AUTHORITIES ||
			parentDao.hasLiveFactCollisionForExactRun(logicalTrackingId, serviceRunId)
		) {
			return retainedScopeUnsupported()
		}
		if (parents.isEmpty()) {
			return if (revisionDao.countForExactRun(logicalTrackingId, serviceRunId) == 0L) {
				RetainedScopeValidation.Ready(0L, 0L)
			} else {
				retainedScopeUnsupported()
			}
		}
		if (retainedFromMs == null) {
			return retainedScopeUnsupported()
		}
		val revisionCount = parents.validatedRetainedPressureRevisionCount(
			logicalTrackingId, serviceRunId, collectedDataEpoch, retainedFromMs,
			allowedPolicyConsentPairs, zonesByManifest, affectedDays,
		)
		return if (revisionCount == null ||
			revisionDao.countForExactRun(logicalTrackingId, serviceRunId) != revisionCount
		) {
			retainedScopeUnsupported()
		} else {
			RetainedScopeValidation.Ready(parents.size.toLong(), revisionCount)
		}
	}

	// One retained lineage pass keeps its captured scope, authority and existing affected-day target together.
	@Suppress("LongParameterList")
	private suspend fun List<PressureFactRetentionAuthorityEntity>.validatedRetainedPressureRevisionCount(
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		retainedFromMs: Long,
		allowedPolicyConsentPairs: Set<Pair<Long, Long>>,
		zonesByManifest: Map<Long, ZoneId>,
		affectedDays: MutableSet<Long>,
	): Long? {
		var revisionCount = 0L
		for (parent in this) {
			currentCoroutineContext().ensureActive()
			val addedRevisions = validatedRetainedPressureParentCount(
				parent, logicalTrackingId, serviceRunId, collectedDataEpoch, retainedFromMs, allowedPolicyConsentPairs,
			) ?: return null
			revisionCount = try {
				Math.addExact(revisionCount, addedRevisions)
			} catch (_: ArithmeticException) {
				return null
			}
			if (revisionCount > MAX_SELECTED_RETENTION_REVISION_AUTHORITIES ||
				!includeRetainedPressureParentDays(parent, zonesByManifest, affectedDays)
			) {
				return null
			}
		}
		return revisionCount
	}

	private suspend fun validatedRetainedPressureParentCount(
		parent: PressureFactRetentionAuthorityEntity,
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		retainedFromMs: Long,
		allowedPolicyConsentPairs: Set<Pair<Long, Long>>,
	): Long? {
		if (!parent.hasValidStoredIntegrity() ||
			!parent.matchesRetainedPressureDeletionScope(
				logicalTrackingId, serviceRunId, collectedDataEpoch, retainedFromMs,
			)
		) {
			return null
		}
		val revisions = database.pressureFactRetentionRevisionAuthorityDao().forLineage(
			parent.writerProjectionId, parent.writerProjectionVersion, parent.logicalFactId,
			MAX_PRESSURE_LINEAGE_REVISIONS + 1,
		)
		return if (!parent.authenticatesRevisionAuthorities(revisions) || revisions.any { revision ->
				(revision.sourcePolicyRevision to revision.captureConsentEpoch) !in allowedPolicyConsentPairs
			}) {
			null
		} else {
			revisions.size.toLong()
		}
	}

	private fun PressureFactRetentionAuthorityEntity.matchesRetainedPressureDeletionScope(
		expectedLogicalId: String,
		expectedRunId: String,
		expectedEpoch: Long,
		maximumRetainedFromMs: Long,
	): Boolean = logicalTrackingId == expectedLogicalId && serviceRunId == expectedRunId &&
		collectedDataEpoch == expectedEpoch && retainedFromMs <= maximumRetainedFromMs

	private fun includeRetainedPressureParentDays(
		parent: PressureFactRetentionAuthorityEntity,
		zonesByManifest: Map<Long, ZoneId>,
		affectedDays: MutableSet<Long>,
	): Boolean {
		for (zone in zonesByManifest.values.distinct()) {
			affectedEpochDays(parent.lineageIntervalStartMs, parent.lineageIntervalEndMs, zone)
				?.let(affectedDays::addAll) ?: return false
		}
		plausiblyPersistedEpochDays(parent.lineageIntervalStartMs, parent.lineageIntervalEndMs)
			?.let(affectedDays::addAll) ?: return false
		return affectedDays.size.toLong() <= MAX_AFFECTED_DAYS
	}

	private fun retainedScopeUnsupported() = RetainedScopeValidation.Unsupported(
		PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
	)

	private fun authenticateCompletedPressureLineage(
		revisions: List<PressureFactRevisionEntity>,
		walByEventId: Map<String, SourceEventWalEntity>,
		bindingsByManifestRevision: Map<Long, SessionManifestSourceEntity>,
	): Boolean = revisions.isEmpty() ||
		PressureFactProofAuthenticator.authenticateStoredLineage(
			revisions = revisions,
			walByEventId = walByEventId,
			bindingsByManifestRevision = bindingsByManifestRevision,
		) is PressureFactLineageProof.Authenticated

	@Suppress("LongMethod") // Every frozen Pressure effect field participates in integrity.
	private fun factHasExactIdentityAndChecksum(
		fact: PressureFactRevisionEntity,
		binding: SessionManifestSourceEntity,
		requiresOriginalLogicalFactId: Boolean,
	): Boolean {
		val expectedLogicalFactId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:${fact.sourceEventId}"
		if ((requiresOriginalLogicalFactId &&
				(fact.semanticRevision != 1L || fact.logicalFactId != expectedLogicalFactId)) ||
			fact.mutationId != "${fact.logicalFactId}:${fact.semanticRevision}"
		) {
			return false
		}
		return PressureFactRevisionIntegrity.hasValidEffectChecksum(fact, binding)
	}

	private suspend fun loadPressureWalCarriers(
		revisions: List<PressureFactRevisionEntity>,
	): Map<String, SourceEventWalEntity>? {
		val expectedEventIds =
			revisions.mapTo(linkedSetOf(), PressureFactRevisionEntity::sourceEventId)
		val walRows = mutableListOf<SourceEventWalEntity>()
		for (eventIds in expectedEventIds
			.chunked(PRESSURE_WAL_ID_BATCH_SIZE)
		) {
			walRows += database.sourceEventWalDao().boundedPayloadsByEventIds(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				eventIds = eventIds,
				maximumPayloadBytes = MAX_PRESSURE_WAL_PAYLOAD_BYTES,
				limit = eventIds.size,
			)
		}
		val walByEventId = walRows.associateBy(SourceEventWalEntity::eventId)
		if (walRows.size != walByEventId.size ||
			walByEventId.keys != expectedEventIds
		) {
			return null
		}
		return walByEventId
	}

	private fun affectedEpochDays(
		startTimeMs: Long,
		endTimeMs: Long,
		zoneId: ZoneId,
	): List<Long>? = affectedEpochDays(startTimeMs, endTimeMs, zoneId, zoneId)

	private fun plausiblyPersistedEpochDays(startTimeMs: Long, endTimeMs: Long): List<Long>? =
		affectedEpochDays(startTimeMs, endTimeMs, ZoneOffset.MIN, ZoneOffset.MAX)

	private fun affectedEpochDays(
		startTimeMs: Long,
		endTimeMs: Long,
		startZoneId: ZoneId,
		endZoneId: ZoneId,
	): List<Long>? {
		val lastCoveredMs = if (endTimeMs > startTimeMs) {
			endTimeMs - 1L
		} else {
			startTimeMs
		}
		val startDay: Long
		val endDay: Long
		try {
			startDay = Instant.ofEpochMilli(startTimeMs).atZone(startZoneId).toLocalDate().toEpochDay()
			endDay = Instant.ofEpochMilli(lastCoveredMs).atZone(endZoneId).toLocalDate().toEpochDay()
		} catch (_: DateTimeException) {
			return null
		}
		val count = endDay - startDay + 1L
		if (count <= 0L || count > MAX_AFFECTED_DAYS) {
			return null
		}
		return List(count.toInt()) { offset -> startDay + offset }
	}

	private fun unsupported(
		reason: PressureSessionDeletionUnsupportedReason,
	): PressureSessionDeletionResult = PressureSessionDeletionResult.UnsupportedScope(reason)

	private class ConcurrentPressureDeletionStateException : IllegalStateException()

	private sealed interface SelectedScopePreflight {
		data class Ready(val scope: SelectedScope) : SelectedScopePreflight
		data class Unsupported(
			val reason: PressureSessionDeletionUnsupportedReason,
		) : SelectedScopePreflight
		data object LegacyUnverifiable : SelectedScopePreflight
	}

	private sealed interface FactScopeValidation {
		data class Ready(val factCount: Long) : FactScopeValidation
		data class Unsupported(
			val reason: PressureSessionDeletionUnsupportedReason,
		) : FactScopeValidation
	}

	private sealed interface RetainedScopeValidation {
		data class Ready(
			val parentCount: Long,
			val revisionCount: Long,
		) : RetainedScopeValidation
		data class Unsupported(
			val reason: PressureSessionDeletionUnsupportedReason,
		) : RetainedScopeValidation
	}

	private data class ManifestFactSlice(
		val startTimeMs: Long,
		val endTimeMs: Long,
		val startElapsedRealtimeNanos: Long,
		val endElapsedRealtimeNanos: Long?,
	)

	private data class PressureFactCursor(
		val writerProjectionId: String,
		val writerProjectionVersion: Int,
		val logicalFactId: String,
		val semanticRevision: Long,
	) : Comparable<PressureFactCursor> {
		constructor(fact: PressureFactRevisionEntity) : this(
			writerProjectionId = fact.writerProjectionId,
			writerProjectionVersion = fact.writerProjectionVersion,
			logicalFactId = fact.logicalFactId,
			semanticRevision = fact.semanticRevision,
		)

		override fun compareTo(other: PressureFactCursor): Int = compareValuesBy(
			this,
			other,
			PressureFactCursor::writerProjectionId,
			PressureFactCursor::writerProjectionVersion,
			PressureFactCursor::logicalFactId,
			PressureFactCursor::semanticRevision,
		)
	}

	private data class PressureFactLineageKey(
		val writerProjectionId: String,
		val writerProjectionVersion: Int,
		val logicalFactId: String,
	) {
		constructor(fact: PressureFactRevisionEntity) : this(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		)
	}

	private data class SelectedScope(
		val segment: SessionSegment,
		val logicalTrackingId: String,
		val logicalSessionState: String,
		val currentServiceRunId: String?,
		val serviceRun: SourceServiceRunEntity,
		val factCount: Long,
		val retentionAuthorityCount: Long,
		val retentionRevisionAuthorityCount: Long,
		val collectedDataEpoch: Long,
		val zoneId: ZoneId,
		val affectedDays: List<Long>,
		val dailySummaries: List<DailySummaryEntity>,
	)

	private companion object {
		const val FIRST_PRESSURE_DELETION_GENERATION = 1L
		const val MIN_CAPTURE_QOS_CODE = 1
		const val MAX_CAPTURE_QOS_CODE = 3
		const val MAX_AFFECTED_DAYS = 370L
		const val MAX_SELECTED_MANIFESTS = 256
		const val MAX_SELECTED_MANIFEST_SOURCES = 3_072
		const val FACT_PAGE_SIZE = 256
		const val MAX_SELECTED_FACT_PAGES = 16_384
		const val MAX_PRESSURE_LINEAGE_REVISIONS = 8_192
		const val MAX_SELECTED_RETENTION_AUTHORITIES = 16_384
		const val MAX_SELECTED_RETENTION_REVISION_AUTHORITIES = 4_194_304L
		const val PRESSURE_WAL_ID_BATCH_SIZE = 128
		const val POLICY_RECONCILIATION_ORIGIN = "POLICY_RECONCILIATION"
		val KNOWN_SOURCE_KINDS = SourceKind.entries.mapTo(hashSetOf(), SourceKind::stableCode)
		val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val TERMINAL_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val SESSION_MODE_BY_START_ORIGIN = mapOf(
			"MANUAL_FOREGROUND_START" to "MANUAL",
			"AUTOMATIC_BACKGROUND_START" to "AUTOMATIC",
			"RECOVERY" to "MANUAL",
			POLICY_RECONCILIATION_ORIGIN to "MANUAL",
		)
	}
}
