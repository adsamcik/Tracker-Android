package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedStepsFactTest {
	@Test
	fun `portable counts preserve covered zero and every nonnumeric state`() {
		listOf(0L, 15L, Long.MAX_VALUE).forEach { count ->
			assertTrue(StepFactRevisionIntegrity.hasValidPortableImportFact(sign(portableFact(count))))
		}
		listOf("BASELINE", "RESET_GAP", "PARTIAL").forEach { coverage ->
			val nonnumeric = portableFact().copy(coverageKind = coverage, effectiveStepCount = null)
			assertTrue(StepFactRevisionIntegrity.hasValidPortableImportFact(sign(nonnumeric)))
			assertThrows(IllegalArgumentException::class.java) { nonnumeric.copy(effectiveStepCount = 0L) }
		}
		assertThrows(IllegalArgumentException::class.java) { portableFact().copy(effectiveStepCount = null) }
	}

	@Test
	fun `portable facts reject fabricated operational fields`() {
		val invalid = listOf<(StepFactRevisionEntity) -> StepFactRevisionEntity>(
			{ it.copy(stepIntervalId = 1L) },
			{ it.copy(sourceEventId = "event") },
			{ it.copy(sourceAdmissionOrdinal = 1L) },
			{ it.copy(intervalStartElapsedRealtimeNanos = 0L) },
			{ it.copy(intervalEndElapsedRealtimeNanos = 0L) },
			{ it.copy(clockDomainId = "boot") },
			{ it.copy(bootClockDomainId = "boot") },
			{ it.copy(cumulativeStepCountStart = 0L) },
			{ it.copy(cumulativeStepCountEnd = 1L) },
		)
		invalid.forEach { mutation ->
			assertThrows(IllegalArgumentException::class.java) { mutation(portableFact()) }
		}
		assertThrows(IllegalArgumentException::class.java) {
			portableFact().copy(originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL)
		}
	}

	@Test
	fun `portable retained checksum detects every independent retained mutation`() {
		val fact = sign(portableFact())
		val mutations = listOf<(StepFactRevisionEntity) -> StepFactRevisionEntity>(
			{ it.copy(logicalFactId = opaque('4')) },
			{ it.copy(semanticRevision = 2L) },
			{ it.copy(mutationId = "changed") },
			{ it.copy(originIdentity = opaque('4')) },
			{ it.copy(writerProjectionId = "other-writer") },
			{ it.copy(writerProjectionVersion = 2) },
			{ it.copy(writerBindingGeneration = 2L) },
			{ it.copy(intervalStartTimeMs = 11L) },
			{ it.copy(intervalEndTimeMs = 21L) },
			{ it.copy(wallTimeUncertaintyMs = 1L) },
			{ it.copy(coverageKind = "PARTIAL", effectiveStepCount = null) },
			{ it.copy(effectiveStepCount = 2L) },
			{ it.copy(logicalTrackingId = opaque('4')) },
			{ it.copy(serviceRunId = opaque('4')) },
			{ it.copy(manifestRevision = 2L) },
			{ it.copy(sourcePolicyRevision = 2L) },
			{ it.copy(captureConsentEpoch = 1L) },
			{ it.copy(collectedDataEpoch = 2L) },
			{ it.copy(appliedAtMs = 31L) },
			{ it.copy(effectChecksum = "tampered") },
		)
		mutations.forEachIndexed { index, mutation ->
			assertFalse("mutation $index", StepFactRevisionIntegrity.hasValidPortableImportFact(mutation(fact)))
		}
		assertFalse(StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact))
		assertFalse(StepFactRevisionIntegrity.hasValidLiveWalEffectChecksum(fact))
	}

	@Test
	fun `resigning cannot create a different portable writer shape`() {
		listOf(
			portableFact().copy(logicalTrackingId = "local-session"),
			portableFact().copy(serviceRunId = "local-run"),
			portableFact().copy(logicalFactId = "local-fact"),
			portableFact().copy(originIdentity = opaque('4')),
			portableFact().copy(semanticRevision = 2L),
			portableFact().copy(writerProjectionId = "different"),
		).forEach { fact ->
			assertFalse(StepFactRevisionIntegrity.hasValidPortableImportFact(sign(fact)))
		}
	}

	private fun sign(fact: StepFactRevisionEntity): StepFactRevisionEntity = fact.copy(
		effectChecksum = StepFactRevisionIntegrity.portableImportEffectChecksum(fact),
	)

	private fun opaque(character: Char): String = "sha256:${character.toString().repeat(64)}"

	private fun portableFact(count: Long = 1L) = StepFactRevisionEntity(
		logicalFactId = opaque('3'),
		semanticRevision = 1L,
		mutationId = StepFactRevisionIntegrity.portableImportMutationId(opaque('3')),
		stepIntervalId = null,
		sourceEventId = null,
		sourceAdmissionOrdinal = null,
		originKind = StepFactRevisionEntity.ORIGIN_PORTABLE_IMPORT,
		originIdentity = opaque('3'),
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = 1L,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = 10L,
		intervalEndTimeMs = 20L,
		intervalStartElapsedRealtimeNanos = null,
		intervalEndElapsedRealtimeNanos = null,
		clockDomainId = null,
		bootClockDomainId = null,
		cumulativeStepCountStart = null,
		cumulativeStepCountEnd = null,
		wallTimeUncertaintyMs = 0L,
		coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
		effectiveStepCount = count,
		logicalTrackingId = opaque('1'),
		serviceRunId = opaque('2'),
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 0L,
		collectedDataEpoch = 1L,
		scopeDeletionGeneration = 0L,
		effectChecksum = "unsigned",
		appliedAtMs = 30L,
	)
}
