package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsRecordingLifetimeTest {
	@Test fun `marked recording crosses two quality revisions but ends at real boundary`() {
		val manifests = listOf(manifest(1, 100), manifest(2, 105), manifest(3, 108), manifest(4, 120))
		val bindings = mapOf(1L to binding(1, 1), 2L to binding(2, 1, qos = 2),
			3L to binding(3, 1, qos = 3), 4L to binding(4, 4))
		assertTrue(StepsRecordingLifetime.containsInterval(1, 100, 112, manifests, bindings))
		assertFalse(StepsRecordingLifetime.containsInterval(1, 100, 120, manifests, bindings))
		assertFalse(StepsRecordingLifetime.containsInterval(2, 105, 112, manifests, bindings))
		assertTrue(StepsRecordingLifetime.containsInterval(4, 120, 125, manifests, bindings))
	}

	@Test fun `legacy null retains the next global manifest cutoff`() {
		val manifests = listOf(manifest(1, 100), manifest(2, 105))
		val bindings = mapOf(1L to binding(1, null), 2L to binding(2, null))
		assertTrue(StepsRecordingLifetime.containsInterval(1, 100, 104, manifests, bindings))
		assertFalse(StepsRecordingLifetime.containsInterval(1, 100, 105, manifests, bindings))
	}

	@Test fun `missing middle binding or manifest never revives an old origin`() {
		val manifests = listOf(manifest(1, 100), manifest(2, 105), manifest(3, 108))
		assertFalse(StepsRecordingLifetime.containsInterval(1, 100, 112, manifests,
			mapOf(1L to binding(1, 1), 3L to binding(3, 1))))
		assertFalse(StepsRecordingLifetime.containsInterval(1, 100, 112, manifests.filter { it.manifestRevision != 2L },
			mapOf(1L to binding(1, 1), 3L to binding(3, 1))))
	}

	@Test fun `permission writer run boot and zone changes cannot claim continuity`() {
		val origin = manifest(1, 100)
		val next = manifest(2, 105)
		val originalBinding = binding(1, 1)
		val nextBinding = binding(2, 1)
		val changedManifests = listOf(next.copy(serviceRunId = "other-run"),
			next.copy(effectiveBootId = "other-boot"), next.copy(zoneId = "Europe/Prague"),
			next.copy(rolloutRevision = 2))
		changedManifests.forEach { changed ->
			assertFalse(StepsRecordingLifetime.isContinuation(origin, originalBinding, changed, nextBinding))
		}
		assertFalse(StepsRecordingLifetime.isContinuation(origin, originalBinding, next,
			nextBinding.copy(consentEpoch = 2)))
		assertFalse(StepsRecordingLifetime.isContinuation(origin, originalBinding, next,
			nextBinding.copy(
				writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				writerOwnerGeneration = SourceWriterGenerationContract.canonicalOwnerGeneration(1),
				writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				writerBindingGeneration = 1,
			)))
	}

	private fun manifest(revision: Long, elapsed: Long) = SessionManifestVersionEntity(
		logicalTrackingId = "session", manifestRevision = revision, serviceRunId = "run",
		sessionMode = "MANUAL", sourcePolicyRevision = revision, acquisitionPlanRevision = revision,
		rolloutRevision = 1, startOrigin = if (revision == 1L) "MANUAL_FOREGROUND_START" else "POLICY_RECONCILIATION",
		effectiveBootId = "boot", effectiveElapsedRealtimeNanos = elapsed, effectiveWallTimeMs = elapsed,
		zoneId = "UTC", automationEpoch = null, changeReason = "POLICY_RECONCILIATION", manifestChecksum = "",
	)

	private fun binding(revision: Long, origin: Long?, qos: Int = 1) = SessionManifestSourceEntity(
		logicalTrackingId = "session", manifestRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS, purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch = 1, persistenceEligible = true, qosCode = qos,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
		writerOwnerGeneration = 1, stepsRecordingManifestRevision = origin,
	)
}
