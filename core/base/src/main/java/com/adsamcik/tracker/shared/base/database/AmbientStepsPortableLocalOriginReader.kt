package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AmbientStepsPortableLocalOwnerKind { DAY, FACT, GAP }

enum class AmbientStepsPortableLocalOwnerState { ACTIVE, DELETED }

data class AmbientStepsPortableLocalOwner(
	val identity: String,
	val kind: AmbientStepsPortableLocalOwnerKind,
	val state: AmbientStepsPortableLocalOwnerState,
	val contentChecksum: String?,
)

enum class AmbientStepsPortableLocalOriginFailureReason {
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

class AmbientStepsPortableLocalOriginFailure(
	val reason: AmbientStepsPortableLocalOriginFailureReason,
) : RuntimeException(reason.name)

/**
 * Authenticates retained native Ambient Steps before checking portable-origin identity overlap.
 * Wall-time overlap alone is deliberately not an ownership claim.
 */
class AmbientStepsPortableLocalOriginReader(
	private val database: AppDatabase,
) {
	suspend fun readInTransaction(
		incomingIdentities: Set<String>,
	): List<AmbientStepsPortableLocalOwner> = try {
		readAuthenticatedInTransaction(incomingIdentities)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: AmbientStepsMaintenanceLimitExceeded) {
		throw AmbientStepsPortableLocalOriginFailure(
			AmbientStepsPortableLocalOriginFailureReason.DEPENDENCY_OVERFLOW,
		)
	} catch (_: IllegalArgumentException) {
		storedEvidenceUnverifiable()
	} catch (_: IllegalStateException) {
		storedEvidenceUnverifiable()
	} catch (_: ArithmeticException) {
		storedEvidenceUnverifiable()
	} catch (_: java.time.DateTimeException) {
		storedEvidenceUnverifiable()
	}

	private suspend fun readAuthenticatedInTransaction(
		incomingIdentities: Set<String>,
	): List<AmbientStepsPortableLocalOwner> {
		require(incomingIdentities.isNotEmpty())
		val factDao = database.ambientStepsFactRevisionDao()
		val stateDao = database.ambientStepsImportStateDao()
		if (factDao.countAll() == 0L && stateDao.countCursors() == 0L &&
			stateDao.countGaps() == 0L && stateDao.countAuthorityTransitions() == 0L
		) return emptyList()
		val effectiveFacts = mutableListOf<AmbientStepsFactRevisionEntity>()
		val owners = mutableListOf<AmbientStepsPortableLocalOwner>()
		val authority = database.visitAuthenticatedAmbientStepsState(
			AmbientStepsMaintenanceLimits(),
			checkpoint = { currentCoroutineContext().ensureActive() },
			authenticateRetractedPayloadAuthority = false,
		) { lineage ->
			val factIdentity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				lineage.logicalFactId,
			).value
			if (lineage.latest.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT) {
				if (factIdentity in incomingIdentities) {
					owners += AmbientStepsPortableLocalOwner(
						factIdentity,
						AmbientStepsPortableLocalOwnerKind.FACT,
						AmbientStepsPortableLocalOwnerState.DELETED,
						null,
					)
				}
			} else {
				val fact = lineage.latest
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
			}
		}
		val days = effectiveFacts.mapTo(linkedSetOf()) { fact ->
			PortableLocalDay(
				requireNotNull(fact.structuralDayStartTimeMs),
				requireNotNull(fact.structuralDayEndTimeMs),
			)
		}
		authority.gaps.forEach { gap ->
			gap.subtractFacts(effectiveFacts).forEach { part ->
				days.forEach { day ->
					if (part.endTimeMs > day.startTimeMs &&
						part.startTimeMs < day.endTimeMs
					) {
						part.clip(day, authority.evidence.retainedFromMs)?.takeIf { portable ->
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

	private fun storedEvidenceUnverifiable(): Nothing =
		throw AmbientStepsPortableLocalOriginFailure(
			AmbientStepsPortableLocalOriginFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
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
