package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class StepFactRevisionIntegrityTest {
	@Test
	fun `existing LIVE_WAL retained checksum stays byte compatible`() {
		// Independently reconstructed from the pre-import-origin retained field order and domain.
		assertEquals(
			"94558832903fcae736e5c29d3d5735e44c1121c30e7ad1ad8dadcd53e46156b4",
			StepFactRevisionIntegrity.liveWalEffectChecksum(liveWalFact()),
		)
	}

	@Test
	fun `Steps capture authority rejects every missing or mismatched immutable relation`() {
		val policy = stepsPolicy()
		val consent = stepsConsent()
		val binding = stepsBinding()
		assertTrue(
			StepFactRevisionIntegrity.hasValidStepsCaptureAuthority(
				policy,
				consent,
				SOURCE_POLICY_REVISION,
				binding,
			),
		)

		val invalidAuthorities = listOf(
			Triple(null, consent, binding),
			Triple(policy.copy(policyRevision = SOURCE_POLICY_REVISION + 1L), consent, binding),
			Triple(policy.copy(sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE), consent, binding),
			Triple(policy.copy(enabled = false), consent, binding),
			Triple(policy.copy(capturePersistenceEligible = false), consent, binding),
			Triple(policy.copy(captureConsentEpoch = CAPTURE_CONSENT_EPOCH + 1L), consent, binding),
			Triple(policy.copy(qosCode = CAPTURE_QOS + 1), consent, binding),
			Triple(policy, consent, binding.copy(qosCode = 0)),
			Triple(policy, null, binding),
			Triple(policy, consent.copy(eligible = false), binding),
			Triple(policy, consent.copy(persistenceEligible = false), binding),
			Triple(policy, consent.copy(epoch = CAPTURE_CONSENT_EPOCH + 1L), binding),
			Triple(policy, consent.copy(purpose = SessionManifestPurposeCode.CONTROL), binding),
			Triple(policy, consent.copy(policyRevision = SOURCE_POLICY_REVISION + 1L), binding),
		)
		invalidAuthorities.forEachIndexed { index, (candidatePolicy, candidateConsent, candidateBinding) ->
			assertFalse(
				"invalid authority $index",
				StepFactRevisionIntegrity.hasValidStepsCaptureAuthority(
					candidatePolicy,
					candidateConsent,
					SOURCE_POLICY_REVISION,
					candidateBinding,
				),
			)
		}
	}

	@Test
	fun `local delete checksum verifies deterministic writer content and exact scope linkage`() {
		val expectedScope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = "logical-1",
			serviceRunId = "run-1",
		)
		val canonical = localDeleteRetraction(expectedScope)
		assertTrue(
			StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(canonical, expectedScope),
		)

		assertFalse(
			StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
				canonical.copy(mutationId = "local-delete:tampered"),
				expectedScope,
			),
		)
		assertFalse(
			StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
				canonical.copy(effectChecksum = "tampered-effect"),
				expectedScope,
			),
		)
		assertFalse(
			StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
				canonical.copy(originIdentity = "tampered-origin"),
				expectedScope,
			),
		)

		val otherScope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = "logical-other",
			serviceRunId = "run-other",
		)
		val selfConsistentOtherScope = localDeleteRetraction(otherScope)
		assertFalse(
			StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
				selfConsistentOtherScope,
				expectedScope,
			),
		)
	}

	@Test
	@Suppress("LongMethod")
	fun `retained LIVE_WAL checksum rejects every independently mutable retained field`() {
		val unsigned = liveWalFact()
		val canonical = signLiveWal(unsigned)
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
				it.copy(intervalEndElapsedRealtimeNanos = 11_000_000_001L)
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

	@Test
	fun `canonical LIVE_WAL validator accepts every production boundary shape`() {
		val canonical = liveWalFact()
		val facts = listOf(
			canonical.copy(
				coverageKind = StepFactRevisionEntity.COVERAGE_BASELINE,
				intervalStartTimeMs = canonical.intervalEndTimeMs,
				intervalStartElapsedRealtimeNanos = canonical.intervalEndElapsedRealtimeNanos,
				cumulativeStepCountEnd = canonical.cumulativeStepCountStart,
				effectiveStepCount = 0L,
			),
			canonical,
			canonical.copy(
				coverageKind = StepFactRevisionEntity.COVERAGE_RESET_GAP,
				cumulativeStepCountEnd = 3L,
				effectiveStepCount = 0L,
			),
			canonical.copy(
				writerBindingGeneration =
					SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION,
				coverageKind = StepFactRevisionEntity.COVERAGE_PARTIAL,
				cumulativeStepCountEnd = 3L,
				effectiveStepCount = 0L,
			),
		).map(::signLiveWal)

		facts.forEach { fact ->
			assertTrue(fact.coverageKind, StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact))
		}
	}

	@Test
	fun `canonical LIVE_WAL validator rejects fabricated boundary durations`() {
		val canonical = liveWalFact()
		val nonZeroBaseline = signLiveWal(
			canonical.copy(
				coverageKind = StepFactRevisionEntity.COVERAGE_BASELINE,
				cumulativeStepCountEnd = canonical.cumulativeStepCountStart,
				effectiveStepCount = 0L,
			),
		)
		val zeroDurationCovered = signLiveWal(
			canonical.copy(
				intervalStartTimeMs = canonical.intervalEndTimeMs,
				intervalStartElapsedRealtimeNanos = canonical.intervalEndElapsedRealtimeNanos,
			),
		)
		val zeroDurationReset = signLiveWal(
			zeroDurationCovered.copy(
				coverageKind = StepFactRevisionEntity.COVERAGE_RESET_GAP,
				cumulativeStepCountEnd = 3L,
				effectiveStepCount = 0L,
			),
		)

		assertFalse(StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(nonZeroBaseline))
		assertFalse(StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(zeroDurationCovered))
		assertFalse(StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(zeroDurationReset))
	}

	@Test
	fun `run timeline follows admission and elapsed authority instead of wall ordering`() {
		val first = signLiveWal(liveWalFact())
		val second = signLiveWal(
			liveWalFact().withEventIdentity("event-2", 2L).copy(
				intervalStartTimeMs = 500L,
				intervalEndTimeMs = 1_500L,
				intervalStartElapsedRealtimeNanos = 11_000_000_000L,
				intervalEndElapsedRealtimeNanos = 12_000_000_000L,
				cumulativeStepCountStart = 112L,
				cumulativeStepCountEnd = 115L,
				effectiveStepCount = 3L,
				appliedAtMs = 1_500L,
			),
		)

		assertTrue(
			StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
				listOf(second, first),
				"logical-1",
				"run-1",
			),
		)
	}

	@Test
	@Suppress("LongMethod")
	fun `run timeline rejects overlap gap duplicate admission and counter discontinuity`() {
		val first = signLiveWal(liveWalFact())
		val successor = liveWalFact().withEventIdentity("event-2", 2L).copy(
			intervalStartTimeMs = 2_000L,
			intervalEndTimeMs = 3_000L,
			intervalStartElapsedRealtimeNanos = 11_000_000_000L,
			intervalEndElapsedRealtimeNanos = 12_000_000_000L,
			cumulativeStepCountStart = 112L,
			cumulativeStepCountEnd = 115L,
			effectiveStepCount = 3L,
			appliedAtMs = 3_000L,
		)
		val validSecond = signLiveWal(successor)
		val overlap = signLiveWal(
			successor.copy(
				intervalStartTimeMs = 1_500L,
				intervalStartElapsedRealtimeNanos = 10_500_000_000L,
			),
		)
		val duplicateAdmission = signLiveWal(successor.copy(sourceAdmissionOrdinal = 1L))
		val gap = signLiveWal(
			successor.copy(
				intervalStartTimeMs = 3_000L,
				intervalEndTimeMs = 4_000L,
				intervalStartElapsedRealtimeNanos = 12_000_000_000L,
				intervalEndElapsedRealtimeNanos = 13_000_000_000L,
				appliedAtMs = 4_000L,
			),
		)
		val discontinuity = signLiveWal(
			successor.copy(
				cumulativeStepCountStart = 500L,
				cumulativeStepCountEnd = 503L,
			),
		)

		listOf(overlap, gap, duplicateAdmission, discontinuity).forEach { invalidSecond ->
			assertFalse(
				StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
					listOf(first, invalidSecond),
					"logical-1",
					"run-1",
				),
			)
		}
		assertTrue(
			StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
				listOf(first, validSecond),
				"logical-1",
				"run-1",
			),
		)
		val authorizationBaseline = signLiveWal(
			successor.copy(
				intervalStartTimeMs = 4_000L,
				intervalEndTimeMs = 4_000L,
				intervalStartElapsedRealtimeNanos = 13_000_000_000L,
				intervalEndElapsedRealtimeNanos = 13_000_000_000L,
				cumulativeStepCountStart = 500L,
				cumulativeStepCountEnd = 500L,
				effectiveStepCount = 0L,
				coverageKind = StepFactRevisionEntity.COVERAGE_BASELINE,
				appliedAtMs = 4_000L,
			),
		)
		assertTrue(
			StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
				listOf(first, authorizationBaseline),
				"logical-1",
				"run-1",
			),
		)
		val backwardsAuthorizationBaseline = signLiveWal(
			authorizationBaseline.copy(
				intervalStartTimeMs = 1_000L,
				intervalEndTimeMs = 1_000L,
				intervalStartElapsedRealtimeNanos = 9_000_000_000L,
				intervalEndElapsedRealtimeNanos = 9_000_000_000L,
				appliedAtMs = 1_000L,
			),
		)
		assertFalse(
			StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
				listOf(first, backwardsAuthorizationBaseline),
				"logical-1",
				"run-1",
			),
		)
	}

	@Test
	@Suppress("LongMethod")
	fun `canonical LIVE_WAL validator rejects independently re-signed non-writer semantics`() {
		val canonical = signLiveWal(liveWalFact())
		val mutations = linkedMapOf<String, (StepFactRevisionEntity) -> StepFactRevisionEntity>(
			"projection id" to { fact ->
				val logicalFactId = "other-writer:${fact.sourceEventId}"
				fact.copy(
					writerProjectionId = "other-writer",
					logicalFactId = logicalFactId,
					mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
				)
			},
			"projection version" to { it.copy(writerProjectionVersion = 2) },
			"semantic revision" to {
				it.copy(
					semanticRevision = 2L,
					mutationId = "${it.logicalFactId}:2:${StepFactRevisionEntity.OPERATION_UPSERT}",
				)
			},
			"logical fact identity" to {
				it.copy(
					logicalFactId = "other-logical-fact",
					mutationId = "other-logical-fact:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
				)
			},
			"mutation identity" to { it.copy(mutationId = "other-mutation") },
			"legacy interval link" to { it.copy(stepIntervalId = 7L) },
			"origin identity" to { it.copy(originIdentity = "other-origin") },
			"clock domain" to { it.copy(clockDomainId = "other-clock") },
			"projected wall interval" to { it.copy(intervalStartTimeMs = 1_001L) },
			"applied time" to { it.copy(appliedAtMs = 2_001L) },
			"baseline math" to {
				it.copy(
					coverageKind = StepFactRevisionEntity.COVERAGE_BASELINE,
					effectiveStepCount = 0L,
				)
			},
			"covered math" to { it.copy(effectiveStepCount = 11L) },
			"reset math" to {
				it.copy(
					coverageKind = StepFactRevisionEntity.COVERAGE_RESET_GAP,
					effectiveStepCount = 0L,
				)
			},
			"partial math" to {
				it.copy(
					coverageKind = StepFactRevisionEntity.COVERAGE_PARTIAL,
					effectiveStepCount = 1L,
				)
			},
		)

		mutations.forEach { (caseName, mutate) ->
			val resigned = signLiveWal(mutate(canonical))
			assertFalse(
				caseName,
				StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(resigned),
			)
		}
		assertFalse(
			"checksum",
			StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(
				canonical.copy(effectChecksum = "tampered-effect"),
			),
		)
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
		intervalStartElapsedRealtimeNanos = 10_000_000_000L,
		intervalEndElapsedRealtimeNanos = 11_000_000_000L,
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

	private fun stepsPolicy() = SourcePolicyEntity(
		policyRevision = SOURCE_POLICY_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = true,
		qosCode = CAPTURE_QOS,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 1L,
		effectiveWallTimeMs = 1L,
		changeReason = "TEST",
	)

	private fun stepsConsent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = CAPTURE_CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = true,
		policyRevision = SOURCE_POLICY_REVISION,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 1L,
		effectiveWallTimeMs = 1L,
		changeReason = "TEST",
	)

	private fun stepsBinding() = SessionManifestSourceEntity(
		logicalTrackingId = "logical-1",
		manifestRevision = 1L,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = CAPTURE_CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = CAPTURE_QOS,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	)

	private fun signLiveWal(fact: StepFactRevisionEntity): StepFactRevisionEntity = fact.copy(
		effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(fact),
	)

	private fun StepFactRevisionEntity.withEventIdentity(
		sourceEventId: String,
		admissionOrdinal: Long,
	): StepFactRevisionEntity {
		val logicalFactId = "$writerProjectionId:$sourceEventId"
		return copy(
			logicalFactId = logicalFactId,
			mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceEventId = sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			originIdentity = sourceEventId,
		)
	}

	private fun localDeleteRetraction(scopeIdentityDigest: String): StepFactRevisionEntity {
		val logicalFactId = "steps-session-facts:event-1"
		val semanticRevision = 2L
		val deletionGeneration = 3L
		val unsigned = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = semanticRevision,
			mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
				scopeIdentityDigest = scopeIdentityDigest,
				logicalFactId = logicalFactId,
				semanticRevision = semanticRevision,
				scopeDeletionGeneration = deletionGeneration,
			),
			stepIntervalId = null,
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = scopeIdentityDigest,
			writerProjectionId = "steps-session-facts",
			writerProjectionVersion = 1,
			writerBindingGeneration = 1L,
			operation = StepFactRevisionEntity.OPERATION_RETRACT,
			intervalStartTimeMs = null,
			intervalEndTimeMs = null,
			intervalStartElapsedRealtimeNanos = null,
			intervalEndElapsedRealtimeNanos = null,
			clockDomainId = null,
			bootClockDomainId = null,
			cumulativeStepCountStart = null,
			cumulativeStepCountEnd = null,
			wallTimeUncertaintyMs = null,
			coverageKind = null,
			effectiveStepCount = null,
			logicalTrackingId = null,
			serviceRunId = null,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			collectedDataEpoch = 5L,
			scopeDeletionGeneration = deletionGeneration,
			effectChecksum = "pending-local-delete-effect",
			appliedAtMs = 3_000L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
		)
	}

	private companion object {
		const val SOURCE_POLICY_REVISION = 1L
		const val CAPTURE_CONSENT_EPOCH = 1L
		const val CAPTURE_QOS = 1
	}
}
