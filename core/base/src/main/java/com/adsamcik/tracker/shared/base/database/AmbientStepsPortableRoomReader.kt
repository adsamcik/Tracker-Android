package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_DAY_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_FACT_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_GAP_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AmbientStepsPortableReadRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
	}
}

sealed interface AmbientStepsPortableSnapshot {
	data class Ready(val archive: PortableAmbientStepsArchiveV1) : AmbientStepsPortableSnapshot
	data object NoData : AmbientStepsPortableSnapshot
	data class Unverifiable(val reason: AmbientStepsPortableReadFailure) : AmbientStepsPortableSnapshot
}

enum class AmbientStepsPortableReadFailure {
	SOURCE_AUTHORITY_UNAVAILABLE,
	DELETION_PENDING,
	CORRUPT_RETAINED_STATE,
	RETENTION_CROSSES_FACT,
	MATERIALIZING,
	DEPENDENCY_OVERFLOW,
}

internal enum class AmbientStepsPortableReadCheckpoint {
	TRANSACTION_STARTED,
	AUTHORITY_AUTHENTICATED,
	SNAPSHOT_ASSEMBLED,
}

internal data class AmbientStepsPortableReadLimits(
	val maintenance: AmbientStepsMaintenanceLimits = AmbientStepsMaintenanceLimits(),
	val maximumDays: Int = AmbientStepsPortableFormatV1.MAX_DAYS,
	val maximumFacts: Int = AmbientStepsPortableFormatV1.MAX_FACTS,
	val maximumGaps: Int = AmbientStepsPortableFormatV1.MAX_GAPS,
	val maximumFactsPerDay: Int = AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY,
	val maximumGapsPerDay: Int = AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY,
) {
	init {
		listOf(maximumDays, maximumFacts, maximumGaps, maximumFactsPerDay, maximumGapsPerDay)
			.forEach { require(it in 1 until Int.MAX_VALUE) }
	}
}

/**
 * Read-only Ambient Steps transfer boundary. Every local authority is authenticated inside one
 * Room transaction, while only minimized portable values escape that snapshot.
 */
class AmbientStepsPortableRoomReader(private val database: AppDatabase) {
	suspend fun read(request: AmbientStepsPortableReadRequest): AmbientStepsPortableSnapshot = read(
		request,
		AmbientStepsPortableReadLimits(),
		{ currentCoroutineContext().ensureActive() },
	)

