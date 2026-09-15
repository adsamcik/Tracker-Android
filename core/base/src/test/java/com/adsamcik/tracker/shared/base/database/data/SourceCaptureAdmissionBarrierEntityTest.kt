package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import org.junit.Test

class SourceCaptureAdmissionBarrierEntityTest {
	@Test
	fun `admission barrier requires exact nonnegative durable high-water`() {
		shouldThrow<IllegalArgumentException> {
			SourceCaptureAdmissionBarrierEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				registrationGeneration = 2L,
				sourceInstanceId = "activity-instance",
				throughAuthorizationRevision = 3L,
				lastAdmissionOrdinal = -1L,
				lastSourceSequence = 0L,
				sealedElapsedRealtimeNanos = 10L,
				sealedAtMs = 20L,
			)
		}
	}
}
