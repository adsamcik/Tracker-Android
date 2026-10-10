package com.adsamcik.tracker.shared.base.database.data

import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import org.junit.Test

class WifiWalCarrierAuthenticatorTest {
	@Test
	fun `Wi-Fi fact storage recognizes exact original and final binding owner pairs without changing its carrier`() {
		for (version in listOf(LEGACY_WIFI_WAL_PAYLOAD_VERSION, WIFI_WAL_PAYLOAD_VERSION)) {
			val wal = validWal(version)
			val original = validFact(wal)
			assertEquals(1L, original.writerBindingGeneration)
			assertEquals(2L, original.writerOwnerGeneration)
			val unsigned = original.copy(writerBindingGeneration = 2L, writerOwnerGeneration = 4L)
			val final = unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
			assertIs<WifiWalCarrierAuthentication.Authenticated>(WifiWalCarrierAuthenticator.authenticate(final, wal,
				if (version == WIFI_WAL_PAYLOAD_VERSION) validChildren(final, wal) else null))
			assertEquals(original.sourceEventId, final.sourceEventId)
			assertEquals(original.payloadChecksum, final.payloadChecksum)
			assertFailsWith<IllegalArgumentException> { original.copy(writerBindingGeneration = 2L, writerOwnerGeneration = 2L) }
			assertFailsWith<IllegalArgumentException> { original.copy(writerBindingGeneration = 1L, writerOwnerGeneration = 4L) }
			assertFailsWith<IllegalArgumentException> { original.copy(writerBindingGeneration = 3L, writerOwnerGeneration = 6L) }
		}
	}

