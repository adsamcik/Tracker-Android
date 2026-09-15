package com.adsamcik.tracker.tracker.source.ambient.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Dormant canonical Ambient Wi-Fi writer. Wiring it does not activate the provider. */
@Singleton
internal class AmbientWifiFactProjector @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
	suspend fun project(admissionOrdinal: Long): AmbientWifiProjectionResult =
		withContext(ioDispatcher) {
			require(admissionOrdinal > 0L)
			try {
				database.withTransaction { projectInTransaction(admissionOrdinal) }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientWifiProjectionResult.RetryableFailure
			}
		}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun projectInTransaction(
		admissionOrdinal: Long,
	): AmbientWifiProjectionResult {
		val wal = database.sourceEventWalDao().getByAdmissionOrdinal(admissionOrdinal)
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_MISSING)
		if (wal.sourceKind != SourceKind.WIFI.stableCode) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WRONG_SOURCE)
		}
		if (!wal.hasQualifiedIntegrity()) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_INTEGRITY)
		}
		val deliveryIdentity = wal.deliveryIdentity
			?.takeIf(DIGEST::matches)
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.DELIVERY_IDENTITY)
		if (wal.deliveryUnitIndex != 0 || wal.deliveryUnitCount != 1 ||
			wal.authorizationRevision == null ||
			wal.authorizationFingerprint == null ||
			wal.physicalConfigurationFingerprint == null
		) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_SHAPE)
		}
		val authorizationRows = database.sourceBrokerDao().authorizationRevisionBounded(
			SourceKind.WIFI.stableCode,
			wal.registrationGeneration,
			wal.authorizationRevision,
			MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (authorizationRows.size > MAX_AUTHORIZATION_MEMBERS) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		val authorization = runCatching {
			authorizationRows.toAuthorizationSnapshotOrNull()
		}.getOrNull() ?: return unverifiable(
			AmbientWifiProjectionUnverifiableReason.AUTHORIZATION_MISSING,
		)
		if (authorization.authorizationRevision != wal.authorizationRevision ||
			authorization.authorizationFingerprint != wal.authorizationFingerprint ||
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.effectiveBootId != wal.clockDomainId ||
			authorization.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos
		) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.AUTHORIZATION_MISMATCH)
		}
		val ambientMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				member.persistenceEligible &&
				member.logicalTrackingId == null &&
				member.serviceRunId == null
		} ?: return AmbientWifiProjectionResult.NotAmbientEligible
		val sourcePolicyRevision = requireNotNull(ambientMember.sourcePolicyRevision)
		val ambientConsentEpoch = requireNotNull(ambientMember.consentEpoch)
		val policyAuthority = database.sourcePolicyDao().authority()
		val policy = database.sourcePolicyDao().policyAtRevision(
			sourcePolicyRevision,
			SourceKind.WIFI.stableCode,
		)
		val consent = database.sourcePolicyDao().consentEpoch(
			SourceKind.WIFI.stableCode,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
			ambientConsentEpoch,
		)
		if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			policy == null ||
			policy.ambientConsentEpoch != ambientConsentEpoch ||
			!policy.ambientPersistenceEligible ||
			consent?.eligible != true ||
			!consent.persistenceEligible ||
			consent.policyRevision != sourcePolicyRevision ||
			policy.effectiveBootId != wal.clockDomainId ||
			policy.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos ||
			consent.effectiveBootId != wal.clockDomainId ||
			consent.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos
		) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.POLICY_MISMATCH)
		}
		val authority = database.ambientWifiFactDao().authorityAt(
			wal.clockDomainId,
			wal.observedElapsedNanos,
		)
		if (authority?.state != AmbientWifiAuthorityEntity.STATE_ACTIVE ||
			authority.writerId != AmbientWifiFactRevisionEntity.WRITER_ID ||
			authority.writerVersion != AmbientWifiFactRevisionEntity.WRITER_VERSION ||
			authority.writerOwnerGeneration != FIRST_WRITER_OWNER_GENERATION ||
			authority.sourcePolicyRevision != sourcePolicyRevision ||
			authority.ambientConsentEpoch != ambientConsentEpoch ||
			authority.collectedDataEpoch != wal.capturedCollectedDataEpoch
		) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH)
		}
		val currentDeletionGeneration = database.ambientWifiFactDao()
			.latestDeletionMarker(wal.capturedCollectedDataEpoch)
			?.deletionGeneration ?: 0L
		if (authority.scopeDeletionGeneration != currentDeletionGeneration) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val registration = database.sourceBrokerDao().registration(
			SourceKind.WIFI.stableCode,
			wal.registrationGeneration,
		)
		if (registration == null ||
			registration.sourceInstanceId != wal.sourceInstanceId ||
			registration.clockDomainId != wal.clockDomainId ||
			registration.physicalConfigurationFingerprint != wal.physicalConfigurationFingerprint ||
			registration.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			registration.acceptedElapsedRealtimeNanos?.let { it <= wal.observedElapsedNanos } != true ||
			registration.retiredElapsedRealtimeNanos?.let { wal.observedElapsedNanos < it } == false
		) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.REGISTRATION_MISMATCH)
		}
		val payload = runCatching {
			payloadCodec.decode(SourceKind.WIFI, wal.payloadVersion, wal.payload)
		}.getOrNull() as? WifiResultSnapshotPayload ?: return unverifiable(
			AmbientWifiProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE,
		)
		val aggregate = AmbientWifiAggregate.from(payload)
			?: return recordUnverifiableGap(wal, authority)
		val dao = database.ambientWifiFactDao()
		val logicalFactId = AmbientWifiFactIntegrity.logicalFactId(
			deliveryIdentity,
			ambientConsentEpoch,
			wal.capturedCollectedDataEpoch,
			authority.scopeDeletionGeneration,
		)
		val existingCursor = dao.cursor(
			AmbientWifiFactRevisionEntity.WRITER_ID,
			AmbientWifiFactRevisionEntity.WRITER_VERSION,
			logicalFactId,
		)
		if (existingCursor?.latestSourceAdmissionOrdinal == wal.admissionOrdinal) {
			return AmbientWifiProjectionResult.Duplicate(logicalFactId)
		}
		if (existingCursor != null) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELIVERY_IDENTITY_COLLISION)
		}
		val prior = dao.latestAggregateBefore(
			ambientConsentEpoch,
			wal.capturedCollectedDataEpoch,
			authority.scopeDeletionGeneration,
			wal.admissionOrdinal,
		)
		val unchanged = prior?.toAggregate() == aggregate
		val zone = ZoneId.systemDefault()
		val wallTimeMs = wal.wallTimeMs ?: return recordUnverifiableGap(wal, authority)
		val day = Instant.ofEpochMilli(wallTimeMs).atZone(zone).toLocalDate()
		val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val dayEnd = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		val intervalStart = wal.observedIntervalStartNanos ?: wal.observedElapsedNanos
		val coverageStart = (
			wallTimeMs - (wal.observedElapsedNanos - intervalStart)
				.coerceAtLeast(0L) / NANOS_PER_MILLISECOND
			).coerceAtLeast(dayStart)
		val draft = AmbientWifiFactRevisionEntity(
			writerId = AmbientWifiFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientWifiFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = FIRST_WRITER_OWNER_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = AmbientWifiFactIntegrity.mutationId(logicalFactId, 1L),
			factKind = if (unchanged) {
				AmbientWifiFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
			} else {
				AmbientWifiFactRevisionEntity.FACT_KIND_AGGREGATE
			},
			aggregateOwnerLogicalFactId = prior?.logicalFactId.takeIf { unchanged },
			aggregateOwnerSemanticRevision = prior?.semanticRevision.takeIf { unchanged },
			sourceEventId = wal.eventId,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			payloadChecksum = wal.payloadChecksum,
			sourceDeliveryIdentity = deliveryIdentity,
			sourceInstanceId = wal.sourceInstanceId,
			registrationGeneration = wal.registrationGeneration,
			configurationRevision = wal.configRevision,
			physicalConfigurationFingerprint = requireNotNull(wal.physicalConfigurationFingerprint),
			authorizationRevision = requireNotNull(wal.authorizationRevision),
			authorizationFingerprint = requireNotNull(wal.authorizationFingerprint),
			purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
			sourcePolicyRevision = sourcePolicyRevision,
			ambientConsentEpoch = ambientConsentEpoch,
			retentionPolicyId = authority.retentionPolicyId,
			retentionApprovalRevision = authority.retentionApprovalRevision,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			scopeDeletionGeneration = authority.scopeDeletionGeneration,
			clockDomainId = wal.clockDomainId,
			storedZoneId = zone.id,
			structuralEpochDay = day.toEpochDay(),
			structuralDayStartTimeMs = dayStart,
			structuralDayEndTimeMs = dayEnd,
			observedIntervalStartNanos = intervalStart,
			observedElapsedNanos = wal.observedElapsedNanos,
			receivedElapsedNanos = wal.receivedElapsedNanos,
			coverageStartTimeMs = coverageStart,
			observedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = wal.wallTimeUncertaintyMs ?: 0L,
			coverageCompleteness =
				AmbientWifiFactRevisionEntity.COMPLETENESS_UNVERIFIABLE,
			observationCount = aggregate.observationCount.takeUnless { unchanged },
			twoPointFourGhzCount = aggregate.twoPointFourGhzCount.takeUnless { unchanged },
			fiveGhzCount = aggregate.fiveGhzCount.takeUnless { unchanged },
			sixGhzCount = aggregate.sixGhzCount.takeUnless { unchanged },
			otherBandCount = aggregate.otherBandCount.takeUnless { unchanged },
			strongestSignalDbm = aggregate.strongestSignalDbm.takeUnless { unchanged },
			weakestSignalDbm = aggregate.weakestSignalDbm.takeUnless { unchanged },
			signalSumDbm = aggregate.signalSumDbm.takeUnless { unchanged },
			qualityFlags = wal.qualityFlags,
			qualityConfidence = wal.qualityConfidence,
			effectChecksum = ZERO_DIGEST,
			appliedAtMs = maxOf(wal.createdAtMs, wallTimeMs),
		)
		val fact = draft.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(draft))
		if (dao.insertRevision(fact) == -1L) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WRITE_CONFLICT)
		}
		val cursor = AmbientWifiFactCursorEntity(
			fact.writerId,
			fact.writerVersion,
			fact.logicalFactId,
			fact.semanticRevision,
			fact.mutationId,
			fact.effectChecksum,
			fact.sourceAdmissionOrdinal,
			fact.collectedDataEpoch,
			fact.scopeDeletionGeneration,
			1L,
			fact.appliedAtMs,
		)
		if (dao.insertCursor(cursor) == -1L) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WRITE_CONFLICT)
		}
		check(database.sourceEvidenceStateDao().incrementRevision(fact.appliedAtMs) == 1)
		return if (unchanged) {
			AmbientWifiProjectionResult.CoverageCompacted(logicalFactId)
		} else {
			AmbientWifiProjectionResult.FactWritten(logicalFactId)
		}
	}

	private suspend fun recordUnverifiableGap(
		wal: SourceEventWalEntity,
		authority: AmbientWifiAuthorityEntity,
	): AmbientWifiProjectionResult {
		val wallTimeMs = wal.wallTimeMs
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		val zone = ZoneId.systemDefault()
		val day = Instant.ofEpochMilli(wallTimeMs).atZone(zone).toLocalDate()
		val gapId = AmbientWifiAuthorityIntegrity.digest(
			"ambient-wifi-gap-v1",
			wal.eventId,
			authority.authorityRevision,
			authority.scopeDeletionGeneration,
		)
		database.ambientWifiFactDao().insertGap(
			AmbientWifiFactIntegrity.createGap(
				gapId,
				AmbientWifiGapEntity.REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE,
				wallTimeMs,
				wallTimeMs + 1L,
				zone.id,
				day.toEpochDay(),
				authority.sourcePolicyRevision,
				authority.ambientConsentEpoch,
				authority.retentionPolicyId,
				authority.collectedDataEpoch,
				authority.scopeDeletionGeneration,
				maxOf(wal.createdAtMs, wallTimeMs + 1L),
			),
		)
		return AmbientWifiProjectionResult.UnverifiableCoverage(gapId)
	}

	private data class AmbientWifiAggregate(
		val observationCount: Int,
		val twoPointFourGhzCount: Int,
		val fiveGhzCount: Int,
		val sixGhzCount: Int,
		val otherBandCount: Int,
		val strongestSignalDbm: Int,
		val weakestSignalDbm: Int,
		val signalSumDbm: Long,
	) {
		companion object {
			fun from(payload: WifiResultSnapshotPayload): AmbientWifiAggregate? {
				if (payload.accessPoints.isEmpty() ||
					payload.accessPoints.any {
						it.identifierToken.isNotEmpty() ||
							it.providerTimestampNanos == null ||
							it.providerTimestampNanos <= 0L
					}
				) return null
				val frequencies = payload.accessPoints.map { it.frequencyMhz }
				val signals = payload.accessPoints.map { it.signalLevelDbm }
				return AmbientWifiAggregate(
					observationCount = payload.accessPoints.size,
					twoPointFourGhzCount = frequencies.count { it in 2_400..2_500 },
					fiveGhzCount = frequencies.count { it in 4_900..5_899 },
					sixGhzCount = frequencies.count { it in 5_925..7_125 },
					otherBandCount = frequencies.count {
						it !in 2_400..2_500 && it !in 4_900..5_899 && it !in 5_925..7_125
					},
					strongestSignalDbm = signals.max(),
					weakestSignalDbm = signals.min(),
					signalSumDbm = signals.sumOf(Int::toLong),
				)
			}
		}
	}

	private fun AmbientWifiFactRevisionEntity.toAggregate(): AmbientWifiAggregate? {
		if (factKind != AmbientWifiFactRevisionEntity.FACT_KIND_AGGREGATE) return null
		return AmbientWifiAggregate(
			requireNotNull(observationCount),
			requireNotNull(twoPointFourGhzCount),
			requireNotNull(fiveGhzCount),
			requireNotNull(sixGhzCount),
			requireNotNull(otherBandCount),
			requireNotNull(strongestSignalDbm),
			requireNotNull(weakestSignalDbm),
			requireNotNull(signalSumDbm),
		)
	}

	private companion object {
		const val MAX_AUTHORIZATION_MEMBERS = 64
		const val FIRST_WRITER_OWNER_GENERATION = 1L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val ZERO_DIGEST =
			"0000000000000000000000000000000000000000000000000000000000000000"
		val DIGEST = Regex("[0-9a-f]{64}")
	}
}

