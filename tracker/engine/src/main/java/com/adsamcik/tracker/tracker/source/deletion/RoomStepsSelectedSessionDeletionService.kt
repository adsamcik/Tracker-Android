package com.adsamcik.tracker.tracker.source.deletion

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryLockedDays
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletion
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Exact, source-local selected-session deletion for the new-v28 Steps fact writer. */
@Singleton
internal class RoomStepsSelectedSessionDeletionService internal constructor(
	private val database: AppDatabase,
	private val dirtyTracker: MetricDirtyTracker,
	private val wallTimeMsProvider: () -> Long,
	private val requestStepsDrain: () -> Unit,
	private val afterDayLocksAcquired: suspend () -> Unit = {},
	private val beforeMutation: suspend () -> Unit = {},
) : StepsSessionDeletion {
	@Inject
	constructor(
		database: AppDatabase,
		dirtyTracker: MetricDirtyTracker,
		sourcePipelineRecovery: Provider<SourcePipelineRecovery>,
	) : this(
		database = database,
		dirtyTracker = dirtyTracker,
		wallTimeMsProvider = System::currentTimeMillis,
		requestStepsDrain = { sourcePipelineRecovery.get().requestStepsSessionFactDrain() },
	)

	@Suppress("CyclomaticComplexMethod", "ReturnCount", "ThrowsCount")
	override suspend fun deleteSelectedSession(
		sessionSegmentId: Long,
	): StepsSessionDeletionResult {
		if (sessionSegmentId <= 0L) {
			return unsupported(StepsSessionDeletionUnsupportedReason.INVALID_SEGMENT_ID)
		}
		val deletedAtMs = wallTimeMsProvider().coerceAtLeast(0L)
		val result = try {
			val preReadSegment = database.sessionSegmentDao().getById(sessionSegmentId)
				?: return StepsSessionDeletionResult.NotFound
			if (preReadHasActiveOwner(preReadSegment)) {
				return StepsSessionDeletionResult.BlockedActive
			}
			val expectedScope = when (val preflight = selectedScope(preReadSegment)) {
				is SelectedScopePreflight.Ready -> preflight.scope
				is SelectedScopePreflight.Unsupported -> return unsupported(preflight.reason)
				SelectedScopePreflight.LegacyUnverifiable ->
					return StepsSessionDeletionResult.LegacyUnverifiable
			}
			val lockCoordinator = DailySummaryAggregator(
				dailySummaryDao = database.dailySummaryDao(),
				sessionSegmentDao = database.sessionSegmentDao(),
				zoneId = expectedScope.zoneId,
			)
			lockCoordinator.withDayLocks(expectedScope.affectedDays) { lockedDays ->
				afterDayLocksAcquired()
				database.withTransaction {
					val segment = database.sessionSegmentDao().getById(sessionSegmentId)
						?: throw ConcurrentDeletionStateException()
					val actualScope = (selectedScope(segment) as? SelectedScopePreflight.Ready)?.scope
						?: throw ConcurrentDeletionStateException()
					if (actualScope != expectedScope) {
						throw ConcurrentDeletionStateException()
					}
					deleteInTransaction(
						scope = actualScope,
						deletedAtMs = deletedAtMs,
						lockedDays = lockedDays,
					)
				}
			}
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: ConcurrentDeletionStateException) {
			StepsSessionDeletionResult.RetryableFailure(
				StepsSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			StepsSessionDeletionResult.RetryableFailure(
				StepsSessionDeletionRetryableReason.DATABASE_UNAVAILABLE,
			)
		}

		if (result == StepsSessionDeletionResult.Deleted) {
			// The Room transaction is already committed. Dirty consumers must never observe a write
			// that can still roll back.
			dirtyTracker.markDirty(
				setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
			)
			// A hint is enough: the WAL/fence is durable and startup recovery retries after a crash.
			requestStepsDrain()
		}
		return result
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ThrowsCount")
	private suspend fun deleteInTransaction(
		scope: SelectedScope,
		deletedAtMs: Long,
		lockedDays: DailySummaryLockedDays,
	): StepsSessionDeletionResult {
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
			return StepsSessionDeletionResult.BlockedActive
		}

		val factDao = database.stepFactRevisionDao()
		val repair = when (
			val preflight = StepsDailySummaryRepairComposer(database).compose(
				epochDays = scope.affectedDays,
				excludedSegmentId = segment.id,
				dailySummaries = scope.dailySummaries,
			)
		) {
			is StepsDayRepairPreflight.Ready -> preflight.plans
			is StepsDayRepairPreflight.Unsupported -> return unsupported(preflight.reason)
			StepsDayRepairPreflight.Materializing -> return StepsSessionDeletionResult.RetryableFailure(
				StepsSessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING,
			)
		}
		// Test seam and final cancellation point: every source-aware read completed, while no fence or
		// payload mutation has happened yet. Cancellation still rolls the whole Room transaction back.
		beforeMutation()
		val fenceDao = database.sourceDeletionFenceDao()
		val scopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		val existingFence = fenceDao.get(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = scopeDigest,
		)
		val proposedFence = existingFence ?: run {
			val priorGeneration = scope.factStates.maxOfOrNull(
				StepFactRevisionEntity::scopeDeletionGeneration,
			) ?: 0L
			if (priorGeneration == Long.MAX_VALUE) {
				return unsupported(StepsSessionDeletionUnsupportedReason.DELETION_GENERATION_EXHAUSTED)
			}
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				fenceGeneration = (priorGeneration + 1L).coerceAtLeast(1L),
				collectedDataEpoch = scope.collectedDataEpoch,
				deletedAtMs = deletedAtMs,
			)
		}
		if (existingFence == null) {
			fenceDao.insertIfAbsent(proposedFence)
		}
		val fence = fenceDao.get(
			sourceKind = proposedFence.sourceKind,
			purpose = proposedFence.purpose,
			scopeKind = proposedFence.scopeKind,
			scopeIdentityDigest = proposedFence.scopeIdentityDigest,
		) ?: throw ConcurrentDeletionStateException()

		scope.factStates.asSequence()
			.filter { fact -> fact.operation == StepFactRevisionEntity.OPERATION_UPSERT }
			.forEach { fact -> insertRetractionOrVerify(fact, fence) }
		factDao.deleteUpsertsForServiceRun(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		database.skiRunSegmentDao().deleteBySession(segment.id)
		if (database.sessionSegmentDao().deleteExact(segment.id, logicalTrackingId, serviceRunId) != 1) {
			throw ConcurrentDeletionStateException()
		}
		if (database.sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1) {
			throw ConcurrentDeletionStateException()
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
		return StepsSessionDeletionResult.Deleted
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun selectedScope(segment: SessionSegment): SelectedScopePreflight {
		val logicalTrackingId = segment.logicalTrackingId
		val serviceRunId = segment.serviceRunId
		if (logicalTrackingId == null && serviceRunId == null) {
			return SelectedScopePreflight.LegacyUnverifiable
		}
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.INCOMPLETE_ATTRIBUTION)
		}
		val sessionDao = database.sourceSessionDao()
		val serviceRun = sessionDao.serviceRun(serviceRunId)
			?: return unsupportedScope(StepsSessionDeletionUnsupportedReason.SERVICE_RUN_MISSING)
		if (serviceRun.presentationAcknowledgement ==
			SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
		) {
			return SelectedScopePreflight.LegacyUnverifiable
		}
		if (serviceRun.logicalTrackingId != logicalTrackingId || serviceRun.sessionSegmentId != segment.id) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.SERVICE_RUN_BINDING_MISMATCH)
		}
		val logicalSession = sessionDao.session(logicalTrackingId)
			?: return unsupportedScope(StepsSessionDeletionUnsupportedReason.LOGICAL_SESSION_MISSING)

		val manifests = sessionDao.manifestsForServiceRun(
			serviceRunId = serviceRunId,
			limit = MAX_SELECTED_MANIFESTS + 1,
		)
		if (manifests.isEmpty()) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.MANIFEST_MISSING)
		}
		if (manifests.size > MAX_SELECTED_MANIFESTS) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED)
		}
		val manifestSources = database.trackingHistoryReadDao().manifestSources(
			serviceRunIds = listOf(serviceRunId),
			limit = MAX_SELECTED_MANIFEST_SOURCES + 1,
		)
		if (manifestSources.size > MAX_SELECTED_MANIFEST_SOURCES) {
			return unsupportedScope(
				StepsSessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)
		}
		val sourcesByManifest = manifestSources.groupBy(SessionManifestSourceEntity::manifestRevision)
		val captureBindings = mutableListOf<SessionManifestSourceEntity>()
		val zonesByManifest = linkedMapOf<Long, ZoneId>()
		for (manifest in manifests) {
			if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId) {
				return unsupportedScope(StepsSessionDeletionUnsupportedReason.MANIFEST_MEMBERSHIP_MISMATCH)
			}
			val sources = sourcesByManifest[manifest.manifestRevision].orEmpty()
			if (sources.any { source -> source.logicalTrackingId != logicalTrackingId }) {
				return unsupportedScope(StepsSessionDeletionUnsupportedReason.MANIFEST_MEMBERSHIP_MISMATCH)
			}
			if (!SessionManifestIntegrity.verify(manifest, sources)) {
				return unsupportedScope(StepsSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED)
			}
			if (sources.any { source ->
				source.purpose !in SessionManifestPurposeCode.ALL ||
					source.sourceKind !in KNOWN_SOURCE_KINDS
			}) {
				return unsupportedScope(
					StepsSessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
				)
			}
			val exactCapture = sources.filter { source ->
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
			}
			if (exactCapture.size != 1 ||
				exactCapture.single().sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
				!exactCapture.single().persistenceEligible
			) {
				return unsupportedScope(
					StepsSessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
				)
			}
			captureBindings += exactCapture.single()
			zonesByManifest[manifest.manifestRevision] = try {
				ZoneId.of(manifest.zoneId)
			} catch (_: DateTimeException) {
				return unsupportedScope(StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE)
			}
		}
		if (captureBindings.any { source ->
			source.writerOwner == SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		}) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.LEGACY_WRITER)
		}
		if (!captureBindings.all(::isExactCandidateWriter) ||
			captureBindings.map(SessionManifestSourceEntity::writerOwnerGeneration).distinct().size != 1
		) {
			return unsupportedScope(
				StepsSessionDeletionUnsupportedReason.CANDIDATE_WRITER_BINDING_INVALID,
			)
		}
		val distinctZones = zonesByManifest.values.distinct()
		if (distinctZones.size != 1) {
			// The physical presentation segment is not partitioned by manifest revision.
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE)
		}

		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return unsupportedScope(StepsSessionDeletionUnsupportedReason.SOURCE_EVIDENCE_STATE_MISSING)
		val factDao = database.stepFactRevisionDao()
		val upserts = factDao.upsertsForServiceRun(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			limit = MAX_SELECTED_FACTS + 1,
		)
		if (upserts.isEmpty() || upserts.size > MAX_SELECTED_FACTS) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH)
		}
		val manifestsByRevision = manifests.associateBy(SessionManifestVersionEntity::manifestRevision)
		val bindingsByRevision = captureBindings.associateBy(SessionManifestSourceEntity::manifestRevision)
		for (fact in upserts) {
			val manifestRevision = fact.manifestRevision
			val manifest = manifestsByRevision[manifestRevision]
			val binding = bindingsByRevision[manifestRevision]
			if (
				manifest == null || binding == null ||
				fact.writerProjectionId != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID ||
				fact.writerProjectionVersion != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION ||
				fact.writerBindingGeneration != SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION ||
				fact.logicalTrackingId != logicalTrackingId || fact.serviceRunId != serviceRunId ||
				fact.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
				fact.sourcePolicyRevision != manifest.sourcePolicyRevision ||
				fact.captureConsentEpoch != binding.consentEpoch
			) {
				return unsupportedScope(StepsSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH)
			}
			if (fact.collectedDataEpoch != evidenceState.collectedDataEpoch) {
				return unsupportedScope(StepsSessionDeletionUnsupportedReason.STALE_COLLECTED_DATA_EPOCH)
			}
		}
		val manifestRevisions = manifests.map(SessionManifestVersionEntity::manifestRevision)
		val factStates = factDao.latestStatesForServiceRun(
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevisions = manifestRevisions,
			limit = MAX_SELECTED_FACTS + 1,
		)
		if (factStates.size > MAX_SELECTED_FACTS) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH)
		}
		if (factStates.any { fact -> fact.collectedDataEpoch != evidenceState.collectedDataEpoch }) {
			return unsupportedScope(StepsSessionDeletionUnsupportedReason.STALE_COLLECTED_DATA_EPOCH)
		}

		val affectedDays = sortedSetOf<Long>()
		val segmentZone = distinctZones.single()
		affectedEpochDays(segment.startTimeMs, segment.endTimeMs, segmentZone)?.let(affectedDays::addAll)
			?: return unsupportedScope(
				StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
			)
		plausiblyPersistedEpochDays(segment.startTimeMs, segment.endTimeMs)
			?.let(affectedDays::addAll)
			?: return unsupportedScope(
				StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
			)
		if (affectedDays.size.toLong() > MAX_AFFECTED_DAYS) {
			return unsupportedScope(
				StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
			)
		}
		for (fact in upserts) {
			val startMs = fact.intervalStartTimeMs
			val endMs = fact.intervalEndTimeMs
			val zoneId = zonesByManifest[fact.manifestRevision]
			if (startMs == null || endMs == null || zoneId == null) {
				return unsupportedScope(StepsSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH)
			}
			affectedEpochDays(startMs, endMs, zoneId)?.let(affectedDays::addAll)
				?: return unsupportedScope(
					StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
				)
			plausiblyPersistedEpochDays(startMs, endMs)?.let(affectedDays::addAll)
				?: return unsupportedScope(
					StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
				)
			if (affectedDays.size.toLong() > MAX_AFFECTED_DAYS) {
				return unsupportedScope(
					StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
				)
			}
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
				manifests = manifests,
				captureBindings = captureBindings,
				payloadUpserts = upserts,
				factStates = factStates,
				collectedDataEpoch = evidenceState.collectedDataEpoch,
				zoneId = segmentZone,
				affectedDays = affectedDays.toList(),
				dailySummaries = dailySummaries,
			),
		)
	}

	private fun unsupportedScope(
		reason: StepsSessionDeletionUnsupportedReason,
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
		serviceRun.completedAtMs == null ||
		serviceRun.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_QUIESCED

	private fun isExactCandidateWriter(source: SessionManifestSourceEntity): Boolean =
		source.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS &&
			source.writerOwner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS &&
			source.writerOwnerGeneration?.let { it > 0L } == true &&
			source.writerProjectionId == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID &&
			source.writerProjectionVersion == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION &&
			source.writerBindingGeneration == SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION

	private suspend fun insertRetractionOrVerify(
		fact: StepFactRevisionEntity,
		fence: SourceDeletionFenceEntity,
	) {
		val nextRevision = try {
			Math.addExact(fact.semanticRevision, 1L)
		} catch (_: ArithmeticException) {
			throw ConcurrentDeletionStateException()
		}
		val mutationDigest = digest(
			"steps-local-delete-mutation-v1",
			fence.scopeIdentityDigest,
			fact.logicalFactId,
			nextRevision,
			fence.fenceGeneration,
		)
		val retraction = buildRetraction(fact, fence, nextRevision, mutationDigest)
		if (database.stepFactRevisionDao().insert(retraction) == INSERT_IGNORED) {
			val current = database.stepFactRevisionDao().revision(
				retraction.writerProjectionId,
				retraction.writerProjectionVersion,
				retraction.logicalFactId,
				retraction.semanticRevision,
			)
			if (current != retraction) {
				throw ConcurrentDeletionStateException()
			}
		}
	}

	@Suppress("LongMethod") // Explicit redaction makes every cleared payload field reviewable.
	private fun buildRetraction(
		fact: StepFactRevisionEntity,
		fence: SourceDeletionFenceEntity,
		nextRevision: Long,
		mutationDigest: String,
	) = StepFactRevisionEntity(
			logicalFactId = fact.logicalFactId,
			semanticRevision = nextRevision,
			mutationId = "local-delete:$mutationDigest",
			stepIntervalId = null,
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = fence.scopeIdentityDigest,
			writerProjectionId = fact.writerProjectionId,
			writerProjectionVersion = fact.writerProjectionVersion,
			writerBindingGeneration = fact.writerBindingGeneration,
			operation = StepFactRevisionEntity.OPERATION_RETRACT,
			intervalStartTimeMs = null,
			intervalEndTimeMs = null,
			intervalStartElapsedRealtimeNanos = null,
			intervalEndElapsedRealtimeNanos = null,
			clockDomainId = null,
			bootClockDomainId = null,
			cumulativeStepCountStart = null,
			cumulativeStepCountEnd = null,
			wallTimeUncertaintyMs = null,
			coverageKind = null,
			effectiveStepCount = null,
			logicalTrackingId = null,
			serviceRunId = null,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			collectedDataEpoch = fence.collectedDataEpoch,
			scopeDeletionGeneration = fence.fenceGeneration,
			effectChecksum = digest(
				"steps-local-delete-effect-v1",
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
				nextRevision,
				mutationDigest,
				fence.scopeIdentityDigest,
				fence.fenceGeneration,
				fence.collectedDataEpoch,
				fence.deletedAtMs,
			),
			appliedAtMs = fence.deletedAtMs,
		)

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
		val startMs = startTimeMs
		val lastCoveredMs = if (endTimeMs > startMs) {
			endTimeMs - 1L
		} else {
			startMs
		}
		val startDay: Long
		val endDay: Long
		try {
			startDay = Instant.ofEpochMilli(startMs).atZone(startZoneId).toLocalDate().toEpochDay()
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

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) {
				"-1:"
			} else {
				"${text.length}:$text"
			}
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private fun unsupported(
		reason: StepsSessionDeletionUnsupportedReason,
	): StepsSessionDeletionResult = StepsSessionDeletionResult.UnsupportedScope(reason)

	private class ConcurrentDeletionStateException : IllegalStateException()

	private sealed interface SelectedScopePreflight {
		data class Ready(val scope: SelectedScope) : SelectedScopePreflight
		data class Unsupported(
			val reason: StepsSessionDeletionUnsupportedReason,
		) : SelectedScopePreflight
		data object LegacyUnverifiable : SelectedScopePreflight
	}

	private data class SelectedScope(
		val segment: SessionSegment,
		val logicalTrackingId: String,
		val logicalSessionState: String,
		val currentServiceRunId: String?,
		val serviceRun: SourceServiceRunEntity,
		val manifests: List<SessionManifestVersionEntity>,
		val captureBindings: List<SessionManifestSourceEntity>,
		val payloadUpserts: List<StepFactRevisionEntity>,
		val factStates: List<StepFactRevisionEntity>,
		val collectedDataEpoch: Long,
		val zoneId: ZoneId,
		val affectedDays: List<Long>,
		val dailySummaries: List<DailySummaryEntity>,
	)

	private companion object {
		const val INSERT_IGNORED = -1L
		const val MAX_AFFECTED_DAYS = 370L
		const val MAX_SELECTED_MANIFESTS = 256
		const val MAX_SELECTED_MANIFEST_SOURCES = 3_072
		const val MAX_SELECTED_FACTS = 2_048
		val KNOWN_SOURCE_KINDS = SourceKind.values().mapTo(hashSetOf(), SourceKind::stableCode)
		val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val TERMINAL_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
	}
}
