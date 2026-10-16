package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ActivityCapturedMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	REVISION_PAGE_LOADED,
	LINEAGE_AUTHENTICATED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

internal data class ActivityCapturedMaintenanceLimits(
	val revisionPageSize: Int = 256,
	val maximumRevisions: Int = 65_536,
	val maximumLogicalWindows: Int = 16_384,
	val maximumFragmentsPerRevision: Int = 1_024,
	val maximumEvidencePerRevision: Int = 4_096,
	val maximumTotalFragments: Int = 131_072,
	val maximumTotalEvidence: Int = 262_144,
	val maximumCursors: Int = 16_384,
	val maximumRegistrationPlans: Int = 4_096,
	val maximumManifestsPerRun: Int = 256,
	val maximumSourcesPerRun: Int = 3_072,
	val maximumAuthorizationMembers: Int = 64,
	val maximumCaptureDemands: Int = 256,
	val maximumNonterminalRegistrations: Int = 64,
) {
	init {
		require(revisionPageSize in 1..1_024)
		listOf(
			maximumRevisions,
			maximumLogicalWindows,
			maximumFragmentsPerRevision,
			maximumEvidencePerRevision,
			maximumTotalFragments,
			maximumTotalEvidence,
			maximumCursors,
			maximumRegistrationPlans,
			maximumManifestsPerRun,
			maximumSourcesPerRun,
			maximumAuthorizationMembers,
			maximumCaptureDemands,
			maximumNonterminalRegistrations,
		).forEach { limit -> require(limit in 1 until Int.MAX_VALUE) }
		require(maximumLogicalWindows <= maximumRevisions)
	}

	fun requireRevisionCapacity(revisionCount: Int) {
		if (revisionCount > maximumRevisions) {
			throw ActivityCapturedMaintenanceLimitExceeded()
		}
	}

	fun requirePayloadCapacity(fragmentCount: Int, evidenceCount: Int) {
		if (fragmentCount > maximumTotalFragments || evidenceCount > maximumTotalEvidence) {
			throw ActivityCapturedMaintenanceLimitExceeded()
		}
	}

	fun requireWindowCapacity(windowCount: Int) {
		if (windowCount > maximumLogicalWindows) {
			throw ActivityCapturedMaintenanceLimitExceeded()
		}
	}
}

/**
 * Changed source/destination authority, invalid payload or exceeded maintenance bounds preventing
 * retained Activity pruning.
 */
enum class ActivityCapturedRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_REQUEST,
}

/**
 * Removed Activity logical fact/window and revision counts, unchanged scope or a named authority
 * block.
 */
sealed interface ActivityCapturedRetentionResult {
	/**
	 * Reports logical local/imported scope and revision/gap counts removed by the verified retention
	 * cutoff.
	 */
	data class Pruned(
		val logicalWindowCount: Int,
		val revisionCount: Int,
	) : ActivityCapturedRetentionResult

	/**
	 * No saved payload in this source scope required removal for the requested cutoff.
	 */
	data object NoChange : ActivityCapturedRetentionResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: ActivityCapturedRetentionBlockedReason,
	) : ActivityCapturedRetentionResult
}

/**
 * Active capture consent/provider/demand, changed authority, invalid payload or conflicting fence
 * preventing source erasure.
 */
enum class ActivityCapturedSourceDeletionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	CAPTURE_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
}

/**
 * Source-wide native Activity payload removal and fenced-run counts, prior deletion or explicit
 * authority failure.
 */
sealed interface ActivityCapturedSourceDeletionResult {
	/**
	 * The exact selected payload was fenced and removed; reports original removed scope counts.
	 */
	data class Deleted(
		val logicalWindowCount: Int,
		val revisionCount: Int,
		val registrationPlanCount: Int,
		val fencedServiceRunCount: Int,
	) : ActivityCapturedSourceDeletionResult

	/**
	 * The exact selected source scope was already deleted and remains represented by its original
	 * deletion authority.
	 */
	data object AlreadyDeleted : ActivityCapturedSourceDeletionResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: ActivityCapturedSourceDeletionBlockedReason,
	) : ActivityCapturedSourceDeletionResult
}

/** Immutable physical owner expected to be removed with one selected logical Activity entry. */
data class ActivityCapturedSelectedRunScope(
	val serviceRunId: String,
	val sessionSegmentId: Long,
) {
	init {
		require(serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L)
	}
}

/** Fail-closed outcomes from exact selected captured-Activity payload maintenance. */
enum class ActivityCapturedSelectedDeletionBlockedReason {
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_COLLECTED_DATA_EPOCH,
	STALE_REQUEST,
	DELETION_FENCE_CONFLICT,
}

