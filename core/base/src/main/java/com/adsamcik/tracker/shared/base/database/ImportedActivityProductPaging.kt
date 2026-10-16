package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityOrderedHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.IMPORTED_ACTIVITY_CANDIDATE_LIVE
import com.adsamcik.tracker.shared.base.database.dao.IMPORTED_ACTIVITY_CANDIDATE_RETAINED
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity

internal fun isValidOrderedActivityCandidatePage(
	candidates: List<ImportedActivityOrderedHistoryCandidate>,
): Boolean = candidates.all { it.hasValidOrderedActivityCandidateShape() } &&
	candidates.map(ImportedActivityOrderedHistoryCandidate::identity).distinct().size == candidates.size &&
	candidates.zipWithNext().all { (left, right) ->
		requireNotNull(left.recencyStartTimeMs) > requireNotNull(right.recencyStartTimeMs) ||
			left.recencyStartTimeMs == right.recencyStartTimeMs &&
			requireNotNull(left.recencyMemberIdentity) > requireNotNull(right.recencyMemberIdentity)
	}

private fun ImportedActivityOrderedHistoryCandidate.hasValidOrderedActivityCandidateShape(): Boolean {
	if (importRevision <= 0L || startTimeMs < 0L || endTimeMs < startTimeMs || receivedAtMs < 0L) {
		return false
	}
	if (recencyStartTimeMs == null || recencyStartTimeMs !in startTimeMs..endTimeMs ||
		recencyMemberIdentity == null || candidateState !in ACTIVITY_PRODUCT_CANDIDATE_STATES
	) {
		return false
	}
	return hasCompatibleActivityTemporalAuthority() && runCatching {
		PortableActivityOpaqueIdentity(identity)
		PortableActivityOpaqueIdentity(requireNotNull(recencyMemberIdentity))
	}.isSuccess
}

private fun ImportedActivityOrderedHistoryCandidate.hasCompatibleActivityTemporalAuthority(): Boolean =
	when (candidateState) {
		IMPORTED_ACTIVITY_CANDIDATE_LIVE -> temporalAuthorityState == null
		IMPORTED_ACTIVITY_CANDIDATE_RETAINED -> when (temporalAuthorityState) {
			ImportedActivityRetentionReceiptEntity.TEMPORAL_AUTHORITY_AVAILABLE -> true
			ImportedActivityRetentionReceiptEntity.TEMPORAL_AUTHORITY_UNAVAILABLE ->
				recencyStartTimeMs == startTimeMs && recencyMemberIdentity == identity
			else -> false
		}
		else -> false
	}

internal fun isValidActivityCandidatePage(
	candidates: List<ImportedActivityHistoryCandidate>,
	beforeStartTimeMs: Long?,
	beforeIdentity: String?,
	maximumSize: Int = ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES,
): Boolean {
	if ((beforeStartTimeMs == null) != (beforeIdentity == null)) {
		return false
	}
	if (candidates.size > maximumSize || candidates.map { it.identity }.distinct().size != candidates.size) {
		return false
	}
	if (candidates.any { !it.hasValidActivityCandidateShape() }) {
		return false
	}
	val cursors = buildList {
		if (beforeStartTimeMs != null && beforeIdentity != null) {
			add(beforeStartTimeMs to beforeIdentity)
		}
		addAll(candidates.map { it.startTimeMs to it.identity })
	}
	return cursors.zipWithNext().all { (left, right) ->
		left.first > right.first || left.first == right.first && left.second > right.second
	}
}

private fun ImportedActivityHistoryCandidate.hasValidActivityCandidateShape(): Boolean {
	if (identity.isBlank() || importRevision <= 0L || startTimeMs < 0L) {
		return false
	}
	return endTimeMs >= startTimeMs && receivedAtMs >= 0L && candidateState in ACTIVITY_PRODUCT_CANDIDATE_STATES
}

internal fun isValidAllActivityCandidatePage(
	candidates: List<ImportedActivityHistoryCandidate>,
	beforeStartTimeMs: Long?,
	beforeIdentity: String?,
	beforeCandidateState: String?,
	maximumSize: Int,
): Boolean {
	if ((beforeStartTimeMs == null) != (beforeIdentity == null) ||
		(beforeIdentity == null) != (beforeCandidateState == null)
	) {
		return false
	}
	if (candidates.size > maximumSize || candidates.any { !it.hasValidActivityCandidateShape() }) {
		return false
	}
	val cursors = buildList {
		if (beforeStartTimeMs != null && beforeIdentity != null && beforeCandidateState != null) {
			add(Triple(beforeStartTimeMs, beforeIdentity, beforeCandidateState))
		}
		addAll(candidates.map { Triple(it.startTimeMs, it.identity, it.candidateState) })
	}
	return cursors.hasDescendingActivityCandidateOrder()
}

private fun List<Triple<Long, String, String>>.hasDescendingActivityCandidateOrder(): Boolean =
	zipWithNext().all { (left, right) ->
		if (left.first != right.first) {
			left.first > right.first
		} else if (left.second != right.second) {
			left.second > right.second
		} else {
			left.third < right.third
		}
	}

private val ACTIVITY_PRODUCT_CANDIDATE_STATES = setOf(
	IMPORTED_ACTIVITY_CANDIDATE_LIVE,
	IMPORTED_ACTIVITY_CANDIDATE_RETAINED,
)
