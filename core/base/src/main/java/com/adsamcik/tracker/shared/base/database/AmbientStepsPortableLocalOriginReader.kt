package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1

enum class AmbientStepsPortableLocalOwnerKind { DAY, FACT, GAP }

enum class AmbientStepsPortableLocalOwnerState { ACTIVE, DELETED }

data class AmbientStepsPortableLocalOwner(
	val identity: String,
	val kind: AmbientStepsPortableLocalOwnerKind,
	val state: AmbientStepsPortableLocalOwnerState,
	val contentChecksum: String?,
)

/**
 * Authenticates retained native Ambient Steps before checking portable-origin identity overlap.
 * Wall-time overlap alone is deliberately not an ownership claim.
 */
class AmbientStepsPortableLocalOriginReader(
	private val database: AppDatabase,
) {
	suspend fun readInTransaction(
		incomingIdentities: Set<String>,
	): List<AmbientStepsPortableLocalOwner> {
		require(incomingIdentities.isNotEmpty())
		val factDao = database.ambientStepsFactRevisionDao()
		val stateDao = database.ambientStepsImportStateDao()
		if (factDao.countAll() == 0L && stateDao.countGaps() == 0L) return emptyList()
		val effectiveFacts = mutableListOf<AmbientStepsFactRevisionEntity>()
		val owners = mutableListOf<AmbientStepsPortableLocalOwner>()
		var current = mutableListOf<AmbientStepsFactRevisionEntity>()
		var afterLogicalFactId: String? = null
		var afterSemanticRevision: Long? = null
		var loadedRevisionCount = 0
		fun consumeLineage() {
			if (current.isEmpty()) return
			val lineage = current.authenticatedPortableOwnerLineage()
			val factIdentity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				lineage.last().logicalFactId,
			).value
			if (lineage.last().operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT) {
				if (factIdentity in incomingIdentities) {
					owners += AmbientStepsPortableLocalOwner(
						factIdentity,
						AmbientStepsPortableLocalOwnerKind.FACT,
						AmbientStepsPortableLocalOwnerState.DELETED,
						null,
					)
				}
				current = mutableListOf()
				return
			}
			val fact = lineage.last()
			effectiveFacts += fact
			val portable = PortableAmbientStepsFactV1.create(
				AmbientStepsPortableOpaqueIdentity.derive(
					AmbientStepsPortableIdentityKind.FACT,
					fact.logicalFactId,
				),
				requireNotNull(fact.windowStartTimeMs),
				requireNotNull(fact.windowEndTimeMs),
				requireNotNull(fact.stepCount),
			)
			if (portable.identity.value in incomingIdentities) {
				owners += AmbientStepsPortableLocalOwner(
					portable.identity.value,
					AmbientStepsPortableLocalOwnerKind.FACT,
					AmbientStepsPortableLocalOwnerState.ACTIVE,
					portable.contentChecksum.value,
				)
			}
			val dayIdentity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.DAY,
				"${fact.structuralEpochDay}|${fact.storedZoneId}|" +
					"${fact.structuralDayStartTimeMs}|${fact.structuralDayEndTimeMs}",
			).value
			if (dayIdentity in incomingIdentities) {
				owners += AmbientStepsPortableLocalOwner(
					dayIdentity,
					AmbientStepsPortableLocalOwnerKind.DAY,
					AmbientStepsPortableLocalOwnerState.ACTIVE,
					null,
				)
			}
			current = mutableListOf()
		}
		while (true) {
			val page = factDao.maintenanceRevisionPage(
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
				afterLogicalFactId,
				afterSemanticRevision,
				LOCAL_OWNER_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			loadedRevisionCount = Math.addExact(loadedRevisionCount, page.size)
			if (loadedRevisionCount > MAX_LOCAL_OWNER_REVISIONS) {
				throw AmbientStepsMaintenanceLimitExceeded(
					"Ambient Steps portable owner revision bound exceeded",
				)
			}
			page.forEach { fact ->
				if (!AmbientStepsFactIntegrity.hasValidEffectChecksum(fact)) {
					error("Ambient Steps portable owner fact checksum is invalid")
				}
				if (current.isNotEmpty() && current.last().logicalFactId != fact.logicalFactId) {
					consumeLineage()
				}
				current += fact
			}
			val last = page.last()
			check(last.logicalFactId != afterLogicalFactId ||
				last.semanticRevision != afterSemanticRevision
			) { "Ambient Steps portable owner page did not advance" }
			afterLogicalFactId = last.logicalFactId
			afterSemanticRevision = last.semanticRevision
			if (page.size < LOCAL_OWNER_PAGE_SIZE) break
		}
		consumeLineage()
		val days = effectiveFacts.mapTo(linkedSetOf()) { fact ->
			PortableLocalDay(
				requireNotNull(fact.structuralDayStartTimeMs),
				requireNotNull(fact.structuralDayEndTimeMs),
			)
		}
		val evidence = requireNotNull(database.sourceEvidenceStateDao().get()) {
			"Ambient Steps portable owner read requires source-evidence state"
		}
		val gaps = stateDao.maintenanceGaps(MAX_LOCAL_OWNER_GAPS + 1)
		if (gaps.size > MAX_LOCAL_OWNER_GAPS ||
			gaps.any { it.collectedDataEpoch != evidence.collectedDataEpoch }
		) {
			throw AmbientStepsMaintenanceLimitExceeded(
				"Ambient Steps portable owner gap bound exceeded",
			)
		}
		gaps.forEach { gap ->
			gap.subtractFacts(effectiveFacts).forEach { part ->
				days.forEach { day ->
					if (part.endTimeMs > day.startTimeMs &&
						part.startTimeMs < day.endTimeMs
					) {
						part.clip(day, evidence.retainedFromMs)?.takeIf { portable ->
							portable.identity.value in incomingIdentities
						}?.let { portable ->
							owners += AmbientStepsPortableLocalOwner(
								portable.identity.value,
								AmbientStepsPortableLocalOwnerKind.GAP,
								AmbientStepsPortableLocalOwnerState.ACTIVE,
								portable.contentChecksum.value,
							)
						}
					}
				}
			}
		}
		return owners.distinct()
	}
}