internal sealed interface AmbientWifiProjectionResult {
	data class FactWritten(val logicalFactId: String) : AmbientWifiProjectionResult
	data class CoverageCompacted(val logicalFactId: String) : AmbientWifiProjectionResult
	data class Duplicate(val logicalFactId: String) : AmbientWifiProjectionResult
	data class UnverifiableCoverage(val gapId: String) : AmbientWifiProjectionResult
	data object NotAmbientEligible : AmbientWifiProjectionResult
	data class Unverifiable(val reason: AmbientWifiProjectionUnverifiableReason) :
		AmbientWifiProjectionResult
	data object RetryableFailure : AmbientWifiProjectionResult
}

internal enum class AmbientWifiProjectionUnverifiableReason {
	WAL_MISSING,
	WRONG_SOURCE,
	WAL_INTEGRITY,
	WAL_SHAPE,
	DELIVERY_IDENTITY,
	AUTHORIZATION_MISSING,
	AUTHORIZATION_MISMATCH,
	POLICY_MISMATCH,
	AMBIENT_AUTHORITY_MISMATCH,
	REGISTRATION_MISMATCH,
	DELETED_SCOPE,
	PAYLOAD_UNVERIFIABLE,
	CLOCK_UNVERIFIABLE,
	DELIVERY_IDENTITY_COLLISION,
	DEPENDENCY_OVERFLOW,
	WRITE_CONFLICT,
}

private fun unverifiable(reason: AmbientWifiProjectionUnverifiableReason) =
	AmbientWifiProjectionResult.Unverifiable(reason)
