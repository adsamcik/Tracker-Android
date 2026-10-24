package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAuthorizationPublicationBoundaryTest {
	@Test
	fun `new membership starts no earlier than existing publication and every member request`() {
		val original = demand("old", 100L)
		val previous = authorization(listOf(original), 400L)
		val replacement = original.copy(demandId = "replacement", requestedElapsedRealtimeNanos = 200L,
			requestedAtMs = 200L)
		assertEquals(SourceAuthorizationPublicationBoundary(400L, 400L),
			sourceAuthorizationPublicationBoundary(listOf(replacement), previous, "boot-1", 150L, 150L))
		val laterMember = replacement.copy(demandId = "later", requestedElapsedRealtimeNanos = 500L,
			requestedAtMs = 500L)
		assertEquals(SourceAuthorizationPublicationBoundary(500L, 500L),
			sourceAuthorizationPublicationBoundary(listOf(laterMember), previous, "boot-1", 150L, 150L))
	}

	@Test
	fun `Stop retains its exact cutoff while a sibling survives or all purposes end`() {
		val session = demand("session", 10L)
		val ambient = ambientDemand("ambient", 10L)
		val previous = authorization(listOf(session, ambient), 200L)
		for (remaining in listOf(listOf(ambient), emptyList())) {
			assertEquals(SourceAuthorizationPublicationBoundary(100L, 100L),
				sourceAuthorizationPublicationBoundary(remaining, previous, "boot-1", 100L, 100L))
		}
	}

	@Test
	fun `End keeps its cutoff when another purpose is replaced by a later request`() {
		val session = demand("session", 10L)
		val ambient = ambientDemand("ambient", 10L)
		val replacement = ambient.copy(demandId = "ambient-replacement", requestedElapsedRealtimeNanos = 120L,
			requestedAtMs = 120L)
		assertEquals(SourceAuthorizationPublicationBoundary(100L, 100L),
			sourceAuthorizationPublicationBoundary(listOf(replacement), authorization(listOf(session, ambient), 90L),
				"boot-1", 100L, 100L))
	}

	private fun authorization(demands: List<SourceDemandEntity>, elapsed: Long): SourceAuthorizationSnapshot =
		requireNotNull(SourceBrokerAuthorization.rows(1, 1L, 1L, demands, "boot-1", elapsed, elapsed)
			.toAuthorizationSnapshotOrNull())

	private fun ambientDemand(id: String, elapsed: Long) = demand(id, elapsed).copy(
		consumerId = "ambient:location", purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		logicalTrackingId = null, serviceRunId = null, manifestRevision = null, lifecycleLeaseGeneration = null)

	private fun demand(id: String, elapsed: Long) = SourceDemandEntity(
		demandId = id, consumerId = "session:logical", sourceKind = 1, purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = "logical", serviceRunId = "run", manifestRevision = 1L, lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 1L, consentEpoch = 1L, persistenceEligible = true, qosCode = 0,
		maximumAgeMs = 120_000L, desiredLatencyMs = 10_000L, requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = elapsed, requestedAtMs = elapsed, status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null)
}
