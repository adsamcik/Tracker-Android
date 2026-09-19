package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityResult
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityRequest
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityQuery
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerReference
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
	val provenance: AmbientStepsProviderProvenance?,
	val portableIdentity: String = logicalFactId,
	val origin: QualifiedAmbientStepsFactOrigin = QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER,
	val importedProvenance: ImportedAmbientStepsFactProvenance? = null,
	val correctionRevision: Long = 1L,
	val contentChecksum: String,
	val countDomainOwner: StepsCountDomainOwnerReference? = null,
) {
	init {
		require(logicalFactId.isNotBlank())
		require(portableIdentity.isNotBlank())
		require(startTimeMs >= day.startTimeMs)
		require(endTimeMs <= day.endTimeMs)
		require(endTimeMs > startTimeMs)
		require(stepCount >= 0L)
		require(correctionRevision > 0L)
		require(contentChecksum.isNotBlank())
		require(
			(origin == QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER &&
				provenance != null && importedProvenance == null) ||
				(origin == QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT &&
					provenance == null && importedProvenance != null),
		)
	}
}

internal enum class QualifiedAmbientStepsFactOrigin { LOCAL_PROVIDER, PORTABLE_IMPORT }

internal data class ImportedAmbientStepsFactProvenance(
	val archiveIdentity: String,
	val dayIdentity: String,
	val dayImportRevision: Long,
) {
	init {
		require(archiveIdentity.isNotBlank())
		require(dayIdentity.isNotBlank())
		require(dayImportRevision > 0L)
	}
}

internal data class EffectiveAmbientStepsGap(
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	init {
		require(startTimeMs >= 0L)
		require(endTimeMs > startTimeMs) {
			"A zero-width Ambient Steps discontinuity has no product coverage effect"
		}
	}
}

internal enum class QualifiedSessionStepsOrigin { LOCAL_CAPTURE, PORTABLE_IMPORT }

internal data class QualifiedSessionStepsWindow(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val stepCount: Long?,
	val storedZoneId: String?,
	val compatibility: StepsCountDomainCompatibilityResult =
		StepsCountDomainCompatibilityResult.Unproven,
	val origin: QualifiedSessionStepsOrigin = QualifiedSessionStepsOrigin.LOCAL_CAPTURE,
	val countDomainOwners: List<StepsCountDomainOwnerReference> = emptyList(),
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(startTimeMs >= 0L)
		require(endTimeMs >= startTimeMs)
		require(stepCount == null || stepCount >= 0L)
		require(stepCount == null || endTimeMs > startTimeMs) {
			"A positive or zero session aggregate requires a non-empty capture window"
		}
		require(storedZoneId == null || storedZoneId.isNotBlank())
		storedZoneId?.let(ZoneId::of)
		require(countDomainOwners.distinct().size == countDomainOwners.size)
	}
}

internal enum class AmbientStepsDayCause {
	NO_AMBIENT_FACT,
	AMBIENT_GAP,
	AMBIENT_COVERAGE_PARTIAL,
	AMBIENT_FACT_OVERLAP,
	AMBIENT_ORIGIN_IDENTITY_CONFLICT,
	AMBIENT_COUNT_OVERFLOW,
	AMBIENT_DAY_AUTHORITY_MISMATCH,
	AMBIENT_AUTHORITY_UNVERIFIABLE,
	AMBIENT_MATERIALIZING,
	AMBIENT_IMPORTED_DELETED,
	AMBIENT_IMPORTED_RETAINED,
	SESSION_OUTSIDE_DAY,
	SESSION_VALUE_UNAVAILABLE,
	SESSION_OVERLAP,
	SESSION_PROVIDER_COMPATIBILITY_UNPROVEN,
	SESSION_COUNT_DOMAIN_CONFLICT,
	SESSION_COUNT_DOMAIN_DELETED,
	SESSION_COUNT_DOMAIN_UNVERIFIABLE,
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
	/** Qualified source aggregates are authoritative and session values are never added to them. */
	val total: AmbientStepsNumericValue,
	val inSession: List<QualifiedSessionStepsWindow>,
	val betweenSession: AmbientStepsNumericValue,
	val origins: Set<QualifiedAmbientStepsFactOrigin> = emptySet(),
)

