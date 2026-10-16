package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.TrackingHistoryReadDao
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun revisionKey(entity: ActivityCapturedWindowRevisionEntity) = ActivityRevisionKey(
	entity.writerProjectionId,
	entity.writerProjectionVersion,
	entity.logicalWindowId,
	entity.semanticRevision,
)

internal fun revisionKey(entity: ActivityCapturedFragmentEntity) = ActivityRevisionKey(
	entity.writerProjectionId,
	entity.writerProjectionVersion,
	entity.logicalWindowId,
	entity.semanticRevision,
)

internal fun revisionKey(entity: ActivityCapturedEvidenceEntity) = ActivityRevisionKey(
	entity.writerProjectionId,
	entity.writerProjectionVersion,
	entity.logicalWindowId,
	entity.semanticRevision,
)

internal suspend fun ActivityCapturedFactDao.readActivityHistoryCursors(
	windowIds: List<String>,
	selectedLineages: ActivityRevisionLoad?,
	revisions: List<ActivityCapturedWindowRevisionEntity>,
): List<ActivityCapturedWindowCursorEntity> {
	return if (windowIds.isEmpty()) {
		emptyList()
	} else if (selectedLineages == null) {
		historyCursors(windowIds, ACTIVITY_HISTORY_MAX_CURSORS + 1)
	} else {
		revisions.distinctBy { Triple(it.writerProjectionId, it.writerProjectionVersion, it.logicalWindowId) }
		.mapNotNull { cursor(it.writerProjectionId, it.writerProjectionVersion, it.logicalWindowId) }
	}
}

internal suspend fun ActivityCapturedFactDao.readActivityHistoryFragments(
	windowIds: List<String>,
	selectedLineages: ActivityRevisionLoad?,
	revisions: List<ActivityCapturedWindowRevisionEntity>,
): List<ActivityCapturedFragmentEntity> {
	return if (windowIds.isEmpty()) {
		emptyList()
	} else if (selectedLineages == null) {
		historyFragments(windowIds, ACTIVITY_HISTORY_MAX_FRAGMENTS + 1)
	} else {
		val selected = mutableListOf<ActivityCapturedFragmentEntity>()
		for (revision in revisions) {
			if (selected.size > ACTIVITY_HISTORY_MAX_FRAGMENTS) {
				break
			}
			currentCoroutineContext().ensureActive()
			selected += maintenanceFragments(revision.writerProjectionId, revision.writerProjectionVersion,
				revision.logicalWindowId, revision.semanticRevision, ACTIVITY_HISTORY_MAX_FRAGMENTS - selected.size + 1)
		}
		selected
	}
}

internal suspend fun ActivityCapturedFactDao.readActivityHistoryEvidence(
	windowIds: List<String>,
	selectedLineages: ActivityRevisionLoad?,
	revisions: List<ActivityCapturedWindowRevisionEntity>,
): List<ActivityCapturedEvidenceEntity> {
	return if (windowIds.isEmpty()) {
		emptyList()
	} else if (selectedLineages == null) {
		historyEvidence(windowIds, ACTIVITY_HISTORY_MAX_EVIDENCE + 1)
	} else {
		val selected = mutableListOf<ActivityCapturedEvidenceEntity>()
		for (revision in revisions) {
			if (selected.size > ACTIVITY_HISTORY_MAX_EVIDENCE) {
				break
			}
			currentCoroutineContext().ensureActive()
			selected += maintenanceEvidence(revision.writerProjectionId, revision.writerProjectionVersion,
				revision.logicalWindowId, revision.semanticRevision, ACTIVITY_HISTORY_MAX_EVIDENCE - selected.size + 1)
		}
		selected
	}
}

internal suspend fun ActivityCapturedFactDao.readActivityHistoryRegistrationPlans(
	sourceInstanceIds: List<String>,
	registrationGenerations: List<Long>,
): List<ActivityCapturedRegistrationPlanEntity> {
	return if (sourceInstanceIds.isEmpty() || registrationGenerations.isEmpty()) {
		emptyList()
	} else {
		historyRegistrationPlans(
			sourceInstanceIds,
			registrationGenerations,
			ACTIVITY_HISTORY_MAX_REGISTRATION_PLANS + 1,
		)
	}
}

internal suspend fun TrackingHistoryReadDao.readActivityHistoryDeletionFences(
	segments: List<SessionSegment>,
): List<SourceDeletionFenceEntity> {
	val deletionDigests = segments.mapNotNull { segment ->
		val logicalId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
		val runId = segment.serviceRunId?.takeIf(String::isNotBlank)
		if (logicalId == null || runId == null) {
			null
		} else {
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				ACTIVITY_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalId,
				runId,
			)
		}
	}.distinct()
	return if (deletionDigests.isEmpty()) {
		emptyList()
	} else {
		deletionFences(
			ACTIVITY_SOURCE,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			deletionDigests,
		)
	}
}

internal const val ACTIVITY_HISTORY_MAX_CURSORS = 512
internal const val ACTIVITY_HISTORY_MAX_FRAGMENTS = 8_192
internal const val ACTIVITY_HISTORY_MAX_EVIDENCE = 16_384
internal const val ACTIVITY_HISTORY_MAX_REGISTRATION_PLANS = 512
