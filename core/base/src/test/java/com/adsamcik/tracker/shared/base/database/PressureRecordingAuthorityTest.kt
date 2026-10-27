package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class PressureRecordingAuthorityTest {
	@Test
	fun `manifest and quality replacements preserve the original recording horizon`() {
		val capture = capture()
		val replacement = capture.copy(demandId = "new-quality", manifestRevision = 2L,
			lifecycleLeaseGeneration = 2L, sourcePolicyRevision = 2L)
		val rows = rows(1L, 10L, capture) + rows(2L, 100L, replacement)
		assertEquals(500L, pressureRecordingAuthorizationEnd(rows, 1L, "session", "run", 500L))
	}

	@Test
	fun `End or permission withdrawal ends coverage even when an equal-looking grant returns`() {
		val capture = capture()
		val initial = rows(1L, 10L, capture)
		val renewed = rows(3L, 110L, capture)
		for (interruption in listOf(rows(2L, 100L), rows(2L, 100L, capture.copy(consentEpoch = 2L)),
			rows(2L, 100L, capture.copy(serviceRunId = "other-run")))) {
			assertEquals(100L, pressureRecordingAuthorizationEnd(initial + interruption + renewed,
				1L, "session", "run", 500L))
		}
	}

	@Test
	fun `clock changes and missing original authority cannot extend a window`() {
		val rows = rows(1L, 10L, capture()) + rows(2L, 100L, capture()).map { it.copy(effectiveBootId = "other-boot") }
		assertNull(pressureRecordingAuthorizationEnd(rows, 1L, "session", "run", 500L))
		assertNull(pressureRecordingAuthorizationEnd(rows, 7L, "session", "run", 500L))
	}

	private fun rows(revision: Long, effective: Long, vararg demands: SourceDemandEntity) =
		SourceBrokerAuthorization.rows(4, 1L, revision, demands.toList(), "boot", effective, effective)

	private fun capture() = SourceDemandEntity(
		demandId = "capture", consumerId = "session:session", sourceKind = 4,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE, logicalTrackingId = "session", serviceRunId = "run",
		manifestRevision = 1L, lifecycleLeaseGeneration = 1L, sourcePolicyRevision = 1L, consentEpoch = 1L,
		persistenceEligible = true, qosCode = 2, maximumAgeMs = 60_000L, desiredLatencyMs = 5_000L,
		requestedBootId = "boot", requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE, retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
	)
}
