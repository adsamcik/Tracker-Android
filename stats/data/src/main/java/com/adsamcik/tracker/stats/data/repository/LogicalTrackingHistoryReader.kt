package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.RecentHistoryEntryCandidate
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import javax.inject.Inject

/**
 * Composes recent physical presentation rows into stable logical history entries.
 *
 * Candidate entry keys are paged before the caller's result limit. Every explicitly related
 * physical sibling is then evaluated in bounded batches inside the same Room snapshot. Discovery
 * needs one evidence-bearing seed, while recency uses the newest membership-eligible physical
 * sibling. Membership requires an exact authoritative run-to-segment reverse binding, or the
 * explicit forward identity retained by a typed migrated unverifiable run; it is never inferred
 * from wall-time overlap.
 */
internal class LogicalTrackingHistoryReader @Inject constructor(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
) {
	// The bounded keyset loop keeps each fail-closed candidate and stop condition explicit.
	@Suppress("CyclomaticComplexMethod")
	internal suspend fun selectRecentEntries(limit: Int): List<HistoricalTrackingEntryEvidence> {
		validateLimit(limit)
		return database.withTransaction {
			selectRecentEntriesInTransaction(limit) { true }
		}
	}

	/** Applies the Steps-only product predicate before the accepted-result limit. */
	internal suspend fun selectRecentStepsOnlyEntries(
		limit: Int,
	): List<HistoricalTrackingEntryEvidence> {
		validateLimit(limit)
		return database.withTransaction {
			selectRecentEntriesInTransaction(limit, HistoricalTrackingEntryEvidence::isExactStepsOnlyCapture)
		}
	}

	/**
	 * Composes one finite page against the caller's complete physical candidate window.
	 *
	 * Exact Steps-only intent suppresses authoritative physical members even when product evidence is
	 * baseline, fenced, or otherwise not qualified. Only independently qualified logical entries gain
	 * an opaque Steps-only replacement row.
	 */
	internal suspend fun selectRecentStepsAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): List<HistoricalStepsAwarePageEntry> {
		validatePageRequest(candidateSegmentIds, limit)
		return database.withTransaction {
			val candidateSegments = if (candidateSegmentIds.isEmpty()) {
				emptyList()
			} else {
				database.trackingHistoryReadDao().segments(candidateSegmentIds)
			}
			val candidateLogicalIds = candidateSegments.mapNotNull { segment ->
				segment.logicalTrackingId?.takeIf(String::isNotBlank)
			}.distinct()
			val candidateGroups = loadLogicalMemberEvidence(candidateLogicalIds).toTrackingEntries()
			val suppressedCandidateIds = candidateGroups.values
				.filter(HistoricalTrackingEntryEvidence::hasExactStepsOnlyIntent)
				.flatMapTo(hashSetOf()) { entry ->
					entry.physicalMembers.map { member -> member.segment.id }
				}

			val physicalRows = candidateSegments
				.filterNot { segment -> segment.id in suppressedCandidateIds }
				.map(HistoricalStepsAwarePageEntry::Physical)
			val stepsOnlyRows = selectRecentEntriesInTransaction(
				limit = limit,
				accept = HistoricalTrackingEntryEvidence::isExactStepsOnlyCapture,
			).map(HistoricalStepsAwarePageEntry::StepsOnly)

			(physicalRows + stepsOnlyRows)
				.sortedWith(stepsAwarePageOrder)
				.take(limit)
		}
	}

	@Suppress("CyclomaticComplexMethod")
	private suspend fun selectRecentEntriesInTransaction(
		limit: Int,
		accept: (HistoricalTrackingEntryEvidence) -> Boolean,
	): List<HistoricalTrackingEntryEvidence> {
		val accepted = ArrayList<HistoricalTrackingEntryEvidence>(limit)
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null

		entryPages@ while (accepted.size < limit) {
			val candidates = database.trackingHistoryReadDao().recentEntryCandidatePage(
				limit = ENTRY_CANDIDATE_BATCH_CAP,
				stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeSegmentId = beforeSegmentId,
			)
			if (candidates.isEmpty()) {
				break
			}

			val entries = loadCandidateEntries(candidates)
			for (candidate in candidates) {
				val identity = candidate.toIdentity() ?: continue
				val entry = entries[identity] ?: continue
				if (!entry.isOrdinarilyDiscoverable || !accept(entry)) {
					continue
				}
				accepted += entry
				if (accepted.size == limit) {
					break@entryPages
				}
			}

			val lastScanned = candidates.last()
			beforeStartTimeMs = lastScanned.sortStartTimeMs
			beforeSegmentId = lastScanned.sortSegmentId
			if (candidates.size < ENTRY_CANDIDATE_BATCH_CAP) {
				break
			}
		}
		return accepted
	}

	private suspend fun loadCandidateEntries(
		candidates: List<RecentHistoryEntryCandidate>,
	): Map<HistoricalEntryIdentity, HistoricalTrackingEntryEvidence> {
		val logicalIds = candidates.mapNotNull(RecentHistoryEntryCandidate::logicalTrackingId)
			.filter(String::isNotBlank)
			.distinct()
		val legacyIds = candidates.mapNotNull(RecentHistoryEntryCandidate::legacySegmentId).distinct()
		val evidence = loadLogicalMemberEvidence(logicalIds).toMutableList()
		if (legacyIds.isNotEmpty()) {
			val legacySegments = database.trackingHistoryReadDao().segments(legacyIds)
			evidence += stepsSelector.selectManyInTransaction(legacySegments)
		}
		return evidence.toTrackingEntries()
	}

	private fun List<HistoricalSegmentEvidence>.toTrackingEntries():
		Map<HistoricalEntryIdentity, HistoricalTrackingEntryEvidence> = mapNotNull { member ->
			member.entryIdentity?.let { identity -> identity to member }
		}.groupBy(
			keySelector = { (identity, _) -> identity },
			valueTransform = { (_, member) -> member },
		).mapValues { (identity, members) ->
			HistoricalTrackingEntryEvidence(
				identity = identity,
				physicalMembers = members.sortedWith(physicalMemberOrder),
			)
		}

	private suspend fun loadLogicalMemberEvidence(
		logicalIds: List<String>,
	): List<HistoricalSegmentEvidence> {
		if (logicalIds.isEmpty()) {
			return emptyList()
		}
		val evidence = mutableListOf<HistoricalSegmentEvidence>()
		var afterStartTimeMs: Long? = null
		var afterSegmentId: Long? = null
		while (true) {
			val segmentPage = database.trackingHistoryReadDao().logicalEntrySegmentPage(
				logicalTrackingIds = logicalIds,
				limit = HISTORY_SEGMENT_BATCH_CAP,
				afterStartTimeMs = afterStartTimeMs,
				afterSegmentId = afterSegmentId,
			)
			if (segmentPage.isEmpty()) {
				break
			}
			evidence += stepsSelector.selectManyInTransaction(segmentPage)
			if (segmentPage.size < HISTORY_SEGMENT_BATCH_CAP) {
				break
			}
			val lastScanned = segmentPage.last()
			afterStartTimeMs = lastScanned.startTimeMs
			afterSegmentId = lastScanned.id
		}
		return evidence
	}

	private fun RecentHistoryEntryCandidate.toIdentity(): HistoricalEntryIdentity? =
		logicalTrackingId?.takeIf(String::isNotBlank)?.let {
			HistoricalEntryIdentity.Logical(it)
		} ?: legacySegmentId?.takeIf { it > 0L }?.let {
			HistoricalEntryIdentity.LegacyPhysical(it)
		}

	private fun validatePageRequest(candidateSegmentIds: List<Long>, limit: Int) {
		validateLimit(limit)
		require(candidateSegmentIds.size <= MAX_PHYSICAL_CANDIDATE_COUNT) {
			"Physical history candidate count cannot exceed $MAX_PHYSICAL_CANDIDATE_COUNT"
		}
		require(candidateSegmentIds.all { it > 0L }) {
			"Physical history candidate ids must be positive"
		}
		require(candidateSegmentIds.distinct().size == candidateSegmentIds.size) {
			"Physical history candidate ids must be distinct"
		}
	}

	private fun validateLimit(limit: Int) {
		require(limit in 1..MAX_RECENT_ENTRY_COUNT) {
			"Recent history limit must be between 1 and $MAX_RECENT_ENTRY_COUNT"
		}
	}

	private companion object {
		const val ENTRY_CANDIDATE_BATCH_CAP = 64
		const val MAX_PHYSICAL_CANDIDATE_COUNT = 100
		const val MAX_RECENT_ENTRY_COUNT = 100

		val stepsAwarePageOrder =
			compareByDescending<HistoricalStepsAwarePageEntry> { it.recencyStartTimeMs }
				.thenByDescending { it.recencySegmentId }
	}
}