	internal suspend fun read(
		request: AmbientStepsPortableReadRequest,
		limits: AmbientStepsPortableReadLimits,
		checkpoint: suspend (AmbientStepsPortableReadCheckpoint) -> Unit,
	): AmbientStepsPortableSnapshot = try {
		database.withTransaction {
			checkpoint(AmbientStepsPortableReadCheckpoint.TRANSACTION_STARTED)
			readInTransaction(request, limits, checkpoint)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: AmbientStepsMaintenanceLimitExceeded) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.DEPENDENCY_OVERFLOW,
		)
	} catch (_: AmbientStepsPortableLimitExceeded) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.DEPENDENCY_OVERFLOW,
		)
	} catch (_: IllegalArgumentException) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	} catch (_: IllegalStateException) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	} catch (_: ArithmeticException) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readInTransaction(
		request: AmbientStepsPortableReadRequest,
		limits: AmbientStepsPortableReadLimits,
		checkpoint: suspend (AmbientStepsPortableReadCheckpoint) -> Unit,
	): AmbientStepsPortableSnapshot {
		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		)
		val evidence = database.sourceEvidenceStateDao().get()
		if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS || evidence == null) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.SOURCE_AUTHORITY_UNAVAILABLE,
			)
		}
		if (database.ambientStepsFactRevisionDao().countUnrecognizedPayloadRows(
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
			) != 0L
		) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
			)
		}
		val audit = database.loadAuthenticatedAmbientStepsState(
			limits.maintenance,
			checkpoint = { current ->
				if (current == AmbientStepsMaintenanceCheckpoint.AUTHORITY_AUTHENTICATED) {
					checkpoint(AmbientStepsPortableReadCheckpoint.AUTHORITY_AUTHENTICATED)
				} else {
					currentCoroutineContext().ensureActive()
				}
			},
			authenticateRetractedPayloadAuthority = false,
		)
		val effectiveFacts = audit.lineages.mapNotNull { lineage ->
			lineage.latest.takeIf {
				it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
			}
		}
		if (effectiveFacts.isEmpty()) return AmbientStepsPortableSnapshot.NoData
		val policyAuthority = database.sourcePolicyDao().authority()
		val currentPolicy = policyAuthority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.let { authority ->
			database.sourcePolicyDao().policyAtRevision(
				authority.currentPolicyRevision,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			)
		}
		val currentConsent = database.sourcePolicyDao().latestConsentEpoch(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		if (currentPolicy == null || currentConsent == null) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.SOURCE_AUTHORITY_UNAVAILABLE,
			)
		}
		if (!currentPolicy.enabled || !currentPolicy.ambientPersistenceEligible ||
			currentPolicy.ambientConsentEpoch != currentConsent.epoch || !currentConsent.eligible ||
			!currentConsent.persistenceEligible ||
			currentConsent.policyRevision != currentPolicy.policyRevision
		) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.DELETION_PENDING,
			)
		}
		val retainedFromMs = evidence.retainedFromMs
		if (retainedFromMs != null && effectiveFacts.any {
			requireNotNull(it.windowStartTimeMs) < retainedFromMs
		}) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.RETENTION_CROSSES_FACT,
			)
		}
		val overlappingFacts = effectiveFacts.filter { fact ->
			requireNotNull(fact.windowEndTimeMs) > request.fromInclusiveMs &&
				requireNotNull(fact.windowStartTimeMs) < request.toExclusiveMs
		}
		if (overlappingFacts.isEmpty()) return AmbientStepsPortableSnapshot.NoData
		val selectedKeys = overlappingFacts.mapTo(linkedSetOf()) { it.dayKey() }
		requirePortableBound(selectedKeys.size <= limits.maximumDays)
		val selectedFacts = effectiveFacts.filter { it.dayKey() in selectedKeys }
		requirePortableBound(selectedFacts.size <= limits.maximumFacts)
		val activeCursors = audit.cursors.filter {
			it.status == AmbientStepsImportCursorEntity.STATUS_ACTIVE
		}
		if (activeCursors.any { cursor ->
			!database.hasExactActiveAmbientCursorAuthority(
				cursor,
				currentPolicy,
				currentConsent,
				limits.maintenance.maximumAuthorizationMembersPerRevision,
			)
		}) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
			)
		}
		if (selectedKeys.any { day ->
			activeCursors.any { cursor -> cursor.isMaterializing(day) }
		}) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.MATERIALIZING,
			)
		}
		val effectiveGaps = mutableListOf<AmbientStepsPortableEffectiveGap>()
		audit.gaps.forEach { gap ->
			gap.subtractCoveredFacts(
				effectiveFacts,
				limits.maximumGaps - effectiveGaps.size,
			).forEach { part ->
				effectiveGaps += part
			}
		}
		val factsByDay = selectedFacts.groupBy(AmbientStepsFactRevisionEntity::dayKey)
		var portableGapCount = 0
		val days = selectedKeys.map { day ->
			val gaps = mutableListOf<PortableAmbientStepsGapV1>()
			effectiveGaps.forEach { gap ->
				gap.clipTo(day, retainedFromMs)?.let { portableGap ->
					requirePortableBound(portableGapCount < limits.maximumGaps)
					requirePortableBound(gaps.size < limits.maximumGapsPerDay)
					gaps += portableGap
					portableGapCount++
				}
			}
			val facts = factsByDay[day].orEmpty()
			day.toPortableDay(facts, gaps, retainedFromMs, limits)
		}.sortedWith(PORTABLE_AMBIENT_STEPS_DAY_ORDER)
		val archive = PortableAmbientStepsArchiveV1.create(days)
		checkpoint(AmbientStepsPortableReadCheckpoint.SNAPSHOT_ASSEMBLED)
		return AmbientStepsPortableSnapshot.Ready(archive)
	}
}

private data class AmbientStepsPortableDayKey(
	val epochDay: Long,
	val zoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
)

private data class AmbientStepsPortableEffectiveGap(
	val localGapId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val reason: PortableAmbientStepsGapReason,
)

private fun AmbientStepsFactRevisionEntity.dayKey() = AmbientStepsPortableDayKey(
	requireNotNull(structuralEpochDay),
	requireNotNull(storedZoneId),
	requireNotNull(structuralDayStartTimeMs),
	requireNotNull(structuralDayEndTimeMs),
)

private fun AmbientStepsImportCursorEntity.isMaterializing(day: AmbientStepsPortableDayKey): Boolean =
	status == AmbientStepsImportCursorEntity.STATUS_ACTIVE && segmentStartTimeMs < day.endTimeMs &&
		importedThroughTimeMs < day.endTimeMs

