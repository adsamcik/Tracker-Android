package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsNativeReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsNativeReplayFootprintIntegrity
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
	val ownerDayIdentity: String? = null,
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

	internal suspend fun readAllForFullClearInTransaction():
		List<AmbientStepsPortableLocalOwner> = readAuthenticatedInTransaction(null)

	private suspend fun readAuthenticatedInTransaction(
		incomingIdentities: Set<String>?,
	): List<AmbientStepsPortableLocalOwner> {
		require(incomingIdentities == null || incomingIdentities.isNotEmpty())
		val factDao = database.ambientStepsFactRevisionDao()
		val stateDao = database.ambientStepsImportStateDao()
		val evidenceEpoch = database.sourceEvidenceStateDao().get()?.collectedDataEpoch
		val preservedRows = if (incomingIdentities == null) {
			database.authenticatedNativeReplayFootprints(
				checkNotNull(evidenceEpoch) {
					"Ambient Steps source-evidence state is unavailable"
				},
			)
		} else {
			incomingIdentities.chunked(IDENTITY_QUERY_CHUNK_SIZE).flatMap { identities ->
				factDao.nativeReplayFootprints(identities, identities.size + 1)
			}
		}
		if ((incomingIdentities != null && preservedRows.size > incomingIdentities.size) ||
			preservedRows.any {
				!AmbientStepsNativeReplayFootprintIntegrity.isAuthentic(it) ||
					evidenceEpoch == null ||
					it.collectedDataEpoch != evidenceEpoch
			}
		) {
			storedEvidenceUnverifiable()
		}
		val preserved = preservedRows.map { row ->
			AmbientStepsPortableLocalOwner(
				identity = row.protectedIdentity,
				kind = row.identityKind.toLocalOwnerKind(),
				state = AmbientStepsPortableLocalOwnerState.DELETED,
				contentChecksum = null,
				ownerDayIdentity = row.ownerDayIdentity,
			)
		}
		if (factDao.countAll() == 0L && stateDao.countCursors() == 0L &&
			stateDao.countGaps() == 0L && stateDao.countAuthorityTransitions() == 0L
		) return preserved
		val authority = database.loadAuthenticatedAmbientStepsState(
			AmbientStepsMaintenanceLimits(),
			checkpoint = { currentCoroutineContext().ensureActive() },
			authenticateRetractedPayloadAuthority = false,
		)
		val owners = authority.toPortableLocalOwners(
			incomingIdentities,
			preservedRows.mapTo(mutableSetOf()) {
				it.protectedIdentity
			},
		)
		return (preserved + owners).distinct()
	}

	private fun String.toLocalOwnerKind(): AmbientStepsPortableLocalOwnerKind = when (this) {
		AmbientStepsNativeReplayFootprintEntity.KIND_DAY -> AmbientStepsPortableLocalOwnerKind.DAY
		AmbientStepsNativeReplayFootprintEntity.KIND_FACT -> AmbientStepsPortableLocalOwnerKind.FACT
		AmbientStepsNativeReplayFootprintEntity.KIND_GAP -> AmbientStepsPortableLocalOwnerKind.GAP
		else -> error("Unknown Ambient Steps native replay footprint kind $this")
	}

	private fun storedEvidenceUnverifiable(): Nothing =
		throw AmbientStepsPortableLocalOriginFailure(
			AmbientStepsPortableLocalOriginFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
}

internal fun AmbientStepsMaintenanceAudit.toPortableLocalOwners(
	incomingIdentities: Set<String>?,
	protectedIdentities: Set<String>,
): List<AmbientStepsPortableLocalOwner> {
	val effectiveFacts = mutableListOf<AmbientStepsFactRevisionEntity>()
	val structuralFacts = mutableListOf<AmbientStepsFactRevisionEntity>()
	val owners = mutableListOf<AmbientStepsPortableLocalOwner>()
	lineages.forEach { lineage ->
		val factIdentity = AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.FACT,
			lineage.logicalFactId,
		).value
		val structuralFact = lineage.upserts.lastOrNull()
		if (structuralFact == null) {
			if (incomingIdentities == null || factIdentity in incomingIdentities) {
				check(factIdentity in protectedIdentities) {
					"Redacted Ambient Steps lineage is missing replay protection"
				}
			}
			return@forEach
		}
		structuralFacts += structuralFact
		val dayIdentity = AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.DAY,
			"${structuralFact.structuralEpochDay}|${structuralFact.storedZoneId}|" +
				"${structuralFact.structuralDayStartTimeMs}|" +
				"${structuralFact.structuralDayEndTimeMs}",
		).value
		if (lineage.latest.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT) {
			if (incomingIdentities == null || factIdentity in incomingIdentities) {
				owners += AmbientStepsPortableLocalOwner(
					factIdentity,
					AmbientStepsPortableLocalOwnerKind.FACT,
					AmbientStepsPortableLocalOwnerState.DELETED,
					null,
					dayIdentity,
				)
			}
			if (incomingIdentities == null || dayIdentity in incomingIdentities) {
				owners += AmbientStepsPortableLocalOwner(
					dayIdentity,
					AmbientStepsPortableLocalOwnerKind.DAY,
					AmbientStepsPortableLocalOwnerState.DELETED,
					null,
					dayIdentity,
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
			if (incomingIdentities == null || portable.identity.value in incomingIdentities) {
				owners += AmbientStepsPortableLocalOwner(
					portable.identity.value,
					AmbientStepsPortableLocalOwnerKind.FACT,
					AmbientStepsPortableLocalOwnerState.ACTIVE,
					portable.contentChecksum.value,
					dayIdentity,
				)
			}
			if (incomingIdentities == null || dayIdentity in incomingIdentities) {
				owners += AmbientStepsPortableLocalOwner(
					dayIdentity,
					AmbientStepsPortableLocalOwnerKind.DAY,
					AmbientStepsPortableLocalOwnerState.ACTIVE,
					null,
					dayIdentity,
				)
			}
		}
	}
	val days = structuralFacts.mapTo(linkedSetOf()) { fact ->
		PortableLocalDay(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.DAY,
				"${fact.structuralEpochDay}|${fact.storedZoneId}|" +
					"${fact.structuralDayStartTimeMs}|${fact.structuralDayEndTimeMs}",
			).value,
			requireNotNull(fact.structuralDayStartTimeMs),
			requireNotNull(fact.structuralDayEndTimeMs),
		)
	}
	gaps.forEach { gap ->
		gap.subtractFacts(effectiveFacts).forEach { part ->
			days.forEach { day ->
				if (part.endTimeMs > day.startTimeMs &&
					part.startTimeMs < day.endTimeMs
				) {
					part.clip(day, evidence.retainedFromMs)?.takeIf { portable ->
						incomingIdentities == null ||
							portable.identity.value in incomingIdentities
					}?.let { portable ->
						owners += AmbientStepsPortableLocalOwner(
							portable.identity.value,
							AmbientStepsPortableLocalOwnerKind.GAP,
							AmbientStepsPortableLocalOwnerState.ACTIVE,
							portable.contentChecksum.value,
							day.identity,
						)
					}
				}
			}
		}
	}
	return owners.distinct()
}

private data class PortableLocalDay(
	val identity: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
)

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

private const val IDENTITY_QUERY_CHUNK_SIZE = 500
internal const val MAX_NATIVE_REPLAY_FOOTPRINTS = 65_536
