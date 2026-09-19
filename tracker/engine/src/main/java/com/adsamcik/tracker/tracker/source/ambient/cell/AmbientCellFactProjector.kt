package com.adsamcik.tracker.tracker.source.ambient.cell

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Dormant canonical Ambient Cell writer; it consumes only already-durable callback WAL. */
@Singleton
internal class AmbientCellFactProjector @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
	suspend fun project(admissionOrdinal: Long): AmbientCellProjectionResult =
		withContext(ioDispatcher) {
			require(admissionOrdinal > 0L)
			try {
				database.withTransaction { projectInTransaction(admissionOrdinal) }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientCellProjectionResult.RetryableFailure
			}
		}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun projectInTransaction(
		admissionOrdinal: Long,
	): AmbientCellProjectionResult {
		if (
			database.collectedDataDeletionOperationDao()
				.activeRetentionFloorSettlement() != null
		) {
			return AmbientCellProjectionResult.RetryableFailure
		}
		val wal = database.sourceEventWalDao().getByAdmissionOrdinal(admissionOrdinal)
			?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WAL_MISSING)
		if (wal.sourceKind != SourceKind.CELL.stableCode) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WRONG_SOURCE)
		}
		if (!wal.hasQualifiedIntegrity()) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WAL_INTEGRITY)
		}
		val deliveryIdentity = wal.deliveryIdentity?.takeIf(DIGEST::matches)
			?: return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.DELIVERY_IDENTITY,
			)
		if (wal.deliveryUnitIndex != 0 || wal.deliveryUnitCount != 1 ||
			wal.authorizationRevision == null ||
			wal.authorizationFingerprint == null ||
			wal.physicalConfigurationFingerprint == null
		) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WAL_SHAPE)
		}
		val observedWallTimeMs = wal.wallTimeMs
			?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH,
			)
		if (lifecycle.collectedDataEpoch != wal.capturedCollectedDataEpoch) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH,
			)
		}
		val rows = database.sourceBrokerDao().authorizationRevisionBounded(
			SourceKind.CELL.stableCode,
			wal.registrationGeneration,
			wal.authorizationRevision,
			MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (rows.size > MAX_AUTHORIZATION_MEMBERS) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		val authorization = runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull()
			?: return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.AUTHORIZATION_MISSING,
			)
		if (authorization.authorizationRevision != wal.authorizationRevision ||
			authorization.authorizationFingerprint != wal.authorizationFingerprint ||
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.effectiveBootId != wal.clockDomainId ||
			authorization.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos
		) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.AUTHORIZATION_MISMATCH)
		}
		val ambient = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				member.persistenceEligible &&
				member.logicalTrackingId == null &&
				member.serviceRunId == null
		} ?: return AmbientCellProjectionResult.NotAmbientEligible
		val sourcePolicyRevision = requireNotNull(ambient.sourcePolicyRevision)
		val ambientConsentEpoch = requireNotNull(ambient.consentEpoch)
		val policyAuthority = database.sourcePolicyDao().authority()
		val policy = database.sourcePolicyDao().policyAtRevision(
			sourcePolicyRevision,
			SourceKind.CELL.stableCode,
		)
		val consent = database.sourcePolicyDao().consentEpoch(
			SourceKind.CELL.stableCode,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
			ambientConsentEpoch,
		)
		val currentPolicy = policyAuthority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.let {
			database.sourcePolicyDao().policyAtRevision(
				it.currentPolicyRevision,
				SourceKind.CELL.stableCode,
			)
		}
		val currentConsent = database.sourcePolicyDao().latestConsentEpoch(
			SourceKind.CELL.stableCode,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		if (policyAuthority == null ||
			policyAuthority.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			policy == null ||
			consent == null ||
			!policy.hasExactEligibleAmbientConsentReference(consent) ||
			!policy.isEffectiveAtOrBefore(
				wal.clockDomainId,
				wal.observedElapsedNanos,
				observedWallTimeMs,
			) ||
			!consent.isEffectiveAtOrBefore(
				wal.clockDomainId,
				wal.observedElapsedNanos,
				observedWallTimeMs,
			) ||
			currentPolicy == null ||
			currentConsent == null ||
			currentPolicy.ambientConsentEpoch != ambientConsentEpoch ||
			!currentPolicy.hasExactEligibleAmbientConsentReference(currentConsent)
		) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.POLICY_MISMATCH)
		}
		val authority = database.ambientCellFactDao().authorityAt(
			wal.clockDomainId,
			wal.observedElapsedNanos,
		)
		if (authority == null ||
			!AmbientCellAuthorityIntegrity.isAuthentic(authority) ||
			authority.state != AmbientCellAuthorityEntity.STATE_ACTIVE ||
			authority.writerId != AmbientCellFactRevisionEntity.WRITER_ID ||
			authority.writerVersion != AmbientCellFactRevisionEntity.WRITER_VERSION ||
			authority.writerOwnerGeneration != FIRST_WRITER_OWNER_GENERATION ||
			authority.sourcePolicyRevision != sourcePolicyRevision ||
			authority.ambientConsentEpoch != ambientConsentEpoch ||
			authority.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			authority.demandId != ambient.demandId
		) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH,
			)
		}
		val currentAuthority = database.ambientCellFactDao().latestAuthority()
		if (currentAuthority == null ||
			!AmbientCellAuthorityIntegrity.isAuthentic(currentAuthority) ||
			!currentAuthority.isActive ||
			currentAuthority.collectedDataEpoch != wal.capturedCollectedDataEpoch
		) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH,
			)
		}
		val retention = database.ambientCellFactDao().retentionAuthorityAt(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			wal.clockDomainId,
			wal.observedElapsedNanos,
		)
		if (retention == null ||
			!AmbientCellRetentionAuthorityIntegrity.isAuthentic(retention) ||
			!retention.isActive ||
			retention.approvalRevision != authority.retentionApprovalRevision ||
			retention.opaquePolicyId != authority.retentionPolicyId ||
			retention.sourcePolicyRevision != sourcePolicyRevision ||
			retention.ambientConsentEpoch != ambientConsentEpoch ||
			retention.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			retention.retainedFromMs != lifecycle.retainedFromMs
		) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH,
			)
		}
		val currentRetention = database.ambientCellFactDao().latestRetentionAuthority(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)
		if (currentRetention == null ||
			!AmbientCellRetentionAuthorityIntegrity.isAuthentic(currentRetention) ||
			!currentRetention.isActive ||
			currentRetention.opaquePolicyId != retention.opaquePolicyId ||
			currentRetention.approvalRevision != retention.approvalRevision ||
			currentRetention.sourcePolicyRevision != sourcePolicyRevision ||
			currentRetention.ambientConsentEpoch != ambientConsentEpoch ||
			currentRetention.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			currentRetention.retainedFromMs != lifecycle.retainedFromMs ||
			currentRetention.effectiveBootId != wal.clockDomainId ||
			currentRetention.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos ||
			currentRetention.effectiveWallTimeMs > observedWallTimeMs
		) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH,
			)
		}
		val deletionMarker = database.ambientCellFactDao()
			.latestDeletionMarker(wal.capturedCollectedDataEpoch)
		if (deletionMarker != null && !AmbientCellFactIntegrity.isAuthentic(deletionMarker)) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val deletionGeneration = deletionMarker?.deletionGeneration ?: 0L
		if (authority.scopeDeletionGeneration != deletionGeneration) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELETED_SCOPE)
		}
		if (currentAuthority.scopeDeletionGeneration != deletionGeneration) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val registration = database.sourceBrokerDao().registration(
			SourceKind.CELL.stableCode,
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
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.REGISTRATION_MISMATCH)
		}
		val payload = runCatching {
			payloadCodec.decode(SourceKind.CELL, wal.payloadVersion, wal.payload)
		}.getOrNull() as? CellSnapshotPayload ?: return cellUnverifiable(
			AmbientCellProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE,
		)
		val zone = payload.observationZoneId?.let {
			runCatching { ZoneId.of(it) }.getOrNull()
		} ?: return cellUnverifiable(
			AmbientCellProjectionUnverifiableReason.OBSERVATION_ZONE_UNVERIFIABLE,
		)
		val aggregate = AmbientCellAggregate.from(payload)
			?: return recordUnverifiableGap(wal, authority, zone)
		val dao = database.ambientCellFactDao()
		val logicalFactId = AmbientCellFactIntegrity.logicalFactId(
			deliveryIdentity,
			ambientConsentEpoch,
			wal.capturedCollectedDataEpoch,
			authority.scopeDeletionGeneration,
		)
		val factFootprint = dao.replayFootprint(
			AmbientCellReplayFootprintEntity.KIND_LOCAL_FACT,
			logicalFactId,
			1L,
		)
		if (factFootprint != null && !AmbientCellFactIntegrity.isAuthentic(factFootprint)) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.REPLAY_FOOTPRINT_CORRUPT,
			)
		}
		if (factFootprint != null) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val existingCursor = dao.cursor(
			AmbientCellFactRevisionEntity.WRITER_ID,
			AmbientCellFactRevisionEntity.WRITER_VERSION,
			logicalFactId,
		)
		if (existingCursor?.latestSourceAdmissionOrdinal == wal.admissionOrdinal) {
			return AmbientCellProjectionResult.Duplicate(logicalFactId)
		}
		if (existingCursor != null) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.DELIVERY_IDENTITY_COLLISION,
			)
		}
		val prior = dao.latestAggregateBefore(
			ambientConsentEpoch,
			wal.capturedCollectedDataEpoch,
			authority.scopeDeletionGeneration,
			wal.admissionOrdinal,
		)
		val unchanged = prior?.toAggregate() == aggregate
		val wallTimeMs = observedWallTimeMs
		val day = Instant.ofEpochMilli(wallTimeMs).atZone(zone).toLocalDate()
		val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val dayEnd = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		val intervalStart = wal.observedIntervalStartNanos ?: wal.observedElapsedNanos
		val coverageStart = (
			wallTimeMs - (wal.observedElapsedNanos - intervalStart)
				.coerceAtLeast(0L) / NANOS_PER_MILLISECOND
			).coerceAtLeast(dayStart)
		val draft = AmbientCellFactRevisionEntity(
			writerId = AmbientCellFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientCellFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = FIRST_WRITER_OWNER_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = AmbientCellFactIntegrity.mutationId(logicalFactId, 1L),
			factKind = if (unchanged) {
				AmbientCellFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
			} else {
				AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE
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
				AmbientCellFactRevisionEntity.COMPLETENESS_UNVERIFIABLE,
			subscriptionCompleteness = AmbientCellFactRevisionEntity.SUBSCRIPTION_UNKNOWN,
			observationCount = aggregate.observationCount.takeUnless { unchanged },
			registeredObservationCount =
				aggregate.registeredObservationCount.takeUnless { unchanged },
			gsmCount = aggregate.technologies.getValue("GSM").takeUnless { unchanged },
			cdmaCount = aggregate.technologies.getValue("CDMA").takeUnless { unchanged },
			wcdmaCount = aggregate.technologies.getValue("WCDMA").takeUnless { unchanged },
			tdscdmaCount = aggregate.technologies.getValue("TDSCDMA").takeUnless { unchanged },
			lteCount = aggregate.technologies.getValue("LTE").takeUnless { unchanged },
			nrCount = aggregate.technologies.getValue("NR").takeUnless { unchanged },
			qualityUnknownCount = aggregate.qualities[0].takeUnless { unchanged },
			qualityNoneOrUnknownCount = aggregate.qualities[1].takeUnless { unchanged },
			qualityPoorCount = aggregate.qualities[2].takeUnless { unchanged },
			qualityModerateCount = aggregate.qualities[3].takeUnless { unchanged },
			qualityGoodCount = aggregate.qualities[4].takeUnless { unchanged },
			qualityGreatCount = aggregate.qualities[5].takeUnless { unchanged },
			qualityFlags = wal.qualityFlags,
			qualityConfidence = wal.qualityConfidence,
			effectChecksum = ZERO_DIGEST,
			appliedAtMs = maxOf(wal.createdAtMs, wallTimeMs),
		)
		val fact = draft.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(draft))
		if (dao.insertRevision(fact) == -1L) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WRITE_CONFLICT)
		}
		val cursor = AmbientCellFactCursorEntity(
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
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WRITE_CONFLICT)
		}
		check(database.sourceEvidenceStateDao().incrementRevision(fact.appliedAtMs) == 1)
		return if (unchanged) {
			AmbientCellProjectionResult.CoverageCompacted(logicalFactId)
		} else {
			AmbientCellProjectionResult.FactWritten(logicalFactId)
		}
	}

	private suspend fun recordUnverifiableGap(
		wal: SourceEventWalEntity,
		authority: AmbientCellAuthorityEntity,
		zone: ZoneId,
	): AmbientCellProjectionResult {
		val wallTimeMs = wal.wallTimeMs
			?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		val day = Instant.ofEpochMilli(wallTimeMs).atZone(zone).toLocalDate()
		val gapId = AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-gap-v1",
			wal.eventId,
			authority.authorityRevision,
			authority.scopeDeletionGeneration,
		)
		val gapFootprint = database.ambientCellFactDao().replayFootprint(
			AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP,
			gapId,
			0L,
		)
		if (gapFootprint != null && !AmbientCellFactIntegrity.isAuthentic(gapFootprint)) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.REPLAY_FOOTPRINT_CORRUPT,
			)
		}
		if (gapFootprint != null) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELETED_SCOPE)
		}
		database.ambientCellFactDao().insertGap(
			AmbientCellFactIntegrity.createGap(
				gapId,
				AmbientCellGapEntity.REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE,
				wallTimeMs,
				wallTimeMs + 1L,
				zone.id,
				day.toEpochDay(),
				authority.sourcePolicyRevision,
				authority.ambientConsentEpoch,
				authority.retentionPolicyId,
				authority.retentionApprovalRevision,
				authority.collectedDataEpoch,
				authority.scopeDeletionGeneration,
				maxOf(wal.createdAtMs, wallTimeMs + 1L),
			),
		)
		return AmbientCellProjectionResult.UnverifiableCoverage(gapId)
	}

	private data class AmbientCellAggregate(
		val observationCount: Int,
		val registeredObservationCount: Int,
		val technologies: Map<String, Int>,
		val qualities: List<Int>,
	) {
		companion object {
			fun from(payload: CellSnapshotPayload): AmbientCellAggregate? {
				if (payload.subscriptionId != null ||
					payload.refreshOutcome != CellRefreshOutcome.CALLBACK ||
					payload.observations.isEmpty() ||
					payload.observations.any {
						it.identifierToken.isNotEmpty() ||
							it.providerTimestampNanos == null ||
							it.providerTimestampNanos <= 0L ||
							it.radioType !in TECHNOLOGIES
					}
				) return null
				val technologies = TECHNOLOGIES.associateWith { technology ->
					payload.observations.count { it.radioType == technology }
				}
				val qualities = IntArray(6)
				payload.observations.forEach { observation ->
					val bucket = when (val signal = observation.signalLevelDbm) {
						null -> 0
						in Int.MIN_VALUE..-111 -> 1
						in -110..-101 -> 2
						in -100..-91 -> 3
						in -90..-81 -> 4
						else -> 5
					}
					qualities[bucket]++
				}
				return AmbientCellAggregate(
					payload.observations.size,
					payload.observations.count { it.registered },
					technologies,
					qualities.toList(),
				)
			}
		}
	}

	private fun AmbientCellFactRevisionEntity.toAggregate(): AmbientCellAggregate? {
		if (factKind != AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE) return null
		return AmbientCellAggregate(
			requireNotNull(observationCount),
			requireNotNull(registeredObservationCount),
			mapOf(
				"GSM" to requireNotNull(gsmCount),
				"CDMA" to requireNotNull(cdmaCount),
				"WCDMA" to requireNotNull(wcdmaCount),
				"TDSCDMA" to requireNotNull(tdscdmaCount),
				"LTE" to requireNotNull(lteCount),
				"NR" to requireNotNull(nrCount),
			),
			listOf(
				requireNotNull(qualityUnknownCount),
				requireNotNull(qualityNoneOrUnknownCount),
				requireNotNull(qualityPoorCount),
				requireNotNull(qualityModerateCount),
				requireNotNull(qualityGoodCount),
				requireNotNull(qualityGreatCount),
			),
		)
	}

	private companion object {
		const val MAX_AUTHORIZATION_MEMBERS = 64
		const val FIRST_WRITER_OWNER_GENERATION = 1L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val ZERO_DIGEST =
			"0000000000000000000000000000000000000000000000000000000000000000"
		val DIGEST = Regex("[0-9a-f]{64}")
		val TECHNOLOGIES = setOf("GSM", "CDMA", "WCDMA", "TDSCDMA", "LTE", "NR")
	}
}