private suspend fun AppDatabase.hasExactActiveAmbientCursorAuthority(
	cursor: AmbientStepsImportCursorEntity,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	maximumAuthorizationMembers: Int,
): Boolean {
	val registration = sourceBrokerDao().registration(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		cursor.registrationGeneration,
	) ?: return false
	if (registration.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
		registration.registrationGeneration != cursor.registrationGeneration ||
		registration.sourceInstanceId != cursor.sourceInstanceId ||
		registration.ownerScope != SourceProviderPurposeScope.exactOwnerScope(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		) || registration.clockDomainId != cursor.registrationClockDomainId ||
		registration.physicalConfigurationFingerprint !=
			"ambient-steps-provider:v1:mechanism=${cursor.provider}" ||
		registration.collectedDataEpoch != cursor.collectedDataEpoch ||
		registration.providerResidency !=
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ||
		registration.providerProcessIncarnationId != null ||
		registration.status != ProviderRegistrationGenerationEntity.STATUS_ACTIVE ||
		registration.acceptedAtMs != cursor.registrationAcceptedAtMs ||
		registration.acceptedElapsedRealtimeNanos !=
			cursor.registrationAcceptedElapsedRealtimeNanos ||
		registration.retiredAtMs != null || registration.retiredElapsedRealtimeNanos != null ||
		registration.failureCode != null ||
		registration.captureCallbackBarrierAuthorizationRevision != 0L
	) return false
	if (ambientStepsImportStateDao().latestAuthorizationRevision(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			cursor.registrationGeneration,
		) != cursor.authorizationRevision
	) return false
	val pointer = sourceRegistrationStateDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		registration.ownerScope,
	) ?: return false
	if (pointer.sourceKind != registration.sourceKind ||
		pointer.ownerScope != registration.ownerScope ||
		pointer.sourceInstanceId != registration.sourceInstanceId ||
		pointer.clockDomainId != registration.clockDomainId ||
		pointer.registrationGeneration != registration.registrationGeneration ||
		pointer.appliedRevision != cursor.sourcePolicyRevision ||
		pointer.collectedDataEpoch != registration.collectedDataEpoch ||
		pointer.updatedAtMs < cursor.registrationAcceptedAtMs
	) return false

	val authorization = sourceBrokerDao().authorizationRevisionBounded(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		cursor.registrationGeneration,
		cursor.authorizationRevision,
		maximumAuthorizationMembers + 1,
	)
	requirePortableBound(authorization.size <= maximumAuthorizationMembers)
	val first = authorization.firstOrNull() ?: return false
	if (authorization.any { member -> !member.matches(cursor, first) }) return false
	val demandIds = authorization.mapNotNull(SourceAuthorizationEntity::demandId)
	if (demandIds.size != authorization.size || demandIds.distinct().size != demandIds.size) {
		return false
	}
	val demands = sourceBrokerDao().demandsByIds(demandIds)
	if (demands.size != demandIds.size ||
		demands.map(SourceDemandEntity::demandId).toSet() != demandIds.toSet() ||
		SourceBrokerAuthorization.fingerprint(demands) != cursor.authorizationFingerprint ||
		demands.any { demand -> !demand.matchesActiveAmbientCursor(cursor) }
	) return false
	val expectedAuthorization = SourceBrokerAuthorization.rows(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		cursor.registrationGeneration,
		cursor.authorizationRevision,
		demands,
		cursor.authorizationEffectiveBootId,
		cursor.authorizationEffectiveElapsedRealtimeNanos,
		cursor.authorizationEffectiveWallTimeMs,
	)
	if (authorization.toSet() != expectedAuthorization.toSet()) return false

	return policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && policy.enabled &&
		policy.policyRevision == cursor.sourcePolicyRevision && policy.ambientPersistenceEligible &&
		policy.ambientConsentEpoch == cursor.ambientConsentEpoch &&
		policy.effectiveBootId == cursor.authorizationEffectiveBootId &&
		policy.effectiveElapsedRealtimeNanos <= cursor.authorizationEffectiveElapsedRealtimeNanos &&
		policy.effectiveWallTimeMs <= cursor.authorizationEffectiveWallTimeMs &&
		consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
		consent.epoch == cursor.ambientConsentEpoch && consent.eligible &&
		consent.persistenceEligible && consent.policyRevision == cursor.sourcePolicyRevision &&
		consent.effectiveBootId == cursor.authorizationEffectiveBootId &&
		consent.effectiveElapsedRealtimeNanos <=
			cursor.authorizationEffectiveElapsedRealtimeNanos &&
		consent.effectiveWallTimeMs <= cursor.authorizationEffectiveWallTimeMs
}

