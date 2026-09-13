package com.adsamcik.tracker.stats.data.repository

import java.time.LocalDate
import java.time.ZoneId

/** Immutable civil-day identity carried by the Ambient Steps facts themselves. */
internal data class AmbientStepsDayIdentity(
	val epochDay: Long,
	val storedZoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	init {
		require(storedZoneId.isNotBlank())
		require(startTimeMs >= 0L)
		require(endTimeMs > startTimeMs)
		val zone = ZoneId.of(storedZoneId)
		val date = LocalDate.ofEpochDay(epochDay)
		require(date.atStartOfDay(zone).toInstant().toEpochMilli() == startTimeMs)
		require(date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli() == endTimeMs)
	}
}

/** Provider lineage is retained internally; it is not a user-visible account identifier. */
internal data class AmbientStepsProviderProvenance(
	val provider: String,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val continuitySegmentGeneration: Long,
) {
	init {
		require(provider.isNotBlank())
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration > 0L)
		require(continuitySegmentGeneration > 0L)
	}
}

internal data class QualifiedAmbientStepsFact(
	val logicalFactId: String,
	val day: AmbientStepsDayIdentity,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val stepCount: Long,
	val provenance: AmbientStepsProviderProvenance,
) {
	init {
		require(logicalFactId.isNotBlank())
		require(startTimeMs >= day.startTimeMs)
		require(endTimeMs <= day.endTimeMs)
		require(endTimeMs > startTimeMs)
		require(stepCount >= 0L)
	}
}

internal data class EffectiveAmbientStepsGap(
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	init {
		require(startTimeMs >= 0L)
		require(endTimeMs >= startTimeMs)
	}
}

/** Only an exact durable provider-domain link can authorize subtraction. */
internal sealed interface SessionAmbientCompatibility {
	data class ExactProviderDomain(
		val provider: String,
		val sourceInstanceId: String,
	) : SessionAmbientCompatibility {
		init {
			require(provider.isNotBlank())
			require(sourceInstanceId.isNotBlank())
		}
	}

	data object Unproven : SessionAmbientCompatibility
}

internal data class QualifiedSessionStepsWindow(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val stepCount: Long?,
	val storedZoneId: String,
	val compatibility: SessionAmbientCompatibility,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(startTimeMs >= 0L)
		require(endTimeMs > startTimeMs)
		require(stepCount == null || stepCount >= 0L)
		require(storedZoneId.isNotBlank())
	}
}

internal enum class AmbientStepsDayCause {
	NO_AMBIENT_FACT,
	AMBIENT_GAP,
	AMBIENT_COVERAGE_PARTIAL,
	AMBIENT_FACT_OVERLAP,
	AMBIENT_COUNT_OVERFLOW,
	AMBIENT_DAY_AUTHORITY_MISMATCH,
	SESSION_OUTSIDE_DAY,
	SESSION_VALUE_UNAVAILABLE,
	SESSION_OVERLAP,
	SESSION_PROVIDER_COMPATIBILITY_UNPROVEN,
	SESSION_NOT_COVERED_BY_COMPATIBLE_AMBIENT_FACT,
	SESSION_COUNT_EXCEEDS_AMBIENT_TOTAL,
}

internal sealed interface AmbientStepsNumericValue {
	val count: Long?

	data class Exact(override val count: Long) : AmbientStepsNumericValue {
		init { require(count >= 0L) }
	}

	data class Partial(
		override val count: Long,
		val causes: Set<AmbientStepsDayCause>,
	) : AmbientStepsNumericValue {
		init {
			require(count >= 0L)
			require(causes.isNotEmpty())
		}
	}

	data class Unavailable(val causes: Set<AmbientStepsDayCause>) : AmbientStepsNumericValue {
		override val count: Long? = null

		init { require(causes.isNotEmpty()) }
	}
}

internal data class AmbientStepsDayProduct(
	val day: AmbientStepsDayIdentity,
	/** The provider aggregate is authoritative and session values are never added to it. */
	val total: AmbientStepsNumericValue,
	val inSession: List<QualifiedSessionStepsWindow>,
	val betweenSession: AmbientStepsNumericValue,
)