/** Narrow P5 request seam; no wall, count, zone, or display-source value enters compatibility. */
internal fun QualifiedSessionStepsWindow.countDomainCompatibilityRequest(
	facts: List<QualifiedAmbientStepsFact>,
): StepsCountDomainCompatibilityRequest = StepsCountDomainCompatibilityRequest(
	sessionOwners = countDomainOwners,
	ambientOwners = facts.mapNotNull(QualifiedAmbientStepsFact::countDomainOwner).distinct(),
)

/** Applies one bounded query result per session without changing any count or wall membership. */
internal suspend fun List<QualifiedSessionStepsWindow>.withCountDomainCompatibility(
	facts: List<QualifiedAmbientStepsFact>,
	query: StepsCountDomainCompatibilityQuery,
): List<QualifiedSessionStepsWindow> {
	if (isEmpty()) return emptyList()
	val results = query.compare(map { it.countDomainCompatibilityRequest(facts) })
	if (results.size != size) {
		return map {
			it.copy(compatibility = StepsCountDomainCompatibilityResult.Unverifiable)
		}
	}
	return zip(results) { session, compatibility ->
		session.copy(compatibility = compatibility)
	}
}

/** Pure composition over integrity-qualified, latest-effective source facts and session evidence. */
internal fun composeAmbientStepsDay(
	day: AmbientStepsDayIdentity,
	facts: List<QualifiedAmbientStepsFact>,
	gaps: List<EffectiveAmbientStepsGap>,
	sessions: List<QualifiedSessionStepsWindow>,
	sourceCauses: Set<AmbientStepsDayCause> = emptySet(),
): AmbientStepsDayProduct {
	val origins = facts.mapTo(linkedSetOf(), QualifiedAmbientStepsFact::origin)
	val containedSessions = sessions.filter {
		it.startTimeMs >= day.startTimeMs && it.endTimeMs <= day.endTimeMs &&
			it.storedZoneId == day.storedZoneId
	}.sortedWith(compareBy(QualifiedSessionStepsWindow::startTimeMs, QualifiedSessionStepsWindow::serviceRunId))
	val outsideSession = containedSessions.size != sessions.size
	if (facts.any { it.day != day }) {
		val unavailable = AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_DAY_AUTHORITY_MISMATCH),
		)
		return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable, origins)
	}
	val factsByPortableIdentity = facts.groupBy(QualifiedAmbientStepsFact::portableIdentity)
	val conflictingPortableIdentity = factsByPortableIdentity.values.any { group ->
			group.drop(1).any { candidate ->
				candidate.day != group.first().day ||
					candidate.startTimeMs != group.first().startTimeMs ||
					candidate.endTimeMs != group.first().endTimeMs ||
					candidate.stepCount != group.first().stepCount ||
					candidate.contentChecksum != group.first().contentChecksum
			}
		}
	if (conflictingPortableIdentity) {
		val unavailable = AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
		)
		return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable, origins)
	}
	val orderedFacts = factsByPortableIdentity.values.map { group ->
		group.firstOrNull { it.origin == QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER } ?: group.first()
	}.sortedWith(
		compareBy(QualifiedAmbientStepsFact::startTimeMs, QualifiedAmbientStepsFact::logicalFactId),
	)
	if (orderedFacts.isEmpty()) {
		val totalCauses = buildSet {
			add(AmbientStepsDayCause.NO_AMBIENT_FACT)
			addAll(sourceCauses)
			if (gaps.any { it.endTimeMs > day.startTimeMs && it.startTimeMs < day.endTimeMs }) {
				add(AmbientStepsDayCause.AMBIENT_GAP)
			}
		}
		val total = AmbientStepsNumericValue.Unavailable(totalCauses)
		val between = AmbientStepsNumericValue.Unavailable(buildSet {
			addAll(totalCauses)
			if (outsideSession) add(AmbientStepsDayCause.SESSION_OUTSIDE_DAY)
		})
		return AmbientStepsDayProduct(day, total, containedSessions, between, origins)
	}
	val authorityDomains = orderedFacts.mapTo(linkedSetOf()) { fact ->
		when (fact.origin) {
			QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER -> {
				val provenance = requireNotNull(fact.provenance)
				AmbientStepsAuthorityDomain.Native(
					provenance.provider,
					provenance.sourceInstanceId,
				)
			}
			QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT ->
				requireNotNull(fact.importedProvenance).let { provenance ->
					AmbientStepsAuthorityDomain.Portable(
						provenance.archiveIdentity,
						provenance.dayIdentity,
						provenance.dayImportRevision,
					)
				}
		}
	}
	if (authorityDomains.size != 1) {
		val unavailable = AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
		)
		return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable, origins)
	}
	if (orderedFacts.zipWithNext().any { (left, right) -> right.startTimeMs < left.endTimeMs }) {
		val unavailable = AmbientStepsNumericValue.Unavailable(setOf(AmbientStepsDayCause.AMBIENT_FACT_OVERLAP))
		return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable, origins)
	}
	var ambientTotal = 0L
	for (fact in orderedFacts) {
		if (fact.stepCount > Long.MAX_VALUE - ambientTotal) {
			val unavailable = AmbientStepsNumericValue.Unavailable(
				setOf(AmbientStepsDayCause.AMBIENT_COUNT_OVERFLOW),
			)
			return AmbientStepsDayProduct(day, unavailable, containedSessions, unavailable, origins)
		}
		ambientTotal += fact.stepCount
	}
	val effectiveGap = gaps.any { it.endTimeMs > day.startTimeMs && it.startTimeMs < day.endTimeMs }
	val continuousCoverage = orderedFacts.first().startTimeMs == day.startTimeMs &&
		orderedFacts.last().endTimeMs == day.endTimeMs &&
		orderedFacts.zipWithNext().all { (left, right) -> left.endTimeMs == right.startTimeMs }
	val totalCauses = buildSet {
		addAll(sourceCauses)
		if (!continuousCoverage) add(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL)
		if (effectiveGap) add(AmbientStepsDayCause.AMBIENT_GAP)
	}
	val total = if (totalCauses.isEmpty()) {
		AmbientStepsNumericValue.Exact(ambientTotal)
	} else {
		AmbientStepsNumericValue.Partial(ambientTotal, totalCauses)
	}
	val betweenCauses = linkedSetOf<AmbientStepsDayCause>()
	if (total !is AmbientStepsNumericValue.Exact) betweenCauses += totalCauses
	if (outsideSession) betweenCauses += AmbientStepsDayCause.SESSION_OUTSIDE_DAY
	if (containedSessions.zipWithNext().any { (left, right) -> right.startTimeMs < left.endTimeMs }) {
		betweenCauses += AmbientStepsDayCause.SESSION_OVERLAP
	}
	var inSessionTotal = 0L
	for (session in containedSessions) {
		val compatible = when (session.compatibility) {
			StepsCountDomainCompatibilityResult.ExactCompatible ->
				orderedFacts.filter {
					it.origin == QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER
				}
			StepsCountDomainCompatibilityResult.Conflict -> {
				betweenCauses += AmbientStepsDayCause.SESSION_COUNT_DOMAIN_CONFLICT
				continue
			}
			StepsCountDomainCompatibilityResult.Unproven -> {
				betweenCauses +=
					AmbientStepsDayCause.SESSION_PROVIDER_COMPATIBILITY_UNPROVEN
				continue
			}
			StepsCountDomainCompatibilityResult.Deleted -> {
				betweenCauses += AmbientStepsDayCause.SESSION_COUNT_DOMAIN_DELETED
				continue
			}
			StepsCountDomainCompatibilityResult.Unverifiable -> {
				betweenCauses += AmbientStepsDayCause.SESSION_COUNT_DOMAIN_UNVERIFIABLE
				continue
			}
		}
		val sessionCount = session.stepCount
		if (sessionCount == null) {
			betweenCauses += AmbientStepsDayCause.SESSION_VALUE_UNAVAILABLE
			continue
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
	return AmbientStepsDayProduct(day, total, containedSessions, between, origins)
}

private sealed interface AmbientStepsAuthorityDomain {
	data class Native(
		val provider: String,
		val sourceInstanceId: String,
	) : AmbientStepsAuthorityDomain

	data class Portable(
		val archiveIdentity: String,
		val dayIdentity: String,
		val dayImportRevision: Long,
	) : AmbientStepsAuthorityDomain
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
