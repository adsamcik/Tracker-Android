package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CellWalPayload
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity

/** Exact item indexes for one uninterrupted demand interval, reconstructed from retained authority. */
sealed interface CellPurposeItems {
	/**
	 * Individually accepted item indexes within one uninterrupted demand interval, with elapsed
	 * nanosecond bounds and maximum age.
	 */
	data class Qualified(
		val indexes: List<Int>,
		val continuousFromNanos: Long,
		val continuousUntilNanos: Long,
		val demandMaximumAgeNanos: Long,
	) : CellPurposeItems
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data object Unverifiable : CellPurposeItems
}

/** Must be called inside the product writer's Room snapshot/transaction. */
suspend fun AppDatabase.qualifyCellPurposeItems(
	wal: SourceEventWalEntity,
	payload: CellWalPayload,
	demandId: String,
	providerStartInclusiveNanos: Long,
	providerEndExclusiveNanos: Long,
): CellPurposeItems {
	val qualified = qualifyCellPurposeItems(wal, payload, providerStartInclusiveNanos,
		providerEndExclusiveNanos) ?: return CellPurposeItems.Unverifiable
	return qualified.cellPurposeWindow(payload, demandId, providerEndExclusiveNanos)
}

/** Reconstructs the fact's own capture member, independently of the callback's current purpose. */
suspend fun AppDatabase.qualifyCellCapturePurposeItems(
	wal: SourceEventWalEntity,
	payload: CellWalPayload,
	fact: CellCapturedFactRevisionEntity,
	providerStartInclusiveNanos: Long = fact.providerAcceptanceStartNanos,
	providerEndExclusiveNanos: Long = fact.providerAcceptanceEndNanos,
): CellPurposeItems {
	val items = qualifyCellPurposeItems(wal, payload, providerStartInclusiveNanos,
		providerEndExclusiveNanos) ?: return CellPurposeItems.Unverifiable
	val member = items.authorizationTimeline.flatMap { it.authorizedMembers }.singleOrNull {
		it.matchesCapturedCellFact(fact)
	} ?: return CellPurposeItems.Unverifiable
	return items.cellPurposeWindow(payload, requireNotNull(member.demandId), providerEndExclusiveNanos)
}


// Complete saved authorization-member/fact correspondence, with every original comparison in order.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private fun SourceAuthorizationEntity.matchesCapturedCellFact(fact: CellCapturedFactRevisionEntity): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible &&
		authorizationRevision == fact.authorizationRevision &&
		authorizationFingerprint == fact.authorizationFingerprint &&
		purposeEligibilityMask == fact.purposeEligibilityMask &&
		logicalTrackingId == fact.logicalTrackingId && serviceRunId == fact.serviceRunId &&
		manifestRevision == fact.manifestRevision &&
		lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration &&
		sourcePolicyRevision == fact.sourcePolicyRevision && consentEpoch == fact.captureConsentEpoch

/** Reuses the same uninterrupted demand window in retained history and the live writer. */
fun RadioPurposeItems.cellPurposeWindow(
	payload: CellWalPayload,
	demandId: String,
	providerEndExclusiveNanos: Long,
): CellPurposeItems {
	val demand = immutableDemands[demandId] ?: return CellPurposeItems.Unverifiable
	val indexes = forDemand(demandId)
	val timeline = authorizationTimeline
	if (timeline.isEmpty()) {
		return CellPurposeItems.Unverifiable
	}
	val activeIndex = timeline.activeCellPurposeIndex(payload, indexes)
	if (indexes.isNotEmpty() && !timeline.hasEligibleCellDemand(activeIndex, demandId)) {
		return CellPurposeItems.Unverifiable
	}
	val (first, after) = timeline.continuousCellPurposeRange(activeIndex, demandId)
	val start = maxOf(timeline[first].effectiveElapsedRealtimeNanos, demand.requestedElapsedRealtimeNanos)
	val end = minOf(timeline.getOrNull(after)?.effectiveElapsedRealtimeNanos ?: providerEndExclusiveNanos,
		providerEndExclusiveNanos,
		demand.retireElapsedRealtimeNanos?.takeIf { demand.retireBootId == demand.requestedBootId }
			?: Long.MAX_VALUE)
	return if (end <= start) {
		CellPurposeItems.Unverifiable
	} else {
		val maximumAgeNanos = if (demand.maximumAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
			Long.MAX_VALUE
		} else {
			demand.maximumAgeMs * NANOS_PER_MILLISECOND
		}
		CellPurposeItems.Qualified(indexes.filter { index ->
			requireNotNull(payload.observations[index].providerTimestampNanos) in start until end
		}, start, end, maximumAgeNanos)
	}
}

private const val NANOS_PER_MILLISECOND = 1_000_000L

private fun List<SourceAuthorizationSnapshot>.activeCellPurposeIndex(
	payload: CellWalPayload, indexes: List<Int>,
): Int {
	val latestItemTime = indexes.maxOfOrNull { index ->
		requireNotNull(payload.observations[index].providerTimestampNanos)
	}
	return indexOfLast { revision ->
		latestItemTime != null && revision.effectiveElapsedRealtimeNanos <= latestItemTime
	}.takeIf { it >= 0 } ?: lastIndex
}

private fun List<SourceAuthorizationSnapshot>.hasEligibleCellDemand(index: Int, demandId: String): Boolean =
	this[index].authorizedMembers.any { member ->
		member.demandId == demandId && member.persistenceEligible
	}

private fun List<SourceAuthorizationSnapshot>.continuousCellPurposeRange(
	activeIndex: Int, demandId: String,
): Pair<Int, Int> {
	var first = activeIndex
	while (first > 0 && hasEligibleCellDemand(first - 1, demandId)) {
		first--
	}
	var after = activeIndex + 1
	while (after < size && hasEligibleCellDemand(after, demandId)) {
		after++
	}
	return first to after
}
