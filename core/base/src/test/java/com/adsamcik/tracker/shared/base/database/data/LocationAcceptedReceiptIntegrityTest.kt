package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationAcceptedReceiptIntegrityTest {
	@Test
	fun `retained receipt binds provider identity authorization delivery clock and epoch`() {
		val original = LocationAcceptedReceiptIntegrity.seal(receipt(sample()))
		assertTrue(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(original))
		val changes = listOf(original.copy(sourceSequence = 99), original.copy(authorizationRevision = 7),
			original.copy(sourceInstanceId = "other"), original.copy(registrationGeneration = 2),
			original.copy(deliveryIdentity = "other"), original.copy(sourceAdmissionOrdinal = 2),
			original.copy(walIntegrityIdentity = "b".repeat(64)), original.copy(configurationRevision = 2),
			original.copy(physicalConfigurationFingerprint = "other"), original.copy(authorizationFingerprint = "other"),
			original.copy(clockDomainId = "other"), original.copy(bootId = "other"),
			original.copy(collectedDataEpoch = 1), original.copy(canonicalContentChecksum = "c".repeat(64)),
			original.copy(receivedWallTimeMs = 1_011), original.copy(wallTimeUncertaintyMs = 11))
		changes.forEach { assertFalse(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(it)) }
	}

	@Test
	fun `purpose binding survives raw log absence without lending consent to a sibling`() {
		val receipt = LocationAcceptedReceiptIntegrity.seal(receipt(sample()))
		val purpose = LocationAcceptedReceiptIntegrity.seal(ambientPurpose(), receipt)
		assertTrue(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(purpose, receipt))
		val changes = listOf(purpose.copy(consentEpoch = 2), purpose.copy(sourcePolicyRevision = 2),
			purpose.copy(authorityId = "other"), purpose.copy(ambientIntentId = "other"),
			purpose.copy(retentionPolicyId = "other"), purpose.copy(retentionApprovalRevision = 2),
			purpose.copy(effectiveUntilElapsedNanos = 12), purpose.copy(scopeDeletionGeneration = 1))
		changes.forEach { assertFalse(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(it, receipt)) }
		val different = LocationAcceptedReceiptIntegrity.seal(receipt.copy(sourceSequence = 1))
		assertFalse(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(purpose, different))
		assertFalse(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(purpose, receipt(sample())))
	}

	@Test
	fun `legacy replay equality never upgrades blank proof or accepts a conflicting tuple`() {
		val oldReceipt = receipt(sample())
		val sealed = LocationAcceptedReceiptIntegrity.seal(oldReceipt)
		assertTrue(LocationAcceptedReceiptIntegrity.compatibleStoredReceipt(oldReceipt, sealed))
		assertFalse(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(oldReceipt))
		assertFalse(LocationAcceptedReceiptIntegrity.compatibleStoredReceipt(oldReceipt.copy(sourceSequence = 9), sealed))
		val oldPurpose = ambientPurpose()
		assertTrue(LocationAcceptedReceiptIntegrity.compatibleStoredPurpose(oldPurpose,
			LocationAcceptedReceiptIntegrity.seal(oldPurpose, sealed)))
		assertEquals("", oldPurpose.retainedProofChecksum)
		assertFalse(LocationAcceptedReceiptIntegrity.compatibleStoredPurpose(oldPurpose.copy(consentEpoch = 9),
			LocationAcceptedReceiptIntegrity.seal(oldPurpose, sealed)))
	}

	private fun ambientPurpose() = LocationSamplePurposeAuthorityEntity(1, SourceBrokerPurpose.AMBIENT_PRODUCT,
		"ambient", 1, 1, 10, 11, null, null, null, null, null, "ambient", "retention", 1, 0, null)
	@Test
	fun `checksum preserves the existing protected writer encoding`() {
		val sample = sample()
		val previousWriterChecksum = MessageDigest.getInstance("SHA-256")
			.digest(sample.protectedLocationCanonicalReceiptBytes())
			.joinToString("") { "%02x".format(it) }
		assertEquals(previousWriterChecksum, LocationAcceptedReceiptIntegrity.canonicalChecksum(sample))
		assertTrue(LocationAcceptedReceiptIntegrity.matchesCanonicalSample(receipt(sample), sample))
	}

	@Test
	fun `receipt cannot adopt a different sample identity clock or callback time`() {
		val sample = sample()
		val receipt = receipt(sample)
		val substitutes = listOf(
			receipt.copy(sampleId = 2), receipt.copy(sourceEventId = "other"),
			receipt.copy(sourceSignalId = "legacy-signal"), receipt.copy(clockDomainId = "other"),
			receipt.copy(bootId = "other"), receipt.copy(observedWallTimeMs = 1_001),
			receipt.copy(observedElapsedRealtimeNanos = 9),
			receipt.copy(receivedElapsedRealtimeNanos = 12),
		)
		for (substitute in substitutes) {
			assertFalse(LocationAcceptedReceiptIntegrity.matchesCanonicalSample(substitute, sample))
		}
	}

	@Test
	fun `content changes cannot retain the previous canonical receipt`() {
		val sample = sample()
		val receipt = receipt(sample)
		for (changed in listOf(sample.copy(latE7 = 1), sample.copy(hAccM = 1f),
			sample.copy(rawPlatformSpeedMps = 2f), sample.copy(permissionPrecision = "FINE"),
			sample.copy(altitudeModelVersion = 2), sample.copy(sourceRevision = 4))) {
			assertFalse(LocationAcceptedReceiptIntegrity.matchesCanonicalSample(receipt, changed))
		}
	}

	@Test
	fun `exact content alone never authenticates the provider tuple or purpose grant`() {
		val sample = sample()
		// This is explicitly a content check. The source-owned reader must authenticate all remaining
		// receipt fields and an independent purpose grant using the original event or durable proof.
		assertTrue(LocationAcceptedReceiptIntegrity.matchesCanonicalSample(
			receipt(sample).copy(sourceSequence = 99, authorizationRevision = 7), sample,
		))
	}

	@Test
	fun `coarse precision and historically absent secondary boot field preserve exact content`() {
		val coarse = sample().copy(hAccM = 1_200f, bootClockDomainId = null)
		assertTrue(LocationAcceptedReceiptIntegrity.matchesCanonicalSample(receipt(coarse), coarse))
		assertEquals(1_200f, coarse.hAccM)
	}

	private fun sample() = LocationSample(
		id = 1, timeMs = 1_000, elapsedRealtimeNanos = 10,
		latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null,
		hAccM = 200f, vAccM = null, speedMps = null, speedAccuracyMps = null,
		provider = "fused", quality = SampleQuality.COARSE, motionState = null,
		policy = null, bucketId = null, createdAt = 1_010, receivedElapsedRealtimeNanos = 11,
		sourceEventId = "event", sourceSignalId = protectedLocationCanonicalProductIdentity("event"),
		clockDomainId = "boot", bootClockDomainId = "boot", sourceRevision = 3,
	)

	private fun receipt(sample: LocationSample) = LocationAcceptedReceiptEntity(
		sampleId = sample.id, sourceEventId = "event", sourceSignalId = requireNotNull(sample.sourceSignalId),
		sourceAdmissionOrdinal = 1, walIntegrityIdentity = "a".repeat(64),
		canonicalContentChecksum = LocationAcceptedReceiptIntegrity.canonicalChecksum(sample),
		sourceInstanceId = "instance", registrationGeneration = 1, sourceSequence = 0,
		deliveryIdentity = "delivery", deliveryUnitIndex = 0, deliveryUnitCount = 1,
		writerId = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
		writerVersion = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
		configurationRevision = 1, physicalConfigurationFingerprint = "physical",
		authorizationRevision = 1, authorizationFingerprint = "authority", clockDomainId = "boot", bootId = "boot",
		observedElapsedRealtimeNanos = 10, receivedElapsedRealtimeNanos = 11,
		observedWallTimeMs = 1_000, receivedWallTimeMs = 1_010, wallTimeUncertaintyMs = 10,
		collectedDataEpoch = 0,
	)
}