private fun List<AmbientStepsFactRevisionEntity>.authenticatedPortableOwnerLineage():
	List<AmbientStepsFactRevisionEntity> {
	check(isNotEmpty())
	val first = first()
	val expectedFirstRevision = if (
		first.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT && size == 1
	) first.semanticRevision else 1L
	check(withIndex().all { (index, fact) ->
		fact.logicalFactId == first.logicalFactId &&
			fact.semanticRevision == expectedFirstRevision + index &&
			fact.writerId == first.writerId && fact.writerVersion == first.writerVersion &&
			fact.writerOwnerGeneration == first.writerOwnerGeneration &&
			fact.collectedDataEpoch == first.collectedDataEpoch && fact.purpose == first.purpose
	}) { "Ambient Steps portable owner correction lineage is not contiguous" }
	val retractions = filter { it.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT }
	check(retractions.size <= 1 && (retractions.isEmpty() || last() == retractions.single())) {
		"Ambient Steps portable owner retraction is not terminal"
	}
	val upserts = filter { it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT }
	upserts.firstOrNull()?.let { origin ->
		check(upserts.all { fact -> fact.hasSamePortableOwnerOrigin(origin) }) {
			"Ambient Steps portable owner corrections cross immutable origin"
		}
	}
	return this
}

private fun AmbientStepsFactRevisionEntity.hasSamePortableOwnerOrigin(
	other: AmbientStepsFactRevisionEntity,
): Boolean = provider == other.provider &&
	registrationGeneration == other.registrationGeneration &&
	continuitySegmentGeneration == other.continuitySegmentGeneration &&
	sourceInstanceId == other.sourceInstanceId && windowStartTimeMs == other.windowStartTimeMs &&
	structuralEpochDay == other.structuralEpochDay && storedZoneId == other.storedZoneId &&
	structuralDayStartTimeMs == other.structuralDayStartTimeMs &&
	structuralDayEndTimeMs == other.structuralDayEndTimeMs

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

private const val LOCAL_OWNER_PAGE_SIZE = 256
private const val MAX_LOCAL_OWNER_REVISIONS = 65_536
private const val MAX_LOCAL_OWNER_GAPS = 16_384