/** Pure composition over integrity-qualified, latest-effective source facts and session evidence. */
internal fun composeAmbientStepsDay(
	day: AmbientStepsDayIdentity,
	facts: List<QualifiedAmbientStepsFact>,
	gaps: List<EffectiveAmbientStepsGap>,
	sessions: List<QualifiedSessionStepsWindow>,
): AmbientStepsDayProduct {
	val containedSessions = sessions.filter {
		it.startTimeMs >= day.startTimeMs && it.endTimeMs <= day.endTimeMs &&
			it.storedZoneId == day.storedZoneId
	}.sortedWith(compareBy(QualifiedSessionStepsWindow::startTimeMs, QualifiedSessionStepsWindow::serviceRunId))
	val outsideSession = containedSessions.size != sessions.size
	if (facts.any { it.day != day }) {
		val unavailable = AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_DAY_AUTHORITY_MISMATCH),
		)
		return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable)
	}
	val orderedFacts = facts.sortedWith(
		compareBy(QualifiedAmbientStepsFact::startTimeMs, QualifiedAmbientStepsFact::logicalFactId),
	)
	if (orderedFacts.isEmpty()) {
		val causes = buildSet {
			add(AmbientStepsDayCause.NO_AMBIENT_FACT)
			if (outsideSession) add(AmbientStepsDayCause.SESSION_OUTSIDE_DAY)
		}
		val unavailable = AmbientStepsNumericValue.Unavailable(causes)
		return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable)
	}
	if (orderedFacts.zipWithNext().any { (left, right) -> right.startTimeMs < left.endTimeMs }) {
		val unavailable = AmbientStepsNumericValue.Unavailable(setOf(AmbientStepsDayCause.AMBIENT_FACT_OVERLAP))
		return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable)
	}
	var ambientTotal = 0L
	for (fact in orderedFacts) {
		if (fact.stepCount > Long.MAX_VALUE - ambientTotal) {
			val unavailable = AmbientStepsNumericValue.Unavailable(
				setOf(AmbientStepsDayCause.AMBIENT_COUNT_OVERFLOW),
			)
			return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable)
		}
		ambientTotal += fact.stepCount
	}
	val effectiveGap = gaps.any { it.endTimeMs > day.startTimeMs && it.startTimeMs < day.endTimeMs }
	val continuousCoverage = orderedFacts.first().startTimeMs == day.startTimeMs &&
		orderedFacts.last().endTimeMs == day.endTimeMs &&
		orderedFacts.zipWithNext().all { (left, right) -> left.endTimeMs == right.startTimeMs }
	val totalCauses = buildSet {
		if (!continuousCoverage) add(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL)
		if (effectiveGap) add(AmbientStepsDayCause.AMBIENT_GAP)
		if (outsideSession) add(AmbientStepsDayCause.SESSION_OUTSIDE_DAY)
	}
	val total = if (totalCauses.isEmpty()) {
		AmbientStepsNumericValue.Exact(ambientTotal)
	} else {
		AmbientStepsNumericValue.Partial(ambientTotal, totalCauses)
	}
	val betweenCauses = linkedSetOf<AmbientStepsDayCause>()
	if (total !is AmbientStepsNumericValue.Exact) betweenCauses += totalCauses
	if (containedSessions.zipWithNext().any { (left, right) -> right.startTimeMs < left.endTimeMs }) {
		betweenCauses += AmbientStepsDayCause.SESSION_OVERLAP
	}
	var inSessionTotal = 0L
	for (session in containedSessions) {
		val sessionCount = session.stepCount
		if (sessionCount == null) {
			betweenCauses += AmbientStepsDayCause.SESSION_VALUE_UNAVAILABLE
			continue
		}
		val proof = session.compatibility as? SessionAmbientCompatibility.ExactProviderDomain
		if (proof == null) {
			betweenCauses += AmbientStepsDayCause.SESSION_PROVIDER_COMPATIBILITY_UNPROVEN
			continue
		}
		val compatible = orderedFacts.filter {
			it.provenance.provider == proof.provider && it.provenance.sourceInstanceId == proof.sourceInstanceId
		}
		if (!compatible.covers(session.startTimeMs, session.endTimeMs)) {
			betweenCauses += AmbientStepsDayCause.SESSION_NOT_COVERED_BY_COMPATIBLE_AMBIENT_FACT
			continue
		}
		if (sessionCount > Long.MAX_VALUE - inSessionTotal) {
			betweenCauses += AmbientStepsDayCause.SESSION_COUNT_EXCEEDS_AMBIENT_TOTAL
			continue
		}
		inSessionTotal += sessionCount
	}
	if (inSessionTotal > ambientTotal) betweenCauses += AmbientStepsDayCause.SESSION_COUNT_EXCEEDS_AMBIENT_TOTAL
	val between = if (betweenCauses.isEmpty()) {
		AmbientStepsNumericValue.Exact(ambientTotal - inSessionTotal)
	} else {
		AmbientStepsNumericValue.Unavailable(betweenCauses)
	}
	return AmbientStepsDayProduct(day, total, containedSessions, between)
}

private fun List<QualifiedAmbientStepsFact>.covers(startTimeMs: Long, endTimeMs: Long): Boolean {
	var coveredThrough = startTimeMs
	for (fact in this) {
		if (fact.endTimeMs <= coveredThrough) continue
		if (fact.startTimeMs > coveredThrough) return false
		coveredThrough = fact.endTimeMs
		if (coveredThrough >= endTimeMs) return true
	}
	return false
}
