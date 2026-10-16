package com.adsamcik.tracker.shared.base.database.data

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class CellWalCarrierAuthenticatorTest {
	@Test
	fun `current Cell payload is canonically authenticated`() {
		val result = CellWalCarrierAuthenticator.authenticate(validWal())

		val authenticated = assertIs<CellWalCarrierAuthentication.Authenticated>(result)
		assertEquals(2, authenticated.carrier.payload.observations.size)
		assertEquals("Europe/Prague", authenticated.carrier.payload.observationZoneId)
	}

	@Test
	fun `version five identity-free Cell payload remains authentic`() {
		val authenticated = assertIs<CellWalCarrierAuthentication.Authenticated>(
			CellWalCarrierAuthenticator.authenticate(validWal(payloadVersion = LEGACY_CELL_WAL_PAYLOAD_VERSION)),
		)
		assertEquals(2, authenticated.carrier.payload.observations.size)
	}

	@Test
	fun `version five still rejects a tower identifier`() {
		val original = validWal(payloadVersion = LEGACY_CELL_WAL_PAYLOAD_VERSION)
		val payload = CellWalPayload(
			subscriptionId = null,
			observations = listOf(observation(
				"LTE", true, -100, 1_500_000_000L, "310:260:123:456",
			)),
			refreshOutcomeCode = CELL_REFRESH_OUTCOME_CALLBACK,
			observationZoneId = "Europe/Prague",
		)
		val rejected = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(
				original.withPayload(CellWalPayloadCodec.encode(
					payload, LEGACY_CELL_WAL_PAYLOAD_VERSION,
				).toByteArray()),
			),
		)
		assertEquals(CellWalCarrierFailure.IDENTITY_BEARING_CHILD, rejected.reason)
	}

	@Test
	fun `version six retains tower identity and binds it to delivery identity`() {
		val tower = observation("LTE", true, -100, 1_500_000_000L, "310:260:123:456")
		val wal = validWal(observations = listOf(tower))
		val authenticated = assertIs<CellWalCarrierAuthentication.Authenticated>(
			CellWalCarrierAuthenticator.authenticate(wal),
		)
		assertEquals(tower.identifierToken, authenticated.carrier.payload.observations.single().identifierToken)
		val changedTower = wal.withPayload(CellWalPayloadCodec.encode(
			CellWalPayload(null, listOf(tower.copy(identifierToken = "310:260:123:457")),
				CELL_REFRESH_OUTCOME_CALLBACK, "Europe/Prague"),
		).toByteArray())
		val rejected = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(changedTower),
		)
		assertEquals(CellWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH, rejected.reason)
	}

	@Test
	fun `old and unknown payload versions are typed failures`() {
		listOf(1, CELL_WAL_PAYLOAD_VERSION + 1).forEach { version ->
			val rejected = assertIs<CellWalCarrierAuthentication.Rejected>(
				CellWalCarrierAuthenticator.authenticate(validWal().resigned(payloadVersion = version)),
			)
			assertEquals(CellWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION, rejected.reason)
		}
	}

	@Test
	fun `Cell carrier rejects provider dedup and Activity automation authority`() {
		val dedup = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(
				validWal().resigned(providerDedupKey = "cell-dedup"),
			),
		)
		assertEquals(CellWalCarrierFailure.PROVIDER_DEDUP_KEY_PRESENT, dedup.reason)

		val automation = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(
				validWal().resigned(activityAutomationEpoch = 1L),
			),
		)
		assertEquals(CellWalCarrierFailure.ACTIVITY_AUTOMATION_PRESENT, automation.reason)

		val wrongPurpose = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(
				validWal(payloadVersion = LEGACY_CELL_WAL_PAYLOAD_VERSION).resigned(
					authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				),
			),
		)
		assertEquals(CellWalCarrierFailure.NON_CAPTURE_PURPOSE, wrongPurpose.reason)
	}

	@Test
	fun `noncanonical children and validly rehashed arbitrary bytes fail`() {
		val noncanonical = validWal(
			observations = listOf(
				observation("NR", false, -85, 1_600_000_000L),
				observation("LTE", true, -100, 1_500_000_000L),
			),
		)
		val orderingFailure = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(noncanonical),
		)
		assertEquals(CellWalCarrierFailure.NON_CANONICAL_CHILD_ORDER, orderingFailure.reason)

		val arbitrary = validWal().withPayload(byteArrayOf(1, 2, 3, 4))
		val payloadFailure = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(arbitrary),
		)
		assertEquals(CellWalCarrierFailure.PAYLOAD_DECODE_FAILED, payloadFailure.reason)
	}

	@Test
	fun `delivery identity is recomputed from canonical children`() {
		val rejected = assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(
				validWal().resigned(deliveryIdentity = "f".repeat(64)),
			),
		)

		assertEquals(CellWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH, rejected.reason)
	}

	// Keep the Cell payload, delivery identity and complete signed WAL record together as one fixture.
	@Suppress("LongMethod")
	private fun validWal(
		observations: List<CellWalPayloadObservation> = listOf(
			observation("LTE", true, -100, 1_500_000_000L),
			observation("NR", false, -85, 1_600_000_000L),
		),
		payloadVersion: Int = CELL_WAL_PAYLOAD_VERSION,
	): SourceEventWalEntity {
		val payload = CellWalPayload(
			subscriptionId = null,
			observations = observations,
			refreshOutcomeCode = CELL_REFRESH_OUTCOME_CALLBACK,
			observationZoneId = "Europe/Prague",
		)
		val encoded = CellWalPayloadCodec.encode(payload, payloadVersion)
		val deliveryIdentity = canonicalCellProviderDeliveryIdentity(
			"boot-1",
			observations.map { observation ->
				CellProviderDeliveryIdentityFact(
					observation.radioType,
					observation.registered,
					observation.signalLevelDbm,
					requireNotNull(observation.providerTimestampNanos),
					observation.identifierToken,
				)
			},
			payloadVersion,
		)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = 1L,
			eventId = "cell-event",
			providerDedupKey = null,
			deliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = "logical-cell",
			serviceRunId = "run-cell",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
			sourceInstanceId = "cell-instance",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "cell-plan-v1",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = 0L,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "boot-1",
			observedElapsedNanos = observations.maxOf {
				requireNotNull(it.providerTimestampNanos)
			},
			observedIntervalStartNanos = observations.minOf {
				requireNotNull(it.providerTimestampNanos)
			},
			receivedElapsedNanos = 1_700_000_000L,
			wallTimeMs = 1_700_000_000_000L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L,
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
			createdAtMs = 1_700_000_000_000L,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun observation(
		radioType: String,
		registered: Boolean,
		signalLevelDbm: Int?,
		providerTimestampNanos: Long,
		identifierToken: String = "",
	) = CellWalPayloadObservation(
		identifierToken = identifierToken,
		radioType = radioType,
		registered = registered,
		signalLevelDbm = signalLevelDbm,
		providerTimestampNanos = providerTimestampNanos,
	)

	private fun SourceEventWalEntity.resigned(
		payloadVersion: Int = this.payloadVersion,
		providerDedupKey: String? = this.providerDedupKey,
		activityAutomationEpoch: Long? = this.activityAutomationEpoch,
		deliveryIdentity: String? = this.deliveryIdentity,
		authorizationPurposeEligibilityMask: Long = this.authorizationPurposeEligibilityMask,
	): SourceEventWalEntity {
		val unsigned = copy(
			payloadVersion = payloadVersion,
			providerDedupKey = providerDedupKey,
			activityAutomationEpoch = activityAutomationEpoch,
			deliveryIdentity = deliveryIdentity,
			authorizationPurposeEligibilityMask = authorizationPurposeEligibilityMask,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun SourceEventWalEntity.withPayload(bytes: ByteArray): SourceEventWalEntity {
		val withPayload = copy(
			payload = bytes,
			payloadChecksum = copy(payload = bytes).calculatedPayloadChecksum(),
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		return withPayload.copy(integrityIdentity = withPayload.calculatedIntegrityIdentity())
	}
}
