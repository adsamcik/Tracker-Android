package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepFactRevisionIntegrityTest {

	@Test
	@Suppress("LongMethod")
	fun `retained LIVE_WAL checksum rejects every independently mutable retained field`() {
		val unsigned = liveWalFact()
		val canonical = unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
		assertTrue(StepFactRevisionIntegrity.hasValidLiveWalEffectChecksum(canonical))

		val mutations = linkedMapOf<String, (StepFactRevisionEntity) -> StepFactRevisionEntity>(
			"logicalFactId" to { it.copy(logicalFactId = "steps-session-facts:event-other") },
			"semanticRevision" to { it.copy(semanticRevision = 2L) },
			"mutationId" to { it.copy(mutationId = "other-mutation") },
			"stepIntervalId" to { it.copy(stepIntervalId = 7L) },
			"sourceEventId" to { it.copy(sourceEventId = "event-other") },
			"sourceAdmissionOrdinal" to { it.copy(sourceAdmissionOrdinal = 2L) },
			"originIdentity" to { it.copy(originIdentity = "event-other") },
			"writerProjectionId" to { it.copy(writerProjectionId = "other-writer") },
			"writerProjectionVersion" to { it.copy(writerProjectionVersion = 2) },
			"writerBindingGeneration" to { it.copy(writerBindingGeneration = 2L) },
			"intervalStartTimeMs" to { it.copy(intervalStartTimeMs = 1_001L) },
			"intervalEndTimeMs" to { it.copy(intervalEndTimeMs = 2_001L) },
			"intervalStartElapsedRealtimeNanos" to {
				it.copy(intervalStartElapsedRealtimeNanos = 10_001L)
			},
			"intervalEndElapsedRealtimeNanos" to {
				it.copy(intervalEndElapsedRealtimeNanos = 20_001L)
			},
			"clockDomainId" to { it.copy(clockDomainId = "other-clock") },
			"bootClockDomainId" to { it.copy(bootClockDomainId = "other-boot") },
			"cumulativeStepCountStart" to { it.copy(cumulativeStepCountStart = 99L) },
			"cumulativeStepCountEnd" to { it.copy(cumulativeStepCountEnd = 113L) },
			"wallTimeUncertaintyMs" to { it.copy(wallTimeUncertaintyMs = 26L) },
			"coverageKind" to { it.copy(coverageKind = StepFactRevisionEntity.COVERAGE_PARTIAL) },
			"effectiveStepCount" to { it.copy(effectiveStepCount = 13L) },
			"logicalTrackingId" to { it.copy(logicalTrackingId = "logical-other") },
			"serviceRunId" to { it.copy(serviceRunId = "run-other") },
			"manifestRevision" to { it.copy(manifestRevision = 2L) },
			"sourcePolicyRevision" to { it.copy(sourcePolicyRevision = 2L) },
			"captureConsentEpoch" to { it.copy(captureConsentEpoch = 2L) },
			"collectedDataEpoch" to { it.copy(collectedDataEpoch = 2L) },
			"appliedAtMs" to { it.copy(appliedAtMs = 2_001L) },
		)

		mutations.forEach { (field, mutate) ->
			assertFalse(field, StepFactRevisionIntegrity.hasValidLiveWalEffectChecksum(mutate(canonical)))
		}
	}

	private fun liveWalFact() = StepFactRevisionEntity(
		logicalFactId = "steps-session-facts:event-1",
		semanticRevision = 1L,
		mutationId = "steps-session-facts:event-1:1:UPSERT",
		stepIntervalId = null,
		sourceEventId = "event-1",
		sourceAdmissionOrdinal = 1L,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = "event-1",
		writerProjectionId = "steps-session-facts",
		writerProjectionVersion = 1,
		writerBindingGeneration = 1L,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 2_000L,
		intervalStartElapsedRealtimeNanos = 10_000L,
		intervalEndElapsedRealtimeNanos = 20_000L,
		clockDomainId = "boot-1",
		bootClockDomainId = "boot-1",
		cumulativeStepCountStart = 100L,
		cumulativeStepCountEnd = 112L,
		wallTimeUncertaintyMs = 25L,
		coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
		effectiveStepCount = 12L,
		logicalTrackingId = "logical-1",
		serviceRunId = "run-1",
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 1L,
		scopeDeletionGeneration = 0L,
		effectChecksum = "pending-live-wal-effect",
		appliedAtMs = 2_000L,
	)
}
