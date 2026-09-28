package com.adsamcik.tracker.shared.base.database.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocationPurposeAuthorityTest {
	@Test
	fun `ambient association is sessionless and cannot borrow capture authority`() {
		val ambient = ambient()
		assertNull(ambient.sessionSegmentId)
		assertNull(ambient.logicalTrackingId)
		assertFailsWith<IllegalArgumentException> { ambient.copy(sessionSegmentId = 1) }
		assertFailsWith<IllegalArgumentException> { ambient.copy(logicalTrackingId = "unrelated") }
		assertFailsWith<IllegalArgumentException> { ambient.copy(retentionApprovalRevision = null) }
	}

	@Test
	fun `capture requires its complete manifest scope and cannot impersonate ambient`() {
		val capture = ambient().copy(
			purpose = SourceBrokerPurpose.SESSION_CAPTURE, authorityId = "run",
			logicalTrackingId = "logical", serviceRunId = "run", sessionSegmentId = 1,
			sessionManifestRevision = 1, lifecycleLeaseGeneration = 1,
			ambientIntentId = null, retentionPolicyId = null, retentionApprovalRevision = null,
		)
		assertEquals(1L, capture.sessionSegmentId)
		assertFailsWith<IllegalArgumentException> { capture.copy(serviceRunId = null) }
		assertFailsWith<IllegalArgumentException> { capture.copy(ambientIntentId = "ambient") }
	}

	@Test
	fun `control and changed retention bytes cannot become product authority`() {
		assertFailsWith<IllegalStateException> { ambient().copy(purpose = SourceBrokerPurpose.CONTROL_AUTOSTART) }
		val retention = AmbientLocationRetentionAuthorityIntegrity.create(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, 1,
			AmbientLocationRetentionAuthorityEntity.STATE_ACTIVE, "policy", 1, 1,
			0, "boot", 10, 100,
		)
		assertFailsWith<IllegalArgumentException> { retention.copy(ambientConsentEpoch = 2) }
		assertFailsWith<IllegalArgumentException> { retention.copy(retainedFromMs = 99) }
	}

	private fun ambient() = LocationSamplePurposeAuthorityEntity(
		sampleId = 1, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, authorityId = "ambient-1",
		sourcePolicyRevision = 1, consentEpoch = 1,
		effectiveFromElapsedNanos = 10, effectiveUntilElapsedNanos = 11,
		logicalTrackingId = null, serviceRunId = null, sessionSegmentId = null,
		sessionManifestRevision = null, lifecycleLeaseGeneration = null,
		ambientIntentId = "ambient-1", retentionPolicyId = "retention-1", retentionApprovalRevision = 1,
		scopeDeletionGeneration = 0, zoneId = "Europe/Prague",
	)
}