	@Test
	fun `one malformed Wi-Fi child does not discard its valid sibling in either version`() {
		for (version in listOf(LEGACY_WIFI_WAL_PAYLOAD_VERSION, WIFI_WAL_PAYLOAD_VERSION)) {
			val mixed = payload(listOf(
				observation(1, -60, 1_500_000_000L),
				observation(5_180, -40, 1_600_000_000L),
			))
			val encoded = WifiWalPayloadCodec.encode(mixed, version)
			val unsignedWal = validWal(version).copy(payload = encoded.toByteArray(),
				payloadChecksum = encoded.checksum,
				deliveryIdentity = canonicalWifiProviderDeliveryIdentity(BOOT_ID, mixed.observations, version))
			val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
			assertIs<WifiWalCarrierAuthentication.Authenticated>(WifiWalCarrierAuthenticator.authenticate(wal))
			val unsignedFact = validFact(wal).copy(acceptedResultCount = 1,
				malformedResultCount = 1, coverageCompleteness = WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL,
				observationCount = 1, twoPointFourGhzCount = 0, fiveGhzCount = 1,
				strongestSignalDbm = -40, weakestSignalDbm = -40, signalSumDbm = -40L,
				coverageIntervalStartNanos = 1_600_000_000L)
			val fact = unsignedFact.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsignedFact))
			val children = if (version == WIFI_WAL_PAYLOAD_VERSION) validChildren(fact, wal).drop(1) else null
			val authenticated = assertIs<WifiWalCarrierAuthentication.Authenticated>(
				WifiWalCarrierAuthenticator.authenticate(fact, wal, children))
			assertEquals(1, authenticated.carrier.coverage?.acceptedResultCount)
			assertEquals(1, authenticated.carrier.coverage?.malformedResultCount)
		}
	}

	@Test
	fun `canonical production Wi-Fi payload and fact authenticate`() {
		val wal = validWal()
		val fact = validFact(wal)

		val authenticated = assertIs<WifiWalCarrierAuthentication.Authenticated>(
			WifiWalCarrierAuthenticator.authenticate(fact, wal, validChildren(fact, wal)),
		)

		assertEquals(2, authenticated.carrier.coverage?.acceptedResultCount)
		assertEquals("Europe/Prague", authenticated.carrier.payload.observationZoneId)
	}

	@Test
	fun `legacy v5 Wi-Fi payload remains byte exact and authentic`() {
		val historical = WifiWalPayload(
			observations = listOf(WifiWalPayloadObservation("", 2_412, -50, 1_000L)),
			platformTimestampMs = 0L,
			resultAgeMs = null,
			observationZoneId = "UTC",
		)
		val encoded = WifiWalPayloadCodec.encode(historical, LEGACY_WIFI_WAL_PAYLOAD_VERSION)
		assertEquals(
			"000000070000000100000000096cffffffce0100000000000003e8010000000000000000000003555443",
			encoded.toByteArray().joinToString("") { "%02x".format(it) },
		)
		assertEquals(
			historical,
			assertIs<WifiWalPayloadDecodeResult.Decoded>(
				WifiWalPayloadCodec.decodeCanonical(LEGACY_WIFI_WAL_PAYLOAD_VERSION, encoded.toByteArray()),
			).payload,
		)
		val wal = validWal(LEGACY_WIFI_WAL_PAYLOAD_VERSION)
		assertIs<WifiWalCarrierAuthentication.Authenticated>(
			WifiWalCarrierAuthenticator.authenticate(validFact(wal), wal),
		)
	}

	@Test
	fun `v6 network identity and name are retained and distinguish provider deliveries`() {
		val wal = validWal(withNetworkIdentity = true)
		val authenticated = assertIs<WifiWalCarrierAuthentication.Authenticated>(
			WifiWalCarrierAuthenticator.authenticate(wal),
		).carrier
		assertEquals("aa:bb:cc:dd:ee:ff", authenticated.payload.observations.first().identifierToken)
		assertEquals("Home Wi-Fi", authenticated.payload.observations.first().networkName)
		assertEquals("WifiWalPayloadObservation", authenticated.payload.observations.first().toString())
		assertNotEquals(
			wal.deliveryIdentity,
			canonicalWifiProviderDeliveryIdentity(
				BOOT_ID,
				authenticated.payload.observations.mapIndexed { index, observation ->
					if (index == 0) observation.copy(networkName = "Another network") else observation
				},
			),
		)
	}

	@Test
	fun `v6 fact requires exact purpose-owned children and rejects a changed network name`() {
		val wal = validWal(withNetworkIdentity = true)
		val fact = validFact(wal)
		val children = validChildren(fact, wal)
		assertEquals(WifiWalCarrierFailure.CHILD_EVIDENCE_MISMATCH,
			assertIs<WifiWalCarrierAuthentication.Rejected>(
				WifiWalCarrierAuthenticator.authenticate(fact, wal),
			).reason)
		assertIs<WifiWalCarrierAuthentication.Authenticated>(
			WifiWalCarrierAuthenticator.authenticate(fact, wal, children),
		)
		assertEquals(WifiWalCarrierFailure.CHILD_EVIDENCE_MISMATCH,
			assertIs<WifiWalCarrierAuthentication.Rejected>(
				WifiWalCarrierAuthenticator.authenticate(fact, wal,
					listOf(children.first().copy(ssid = "forged")) + children.drop(1)),
			).reason)
	}

	@Test
	fun `v6 full callback fits the bounded WAL with worst-case encoded names`() {
		val observations = List(MAX_WIFI_WAL_CHILDREN) { index ->
			WifiWalPayloadObservation(
				identifierToken = "\u0800".repeat(64),
				frequencyMhz = 2_412,
				signalLevelDbm = -50,
				providerTimestampNanos = 1_000_000_000L + index,
				networkName = "\u0000".repeat(128),
			)
		}
		val payload = payload(observations)
		val encoded = WifiWalPayloadCodec.encode(payload)

		assertEquals(true, encoded.bytes.size <= MAX_WIFI_WAL_PAYLOAD_BYTES)
		assertEquals("EncodedWifiWalPayload", encoded.toString())
		assertEquals(
			payload,
			assertIs<WifiWalPayloadDecodeResult.Decoded>(
				WifiWalPayloadCodec.decodeCanonical(WIFI_WAL_PAYLOAD_VERSION, encoded.toByteArray()),
			).payload,
		)
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
			WifiWalCarrierAuthenticator.authenticate(inconsistent, wal, validChildren(original, wal)),
		)

		assertEquals(WifiWalCarrierFailure.CHILD_EVIDENCE_MISMATCH, rejected.reason)
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
			WifiWalCarrierAuthenticator.authenticate(correction, wal, validChildren(correction, wal)),
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

	@Test
	fun `durable carrier version is sealed and must match the actual WAL format`() {
		val wal = validWal()
		val legacy = validFact(wal)
		assertEquals(null, legacy.payloadVersion)
		assertEquals(true, WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(legacy))
		val unsigned = legacy.copy(payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		assertEquals(false, WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(unsigned))
		val detailed = unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
		assertIs<WifiWalCarrierAuthentication.Authenticated>(
			WifiWalCarrierAuthenticator.authenticate(detailed, wal, validChildren(detailed, wal)))
		val wrongVersion = detailed.copy(payloadVersion = LEGACY_WIFI_WAL_PAYLOAD_VERSION).let {
			it.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(it))
		}
		assertEquals(WifiWalCarrierFailure.FACT_LINKAGE_MISMATCH,
			assertIs<WifiWalCarrierAuthentication.Rejected>(
				WifiWalCarrierAuthenticator.authenticate(wrongVersion, wal, validChildren(wrongVersion, wal))).reason)
	}

	@Test
	fun `corrections preserve the original nullable or detailed carrier version`() {
		val wal = validWal()
		for (version in listOf(null, WIFI_WAL_PAYLOAD_VERSION)) {
			val first = validFact(wal).copy(payloadVersion = version).let {
				it.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(it))
			}
			fun correction(carrierVersion: Int?) = first.copy(
				semanticRevision = 2L, supersedesSemanticRevision = 1L,
				mutationId = WifiCapturedFactRevisionIntegrity.mutationId(first.logicalFactId, 2L),
				payloadVersion = carrierVersion,
			).let { it.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(it)) }
			assertEquals(true, WifiCapturedFactRevisionIntegrity.hasValidRetainedLineage(
				listOf(first, correction(version))))
			assertEquals(false, WifiCapturedFactRevisionIntegrity.hasValidRetainedLineage(
				listOf(first, correction(if (version == null) WIFI_WAL_PAYLOAD_VERSION else null))))
		}
	}

	private fun validWal(
		payloadVersion: Int = WIFI_WAL_PAYLOAD_VERSION,
		withNetworkIdentity: Boolean = false,
	): SourceEventWalEntity {
		val payload = payload(
			listOf(
				observation(2_412, -60, 1_500_000_000L).let { observation ->
					if (withNetworkIdentity) observation.copy(
						identifierToken = "aa:bb:cc:dd:ee:ff",
						networkName = "Home Wi-Fi",
					) else observation
				},
				observation(5_180, -40, 1_600_000_000L),
			),
		)
		val encoded = WifiWalPayloadCodec.encode(payload, payloadVersion)
		val deliveryIdentity = canonicalWifiProviderDeliveryIdentity(
			BOOT_ID, payload.observations, payloadVersion,
		)
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
			payloadVersion = payloadVersion,
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

	private fun validChildren(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): List<WifiCapturedChildEntity> {
		val payload = assertIs<WifiWalPayloadDecodeResult.Decoded>(
			WifiWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload),
		).payload
		return payload.observations.mapIndexed { index, observation ->
			val providerNanos = requireNotNull(observation.providerTimestampNanos)
			val age = wal.observedElapsedNanos - providerNanos
			val wall = requireNotNull(wal.wallTimeMs) - age / 1_000_000L
			val uncertainty = requireNotNull(wal.wallTimeUncertaintyMs) +
				if (age % 1_000_000L == 0L) 0L else 1L
			val bssid = observation.identifierToken.takeIf(String::isNotEmpty)
			val owner = RadioChildOwner(RadioChildPurpose.SESSION, fact.writerProjectionId,
				fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision, index)
			WifiCapturedChildEntity(
				fact.writerProjectionId, fact.writerProjectionVersion, fact.logicalFactId,
				fact.semanticRevision, index, providerNanos, wall, uncertainty, bssid,
				observation.networkName, observation.frequencyMhz, observation.signalLevelDbm,
				RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(owner, providerNanos,
					wall, uncertainty, bssid, observation.networkName,
					observation.frequencyMhz, observation.signalLevelDbm)),
			)
		}
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
