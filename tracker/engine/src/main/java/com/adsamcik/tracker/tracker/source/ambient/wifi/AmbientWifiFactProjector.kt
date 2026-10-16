package com.adsamcik.tracker.tracker.source.ambient.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayload
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadDecodeResult
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.canonicalWifiProviderDeliveryIdentity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.wifi.WifiPurposeChildren
import com.adsamcik.tracker.tracker.source.wifi.qualifyWifiPurposeItems
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
	private val bootClockDomainProvider: BootClockDomainProvider,
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
		if (
			database.collectedDataDeletionOperationDao()
				.activeRetentionFloorSettlement() != null
		) {
			return AmbientWifiProjectionResult.RetryableFailure
		}
		val wal = database.sourceEventWalDao().boundedPayloadByAdmissionOrdinal(admissionOrdinal,
            MAX_WIFI_WAL_PAYLOAD_BYTES)
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_MISSING)
		if (wal.sourceKind != SourceKind.WIFI.stableCode) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WRONG_SOURCE)
		}
		if (!wal.hasQualifiedIntegrity()) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_INTEGRITY)
		}
		val sequence = database.sourceEventWalDao().identityBySourceSequence(wal.sourceKind, wal.sourceInstanceId,
            wal.sourceSequence)
		if (wal.sourceSequence < 0L || sequence?.eventId != wal.eventId ||
			sequence.admissionOrdinal != wal.admissionOrdinal || sequence.integrityIdentity != wal.integrityIdentity) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_SHAPE)
		}
		val deliveryIdentity = wal.deliveryIdentity
			?.takeIf(DIGEST::matches)
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.DELIVERY_IDENTITY)
		if (wal.deliveryUnitIndex != 0 || wal.deliveryUnitCount != 1 || wal.authorizationRevision == null) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_SHAPE)
		}
		if (wal.authorizationFingerprint == null || wal.physicalConfigurationFingerprint == null) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.WAL_SHAPE)
		}
		val observedWallTimeMs = wal.wallTimeMs
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		val decodedWal = (WifiWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
			as? WifiWalPayloadDecodeResult.Decoded)?.payload
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE)
		val canonicalDelivery = runCatching {
			canonicalWifiProviderDeliveryIdentity(wal.clockDomainId,
				decodedWal.observations, wal.payloadVersion)
		}.getOrNull()
		if (canonicalDelivery != deliveryIdentity) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELIVERY_IDENTITY)
		}
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return unverifiable(
				AmbientWifiProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH,
			)
		if (lifecycle.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
			return unverifiable(
				AmbientWifiProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH,
			)
		}
		val perReading = wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION
		val providerTimes = if (perReading) {
			val provider = database.sourceBrokerDao().registration(wal.sourceKind, wal.registrationGeneration)
				?: return unverifiable(AmbientWifiProjectionUnverifiableReason.REGISTRATION_MISMATCH)
			val start = provider.acceptedElapsedRealtimeNanos
				?: return unverifiable(AmbientWifiProjectionUnverifiableReason.REGISTRATION_MISMATCH)
			val end = provider.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
			decodedWal.observations.mapNotNull { it.providerTimestampNanos }
				.filter { it >= start && it < end && it <= wal.receivedElapsedNanos }
				.takeIf { it.isNotEmpty() }
				?: return unverifiable(AmbientWifiProjectionUnverifiableReason.REGISTRATION_MISMATCH)
		} else {
			null
		}
		val providerObservedStart = providerTimes?.minOrNull() ?: wal.observedElapsedNanos
		val providerObservedEnd = providerTimes?.maxOrNull() ?: wal.observedElapsedNanos
		val providerObservedWall = runCatching { Math.subtractExact(observedWallTimeMs,
			Math.subtractExact(wal.observedElapsedNanos, providerObservedEnd) / NANOS_PER_MILLISECOND)
		}.getOrNull() ?: return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
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
			return unverifiable(AmbientWifiProjectionUnverifiableReason.REGISTRATION_MISMATCH)
		}
		val purposeItems = database.qualifyWifiPurposeItems(
			wal, decodedWal, registrationAuthentication.interval.startInclusiveNanos,
			registrationAuthentication.interval.endExclusiveNanos,
		) ?: return unverifiable(AmbientWifiProjectionUnverifiableReason.AUTHORIZATION_MISMATCH)
		val candidates = if (perReading) {
			purposeItems.historicalMembers()
		}
			else {
			purposeItems.callbackAuthorization.authorizedMembers
		}
		val members = candidates.filter { member ->
			member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && member.persistenceEligible &&
				member.logicalTrackingId == null && member.serviceRunId == null &&
				(!perReading || purposeItems.forDemand(requireNotNull(member.demandId)).isNotEmpty())
		}
		if (members.isEmpty()) {
			return AmbientWifiProjectionResult.NotAmbientEligible
		}
		if (!perReading && members.size != 1) {
			return unverifiable(
			AmbientWifiProjectionUnverifiableReason.AUTHORIZATION_MISMATCH)
		}
		val results = members.map { member ->
			projectGroup(wal, decodedWal, lifecycle, member,
				if (perReading) {
					purposeItems.forDemand(requireNotNull(member.demandId))
				}
				else {
					decodedWal.observations.indices.toList()
				}, observedWallTimeMs)
		}
		return results.singleOrNull() ?: AmbientWifiProjectionResult.Groups(results)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun projectGroup(
		wal: SourceEventWalEntity,
		decodedWal: WifiWalPayload,
		lifecycle: SourceEvidenceState,
		ambientMember: SourceAuthorizationEntity,
		purposeIndexes: List<Int>,
		observedWallTimeMs: Long,
	): AmbientWifiProjectionResult {
		val deliveryIdentity = requireNotNull(wal.deliveryIdentity)
		val purposeDemandId = ambientMember.demandId.takeIf { wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION }
		val purposeTimes = purposeIndexes.map { index ->
			decodedWal.observations[index].providerTimestampNanos
				?: return unverifiable(AmbientWifiProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE)
		}
		val purposeStart = requireNotNull(purposeTimes.minOrNull())
		val purposeEnd = requireNotNull(purposeTimes.maxOrNull())
		val policyTime = if (purposeDemandId != null) {
			purposeStart
		} else {
			purposeEnd
		}
		val purposeAgeNanos = wal.observedElapsedNanos - purposeEnd
		if (purposeAgeNanos < 0L) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		}
		val purposeWallTimeMs = runCatching {
			Math.subtractExact(observedWallTimeMs, purposeAgeNanos / NANOS_PER_MILLISECOND)
		}.getOrNull()?.takeIf { it >= 0L }
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
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
		if (policyAuthority == null ||
			policyAuthority.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			policy == null || consent == null) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.POLICY_MISMATCH)
		}
		if (!policy.hasExactEligibleAmbientConsentReference(consent) ||
			!policy.isEffectiveAtOrBefore(wal.clockDomainId, policyTime, purposeWallTimeMs) ||
			!consent.isEffectiveAtOrBefore(wal.clockDomainId, policyTime, purposeWallTimeMs)) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.POLICY_MISMATCH)
		}
		val authority = database.ambientWifiFactDao().authorityAt(
			wal.clockDomainId,
			purposeEnd,
		)
		if (authority == null || !AmbientWifiAuthorityIntegrity.isAuthentic(authority) ||
			authority.state != AmbientWifiAuthorityEntity.STATE_ACTIVE ||
			authority.writerId != AmbientWifiFactRevisionEntity.WRITER_ID) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH)
		}
		if (authority.writerVersion != AmbientWifiFactRevisionEntity.WRITER_VERSION ||
			authority.writerOwnerGeneration != FIRST_WRITER_OWNER_GENERATION ||
			authority.sourcePolicyRevision != sourcePolicyRevision ||
			authority.ambientConsentEpoch != ambientConsentEpoch) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH)
		}
		if (authority.collectedDataEpoch != wal.capturedCollectedDataEpoch || authority.demandId != ambientMember.demandId) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH)
		}
		val currentAuthority = database.ambientWifiFactDao().latestAuthority()
		if (currentAuthority == null ||
			!AmbientWifiAuthorityIntegrity.isAuthentic(currentAuthority) ||
			currentAuthority.collectedDataEpoch != wal.capturedCollectedDataEpoch
		) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.AMBIENT_AUTHORITY_MISMATCH)
		}
		val retention = database.ambientWifiFactDao().retentionAuthorityAt(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			wal.clockDomainId,
			purposeEnd,
		)
		if (retention == null || !AmbientWifiRetentionAuthorityIntegrity.isAuthentic(retention) ||
			!retention.isActive || retention.approvalRevision != authority.retentionApprovalRevision) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH)
		}
		if (retention.opaquePolicyId != authority.retentionPolicyId ||
			retention.sourcePolicyRevision != sourcePolicyRevision ||
			retention.ambientConsentEpoch != ambientConsentEpoch ||
			retention.collectedDataEpoch != wal.capturedCollectedDataEpoch) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH)
		}
		if (retention.retainedFromMs != lifecycle.retainedFromMs) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH)
		}
		val currentRetention = database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)
		if (currentRetention == null ||
			!AmbientWifiRetentionAuthorityIntegrity.isAuthentic(currentRetention) ||
			currentRetention.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			currentRetention.retainedFromMs != lifecycle.retainedFromMs
		) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH)
		}
		val deletionMarker = database.ambientWifiFactDao()
			.latestDeletionMarker(wal.capturedCollectedDataEpoch)
		if (deletionMarker != null && !AmbientWifiFactIntegrity.isAuthentic(deletionMarker)) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val currentDeletionGeneration = deletionMarker?.deletionGeneration ?: 0L
		if (authority.scopeDeletionGeneration != currentDeletionGeneration) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELETED_SCOPE)
		}
		if (currentAuthority.scopeDeletionGeneration != currentDeletionGeneration) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val acceptedIndexes = if (purposeDemandId == null) {
			purposeIndexes
		} else {
			purposeIndexes.filter { index ->
			val time = decodedWal.observations[index].providerTimestampNanos ?: return@filter false
			val age = wal.observedElapsedNanos - time
			val wallTime = runCatching {
				Math.subtractExact(observedWallTimeMs, age / NANOS_PER_MILLISECOND)
			}.getOrNull()
			time >= policy.effectiveElapsedRealtimeNanos && time >= consent.effectiveElapsedRealtimeNanos &&
				time >= authority.effectiveElapsedRealtimeNanos && time >= retention.effectiveElapsedRealtimeNanos &&
				age >= 0L && wallTime != null && wallTime >= 0L &&
				retention.retainedFromMs?.let { wallTime >= it } != false &&
				lifecycle.retainedFromMs?.let { wallTime >= it } != false
		}
		}
		if (acceptedIndexes.isEmpty()) {
			return AmbientWifiProjectionResult.NotAmbientEligible
		}
		val acceptedTimes = acceptedIndexes.map { index ->
			decodedWal.observations[index].providerTimestampNanos
				?: return unverifiable(AmbientWifiProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE)
		}
		val acceptedStart = requireNotNull(acceptedTimes.minOrNull())
		val acceptedEnd = requireNotNull(acceptedTimes.maxOrNull())
		val acceptedAgeNanos = wal.observedElapsedNanos - acceptedEnd
		if (acceptedAgeNanos < 0L) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		}
		val acceptedWallTimeMs = runCatching {
			Math.subtractExact(observedWallTimeMs, acceptedAgeNanos / NANOS_PER_MILLISECOND)
		}.getOrNull()?.takeIf { it >= 0L }
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		val acceptedWallUncertaintyMs = runCatching {
			Math.addExact(wal.wallTimeUncertaintyMs ?: 0L,
				if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
					if (acceptedTimes.any { (wal.observedElapsedNanos - it) % NANOS_PER_MILLISECOND != 0L }) {
						1L
					} else {
						0L
					}
				} else if (acceptedAgeNanos % NANOS_PER_MILLISECOND == 0L) {
					0L
				} else {
					1L
				})
		}.getOrNull()
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
		val payload = runCatching {
			payloadCodec.decode(SourceKind.WIFI, wal.payloadVersion, wal.payload)
		}.getOrNull() as? WifiResultSnapshotPayload ?: return unverifiable(
			AmbientWifiProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE,
		)
		val zone = payload.observationZoneId?.let {
			runCatching { ZoneId.of(it) }.getOrNull()
		} ?: return unverifiable(AmbientWifiProjectionUnverifiableReason.OBSERVATION_ZONE_UNVERIFIABLE)
		val aggregate = AmbientWifiAggregate.from(payload, acceptedIndexes)
			?: return recordUnverifiableGap(wal, authority, zone, acceptedWallTimeMs)
		val dao = database.ambientWifiFactDao()
		val logicalFactId = AmbientWifiFactIntegrity.logicalFactId(
			deliveryIdentity,
			ambientConsentEpoch,
			wal.capturedCollectedDataEpoch,
			authority.scopeDeletionGeneration,
			purposeDemandId,
		)
		val factFootprint = dao.replayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_LOCAL_FACT,
			logicalFactId,
			1L,
		)
		if (factFootprint != null && !AmbientWifiFactIntegrity.isAuthentic(factFootprint)) {
			return unverifiable(
				AmbientWifiProjectionUnverifiableReason.REPLAY_FOOTPRINT_CORRUPT,
			)
		}
		if (factFootprint != null) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELETED_SCOPE)
		}
		val existingCursor = dao.cursor(
			AmbientWifiFactRevisionEntity.WRITER_ID,
			AmbientWifiFactRevisionEntity.WRITER_VERSION,
			logicalFactId,
		)
		if (existingCursor?.latestSourceAdmissionOrdinal == wal.admissionOrdinal) {
			if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
				val existingFact = dao.revision(
					AmbientWifiFactRevisionEntity.WRITER_ID,
					AmbientWifiFactRevisionEntity.WRITER_VERSION,
					logicalFactId, existingCursor.latestSemanticRevision,
				) ?: return unverifiable(AmbientWifiProjectionUnverifiableReason.WRITE_CONFLICT)
				val expected = WifiPurposeChildren.background(existingFact, wal, acceptedIndexes)
				val stored = database.radioObservationChildDao().wifiBackgroundChildren(
					existingFact.writerId, existingFact.writerVersion,
					existingFact.logicalFactId, existingFact.semanticRevision, 65,
				)
				if (expected == null || stored != expected ||
					AmbientWifiFactIntegrity.effectChecksum(existingFact) != existingFact.effectChecksum) {
					return unverifiable(AmbientWifiProjectionUnverifiableReason.WRITE_CONFLICT)
				}
			}
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
		val unchanged = wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION && prior?.toAggregate() == aggregate
		val wallTimeMs = acceptedWallTimeMs
		val day = Instant.ofEpochMilli(wallTimeMs).atZone(zone).toLocalDate()
		val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val dayEnd = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		val intervalStart = acceptedStart
		val coverageStart = runCatching {
			Math.subtractExact(observedWallTimeMs,
				(wal.observedElapsedNanos - intervalStart) / NANOS_PER_MILLISECOND)
		}.getOrNull()?.takeIf { it >= 0L }
			?: return unverifiable(AmbientWifiProjectionUnverifiableReason.CLOCK_UNVERIFIABLE)
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
			observedElapsedNanos = acceptedEnd,
			receivedElapsedNanos = wal.receivedElapsedNanos,
			coverageStartTimeMs = coverageStart,
			observedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = acceptedWallUncertaintyMs,
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
			purposeDemandId = purposeDemandId,
		)
		val fact = draft.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(draft))
		val children = if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
			WifiPurposeChildren.background(fact, wal, acceptedIndexes)
				?: return unverifiable(AmbientWifiProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE)
		} else {
			emptyList()
		}
		if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION && children.size != aggregate.observationCount) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.PAYLOAD_UNVERIFIABLE)
		}
		check(dao.insertRevision(fact) != -1L)
		if (children.isNotEmpty()) {
			database.radioObservationChildDao().insertWifiBackground(children)
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
		check(dao.insertCursor(cursor) != -1L)
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
		zone: ZoneId,
		wallTimeMs: Long,
	): AmbientWifiProjectionResult {
		val day = Instant.ofEpochMilli(wallTimeMs).atZone(zone).toLocalDate()
		val gapId = AmbientWifiAuthorityIntegrity.digest(
			"ambient-wifi-gap-v1",
			wal.eventId,
			authority.authorityRevision,
			authority.scopeDeletionGeneration,
		)
		val gapFootprint = database.ambientWifiFactDao().replayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_LOCAL_GAP,
			gapId,
			0L,
		)
		if (gapFootprint != null && !AmbientWifiFactIntegrity.isAuthentic(gapFootprint)) {
			return unverifiable(
				AmbientWifiProjectionUnverifiableReason.REPLAY_FOOTPRINT_CORRUPT,
			)
		}
		if (gapFootprint != null) {
			return unverifiable(AmbientWifiProjectionUnverifiableReason.DELETED_SCOPE)
		}
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
				authority.retentionApprovalRevision,
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
			fun from(payload: WifiResultSnapshotPayload, indexes: List<Int>): AmbientWifiAggregate? {
				val points = indexes.map { index -> payload.accessPoints.getOrNull(index) ?: return null }
				if (points.isEmpty() || points.any {
						it.providerTimestampNanos == null || it.providerTimestampNanos <= 0L
					}) {
					return null
				}
				val frequencies = points.map { it.frequencyMhz }
				val signals = points.map { it.signalLevelDbm }
				return AmbientWifiAggregate(
					observationCount = points.size,
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
		if (factKind != AmbientWifiFactRevisionEntity.FACT_KIND_AGGREGATE) {
			return null
		}
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
		const val FIRST_WRITER_OWNER_GENERATION = 1L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val ZERO_DIGEST =
			"0000000000000000000000000000000000000000000000000000000000000000"
		val DIGEST = Regex("[0-9a-f]{64}")
	}
}

internal sealed interface AmbientWifiProjectionResult {
	data class Groups(val results: List<AmbientWifiProjectionResult>) : AmbientWifiProjectionResult
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

private fun unverifiable(reason: AmbientWifiProjectionUnverifiableReason) =
	AmbientWifiProjectionResult.Unverifiable(reason)
