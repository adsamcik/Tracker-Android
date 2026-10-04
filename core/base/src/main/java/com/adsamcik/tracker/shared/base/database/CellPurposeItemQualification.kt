package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CellWalPayload
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity

/** Exact item indexes for one uninterrupted demand interval, reconstructed from retained authority. */
sealed interface CellPurposeItems {
	data class Qualified(
		val indexes: List<Int>,
		val continuousFromNanos: Long,
		val continuousUntilNanos: Long,
		val demandMaximumAgeNanos: Long,
	) : CellPurposeItems
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
		it.purpose == SourceBrokerPurpose.SESSION_CAPTURE && it.persistenceEligible &&
			it.authorizationRevision == fact.authorizationRevision &&
			it.authorizationFingerprint == fact.authorizationFingerprint &&
			it.purposeEligibilityMask == fact.purposeEligibilityMask &&
			it.logicalTrackingId == fact.logicalTrackingId && it.serviceRunId == fact.serviceRunId &&
			it.manifestRevision == fact.manifestRevision &&
			it.lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration &&
			it.sourcePolicyRevision == fact.sourcePolicyRevision && it.consentEpoch == fact.captureConsentEpoch
	} ?: return CellPurposeItems.Unverifiable
	return items.cellPurposeWindow(payload, requireNotNull(member.demandId), providerEndExclusiveNanos)
}

/** Reuses the same uninterrupted demand window in retained history and the live writer. */
fun RadioPurposeItems.cellPurposeWindow(
	payload: CellWalPayload,
	demandId: String,
	providerEndExclusiveNanos: Long,
): CellPurposeItems {
	val demand = immutableDemands[demandId] ?: return CellPurposeItems.Unverifiable
	val indexes = forDemand(demandId)
	val timeline = authorizationTimeline
	if (timeline.isEmpty()) return CellPurposeItems.Unverifiable
	val latestItemTime = indexes.maxOfOrNull { index ->
		requireNotNull(payload.observations[index].providerTimestampNanos)
	}
	val activeIndex = timeline.indexOfLast { revision ->
		latestItemTime != null && revision.effectiveElapsedRealtimeNanos <= latestItemTime
	}.takeIf { it >= 0 } ?: timeline.lastIndex
	fun active(index: Int) = timeline[index].authorizedMembers.any { member ->
		member.demandId == demandId && member.persistenceEligible
	}
	if (indexes.isNotEmpty() && !active(activeIndex)) return CellPurposeItems.Unverifiable
	var first = activeIndex
	while (first > 0 && active(first - 1)) first--
	var after = activeIndex + 1
	while (after < timeline.size && active(after)) after++
	val start = maxOf(timeline[first].effectiveElapsedRealtimeNanos, demand.requestedElapsedRealtimeNanos)
	val end = minOf(timeline.getOrNull(after)?.effectiveElapsedRealtimeNanos ?: providerEndExclusiveNanos,
		providerEndExclusiveNanos,
		demand.retireElapsedRealtimeNanos?.takeIf { demand.retireBootId == demand.requestedBootId }
			?: Long.MAX_VALUE)
	if (end <= start) return CellPurposeItems.Unverifiable
	val maximumAgeNanos = if (demand.maximumAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else demand.maximumAgeMs * NANOS_PER_MILLISECOND
	return CellPurposeItems.Qualified(indexes.filter { index ->
		requireNotNull(payload.observations[index].providerTimestampNanos) in start until end
	}, start, end, maximumAgeNanos)
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
