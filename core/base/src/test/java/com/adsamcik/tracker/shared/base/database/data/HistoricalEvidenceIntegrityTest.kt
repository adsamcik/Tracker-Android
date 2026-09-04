package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalEvidenceIntegrityTest {
	@Test
	fun `service run timeline accepts elapsed ordering across a wall clock regression`() {
		val run = serviceRun()
		val manifests = manifests()

		assertTrue(SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests))
	}

	@Test
	fun `service run timeline rejects every broken run authority relation`() {
		val run = serviceRun()
		val canonical = manifests()
		val first = canonical.first()
		val successor = canonical.last()
		val invalid = listOf(
			"prepared revision" to canonical.map { manifest ->
				if (manifest === first) {
					manifest.copy(manifestRevision = 2L)
				} else {
					manifest
				}
			},
			"initial origin mode" to canonical.map { manifest -> manifest.copy(sessionMode = "AUTOMATIC") },
			"successor origin" to listOf(first, successor.copy(startOrigin = run.startOrigin)),
			"successor reason" to listOf(first, successor.copy(changeReason = "OTHER")),
			"revision gap" to listOf(first, successor.copy(manifestRevision = 5L)),
			"elapsed regression" to listOf(
				first,
				successor.copy(effectiveElapsedRealtimeNanos = first.effectiveElapsedRealtimeNanos - 1L),
			),
			"last desired plan" to listOf(first, successor.copy(acquisitionPlanRevision = 3L)),
			"rollout" to listOf(first, successor.copy(rolloutRevision = run.rolloutRevision + 1L)),
			"policy revision" to listOf(first, successor.copy(sourcePolicyRevision = 0L)),
		)

		invalid.forEach { (label, manifests) ->
			assertFalse(label, SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests))
		}
		listOf(
			"lease generation" to run.copy(leaseGeneration = 0L),
			"run revision" to run.copy(runRevision = 0L),
			"prepared intent revision" to run.copy(preparedIntentRevision = 0L),
			"start command generation" to run.copy(startCommandGeneration = 0L),
			"start delivery token" to run.copy(startDeliveryToken = null),
		).forEach { (label, invalidRun) ->
			assertFalse(label, SessionManifestIntegrity.hasValidServiceRunTimeline(invalidRun, canonical))
		}
		assertFalse(
			"unknown initial origin",
			SessionManifestIntegrity.hasValidServiceRunTimeline(
				run.copy(startOrigin = "POLICY_RECONCILIATION"),
				canonical.mapIndexed { index, manifest ->
					if (index == 0) {
						manifest.copy(startOrigin = "POLICY_RECONCILIATION")
					} else {
						manifest
					}
				},
			),
		)
	}

	@Test
	fun `logical manifest union uses exact revision authority across replacement runs`() {
		assertTrue(
			SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				listOf(listOf(3L, 4L), listOf(1L, 2L)),
			),
		)
		assertFalse(
			"missing revision",
			SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				listOf(listOf(1L), listOf(3L)),
			),
		)
		assertFalse(
			"duplicate revision",
			SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				listOf(listOf(1L, 2L), listOf(2L, 3L)),
			),
		)
		assertFalse(
			"interleaved run slices",
			SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				listOf(listOf(1L, 3L), listOf(2L)),
			),
		)
		assertTrue(
			"day-local slice may begin after revision one",
			SessionManifestIntegrity.hasValidLogicalManifestRevisionSliceUnion(
				listOf(listOf(8L, 9L), listOf(10L)),
			),
		)
		assertFalse(
			"day-local slice still rejects a gap",
			SessionManifestIntegrity.hasValidLogicalManifestRevisionSliceUnion(
				listOf(listOf(8L), listOf(10L)),
			),
		)
	}

	@Test
	fun `Steps completeness requires unique generations and increasing global high waters`() {
		val first = completeness(generation = 1L, ordinal = 100L)
		val second = completeness(generation = 2L, ordinal = 200L)
		assertTrue(
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				listOf(second, first),
				LOGICAL_ID,
				RUN_ID,
			),
		)

		assertFalse(
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				listOf(first, second.copy(sourceInstanceId = "steps-duplicate", registrationGeneration = 1L)),
				LOGICAL_ID,
				RUN_ID,
			),
		)
		assertFalse(
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				listOf(first, second.copy(lastAdmissionOrdinal = 99L)),
				LOGICAL_ID,
				RUN_ID,
			),
		)
	}

	@Test
	fun `Steps completeness accepts the provider's first sequence and rejects negative sequence`() {
		val firstProviderEvent = completeness(generation = 1L, ordinal = 1L).copy(
			lastSourceSequence = 0L,
		)

		assertTrue(
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				listOf(firstProviderEvent),
				LOGICAL_ID,
				RUN_ID,
			),
		)
		assertFalse(
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				listOf(firstProviderEvent.copy(lastSourceSequence = -1L)),
				LOGICAL_ID,
				RUN_ID,
			),
		)
	}

	@Test
	fun `Steps synthetic settlement is explicit and never a registered provider generation`() {
		val unavailable = completeness(generation = 0L, ordinal = null).copy(
			sourceInstanceId = "unavailable-steps",
			lastSourceSequence = null,
			providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
			stopStatus = "PROVIDER_FAILED",
		)

		assertTrue(
			StepsSessionCompletenessIntegrity.hasValidTimeline(
				listOf(unavailable),
				LOGICAL_ID,
				RUN_ID,
			),
		)
		assertFalse(
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				listOf(unavailable),
				LOGICAL_ID,
				RUN_ID,
			),
		)
		assertFalse(
			StepsSessionCompletenessIntegrity.hasValidTimeline(
				listOf(unavailable.copy(registrationGeneration = 1L)),
				LOGICAL_ID,
				RUN_ID,
			),
		)
	}

	private fun serviceRun() = SourceServiceRunEntity(
		serviceRunId = RUN_ID,
		logicalTrackingId = LOGICAL_ID,
		state = "FINALIZED",
		desiredPlanRevision = 2L,
		rolloutRevision = 7L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1_000L,
		startedElapsedNanos = 10L,
		completedAtMs = 2_000L,
		completionReason = "STOPPED",
		bootId = "boot-1",
		leaseGeneration = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		runRevision = 1L,
		startDeliveryToken = "delivery-1",
		startCommandGeneration = 1L,
		preparedManifestRevision = 3L,
		preparedIntentRevision = 1L,
	)

	private fun manifests() = listOf(
		manifest(
			revision = 3L,
			planRevision = 1L,
			origin = "MANUAL_FOREGROUND_START",
			wallTimeMs = 1_000L,
			elapsedNanos = 10L,
			changeReason = "SESSION_START",
		),
		manifest(
			revision = 4L,
			planRevision = 2L,
			origin = "POLICY_RECONCILIATION",
			wallTimeMs = 900L,
			elapsedNanos = 20L,
			changeReason = "POLICY_RECONCILIATION",
		),
	)

	private fun manifest(
		revision: Long,
		planRevision: Long,
		origin: String,
		wallTimeMs: Long,
		elapsedNanos: Long,
		changeReason: String,
	) = SessionManifestVersionEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = revision,
		serviceRunId = RUN_ID,
		sessionMode = "MANUAL",
		sourcePolicyRevision = revision,
		acquisitionPlanRevision = planRevision,
		rolloutRevision = 7L,
		startOrigin = origin,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = elapsedNanos,
		effectiveWallTimeMs = wallTimeMs,
		zoneId = "UTC",
		automationEpoch = null,
		changeReason = changeReason,
		manifestChecksum = "checksum-$revision",
	)

	private fun completeness(
		generation: Long,
		ordinal: Long?,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		sourceInstanceId = "steps-$generation",
		registrationGeneration = generation,
		lastAdmissionOrdinal = ordinal,
		lastSourceSequence = ordinal,
		appDrainComplete = true,
		providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
		stopStatus = "COMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 2_000L,
	)

	private companion object {
		const val LOGICAL_ID = "logical-1"
		const val RUN_ID = "run-1"
	}
}
