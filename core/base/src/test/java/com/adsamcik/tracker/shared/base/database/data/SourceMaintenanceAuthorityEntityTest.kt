package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import org.junit.Test

class SourceMaintenanceAuthorityEntityTest {
	@Test
	fun `maintenance authority requires exact durable generation and expiry`() {
		SourceMaintenanceAuthorityEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = SourceMaintenanceAuthorityEntity.PURPOSE_SOURCE_ERASE,
			ownerToken = "pressure-erase",
			generation = 1L,
			collectedDataEpoch = 3L,
			policyRevision = 7L,
			consentEpoch = 4L,
			bootId = "boot-1",
			expiresElapsedRealtimeNanos = 10_000L,
			state = SourceMaintenanceAuthorityEntity.STATE_ACTIVE,
			updatedAtMs = 1_000L,
		)

		shouldThrow<IllegalArgumentException> {
			SourceMaintenanceAuthorityEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = SourceMaintenanceAuthorityEntity.PURPOSE_SOURCE_ERASE,
				ownerToken = "pressure-erase",
				generation = 0L,
				collectedDataEpoch = 3L,
				policyRevision = 7L,
				consentEpoch = 4L,
				bootId = "boot-1",
				expiresElapsedRealtimeNanos = 10_000L,
				state = SourceMaintenanceAuthorityEntity.STATE_ACTIVE,
				updatedAtMs = 1_000L,
			)
		}
	}
}
