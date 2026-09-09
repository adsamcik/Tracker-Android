package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage

/** Pure product composition after retained-member integrity, exact membership and local fences pass. */
internal fun PortableStepsRunV1.toImportedStepsHistory(retentionTruncated: Boolean = false): StepsHistory {
	val covered = facts.filter { it.coverage == PortableStepsFactCoverage.COVERED }
	if (covered.isEmpty()) {
		return importedUnavailable(
			if (facts.any { it.coverage == PortableStepsFactCoverage.BASELINE }) {
				StepsHistoryCause.BASELINE_ONLY
			} else {
				StepsHistoryCause.NO_OBSERVATION
			},
			retentionTruncated,
		)
	}
	// Distinct fact identities alone cannot make overlapping counts additive.
	if (covered.zipWithNext().any { (left, right) -> right.intervalStartTimeMs < left.intervalEndTimeMs }) {
		return importedUnavailable(StepsHistoryCause.HISTORY_INTEGRITY_FAILED, retentionTruncated)
	}
	val count = covered.coveredTotalOrNull() ?: return importedUnavailable(
		StepsHistoryCause.VALUE_OVERFLOW, retentionTruncated,
	)
	val reasons = incompleteCauses(covered) + if (retentionTruncated) {
		setOf(StepsHistoryCause.RETENTION_LIMIT)
	} else {
		emptySet()
	}
	return StepsHistory(
		count = count,
		availability = HistoryAvailability.RETAINED_IMPORTED,
		evidence = if (count > 0L) { HistoryEvidence.RECORDED } else { HistoryEvidence.ACTIVE },
		productState = if (reasons.isEmpty()) { HistoryProductState.READY } else { HistoryProductState.PARTIAL },
		coverage = if (reasons.isEmpty()) { StepsHistoryCoverage.COMPLETE } else { StepsHistoryCoverage.PARTIAL },
		causes = reasons,
	)
}

private fun List<PortableStepsFactV1>.coveredTotalOrNull(): Long? {
	var total = 0L
	for (fact in this) {
		val count = requireNotNull(fact.stepCount)
		if (count > Long.MAX_VALUE - total) { return null }
		total += count
	}
	return total
}

private fun PortableStepsRunV1.incompleteCauses(covered: List<PortableStepsFactV1>): Set<StepsHistoryCause> {
	val reasons = linkedSetOf<StepsHistoryCause>()
	if (completeness.captureCoverage != PortableStepsCaptureCoverage.WHOLE_RUN) {
		reasons += StepsHistoryCause.CAPTURE_PARTIAL
	}
	if (!completeness.isSettled()) { reasons += StepsHistoryCause.ACQUISITION_INCOMPLETE }
	val explicitGap = facts.any {
		it.coverage == PortableStepsFactCoverage.RESET_GAP || it.coverage == PortableStepsFactCoverage.PARTIAL
	}
	val missingBoundary = covered.first().intervalStartTimeMs != startTimeMs ||
		covered.last().intervalEndTimeMs != endTimeMs
	val missingInterval = covered.zipWithNext().any { (left, right) ->
		left.intervalEndTimeMs != right.intervalStartTimeMs
	}
	if (explicitGap || missingBoundary || missingInterval) { reasons += StepsHistoryCause.PROVIDER_GAP }
	return reasons
}

private fun PortableStepsCompletenessV1.isSettled(): Boolean =
	providerCoverage == PortableStepsProviderCoverage.COMPLETE &&
		appDrainComplete && stopComplete && !hasUnresolvedProviderRange

private fun importedUnavailable(cause: StepsHistoryCause, retentionTruncated: Boolean = false) = StepsHistory(
	count = null,
	availability = HistoryAvailability.RETAINED_IMPORTED,
	evidence = HistoryEvidence.NONE,
	productState = HistoryProductState.PARTIAL,
	coverage = StepsHistoryCoverage.UNKNOWN,
	causes = if (retentionTruncated) { setOf(cause, StepsHistoryCause.RETENTION_LIMIT) } else { setOf(cause) },
)
