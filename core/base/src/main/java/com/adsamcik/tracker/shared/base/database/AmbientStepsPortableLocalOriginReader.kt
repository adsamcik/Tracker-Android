package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1

enum class AmbientStepsPortableLocalOwnerKind { FACT, GAP }

data class AmbientStepsPortableLocalOwner(
	val identity: String,
	val kind: AmbientStepsPortableLocalOwnerKind,
	val contentChecksum: String,
)

/**
 * Authenticates retained native Ambient Steps before checking portable-origin identity overlap.
 * Wall-time overlap alone is deliberately not an ownership claim.
 */
class AmbientStepsPortableLocalOriginReader(
	private val database: AppDatabase,
) {
	suspend fun readInTransaction(): List<AmbientStepsPortableLocalOwner> {
		val factDao = database.ambientStepsFactRevisionDao()
		val stateDao = database.ambientStepsImportStateDao()
		if (factDao.countAll() == 0L && stateDao.countGaps() == 0L) return emptyList()
		val audit = database.loadAuthenticatedAmbientStepsState(
			AmbientStepsMaintenanceLimits(),
			checkpoint = {},
			authenticateRetractedPayloadAuthority = false,
		)
		val effectiveFacts = audit.lineages.mapNotNull { lineage ->
			lineage.latest.takeIf {
				it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
			}
		}
		val owners = mutableListOf<AmbientStepsPortableLocalOwner>()
		effectiveFacts.forEach { fact ->
			val portable = PortableAmbientStepsFactV1.create(
				AmbientStepsPortableOpaqueIdentity.derive(
					AmbientStepsPortableIdentityKind.FACT,
					fact.logicalFactId,
				),
				requireNotNull(fact.windowStartTimeMs),
				requireNotNull(fact.windowEndTimeMs),
				requireNotNull(fact.stepCount),
			)
			owners += AmbientStepsPortableLocalOwner(
				portable.identity.value,
				AmbientStepsPortableLocalOwnerKind.FACT,
				portable.contentChecksum.value,
			)
		}
		val days = effectiveFacts.mapTo(linkedSetOf()) { fact ->
			PortableLocalDay(
				requireNotNull(fact.structuralDayStartTimeMs),
				requireNotNull(fact.structuralDayEndTimeMs),
			)
		}
		val retainedFromMs = database.sourceEvidenceStateDao().get()?.retainedFromMs
		audit.gaps.forEach { gap ->
			gap.subtractFacts(effectiveFacts).forEach { part ->
				days.forEach { day ->
					part.clip(day, retainedFromMs)?.let { portable ->
						owners += AmbientStepsPortableLocalOwner(
							portable.identity.value,
							AmbientStepsPortableLocalOwnerKind.GAP,
							portable.contentChecksum.value,
						)
					}
				}
			}
		}
		return owners.distinct()
	}
}

private data class PortableLocalDay(val startTimeMs: Long, val endTimeMs: Long)

private data class PortableLocalGap(
	val localGapId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val reason: PortableAmbientStepsGapReason,
)

private fun AmbientStepsImportGapEntity.subtractFacts(
	facts: List<AmbientStepsFactRevisionEntity>,
): List<PortableLocalGap> {
	if (gapStartTimeMs == gapEndTimeMs) return emptyList()
	val coverage = facts.asSequence().filter { fact ->
		fact.provider == provider && fact.sourceInstanceId == sourceInstanceId &&
			fact.registrationGeneration == registrationGeneration &&
			fact.collectedDataEpoch == collectedDataEpoch &&
			requireNotNull(fact.windowEndTimeMs) > gapStartTimeMs &&
			requireNotNull(fact.windowStartTimeMs) < gapEndTimeMs
	}.map { fact ->
		maxOf(gapStartTimeMs, requireNotNull(fact.windowStartTimeMs)) to
			minOf(gapEndTimeMs, requireNotNull(fact.windowEndTimeMs))
	}.sortedBy(Pair<Long, Long>::first).toList()
	val result = mutableListOf<PortableLocalGap>()
	var start = gapStartTimeMs
	coverage.forEach { (coveredStart, coveredEnd) ->
		if (coveredEnd <= start) return@forEach
		if (coveredStart > start) {
			result += PortableLocalGap(
				gapId,
				start,
				minOf(coveredStart, gapEndTimeMs),
				reason.toPortableReason(),
			)
		}
		start = maxOf(start, coveredEnd)
	}
	if (start < gapEndTimeMs) {
		result += PortableLocalGap(gapId, start, gapEndTimeMs, reason.toPortableReason())
	}
	return result
}

private fun PortableLocalGap.clip(
	day: PortableLocalDay,
	retainedFromMs: Long?,
): PortableAmbientStepsGapV1? {
	val start = maxOf(startTimeMs, day.startTimeMs, retainedFromMs ?: day.startTimeMs)
	val end = minOf(endTimeMs, day.endTimeMs)
	if (end <= start) return null
	return PortableAmbientStepsGapV1.create(
		AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.GAP,
			"$localGapId|$start|$end",
		),
		start,
		end,
		reason,
	)
}

private fun String.toPortableReason(): PortableAmbientStepsGapReason = when (this) {
	AmbientStepsImportGapEntity.REASON_PROVIDER_NO_EVIDENCE ->
		PortableAmbientStepsGapReason.PROVIDER_NO_EVIDENCE
	AmbientStepsImportGapEntity.REASON_PROVIDER_RETENTION_LOSS ->
		PortableAmbientStepsGapReason.PROVIDER_RETENTION_LOSS
	AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE ->
		PortableAmbientStepsGapReason.PROCESS_ABSENCE
	AmbientStepsImportGapEntity.REASON_BOOT_CHANGED -> PortableAmbientStepsGapReason.BOOT_CHANGED
	AmbientStepsImportGapEntity.REASON_ZONE_CHANGED -> PortableAmbientStepsGapReason.ZONE_CHANGED
	AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED ->
		PortableAmbientStepsGapReason.PROVIDER_CHANGED
	AmbientStepsImportGapEntity.REASON_CLOCK_DISCONTINUITY ->
		PortableAmbientStepsGapReason.CLOCK_DISCONTINUITY
	AmbientStepsImportGapEntity.REASON_AUTHORITY_BOUNDARY_NOT_DRAINED ->
		PortableAmbientStepsGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED
	AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED ->
		PortableAmbientStepsGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED
	else -> error("Unknown Ambient Steps gap reason")
}