internal sealed interface AmbientCellProjectionResult {
	data class FactWritten(val logicalFactId: String) : AmbientCellProjectionResult
	data class CoverageCompacted(val logicalFactId: String) : AmbientCellProjectionResult
	data class Duplicate(val logicalFactId: String) : AmbientCellProjectionResult
	data class UnverifiableCoverage(val gapId: String) : AmbientCellProjectionResult
	data object NotAmbientEligible : AmbientCellProjectionResult
	data class Unverifiable(val reason: AmbientCellProjectionUnverifiableReason) :
		AmbientCellProjectionResult
	data object RetryableFailure : AmbientCellProjectionResult
}

internal enum class AmbientCellProjectionUnverifiableReason {
	WAL_MISSING,
	WRONG_SOURCE,
	WAL_INTEGRITY,
	WAL_SHAPE,
	DELIVERY_IDENTITY,
	AUTHORIZATION_MISSING,
	AUTHORIZATION_MISMATCH,
	POLICY_MISMATCH,
	AMBIENT_AUTHORITY_MISMATCH,
	RETENTION_AUTHORITY_MISMATCH,
	REGISTRATION_MISMATCH,
	DELETED_SCOPE,
	REPLAY_FOOTPRINT_CORRUPT,
	PAYLOAD_UNVERIFIABLE,
	OBSERVATION_ZONE_UNVERIFIABLE,
	CLOCK_UNVERIFIABLE,
	DELIVERY_IDENTITY_COLLISION,
	DEPENDENCY_OVERFLOW,
	WRITE_CONFLICT,
}

private fun cellUnverifiable(reason: AmbientCellProjectionUnverifiableReason) =
	AmbientCellProjectionResult.Unverifiable(reason)
