package com.adsamcik.tracker.tracker.source.deletion

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.ActivityCapturedSelectedDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.ActivityCapturedSelectedDeletionConcurrentMutationException
import com.adsamcik.tracker.shared.base.database.ActivityCapturedSelectedDeletionResult
import com.adsamcik.tracker.shared.base.database.ActivityCapturedSelectedRunScope
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryLockedDays
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.deleteSelectedCapturedActivityFactsInTransaction
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletion
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionUnsupportedReason
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

/** Exact selected logical-session deletion for captured Activity facts and replacement segments. */
@Singleton
@Suppress("LargeClass", "TooManyFunctions")
internal class RoomActivitySelectedSessionDeletionService internal constructor(
	private val database: AppDatabase,
	private val dirtyTracker: MetricDirtyTracker,
	private val wallTimeMsProvider: () -> Long,
	private val requestActivityDrain: () -> Unit,
	private val afterDayLocksAcquired: suspend () -> Unit = {},
	private val beforeMutation: suspend () -> Unit = {},
	private val afterActivityPayloadDeleted: suspend () -> Unit = {},
) : ActivitySessionDeletion {
	@Inject
	constructor(
		database: AppDatabase,
		dirtyTracker: MetricDirtyTracker,
		sourcePipelineRecovery: Provider<SourcePipelineRecovery>,
	) : this(
		database = database,
		dirtyTracker = dirtyTracker,
		wallTimeMsProvider = System::currentTimeMillis,
		requestActivityDrain = { sourcePipelineRecovery.get().requestActivityCapturedFactDrain() },
	)

	@Suppress("CyclomaticComplexMethod", "ReturnCount", "ThrowsCount")
	override suspend fun deleteSelectedSession(sessionSegmentId: Long): ActivitySessionDeletionResult {
		if (sessionSegmentId <= 0L) return unsupported(
			ActivitySessionDeletionUnsupportedReason.INVALID_SEGMENT_ID,
		)
		val deletedAtMs = wallTimeMsProvider().coerceAtLeast(0L)
		val result = try {
			val selected = database.sessionSegmentDao().getById(sessionSegmentId)
				?: return ActivitySessionDeletionResult.NotFound
			if (preReadHasActiveOwner(selected)) return ActivitySessionDeletionResult.BlockedActive
			val expected = when (val preflight = selectedScope(selected)) {
				is SelectedScopePreflight.Ready -> preflight.scope
				is SelectedScopePreflight.Unsupported -> return unsupported(preflight.reason)
				SelectedScopePreflight.LegacyUnverifiable ->
					return ActivitySessionDeletionResult.LegacyUnverifiable
			}
			DailySummaryAggregator(
				dailySummaryDao = database.dailySummaryDao(),
				sessionSegmentDao = database.sessionSegmentDao(),
				zoneId = expected.lockZone,
			).withDayLocks(expected.affectedDays) { lockedDays ->
				afterDayLocksAcquired()
				database.withTransaction {
					val retainedSelected = database.sessionSegmentDao().getById(sessionSegmentId)
						?: throw ConcurrentActivityDeletionStateException()
					val actual = (selectedScope(retainedSelected) as? SelectedScopePreflight.Ready)?.scope
						?: throw ConcurrentActivityDeletionStateException()
					if (actual != expected) throw ConcurrentActivityDeletionStateException()
					deleteInTransaction(actual, deletedAtMs, lockedDays)
				}
			}
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: ActivityCapturedSelectedDeletionConcurrentMutationException) {
			ActivitySessionDeletionResult.RetryableFailure(
				ActivitySessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: ConcurrentActivityDeletionStateException) {
			ActivitySessionDeletionResult.RetryableFailure(
				ActivitySessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: IllegalArgumentException) {
			unsupported(ActivitySessionDeletionUnsupportedReason.FACT_INTEGRITY_FAILED)
		} catch (_: SQLiteException) {
			ActivitySessionDeletionResult.RetryableFailure(
				ActivitySessionDeletionRetryableReason.DATABASE_UNAVAILABLE,
			)
		}
		if (result == ActivitySessionDeletionResult.Deleted) {
			dirtyTracker.markDirty(
				setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
			)
			requestActivityDrain()
		}
		return result
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ThrowsCount")
	private suspend fun deleteInTransaction(
		scope: SelectedScope,
		deletedAtMs: Long,
		lockedDays: DailySummaryLockedDays,
	): ActivitySessionDeletionResult {
		when (liveMutationAuthority(scope)) {
			LiveMutationAuthority.Blocked -> return ActivitySessionDeletionResult.BlockedActive
			LiveMutationAuthority.Unverifiable -> return unsupported(
				ActivitySessionDeletionUnsupportedReason.PROVIDER_AUTHORITY_UNVERIFIABLE,
			)
			LiveMutationAuthority.Quiesced -> Unit
		}
		val repairPlans = when (val repair = StepsDailySummaryRepairComposer(database).compose(
			epochDays = scope.affectedDays,
			excludedSegmentIds = scope.segments.mapTo(linkedSetOf(), SessionSegment::id),
			dailySummaries = scope.dailySummaries,
		)) {
			is StepsDayRepairPreflight.Ready -> repair.plans
			is StepsDayRepairPreflight.Unsupported -> return unsupported(
				ActivitySessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
			StepsDayRepairPreflight.Materializing -> return ActivitySessionDeletionResult.RetryableFailure(
				ActivitySessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING,
			)
		}
		beforeMutation()
		val payloadDeletion = database.deleteSelectedCapturedActivityFactsInTransaction(
			logicalTrackingId = scope.logicalTrackingId,
			runScopes = scope.runs.map { run ->
				ActivityCapturedSelectedRunScope(
					serviceRunId = run.serviceRunId,
					sessionSegmentId = requireNotNull(run.sessionSegmentId),
				)
			},
			expectedCollectedDataEpoch = scope.collectedDataEpoch,
			deletedAtMs = deletedAtMs,
		)
		when (payloadDeletion) {
			is ActivityCapturedSelectedDeletionResult.Deleted -> Unit
			is ActivityCapturedSelectedDeletionResult.Blocked -> return when (payloadDeletion.reason) {
				ActivityCapturedSelectedDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED -> unsupported(
					ActivitySessionDeletionUnsupportedReason.MAINTENANCE_BOUND_EXCEEDED,
				)
				ActivityCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE -> unsupported(
					ActivitySessionDeletionUnsupportedReason.FACT_INTEGRITY_FAILED,
				)
				ActivityCapturedSelectedDeletionBlockedReason.STALE_COLLECTED_DATA_EPOCH -> unsupported(
					ActivitySessionDeletionUnsupportedReason.STALE_COLLECTED_DATA_EPOCH,
				)
				ActivityCapturedSelectedDeletionBlockedReason.STALE_REQUEST -> unsupported(
					ActivitySessionDeletionUnsupportedReason.STALE_REQUEST,
				)
				ActivityCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT -> unsupported(
					ActivitySessionDeletionUnsupportedReason.DELETION_FENCE_CONFLICT,
				)
			}
		}
		afterActivityPayloadDeleted()
		currentCoroutineContext().ensureActive()
		for ((run, segment) in scope.runs.zip(scope.segments)) {
			database.skiRunSegmentDao().deleteBySession(segment.id)
			if (database.sessionSegmentDao().deleteExact(
					id = segment.id,
					logicalTrackingId = scope.logicalTrackingId,
					serviceRunId = run.serviceRunId,
				) != 1
			) throw ConcurrentActivityDeletionStateException()
		}
		for (plan in repairPlans) {
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
		return ActivitySessionDeletionResult.Deleted
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun selectedScope(selected: SessionSegment): SelectedScopePreflight {
		val logicalTrackingId = selected.logicalTrackingId
		val selectedRunId = selected.serviceRunId
		if (logicalTrackingId == null && selectedRunId == null) {
			return SelectedScopePreflight.LegacyUnverifiable
		}
		if (logicalTrackingId.isNullOrBlank() || selectedRunId.isNullOrBlank()) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.INCOMPLETE_ATTRIBUTION,
		)
		val sessionDao = database.sourceSessionDao()
		val directSelectedRun = sessionDao.serviceRun(selectedRunId) ?: return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.SERVICE_RUN_MISSING,
		)
		if (directSelectedRun.logicalTrackingId != logicalTrackingId ||
			directSelectedRun.sessionSegmentId != selected.id
		) return unsupportedScope(ActivitySessionDeletionUnsupportedReason.SERVICE_RUN_BINDING_MISMATCH)
		val logicalSession = sessionDao.session(logicalTrackingId) ?: return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.LOGICAL_SESSION_MISSING,
		)
		if (logicalSession.logicalTrackingId != logicalTrackingId ||
			logicalSession.sessionMode !in SUPPORTED_SESSION_MODES || logicalSession.rolloutRevision <= 0L ||
			logicalSession.startedAtMs < 0L || logicalSession.startedElapsedNanos < 0L
		) return unsupportedScope(ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH)
		val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			logicalTrackingIds = listOf(logicalTrackingId),
			limit = MAX_REPLACEMENT_RUNS + 1,
			afterLogicalTrackingId = null,
			afterStartedAtMs = null,
			afterServiceRunId = null,
		)
		if (runs.size > MAX_REPLACEMENT_RUNS) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_OVERFLOW,
		)
		if (runs.isEmpty() || runs.map(SourceServiceRunEntity::serviceRunId).distinct().size != runs.size ||
			runs.any { run -> run.logicalTrackingId != logicalTrackingId }
		) return unsupportedScope(ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH)
		val selectedRun = runs.singleOrNull { run -> run.serviceRunId == selectedRunId }
			?: return unsupportedScope(ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH)
		if (selectedRun != directSelectedRun) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH,
		)
		if (runs.any { run ->
			run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE ||
				run.sessionSegmentId == null
		}) return SelectedScopePreflight.LegacyUnverifiable
		val segmentIds = runs.map { run -> requireNotNull(run.sessionSegmentId) }
		if (segmentIds.distinct().size != segmentIds.size) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH,
		)
		val segmentsById = segmentIds.chunked(QUERY_ID_BATCH_SIZE).flatMap { ids ->
			database.trackingHistoryReadDao().segments(ids)
		}.associateBy(SessionSegment::id)
		if (segmentsById.size != segmentIds.size) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH,
		)
		val segments = runs.map { run ->
			val segment = segmentsById[requireNotNull(run.sessionSegmentId)] ?: return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH,
			)
			if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != run.serviceRunId) {
				return unsupportedScope(ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH)
			}
			segment
		}
		if (segments.singleOrNull { segment -> segment.id == selected.id } != selected) {
			return unsupportedScope(ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH)
		}
		val reverseOwnedSegments = database.sessionSegmentDao().selectedOwnershipSegments(
			logicalTrackingId = logicalTrackingId,
			serviceRunIds = runs.map(SourceServiceRunEntity::serviceRunId),
			limit = MAX_REPLACEMENT_RUNS + 1,
		)
		if (reverseOwnedSegments.size > MAX_REPLACEMENT_RUNS) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_OVERFLOW,
		)
		if (reverseOwnedSegments.size != segments.size ||
			reverseOwnedSegments.mapTo(hashSetOf(), SessionSegment::id) !=
			segments.mapTo(hashSetOf(), SessionSegment::id)
		) return unsupportedScope(ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH)

		val runIds = runs.map(SourceServiceRunEntity::serviceRunId)
		val manifests = database.trackingHistoryReadDao().manifests(
			runIds,
			MAX_TOTAL_MANIFESTS + 1,
		)
		if (manifests.size > MAX_TOTAL_MANIFESTS) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_OVERFLOW,
		)
		val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
		if (runs.any { run -> manifestsByRun[run.serviceRunId].isNullOrEmpty() }) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.MANIFEST_MISSING,
		)
		if (runs.any { run ->
			val owned = manifestsByRun[run.serviceRunId].orEmpty()
			owned.size > MAX_MANIFESTS_PER_RUN ||
				!SessionManifestIntegrity.hasValidServiceRunTimeline(run, owned)
		}) return unsupportedScope(ActivitySessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED)
		val logicalCompletedAtMs = logicalSession.completedAtMs
		if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				runs.map { run ->
					manifestsByRun.getValue(run.serviceRunId).map(
						SessionManifestVersionEntity::manifestRevision,
					)
				},
			) || logicalSession.currentManifestRevision != manifests.maxOfOrNull(
				SessionManifestVersionEntity::manifestRevision,
			) || manifests.any { manifest ->
				manifest.sessionMode != logicalSession.sessionMode ||
					manifest.rolloutRevision != logicalSession.rolloutRevision
			} || runs.minOf(SourceServiceRunEntity::startedAtMs) < logicalSession.startedAtMs ||
			(logicalCompletedAtMs != null && runs.any { run ->
				run.completedAtMs?.let { completed -> completed > logicalCompletedAtMs } == true
			})
		) return unsupportedScope(ActivitySessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED)
		val sources = database.trackingHistoryReadDao().manifestSources(
			runIds,
			MAX_TOTAL_MANIFEST_SOURCES + 1,
		)
		if (sources.size > MAX_TOTAL_MANIFEST_SOURCES) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_OVERFLOW,
		)
		val sourcesByRevision = sources.groupBy { source ->
			source.logicalTrackingId to source.manifestRevision
		}
		val zonesByRun = linkedMapOf<String, MutableSet<ZoneId>>()
		val captureByRevision = linkedMapOf<Long, SessionManifestSourceEntity>()
		for (manifest in manifests) {
			if (manifest.logicalTrackingId != logicalTrackingId ||
				manifest.serviceRunId !in runIds
			) return unsupportedScope(ActivitySessionDeletionUnsupportedReason.MANIFEST_MEMBERSHIP_MISMATCH)
			val membership = sourcesByRevision[logicalTrackingId to manifest.manifestRevision].orEmpty()
			if (!SessionManifestIntegrity.verify(manifest, membership)) return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED,
			)
			if (membership.any { source ->
				source.purpose !in SessionManifestPurposeCode.ALL ||
					source.sourceKind !in KNOWN_SOURCE_KINDS
			}) return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)
			val captured = membership.filter { source ->
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
			}
			if (captured.size != 1 || captured.single().sourceKind != ACTIVITY_SOURCE ||
				!captured.single().persistenceEligible
			) return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)
			if (!captured.single().isExactActivityWriter()) return unsupportedScope(
				if (captured.single().writerOwner ==
					SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT
				) ActivitySessionDeletionUnsupportedReason.LEGACY_WRITER
				else ActivitySessionDeletionUnsupportedReason.CANDIDATE_WRITER_BINDING_INVALID,
			)
			captureByRevision[manifest.manifestRevision] = captured.single()
			val zone = try {
				ZoneId.of(manifest.zoneId)
			} catch (_: DateTimeException) {
				return unsupportedScope(ActivitySessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE)
			}
			zonesByRun.getOrPut(manifest.serviceRunId, ::linkedSetOf).add(zone)
		}
		if (captureByRevision.size != manifests.size) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
		)
		val policies = database.trackingHistoryReadDao().policiesForServiceRuns(
			sourceKind = ACTIVITY_SOURCE,
			serviceRunIds = runIds,
		)
		val policiesByRevision = policies.associateBy { policy -> policy.policyRevision }
		if (policiesByRevision.size != policies.size) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
		)
		val consentEpochs = captureByRevision.values.map(
			SessionManifestSourceEntity::consentEpoch,
		).distinct()
		if (consentEpochs.isEmpty() || consentEpochs.size > MAX_TOTAL_MANIFESTS) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
		)
		val consents = consentEpochs.chunked(QUERY_ID_BATCH_SIZE).flatMap { epochs ->
			database.sourcePolicyDao().consentEpochs(
				sourceKind = ACTIVITY_SOURCE,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				epochs = epochs,
			)
		}
		val consentsByEpoch = consents.associateBy { consent -> consent.epoch }
		if (consentsByEpoch.size != consentEpochs.size || consentsByEpoch.keys != consentEpochs.toSet()) {
			return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)
		}
		for (manifest in manifests) {
			val binding = captureByRevision[manifest.manifestRevision] ?: return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)
			val policy = policiesByRevision[manifest.sourcePolicyRevision]
			val consent = consentsByEpoch[binding.consentEpoch]
			if (policy == null || consent == null || policy.sourceKind != ACTIVITY_SOURCE ||
				!policy.enabled || !policy.capturePersistenceEligible ||
				policy.captureConsentEpoch != binding.consentEpoch || policy.qosCode != binding.qosCode ||
				!consent.eligible || !consent.persistenceEligible ||
				consent.policyRevision > manifest.sourcePolicyRevision
			) return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)
		}
		val evidenceState = database.sourceEvidenceStateDao().get() ?: return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.SOURCE_EVIDENCE_STATE_MISSING,
		)
		val affectedDays = sortedSetOf<Long>()
		for ((run, segment) in runs.zip(segments)) {
			for (zone in zonesByRun[run.serviceRunId].orEmpty()) {
				affectedEpochDays(segment.startTimeMs, segment.endTimeMs, zone)
					?.let(affectedDays::addAll) ?: return unsupportedScope(
					ActivitySessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
				)
			}
			plausiblyPersistedEpochDays(segment.startTimeMs, segment.endTimeMs)
				?.let(affectedDays::addAll) ?: return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
			)
			if (affectedDays.size > MAX_AFFECTED_DAYS) return unsupportedScope(
				ActivitySessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
			)
		}
		if (affectedDays.isEmpty()) return unsupportedScope(
			ActivitySessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
		)
		val affectedDaySet = affectedDays.toSet()
		val summaries = database.dailySummaryDao().getBetween(affectedDays.first(), affectedDays.last())
			.filter { summary -> summary.dateEpochDay in affectedDaySet }
		return SelectedScopePreflight.Ready(
			SelectedScope(
				logicalTrackingId = logicalTrackingId,
				logicalSession = logicalSession,
				runs = runs,
				segments = segments,
				collectedDataEpoch = evidenceState.collectedDataEpoch,
				lockZone = zonesByRun.values.flatten().first(),
				affectedDays = affectedDays.toList(),
				dailySummaries = summaries,
			),
		)
	}

	private suspend fun preReadHasActiveOwner(selected: SessionSegment): Boolean {
		val logicalId = selected.logicalTrackingId?.takeIf(String::isNotBlank) ?: return false
		val runId = selected.serviceRunId?.takeIf(String::isNotBlank) ?: return false
		val run = database.sourceSessionDao().serviceRun(runId) ?: return false
		val session = database.sourceSessionDao().session(logicalId) ?: return false
		return run.logicalTrackingId == logicalId && run.sessionSegmentId == selected.id &&
			(session.state !in TERMINAL_SESSION_STATES || session.currentServiceRunId != null ||
				run.state !in TERMINAL_RUN_STATES || run.completedAtMs == null ||
				database.sourceSessionDao().hasNonterminalLatestLifecycleAction(logicalId, runId) ||
				database.sourceBrokerDao().currentDemands("session:$logicalId")
					.any { demand -> demand.serviceRunId == runId })
	}

	@Suppress("ReturnCount")
	private suspend fun liveMutationAuthority(scope: SelectedScope): LiveMutationAuthority {
		if (scope.logicalSession.state !in TERMINAL_SESSION_STATES ||
			scope.logicalSession.currentServiceRunId != null || scope.logicalSession.completedAtMs == null ||
			scope.runs.any { run -> run.state !in TERMINAL_RUN_STATES || run.completedAtMs == null }
		) return LiveMutationAuthority.Blocked
		val runIds = scope.runs.map(SourceServiceRunEntity::serviceRunId)
		if (scope.runs.any { run ->
			database.sourceSessionDao().hasNonterminalLatestLifecycleAction(
				scope.logicalTrackingId,
				run.serviceRunId,
			)
		}) return LiveMutationAuthority.Blocked
		val demands = database.activityCapturedFactDao().selectedCapturedActivityDemands(
			sourceKind = ACTIVITY_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = scope.logicalTrackingId,
			serviceRunIds = runIds,
			limit = MAX_SELECTED_DEMANDS + 1,
		)
		if (demands.size > MAX_SELECTED_DEMANDS) return LiveMutationAuthority.Unverifiable
		if (demands.any { demand ->
			demand.logicalTrackingId != scope.logicalTrackingId || demand.serviceRunId !in runIds
		}) return LiveMutationAuthority.Unverifiable
		if (demands.isNotEmpty()) return LiveMutationAuthority.Blocked

		val registrations = database.activityCapturedFactDao().activityRegistrationsForDeletion(
			ACTIVITY_SOURCE,
			MAX_CURRENT_REGISTRATIONS + 1,
		)
		if (registrations.size > MAX_CURRENT_REGISTRATIONS) return LiveMutationAuthority.Unverifiable
		for (registration in registrations) {
			val rows = try {
				val latestAuthorizationRevision = database.activityCapturedFactDao()
					.latestAuthorizationRevision(ACTIVITY_SOURCE, registration.registrationGeneration)
				latestAuthorizationRevision?.let { revision ->
					database.activityCapturedFactDao().maintenanceAuthorizationMembers(
						sourceKind = ACTIVITY_SOURCE,
						registrationGeneration = registration.registrationGeneration,
						authorizationRevision = revision,
						limit = MAX_AUTHORIZATION_MEMBERS + 1,
					)
				}.orEmpty()
			} catch (_: IllegalArgumentException) {
				return LiveMutationAuthority.Unverifiable
			}
			if (rows.size > MAX_AUTHORIZATION_MEMBERS) return LiveMutationAuthority.Unverifiable
			if (rows.isEmpty()) {
				if (registration.acceptedElapsedRealtimeNanos != null) {
					return LiveMutationAuthority.Unverifiable
				}
				continue
			}
			val authorization = try {
				rows.toAuthorizationSnapshotOrNull()
			} catch (_: IllegalArgumentException) {
				return LiveMutationAuthority.Unverifiable
			}
			if (authorization == null) return LiveMutationAuthority.Unverifiable
			for (row in rows.filter { member ->
				member.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
					(member.logicalTrackingId == scope.logicalTrackingId ||
						member.serviceRunId in runIds)
			}) {
				if (row.logicalTrackingId != scope.logicalTrackingId || row.serviceRunId !in runIds) {
					return LiveMutationAuthority.Unverifiable
				}
				if (!row.persistenceEligible) return LiveMutationAuthority.Unverifiable
				return LiveMutationAuthority.Blocked
			}
		}
		return LiveMutationAuthority.Quiesced
	}

	private fun SessionManifestSourceEntity.isExactActivityWriter(): Boolean =
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
			writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
			writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
			writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
			writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION &&
			writerBindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION

	private fun affectedEpochDays(startTimeMs: Long, endTimeMs: Long, zoneId: ZoneId): List<Long>? =
		affectedEpochDays(startTimeMs, endTimeMs, zoneId, zoneId)

	private fun plausiblyPersistedEpochDays(startTimeMs: Long, endTimeMs: Long): List<Long>? =
		affectedEpochDays(startTimeMs, endTimeMs, ZoneOffset.MIN, ZoneOffset.MAX)

	private fun affectedEpochDays(
		startTimeMs: Long,
		endTimeMs: Long,
		startZoneId: ZoneId,
		endZoneId: ZoneId,
	): List<Long>? {
		val lastCoveredMs = if (endTimeMs > startTimeMs) endTimeMs - 1L else startTimeMs
		val startDay: Long
		val endDay: Long
		try {
			startDay = Instant.ofEpochMilli(startTimeMs).atZone(startZoneId).toLocalDate().toEpochDay()
			endDay = Instant.ofEpochMilli(lastCoveredMs).atZone(endZoneId).toLocalDate().toEpochDay()
		} catch (_: DateTimeException) {
			return null
		}
		val count = endDay - startDay + 1L
		if (count <= 0L || count > MAX_AFFECTED_DAYS) return null
		return List(count.toInt()) { offset -> startDay + offset }
	}

	private fun unsupported(reason: ActivitySessionDeletionUnsupportedReason) =
		ActivitySessionDeletionResult.UnsupportedScope(reason)

	private fun unsupportedScope(reason: ActivitySessionDeletionUnsupportedReason) =
		SelectedScopePreflight.Unsupported(reason)

	private sealed interface SelectedScopePreflight {
		data class Ready(val scope: SelectedScope) : SelectedScopePreflight
		data class Unsupported(
			val reason: ActivitySessionDeletionUnsupportedReason,
		) : SelectedScopePreflight
		data object LegacyUnverifiable : SelectedScopePreflight
	}

	private enum class LiveMutationAuthority { Quiesced, Blocked, Unverifiable }

	private data class SelectedScope(
		val logicalTrackingId: String,
		val logicalSession: LogicalTrackingSessionEntity,
		val runs: List<SourceServiceRunEntity>,
		val segments: List<SessionSegment>,
		val collectedDataEpoch: Long,
		val lockZone: ZoneId,
		val affectedDays: List<Long>,
		val dailySummaries: List<DailySummaryEntity>,
	)

	private class ConcurrentActivityDeletionStateException : IllegalStateException()

	private companion object {
		const val ACTIVITY_SOURCE = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		const val MAX_REPLACEMENT_RUNS = 256
		const val MAX_MANIFESTS_PER_RUN = 256
		const val MAX_TOTAL_MANIFESTS = 4_096
		const val MAX_TOTAL_MANIFEST_SOURCES = 49_152
		const val MAX_AFFECTED_DAYS = 370
		const val MAX_SELECTED_DEMANDS = 256
		const val MAX_CURRENT_REGISTRATIONS = 64
		const val MAX_AUTHORIZATION_MEMBERS = 64
		const val QUERY_ID_BATCH_SIZE = 128
		val KNOWN_SOURCE_KINDS = SourceKind.entries.mapTo(hashSetOf(), SourceKind::stableCode)
		val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val TERMINAL_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val SUPPORTED_SESSION_MODES = setOf("MANUAL", "AUTOMATIC")
	}
}