private fun SourceAuthorizationEntity.matches(
	cursor: AmbientStepsImportCursorEntity,
	first: SourceAuthorizationEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	registrationGeneration == cursor.registrationGeneration &&
	authorizationRevision == cursor.authorizationRevision && !isDenyAll &&
	authorizationFingerprint == cursor.authorizationFingerprint &&
	purposeEligibilityMask == SourceBrokerPurpose.MASK_AMBIENT_PRODUCT &&
	purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && persistenceEligible &&
	sourcePolicyRevision == cursor.sourcePolicyRevision && consentEpoch == cursor.ambientConsentEpoch &&
	effectiveBootId == cursor.authorizationEffectiveBootId &&
	effectiveElapsedRealtimeNanos == cursor.authorizationEffectiveElapsedRealtimeNanos &&
	effectiveWallTimeMs == cursor.authorizationEffectiveWallTimeMs &&
	effectiveBootId == first.effectiveBootId &&
	effectiveElapsedRealtimeNanos == first.effectiveElapsedRealtimeNanos &&
	effectiveWallTimeMs == first.effectiveWallTimeMs && logicalTrackingId == null &&
	serviceRunId == null && manifestRevision == null && lifecycleLeaseGeneration == null

private fun SourceDemandEntity.matchesActiveAmbientCursor(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && persistenceEligible &&
	sourcePolicyRevision == cursor.sourcePolicyRevision && consentEpoch == cursor.ambientConsentEpoch &&
	logicalTrackingId == null && serviceRunId == null && manifestRevision == null &&
	lifecycleLeaseGeneration == null && requestedBootId == cursor.authorizationEffectiveBootId &&
	requestedElapsedRealtimeNanos <= cursor.authorizationEffectiveElapsedRealtimeNanos &&
	requestedAtMs <= cursor.authorizationEffectiveWallTimeMs &&
	minimumAcquisitionSpec == listOf(
		"ambient-steps:v1:mechanism=${cursor.provider}",
		"coverage=OPPORTUNISTIC",
		"record_freshness=SOURCE_NATIVE_CURSOR",
	).joinToString(";") && status == SourceDemandEntity.STATUS_ACTIVE && retireBootId == null &&
	retireElapsedRealtimeNanos == null && retiredAtMs == null

private fun AmbientStepsImportGapEntity.subtractCoveredFacts(
	facts: List<AmbientStepsFactRevisionEntity>,
	maximumParts: Int,
): List<AmbientStepsPortableEffectiveGap> {
	requirePortableBound(maximumParts >= 0)
	if (gapEndTimeMs == gapStartTimeMs) return emptyList()
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
	val result = mutableListOf<AmbientStepsPortableEffectiveGap>()
	var remainingStart = gapStartTimeMs
	coverage.forEach { (start, end) ->
		if (end <= remainingStart) return@forEach
		if (start > remainingStart) {
			requirePortableBound(result.size < maximumParts)
			result += AmbientStepsPortableEffectiveGap(
				gapId,
				remainingStart,
				minOf(start, gapEndTimeMs),
				reason.toPortableGapReason(),
			)
		}
		remainingStart = maxOf(remainingStart, end)
	}
	if (remainingStart < gapEndTimeMs) {
		requirePortableBound(result.size < maximumParts)
		result += AmbientStepsPortableEffectiveGap(
			gapId,
			remainingStart,
			gapEndTimeMs,
			reason.toPortableGapReason(),
		)
	}
	return result
}

private fun AmbientStepsPortableEffectiveGap.clipTo(
	day: AmbientStepsPortableDayKey,
	retainedFromMs: Long?,
): PortableAmbientStepsGapV1? {
	val start = maxOf(startTimeMs, day.startTimeMs, retainedFromMs ?: day.startTimeMs)
	val end = minOf(endTimeMs, day.endTimeMs)
	if (end <= start) return null
	val identity = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.GAP,
		"$localGapId|$start|$end",
	)
	return PortableAmbientStepsGapV1.create(identity, start, end, reason)
}

private fun AmbientStepsPortableDayKey.toPortableDay(
	localFacts: List<AmbientStepsFactRevisionEntity>,
	localGaps: List<PortableAmbientStepsGapV1>,
	retainedFromMs: Long?,
	limits: AmbientStepsPortableReadLimits,
): PortableAmbientStepsDayV1 {
	requirePortableBound(localFacts.size <= limits.maximumFactsPerDay)
	requirePortableBound(localGaps.size <= limits.maximumGapsPerDay)
	val facts = localFacts.map { fact ->
		PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				fact.logicalFactId,
			),
			requireNotNull(fact.windowStartTimeMs),
			requireNotNull(fact.windowEndTimeMs),
			requireNotNull(fact.stepCount),
		)
	}.sortedWith(PORTABLE_AMBIENT_STEPS_FACT_ORDER)
	val gaps = localGaps.sortedWith(PORTABLE_AMBIENT_STEPS_GAP_ORDER)
	val retainedBoundary = retainedFromMs?.takeIf { it > startTimeMs && it < endTimeMs }
	val coverageIntervals = buildList {
		retainedBoundary?.let { add(startTimeMs to it) }
		facts.forEach { add(it.intervalStartTimeMs to it.intervalEndTimeMs) }
		gaps.forEach { add(it.intervalStartTimeMs to it.intervalEndTimeMs) }
	}.sortedBy(Pair<Long, Long>::first)
	val entireDayAccountedFor = coverageIntervals.covers(startTimeMs, endTimeMs)
	val completelyCoveredByFacts = gaps.isEmpty() && retainedBoundary == null &&
		facts.map { it.intervalStartTimeMs to it.intervalEndTimeMs }.covers(startTimeMs, endTimeMs)
	val causes = buildList {
		if (gaps.isNotEmpty()) add(PortableAmbientStepsPartialCause.EXPLICIT_GAP)
		if (retainedBoundary != null) add(PortableAmbientStepsPartialCause.RETENTION)
		if (!entireDayAccountedFor) add(PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY)
	}.distinct().sortedBy { it.ordinal }
	val total = facts.fold(0L) { sum, fact -> Math.addExact(sum, fact.stepCount) }
	val identity = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.DAY,
		"$epochDay|$zoneId|$startTimeMs|$endTimeMs",
	)
	return PortableAmbientStepsDayV1.create(
		identity = identity,
		structuralEpochDay = epochDay,
		storedZoneId = zoneId,
		structuralDayStartTimeMs = startTimeMs,
		structuralDayEndTimeMs = endTimeMs,
		retainedFromTimeMs = retainedBoundary,
		coverage = if (completelyCoveredByFacts) {
			PortableAmbientStepsCoverage.COMPLETE
		} else {
			PortableAmbientStepsCoverage.PARTIAL
		},
		partialCauses = causes,
		retainedStepCount = total,
		facts = facts,
		gaps = gaps,
	)
}