/** Result of authenticated selected captured-Activity payload maintenance. */
sealed interface ActivityCapturedSelectedDeletionResult {
	/** Exact counts removed after every selected physical run was fenced. */
	data class Deleted(
		val logicalWindowCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : ActivityCapturedSelectedDeletionResult

	/** No mutation was accepted because selected payload authority could not be proven. */
	data class Blocked(
		val reason: ActivityCapturedSelectedDeletionBlockedReason,
	) : ActivityCapturedSelectedDeletionResult
}

/** Forces the caller's enclosing Room transaction to roll back after a mutation-time race. */
class ActivityCapturedSelectedDeletionConcurrentMutationException internal constructor() :
	IllegalStateException("Captured Activity selected deletion changed during mutation")

/**
 * Removes whole authenticated captured-Activity window lineages affected by the global floor.
 *
 * A derived wall boundary is an uncertainty interval. If either endpoint of any retained band can
 * precede [beforeMs], the complete stable window lineage is removed. The already-durable global
 * retention floor then prevents the WAL adapter or writer from recreating that window.
 */
suspend fun AppDatabase.pruneCapturedActivityFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	markedAtMs: Long,
): ActivityCapturedRetentionResult = pruneCapturedActivityFactsAffectedByRetentionFloor(
	beforeMs,
	expectedCollectedDataEpoch,
	markedAtMs,
	DEFAULT_ACTIVITY_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.pruneCapturedActivityFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	markedAtMs: Long,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedRetentionResult {
	require(beforeMs >= 0L)
	require(expectedCollectedDataEpoch >= 0L)
	require(markedAtMs >= 0L)
	return try {
		pruneCapturedActivityLineages(beforeMs, expectedCollectedDataEpoch, markedAtMs, limits, checkpoint)
	} catch (blocked: ActivityCapturedRetentionBlockedException) {
		ActivityCapturedRetentionResult.Blocked(blocked.reason)
	} catch (@Suppress("SwallowedException") _: ActivityCapturedMaintenanceLimitExceeded) {
		ActivityCapturedRetentionResult.Blocked(
			ActivityCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		ActivityCapturedRetentionResult.Blocked(
			ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private suspend fun AppDatabase.pruneCapturedActivityLineages(
	beforeMs: Long, expectedCollectedDataEpoch: Long, markedAtMs: Long,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedRetentionResult =
	withTransaction {
		checkpoint(ActivityCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
		val state = sourceEvidenceStateDao().get()
		if (state == null || state.collectedDataEpoch != expectedCollectedDataEpoch ||
			state.retainedFromMs != beforeMs
		) {
			blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		}
		val audit = auditCapturedActivityFacts(limits, checkpoint)
		if (markedAtMs < audit.latestDurableTimeMs) {
			blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.STALE_REQUEST)
		}
		val selected = audit.lineages.filter { lineage ->
			lineage.earliestPossibleWallTimeMs?.let { earliest -> earliest < beforeMs } ?: true
		}
		if (selected.isEmpty()) {
			return@withTransaction ActivityCapturedRetentionResult.NoChange
		}
		val removedRevisions = removeRetainedActivityLineages(selected, checkpoint)
		check(sourceEvidenceStateDao().incrementRevision(maxOf(state.updatedAtMs, markedAtMs)) == 1) {
			"Unable to publish captured Activity retention"
		}
		ActivityCapturedRetentionResult.Pruned(selected.size, removedRevisions)
	}

internal data class ActivityCapturedPersistedRevision(
	val revision: ActivityCapturedWindowRevisionEntity,
	val fragments: List<ActivityCapturedFragmentEntity>,
	val evidence: List<ActivityCapturedEvidenceEntity>,
)

internal data class ActivityCapturedLineage(
	val logicalWindowId: String,
	val revisions: List<ActivityCapturedPersistedRevision>,
	/** Null means the complete lineage contains only explicit gaps and has no wall-time anchor. */
	val earliestPossibleWallTimeMs: Long?,
)

internal data class ActivityCapturedFactAudit(
	val lineages: List<ActivityCapturedLineage>,
	val latestDurableTimeMs: Long,
)

internal data class ActivityCapturedAuditScope(
	val logicalTrackingId: String,
	val runs: Map<String, ActivityCapturedSelectedRunScope>,
) {
	fun containsExactly(revision: ActivityCapturedWindowRevisionEntity): Boolean =
		revision.logicalTrackingId == logicalTrackingId &&
			runs[revision.serviceRunId]?.sessionSegmentId == revision.sessionSegmentId

	fun containsExactly(cursor: ActivityCapturedWindowCursorEntity): Boolean =
		cursor.logicalTrackingId == logicalTrackingId &&
			runs[cursor.serviceRunId]?.sessionSegmentId == cursor.sessionSegmentId
}

internal class ActivityCapturedRetentionBlockedException(
	val reason: ActivityCapturedRetentionBlockedReason,
) : IllegalStateException(reason.name)

internal class ActivityCapturedMaintenanceLimitExceeded : IllegalStateException()

internal fun blockCapturedActivityRetention(reason: ActivityCapturedRetentionBlockedReason): Nothing =
	throw ActivityCapturedRetentionBlockedException(reason)

internal const val ACTIVITY_CAPTURED_SOURCE_KIND = 2
internal const val ACTIVITY_CAPTURED_CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
internal const val ACTIVITY_CAPTURED_WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
internal const val ACTIVITY_CAPTURED_WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
internal const val ACTIVITY_CAPTURED_DELETE_BATCH_SIZE = 128

internal val DEFAULT_ACTIVITY_CAPTURED_MAINTENANCE_LIMITS = ActivityCapturedMaintenanceLimits()

private suspend fun AppDatabase.removeRetainedActivityLineages(
	selected: List<ActivityCapturedLineage>,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): Int {
	val dao = activityCapturedFactDao()
	var removedRevisions = 0
	selected.chunked(ACTIVITY_CAPTURED_DELETE_BATCH_SIZE).forEach { batch ->
		val ids = batch.map(ActivityCapturedLineage::logicalWindowId)
		val deletedCursors = dao.deleteExactCursors(ACTIVITY_CAPTURED_WRITER_ID, ACTIVITY_CAPTURED_WRITER_VERSION, ids)
		if (deletedCursors != batch.size) {
			blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val expectedRevisions = batch.sumOf { lineage -> lineage.revisions.size }
		val deletedRevisions = dao.deleteExactRevisionLineages(
			ACTIVITY_CAPTURED_WRITER_ID,
			ACTIVITY_CAPTURED_WRITER_VERSION,
			ids,
		)
		if (deletedRevisions != expectedRevisions) {
			blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		removedRevisions = Math.addExact(removedRevisions, deletedRevisions)
		checkpoint(ActivityCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
	}
	return removedRevisions
}

