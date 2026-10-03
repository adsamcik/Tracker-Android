package com.adsamcik.tracker.tracker.source.ambient.cell

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.qualifyCellPurposeItems
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.MAX_CELL_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.LEGACY_CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CellProviderDeliveryIdentityFact
import com.adsamcik.tracker.shared.base.database.data.CellChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.canonicalCellProviderDeliveryIdentity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.cell.toCellWalPayload
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
	private val bootClockDomainProvider: BootClockDomainProvider,
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
		val wal = database.sourceEventWalDao().boundedPayloadByAdmissionOrdinal(admissionOrdinal, MAX_CELL_WAL_PAYLOAD_BYTES)
			?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WAL_MISSING)
		if (wal.sourceKind != SourceKind.CELL.stableCode) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WRONG_SOURCE)
		}
		if (!wal.hasQualifiedIntegrity()) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WAL_INTEGRITY)
		}
		val sequence = database.sourceEventWalDao().identityBySourceSequence(wal.sourceKind, wal.sourceInstanceId, wal.sourceSequence)
		if (wal.sourceSequence < 0L || sequence?.eventId != wal.eventId ||
			sequence.admissionOrdinal != wal.admissionOrdinal || sequence.integrityIdentity != wal.integrityIdentity) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WAL_SHAPE)
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
		if (lifecycle.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
			return cellUnverifiable(
				AmbientCellProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH,
			)
		}
		val decodedPayload = runCatching {
			payloadCodec.decode(SourceKind.CELL, wal.payloadVersion, wal.payload)
		}.getOrNull() as? CellSnapshotPayload
		val perReading = wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION
		val providerTimes = if (perReading) {
			val provider = database.sourceBrokerDao().registration(wal.sourceKind, wal.registrationGeneration)
				?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.REGISTRATION_MISMATCH)
			val start = provider.acceptedElapsedRealtimeNanos
				?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.REGISTRATION_MISMATCH)
			val end = provider.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
			val observations = decodedPayload?.observations
				?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE)
			observations.mapNotNull { it.providerTimestampNanos }
				.filter { it >= start && it < end && it <= wal.receivedElapsedNanos }
				.takeIf { it.isNotEmpty() }
				?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.REGISTRATION_MISMATCH)
		} else null
		val providerObservedStart = providerTimes?.minOrNull() ?: wal.observedElapsedNanos
		val providerObservedEnd = providerTimes?.maxOrNull() ?: wal.observedElapsedNanos
		val providerObservedWall = runCatching { Math.subtractExact(observedWallTimeMs,
			Math.subtractExact(wal.observedElapsedNanos, providerObservedEnd) / NANOS_PER_MILLISECOND)
		}.getOrNull() ?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		val registrationAuthentication = database.authenticateHistoricalProviderRegistration(
			sourceKind = wal.sourceKind,
			registrationGeneration = wal.registrationGeneration,
			sourceInstanceId = wal.sourceInstanceId,
			clockDomainId = wal.clockDomainId,
			physicalConfigurationFingerprint = requireNotNull(wal.physicalConfigurationFingerprint),
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			expectedProviderResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = providerObservedStart,
			observedEndNanos = providerObservedEnd,
			observedWallTimeMs = providerObservedWall,
			currentClockDomainId = bootClockDomainProvider.current(),
		)
		if (registrationAuthentication !is ProviderRegistrationIntervalAuthentication.Authenticated) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.REGISTRATION_MISMATCH)
		}
		val payload = decodedPayload ?: return cellUnverifiable(
			AmbientCellProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE,
		)
		val authenticDeliveryIdentity = runCatching {
			canonicalCellProviderDeliveryIdentity(wal.clockDomainId,
				payload.observations.map { observation ->
					CellProviderDeliveryIdentityFact(observation.radioType, observation.registered,
						observation.signalLevelDbm, requireNotNull(observation.providerTimestampNanos),
						observation.identifierToken)
				}, wal.payloadVersion)
		}.getOrNull()
		if (authenticDeliveryIdentity != deliveryIdentity) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELIVERY_IDENTITY)
		}
		val purposeItems = database.qualifyCellPurposeItems(
			wal, payload.toCellWalPayload(), registrationAuthentication.interval.startInclusiveNanos,
			registrationAuthentication.interval.endExclusiveNanos,
		) ?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.AUTHORIZATION_MISMATCH)
		val candidates = if (perReading) purposeItems.historicalMembers()
			else purposeItems.callbackAuthorization.authorizedMembers
		val members = candidates.filter { member ->
			member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && member.persistenceEligible &&
				member.logicalTrackingId == null && member.serviceRunId == null &&
				(!perReading || purposeItems.forDemand(requireNotNull(member.demandId)).isNotEmpty())
		}
		if (members.isEmpty()) return AmbientCellProjectionResult.NotAmbientEligible
		if (!perReading && members.size != 1) return cellUnverifiable(
			AmbientCellProjectionUnverifiableReason.AUTHORIZATION_MISMATCH)
		val results = members.map { member ->
			projectGroup(wal, payload, lifecycle, member,
				if (perReading) purposeItems.forDemand(requireNotNull(member.demandId))
				else payload.observations.indices.toList(), observedWallTimeMs)
		}
		return results.singleOrNull() ?: AmbientCellProjectionResult.Groups(results)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun projectGroup(
		wal: SourceEventWalEntity,
		payload: CellSnapshotPayload,
		lifecycle: SourceEvidenceState,
		ambient: SourceAuthorizationEntity,
		purposeIndexes: List<Int>,
		observedWallTimeMs: Long,
	): AmbientCellProjectionResult {
		val deliveryIdentity = requireNotNull(wal.deliveryIdentity)
		val purposeDemandId = ambient.demandId.takeIf { wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION }
		val purposeTimes = purposeIndexes.map { requireNotNull(payload.observations[it].providerTimestampNanos) }
		val purposeStart = purposeTimes.min()
		val purposeEnd = purposeTimes.max()
		val policyTime = if (purposeDemandId != null) purposeStart else purposeEnd
		val purposeWallTimeMs = cellItemWallTime(wal.observedElapsedNanos, observedWallTimeMs, purposeEnd)
			?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
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
		if (policyAuthority == null ||
			policyAuthority.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			policy == null ||
			consent == null ||
			!policy.hasExactEligibleAmbientConsentReference(consent) ||
			!policy.isEffectiveAtOrBefore(
				wal.clockDomainId,
				policyTime,
				purposeWallTimeMs,
			) ||
			!consent.isEffectiveAtOrBefore(
				wal.clockDomainId,
				policyTime,
				purposeWallTimeMs,
			)
		) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.POLICY_MISMATCH)
		}
		val authority = database.ambientCellFactDao().authorityAt(
			wal.clockDomainId,
			purposeEnd,
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
		if (currentAuthority == null || !AmbientCellAuthorityIntegrity.isAuthentic(currentAuthority) ||
			currentAuthority.collectedDataEpoch != wal.capturedCollectedDataEpoch) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH)
		}
		val retention = database.ambientCellFactDao().retentionAuthorityAt(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			wal.clockDomainId,
			purposeEnd,
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
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
		if (currentRetention == null || !AmbientCellRetentionAuthorityIntegrity.isAuthentic(currentRetention) ||
			currentRetention.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			currentRetention.retainedFromMs != lifecycle.retainedFromMs) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH)
		}
		val deletionMarker = database.ambientCellFactDao()
			.latestDeletionMarker(wal.capturedCollectedDataEpoch)
		if (deletionMarker != null && !AmbientCellFactIntegrity.isAuthentic(deletionMarker)) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val deletionGeneration = deletionMarker?.deletionGeneration ?: 0L
		if (authority.scopeDeletionGeneration != deletionGeneration ||
			currentAuthority.scopeDeletionGeneration != deletionGeneration) {
			return cellUnverifiable(AmbientCellProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val zone = payload.observationZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
			?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.OBSERVATION_ZONE_UNVERIFIABLE)
		val acceptedIndexes = if (purposeDemandId == null) purposeIndexes else purposeIndexes.filter { index ->
			val providerTime = payload.observations[index].providerTimestampNanos ?: return@filter false
			val itemWallTime = cellItemWallTime(wal.observedElapsedNanos, observedWallTimeMs, providerTime)
			providerTime >= policy.effectiveElapsedRealtimeNanos &&
				providerTime >= consent.effectiveElapsedRealtimeNanos &&
				providerTime >= authority.effectiveElapsedRealtimeNanos &&
				providerTime >= retention.effectiveElapsedRealtimeNanos &&
				itemWallTime != null &&
				retention.retainedFromMs?.let { itemWallTime >= it } != false &&
				lifecycle.retainedFromMs?.let { itemWallTime >= it } != false
		}
		if (acceptedIndexes.isEmpty()) return AmbientCellProjectionResult.NotAmbientEligible
		val acceptedTimes = acceptedIndexes.map { index ->
			requireNotNull(payload.observations[index].providerTimestampNanos)
		}
		val productObservedElapsedNanos = acceptedTimes.max()
		val productWallTimeMs = requireNotNull(cellItemWallTime(
			wal.observedElapsedNanos, observedWallTimeMs, productObservedElapsedNanos,
		))
		val aggregate = AmbientCellAggregate.from(payload, wal.payloadVersion, acceptedIndexes)
			?: return recordUnverifiableGap(wal, authority, zone, productWallTimeMs)
		val dao = database.ambientCellFactDao()
		val logicalFactId = AmbientCellFactIntegrity.logicalFactId(
			deliveryIdentity,
			ambientConsentEpoch,
			wal.capturedCollectedDataEpoch,
			authority.scopeDeletionGeneration,
			purposeDemandId,
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
			val existingFact = dao.revision(AmbientCellFactRevisionEntity.WRITER_ID,
				AmbientCellFactRevisionEntity.WRITER_VERSION, logicalFactId,
				existingCursor.latestSemanticRevision)
				?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WRITE_CONFLICT)
			if (AmbientCellFactIntegrity.effectChecksum(existingFact) != existingFact.effectChecksum ||
				existingFact.purposeDemandId != purposeDemandId) {
				return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WRITE_CONFLICT)
			}
			if (wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION &&
				database.radioObservationChildDao().cellBackgroundChildren(existingFact.writerId,
					existingFact.writerVersion, logicalFactId, existingFact.semanticRevision, 65) !=
					backgroundChildren(existingFact, payload, wal, acceptedIndexes)) {
				return cellUnverifiable(AmbientCellProjectionUnverifiableReason.WRITE_CONFLICT)
			}
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
		val unchanged = wal.payloadVersion != CELL_WAL_PAYLOAD_VERSION && prior?.toAggregate() == aggregate
		val wallTimeMs = productWallTimeMs
		val day = Instant.ofEpochMilli(wallTimeMs).atZone(zone).toLocalDate()
		val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val dayEnd = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		val intervalStart = acceptedTimes.min()
		val coverageStart = runCatching {
			Math.subtractExact(observedWallTimeMs,
				(wal.observedElapsedNanos - intervalStart) / NANOS_PER_MILLISECOND)
		}.getOrNull()?.takeIf { it >= 0L }
			?: return cellUnverifiable(AmbientCellProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
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
			observedElapsedNanos = productObservedElapsedNanos,
			receivedElapsedNanos = wal.receivedElapsedNanos,
			coverageStartTimeMs = coverageStart,
			observedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = Math.addExact(wal.wallTimeUncertaintyMs ?: 0L,
				if (wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
					if (acceptedTimes.any { (wal.observedElapsedNanos - it) % NANOS_PER_MILLISECOND != 0L }) 1L else 0L
				} else if ((wal.observedElapsedNanos - productObservedElapsedNanos) % NANOS_PER_MILLISECOND == 0L) 0L else 1L),
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
			purposeDemandId = purposeDemandId,
		)
		val fact = draft.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(draft))
		check(dao.insertRevision(fact) != -1L)
		if (wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			database.radioObservationChildDao().insertCellBackground(
				backgroundChildren(fact, payload, wal, acceptedIndexes))
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
		check(dao.insertCursor(cursor) != -1L)
		check(database.sourceEvidenceStateDao().incrementRevision(fact.appliedAtMs) == 1)
		return if (unchanged) {
			AmbientCellProjectionResult.CoverageCompacted(logicalFactId)
		} else {
			AmbientCellProjectionResult.FactWritten(logicalFactId)
		}
	}

	private fun backgroundChildren(
		fact: AmbientCellFactRevisionEntity,
		payload: CellSnapshotPayload,
		wal: SourceEventWalEntity,
		acceptedIndexes: List<Int>,
	): List<AmbientCellChildEntity> {
		return acceptedIndexes.map { index ->
				val observation = payload.observations[index]
				val providerTime = requireNotNull(observation.providerTimestampNanos)
				val itemWallTime = requireNotNull(cellItemWallTime(
					wal.observedElapsedNanos, requireNotNull(wal.wallTimeMs), providerTime,
				))
				val itemUncertainty = Math.addExact(
					wal.wallTimeUncertaintyMs ?: 0L,
					if ((wal.observedElapsedNanos - providerTime) % 1_000_000L == 0L) 0L else 1L,
				)
				val owner = RadioChildOwner(
					RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion,
					fact.logicalFactId, fact.semanticRevision, index,
				)
				val input = CellChildChecksumInput(
					owner, providerTime, itemWallTime, itemUncertainty,
					observation.identifierToken.ifEmpty { null }, observation.radioType,
					observation.registered, observation.signalLevelDbm,
				)
				AmbientCellChildEntity(
					writerId = fact.writerId,
					writerVersion = fact.writerVersion,
					logicalFactId = fact.logicalFactId,
					semanticRevision = fact.semanticRevision,
					itemIndex = index,
					providerTimestampNanos = providerTime,
					observedWallTimeMs = itemWallTime,
					wallTimeUncertaintyMs = itemUncertainty,
					towerIdentity = input.towerIdentity,
					radioType = observation.radioType,
					registered = observation.registered,
					signalDbm = observation.signalLevelDbm,
					contentChecksum = RadioObservationChildIntegrity.cell(input),
				)
			}
	}

	private suspend fun recordUnverifiableGap(
		wal: SourceEventWalEntity,
		authority: AmbientCellAuthorityEntity,
		zone: ZoneId,
		wallTimeMs: Long,
	): AmbientCellProjectionResult {
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
			fun from(
				payload: CellSnapshotPayload,
				payloadVersion: Int,
				acceptedIndexes: List<Int>,
			): AmbientCellAggregate? {
				val observations = acceptedIndexes.map { index ->
					payload.observations.getOrNull(index) ?: return null
				}
				if (payloadVersion !in LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION ||
					payload.subscriptionId != null ||
					payload.refreshOutcome != CellRefreshOutcome.CALLBACK ||
					observations.isEmpty() ||
					observations.any {
						(payloadVersion == LEGACY_CELL_WAL_PAYLOAD_VERSION &&
							it.identifierToken.isNotEmpty()) ||
							it.providerTimestampNanos == null ||
							it.providerTimestampNanos <= 0L ||
							it.radioType !in TECHNOLOGIES
					}
				) return null
				val technologies = TECHNOLOGIES.associateWith { technology ->
					observations.count { it.radioType == technology }
				}
				val qualities = IntArray(6)
				observations.forEach { observation ->
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
					observations.size,
					observations.count { it.registered },
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
		const val FIRST_WRITER_OWNER_GENERATION = 1L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val ZERO_DIGEST =
			"0000000000000000000000000000000000000000000000000000000000000000"
		val DIGEST = Regex("[0-9a-f]{64}")
		val TECHNOLOGIES = setOf("GSM", "CDMA", "WCDMA", "TDSCDMA", "LTE", "NR")
	}
}

internal sealed interface AmbientCellProjectionResult {
	data class Groups(val results: List<AmbientCellProjectionResult>) : AmbientCellProjectionResult
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

private fun cellItemWallTime(
	callbackElapsedNanos: Long,
	callbackWallTimeMs: Long,
	itemElapsedNanos: Long,
): Long? {
	if (itemElapsedNanos <= 0L || itemElapsedNanos > callbackElapsedNanos) return null
	val ageMs = (callbackElapsedNanos - itemElapsedNanos) / 1_000_000L
	return callbackWallTimeMs.takeIf { it >= ageMs }?.minus(ageMs)
}