private fun List<Pair<Long, Long>>.covers(startTimeMs: Long, endTimeMs: Long): Boolean {
	var coveredThrough = startTimeMs
	for ((start, end) in this) {
		if (end <= coveredThrough) continue
		if (start > coveredThrough) return false
		coveredThrough = maxOf(coveredThrough, end)
		if (coveredThrough >= endTimeMs) return true
	}
	return false
}

private fun String.toPortableGapReason(): PortableAmbientStepsGapReason = when (this) {
	AmbientStepsImportGapEntity.REASON_PROVIDER_NO_EVIDENCE ->
		PortableAmbientStepsGapReason.PROVIDER_NO_EVIDENCE
	AmbientStepsImportGapEntity.REASON_PROVIDER_RETENTION_LOSS ->
		PortableAmbientStepsGapReason.PROVIDER_RETENTION_LOSS
	AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE -> PortableAmbientStepsGapReason.PROCESS_ABSENCE
	AmbientStepsImportGapEntity.REASON_BOOT_CHANGED -> PortableAmbientStepsGapReason.BOOT_CHANGED
	AmbientStepsImportGapEntity.REASON_ZONE_CHANGED -> PortableAmbientStepsGapReason.ZONE_CHANGED
	AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED -> PortableAmbientStepsGapReason.PROVIDER_CHANGED
	AmbientStepsImportGapEntity.REASON_CLOCK_DISCONTINUITY ->
		PortableAmbientStepsGapReason.CLOCK_DISCONTINUITY
	AmbientStepsImportGapEntity.REASON_AUTHORITY_BOUNDARY_NOT_DRAINED ->
		PortableAmbientStepsGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED
	AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED ->
		PortableAmbientStepsGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED
	else -> error("Unknown Ambient Steps gap reason")
}

private fun requirePortableBound(condition: Boolean) {
	if (!condition) throw AmbientStepsPortableLimitExceeded()
}

private class AmbientStepsPortableLimitExceeded : IllegalStateException()
