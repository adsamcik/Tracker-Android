package com.adsamcik.tracker.shared.base.database.data

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class WifiWalCarrierAuthenticatorTest {
	@Test
	fun `canonical production Wi-Fi payload and fact authenticate`() {
		val wal = validWal()
		val fact = validFact(wal)

		val authenticated = assertIs<WifiWalCarrierAuthentication.Authenticated>(
			WifiWalCarrierAuthenticator.authenticate(fact, wal),
		)

		assertEquals(2, authenticated.carrier.coverage?.acceptedResultCount)
		assertEquals("Europe/Prague", authenticated.carrier.payload.observationZoneId)
	}

	@Test
	fun `rehashed Wi-Fi WAL with inconsistent delivery identity is rejected`() {
		val original = validWal()
		val changedPayload = payload(
			listOf(
				observation(2_412, -60, 1_500_000_000L),
				observation(5_180, -41, 1_600_000_000L),
			),
		)
		val encoded = WifiWalPayloadCodec.encode(changedPayload)
		val unsigned = original.copy(
			payload = encoded.toByteArray(),
			payloadChecksum = encoded.checksum,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val rehashed = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())

		val rejected = assertIs<WifiWalCarrierAuthentication.Rejected>(
			WifiWalCarrierAuthenticator.authenticate(rehashed),
		)

		assertEquals(WifiWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH, rejected.reason)
	}

	@Test
	fun `rehashed inconsistent Wi-Fi fact fails exact carrier authentication`() {
		val wal = validWal()
		val original = validFact(wal)
		val inconsistent = original.copy(
			acceptedResultCount = 1,
			staleResultCount = 1,
			coverageCompleteness = WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL,
			observationCount = 1,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 0,
			strongestSignalDbm = -60,
			weakestSignalDbm = -60,
			signalSumDbm = -60L,
			effectChecksum = "0".repeat(64),
		).let { changed ->
			changed.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(changed))
		}

		val rejected = assertIs<WifiWalCarrierAuthentication.Rejected>(
			WifiWalCarrierAuthenticator.authenticate(inconsistent, wal),
		)

		assertEquals(WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH, rejected.reason)
	}

	@Test
	fun `exact correction retains canonical Wi-Fi carrier authority`() {
		val wal = validWal()
		val first = validFact(wal)
		val unsigned = first.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(first.logicalFactId, 2L),
			effectChecksum = "0".repeat(64),
		)
		val correction = unsigned.copy(
			effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)

		assertIs<WifiWalCarrierAuthentication.Authenticated>(
			WifiWalCarrierAuthenticator.authenticate(correction, wal),
		)
	}

	@Test
	fun `empty Wi-Fi payload cannot manufacture confirmed empty coverage`() {
		val encoded = WifiWalPayloadCodec.encode(payload(emptyList()))
		val base = validWal()
		val unsigned = base.copy(
			deliveryIdentity = "f".repeat(64),
			observedIntervalStartNanos = base.observedElapsedNanos,
			payload = encoded.toByteArray(),
			payloadChecksum = encoded.checksum,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val empty = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())

		val rejected = assertIs<WifiWalCarrierAuthentication.Rejected>(
			WifiWalCarrierAuthenticator.authenticate(empty),
		)

		assertEquals(WifiWalCarrierFailure.EMPTY_PROVIDER_DELIVERY, rejected.reason)
	}

	private fun validWal(): SourceEventWalEntity {
		val payload = payload(
			listOf(
				observation(2_412, -60, 1_500_000_000L),
				observation(5_180, -40, 1_600_000_000L),
			),
		)
		val encoded = WifiWalPayloadCodec.encode(payload)
		val deliveryIdentity = canonicalWifiProviderDeliveryIdentity(BOOT_ID, payload.observations)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = 1L,
			eventId = EVENT_ID,
			providerDedupKey = null,
			deliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
			sourceInstanceId = INSTANCE_ID,
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "wifi-fingerprint",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = 1L,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = 1_600_000_000L,
			observedIntervalStartNanos = 1_500_000_000L,
			receivedElapsedNanos = 1_700_000_000L,
			receivedWallTimeMs = 1_700_000_000_100L,
			wallTimeMs = 1_700_000_000_000L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 7L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 1_700_000_000_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = WIFI_WAL_PAYLOAD_VERSION,
			payload = encoded.toByteArray(),
			payloadChecksum = encoded.checksum,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
			createdAtMs = 1_700_000_000_100L,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	@Suppress("LongMethod")
	private fun validFact(wal: SourceEventWalEntity): WifiCapturedFactRevisionEntity {
		val logicalFactId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			requireNotNull(wal.deliveryIdentity),
			LOGICAL_ID,
			RUN_ID,
			1L,
			1L,
			7L,
			0L,
		)
		val unsigned = WifiCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L),
			factKind = WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwnerLogicalFactId = null,
			aggregateOwnerSemanticRevision = null,
			aggregateOwnerCursorRevision = null,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			sessionSegmentId = 1L,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			capturedSourceCodes = SourceDestinationOwnerEntity.SOURCE_WIFI.toString(),
			controlSourceCodes = "",
			sourceEventId = EVENT_ID,
			sourceAdmissionOrdinal = 1L,
			walIntegrityIdentity = wal.integrityIdentity,
			payloadChecksum = wal.payloadChecksum,
			sourceDeliveryIdentity = requireNotNull(wal.deliveryIdentity),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			sourceSequence = 1L,
			planAttribution = WifiCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			sourceInstanceId = INSTANCE_ID,
			registrationGeneration = 1L,
			configurationRevision = 1L,
			physicalConfigurationFingerprint = "wifi-fingerprint",
			authorizationRevision = 1L,
			authorizationFingerprint = "a".repeat(64),
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			collectedDataEpoch = 7L,
			scopeDeletionGeneration = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = "Europe/Prague",
			planPayloadVersion = 1,
			planPayloadChecksum = "b".repeat(64),
			maximumObservationAgeNanos = 1_000_000_000L,
			resultContract = "ANDROID_SCAN_RESULTS_V1",
			registrationAppliedAtNanos = 1_000_000_000L,
			providerAcceptanceStartNanos = 1_000_000_000L,
			providerAcceptanceEndNanos = 2_000_000_000L,
			authorizationEffectStartNanos = 1_000_000_000L,
			authorizationEffectEndNanos = 2_000_000_000L,
			sessionRunEffectStartNanos = 1_000_000_000L,
			sessionRunEffectEndNanos = 2_000_000_000L,
			observedIntervalStartNanos = 1_500_000_000L,
			observedElapsedNanos = 1_600_000_000L,
			receivedElapsedNanos = 1_700_000_000L,
			coverageIntervalStartNanos = 1_500_000_000L,
			coverageIntervalEndNanos = 1_600_000_000L,
			observedWallTimeMs = 1_700_000_000_000L,
			wallTimeUncertaintyMs = 1L,
			acquiredAtMs = 1_700_000_000_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			availability = WifiCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE,
			submittedResultCount = 2,
			acceptedResultCount = 2,
			staleResultCount = 0,
			clockUnverifiableResultCount = 0,
			malformedResultCount = 0,
			coverageCompleteness = WifiCapturedFactRevisionEntity.COVERAGE_COMPLETE,
			observationCount = 2,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 1,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -40,
			weakestSignalDbm = -60,
			signalSumDbm = -100L,
			effectChecksum = "0".repeat(64),
			appliedAtMs = 1_700_000_000_000L,
		)
		return unsigned.copy(
			effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
	}

	private fun payload(observations: List<WifiWalPayloadObservation>) = WifiWalPayload(
		observations = observations,
		platformTimestampMs = observations.mapNotNull { it.providerTimestampNanos }
			.maxOrNull()?.div(1_000_000L),
		resultAgeMs = null,
		observationZoneId = "Europe/Prague",
	)

	private fun observation(
		frequencyMhz: Int,
		signalLevelDbm: Int,
		providerTimestampNanos: Long,
	) = WifiWalPayloadObservation("", frequencyMhz, signalLevelDbm, providerTimestampNanos)

	private companion object {
		const val EVENT_ID = "wifi-event"
		const val LOGICAL_ID = "logical-wifi"
		const val RUN_ID = "run-wifi"
		const val INSTANCE_ID = "wifi-instance"
		const val BOOT_ID = "wifi-boot"
	}
}
