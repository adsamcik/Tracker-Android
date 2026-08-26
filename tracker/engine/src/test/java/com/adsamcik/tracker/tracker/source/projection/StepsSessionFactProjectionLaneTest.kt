package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsSessionFactProjectionLaneTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(legacyStepsOwner())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `canonical lane maps every Steps boundary and mutates evidence once per batch`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val events = listOf(
			stepEvent(
				ordinal = 1L,
				boundary = StepBoundaryKind.BASELINE,
				firstCount = 100L,
				lastCount = 100L,
				delta = 0L,
			),
			stepEvent(
				ordinal = 2L,
				boundary = StepBoundaryKind.COVERED,
				firstCount = 100L,
				lastCount = 105L,
				delta = 5L,
			),
			stepEvent(
				ordinal = 3L,
				boundary = StepBoundaryKind.COUNTER_RESET,
				firstCount = 105L,
				lastCount = 3L,
				delta = 0L,
			),
			stepEvent(
				ordinal = 4L,
				boundary = StepBoundaryKind.LEGACY_AMBIGUOUS,
				firstCount = 3L,
				lastCount = 3L,
				delta = 0L,
			),
		)
		val ingress = sourceIngress(afterOrdinal = 0L, throughOrdinal = 4L, events = events)
		val subject = StepsSessionFactProjectionLane(database, ingress)

		subject.drainThrough(4L) shouldBe StepsSessionFactDrainResult.Complete(
			lastCompletedOrdinal = 4L,
			factsInserted = 4,
			eventsValidated = 4,
		)

		val expected = listOf(
			StepFactRevisionEntity.COVERAGE_BASELINE to 0L,
			StepFactRevisionEntity.COVERAGE_COVERED to 5L,
			StepFactRevisionEntity.COVERAGE_RESET_GAP to 0L,
			StepFactRevisionEntity.COVERAGE_PARTIAL to 0L,
		)
		expected.forEachIndexed { index, (coverage, count) ->
			val ordinal = (index + 1).toLong()
			val fact = requireNotNull(fact(ordinal))
			fact.coverageKind shouldBe coverage
			fact.effectiveStepCount shouldBe count
			fact.stepIntervalId shouldBe null
			fact.writerBindingGeneration shouldBe
				StepsSessionFactProjectionLane.BINDING_GENERATION
		}
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 4L
		database.stepIntervalDao().getAllBetween(0L, Long.MAX_VALUE).shouldBeEmpty()
	}

	@Test
	fun `shadow validates and advances only its cursor without publishing product evidence`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW)
		val events = listOf(stepEvent(1L), stepEvent(2L))
		val ingress = sourceIngress(afterOrdinal = 0L, throughOrdinal = 2L, events = events)

		StepsSessionFactProjectionLane(database, ingress).drainThrough(2L) shouldBe
			StepsSessionFactDrainResult.Complete(2L, factsInserted = 0, eventsValidated = 2)

		database.stepFactRevisionDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L
	}

	@Test
	fun `canonical lane rejects an event bound to the legacy manifest writer`() = runTest {
		installLane(
			stage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			manifestOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
		)
		val ingress = sourceIngress(0L, 1L, listOf(stepEvent(1L)))

		StepsSessionFactProjectionLane(database, ingress).drainThrough(1L) shouldBe
			StepsSessionFactDrainResult.Failed(
				lastCompletedOrdinal = 0L,
				failedOrdinal = 1L,
				failureCode = "STEPS_MANIFEST_WRITER_NOT_ACTIVE",
				terminal = true,
			)

		database.stepFactRevisionDao().countAll() shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
	}

	@Test
	fun `stale epoch and control only Steps are advanced but never stored`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val stale = stepEvent(1L, collectedDataEpoch = COLLECTED_DATA_EPOCH - 1L)
		val control = stepEvent(2L).copy(
			evidence = stepEvent(2L).evidence.copy(
				logicalTrackingId = null,
				serviceRunId = null,
				registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				registrationEligibilityFingerprint = "control-only",
				sourcePolicyRevision = null,
				captureConsentEpoch = null,
				sessionManifestRevision = null,
				lifecycleLeaseGeneration = null,
			),
		)
		val ingress = sourceIngress(0L, 2L, listOf(stale, control))

		StepsSessionFactProjectionLane(database, ingress).drainThrough(2L) shouldBe
			StepsSessionFactDrainResult.Complete(2L, factsInserted = 0, eventsValidated = 2)

		database.stepFactRevisionDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L
	}

	@Test
	fun `retention rejects a fact whose product time is below the retained floor`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = COLLECTED_DATA_EPOCH,
			retainedFromMs = 10_000L,
			updatedAtMs = 20_000L,
		) shouldBe 1
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		val event = stepEvent(
			ordinal = 1L,
			wallTimeMs = 9_999L,
			acquiredAtMs = 10_001L,
		)

		StepsSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(event)),
		).drainThrough(1L) shouldBe
			StepsSessionFactDrainResult.Complete(1L, factsInserted = 0, eventsValidated = 1)

		database.stepFactRevisionDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe evidenceRevision
		activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
	}

	@Test
	fun `retention clears an obsolete terminal corrupt row and releases its cursor`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		insertRawStepsRow(
			ordinal = 1L,
			wallTimeMs = 10_001L,
			acquiredAtMs = 10_001L,
			corruptIntegrity = true,
		)
		val ingress = mockk<DurableSourceIngress>()
		coEvery {
			ingress.committedSourceBatch(SourceKind.STEPS, 0L, 1L, 64)
		} throws CorruptSourceEventException(
			admissionOrdinal = 1L,
			sourceKind = SourceKind.STEPS.stableCode,
			failureCode = "RAW_PAYLOAD_INTEGRITY",
		)
		val subject = StepsSessionFactProjectionLane(database, ingress)

		subject.drainThrough(1L) shouldBe StepsSessionFactDrainResult.Failed(
			lastCompletedOrdinal = 0L,
			failedOrdinal = 1L,
			failureCode = "RAW_PAYLOAD_INTEGRITY",
			terminal = true,
		)

		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = COLLECTED_DATA_EPOCH,
			retainedFromMs = 20_000L,
			updatedAtMs = 20_000L,
		) shouldBe 1
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		subject.drainThrough(1L) shouldBe
			StepsSessionFactDrainResult.Complete(1L, factsInserted = 0, eventsValidated = 0)

		database.sourceProjectionStateDao().failure(
			StepsSessionFactProjectionLane.WRITER_ID,
			StepsSessionFactProjectionLane.WRITER_VERSION,
			1L,
		) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe evidenceRevision
		database.stepFactRevisionDao().countAll() shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
		coVerify(exactly = 2) {
			ingress.committedSourceBatch(SourceKind.STEPS, 0L, 1L, 64)
		}
	}

	@Test
	fun `negative product wall time is terminal poison and is not retried`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = COLLECTED_DATA_EPOCH,
			retainedFromMs = 10_000L,
			updatedAtMs = 20_000L,
		) shouldBe 1
		insertRawStepsRow(ordinal = 1L, wallTimeMs = -1L, acquiredAtMs = 10_001L)
		val ingress = sourceIngress(0L, 1L, listOf(stepEvent(1L, wallTimeMs = -1L)))
		val subject = StepsSessionFactProjectionLane(database, ingress)
		val expected = StepsSessionFactDrainResult.Failed(
			lastCompletedOrdinal = 0L,
			failedOrdinal = 1L,
			failureCode = "STEPS_WALL_TIME_NEGATIVE",
			terminal = true,
		)

		subject.drainThrough(1L) shouldBe expected
		subject.drainThrough(1L) shouldBe expected

		database.stepFactRevisionDao().countAll() shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
		coVerify(exactly = 1) {
			ingress.committedSourceBatch(SourceKind.STEPS, 0L, 1L, 64)
		}
	}

	@Test
	fun `terminal poison commits the valid prefix before blocking its own ordinal`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val poison = stepEvent(
			ordinal = 2L,
			firstCount = 100L,
			lastCount = 102L,
			delta = 1L,
		)
		val ingress = sourceIngress(0L, 3L, listOf(stepEvent(1L), poison, stepEvent(3L)))

		StepsSessionFactProjectionLane(database, ingress).drainThrough(3L) shouldBe
			StepsSessionFactDrainResult.Failed(
				lastCompletedOrdinal = 1L,
				failedOrdinal = 2L,
				failureCode = "STEPS_COVERED_DELTA_INVALID",
				terminal = true,
			)

		requireNotNull(fact(1L)).effectiveStepCount shouldBe 2L
		fact(2L) shouldBe null
		fact(3L) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
	}

	@Test
	fun `exact receipt replay advances cursor without a second effective mutation`() = runTest {
		val event = stepEvent(1L)
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val firstIngress = sourceIngress(0L, 1L, listOf(event))
		StepsSessionFactProjectionLane(database, firstIngress).drainThrough(1L)
		val original = requireNotNull(fact(1L))
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		database.sourceProjectionStateDao().deleteAllProductLanes()
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val replayIngress = sourceIngress(0L, 1L, listOf(event))

		StepsSessionFactProjectionLane(database, replayIngress).drainThrough(1L) shouldBe
			StepsSessionFactDrainResult.Complete(1L, factsInserted = 0, eventsValidated = 1)

		database.stepFactRevisionDao().countAll() shouldBe 1L
		fact(1L) shouldBe original
		database.sourceEvidenceStateDao().get()?.revision shouldBe evidenceRevision
		activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
	}

	@Test
	fun `each receipt identity collision rolls back then durably blocks its exact ordinal`() = runTest {
		val event = stepEvent(1L)
		insertRawStepsRow(ordinal = 1L, wallTimeMs = 10_001L, acquiredAtMs = 10_001L)
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		StepsSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(event)),
		).drainThrough(1L)
		val exact = requireNotNull(fact(1L))
		clearLaneFactsAndFailures()

		val cases = listOf(
			"FACT_REVISION" to exact.copy(
				mutationId = "foreign-mutation",
				sourceEventId = "foreign-event",
				sourceAdmissionOrdinal = 99L,
				originIdentity = "foreign-event",
				effectChecksum = "foreign-fact-revision",
			),
			"MUTATION" to exact.copy(
				logicalFactId = "foreign-fact",
				sourceEventId = "foreign-event",
				sourceAdmissionOrdinal = 99L,
				originIdentity = "foreign-event",
				effectChecksum = "foreign-mutation",
			),
			"WRITER_ADMISSION" to exact.copy(
				logicalFactId = "foreign-fact",
				mutationId = "foreign-mutation",
				sourceEventId = "foreign-event",
				originIdentity = "foreign-event",
				effectChecksum = "foreign-admission",
			),
		)

		cases.forEach { (collisionKind, foreign) ->
			installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
			database.stepFactRevisionDao().insert(foreign) shouldBe 1L
			val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
			val ingress = sourceIngress(0L, 1L, listOf(event))
			val subject = StepsSessionFactProjectionLane(database, ingress)

			val expected = StepsSessionFactDrainResult.Failed(
				lastCompletedOrdinal = 0L,
				failedOrdinal = 1L,
				failureCode = "STEPS_IDENTITY_COLLISION_$collisionKind",
				terminal = true,
			)
			subject.drainThrough(1L) shouldBe expected
			subject.drainThrough(1L) shouldBe expected

			database.stepFactRevisionDao().countAll() shouldBe 1L
			database.sourceEvidenceStateDao().get()?.revision shouldBe evidenceRevision
			activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
			database.sourceProjectionStateDao().failure(
				StepsSessionFactProjectionLane.WRITER_ID,
				StepsSessionFactProjectionLane.WRITER_VERSION,
				1L,
			)?.also { failure ->
				failure.terminal shouldBe true
				failure.failureCode shouldBe "STEPS_IDENTITY_COLLISION_$collisionKind"
			}
			coVerify(exactly = 1) {
				ingress.committedSourceBatch(SourceKind.STEPS, 0L, 1L, 64)
			}

			clearLaneFactsAndFailures()
		}
	}

	@Test
	fun `empty source page advances across unrelated global ordinals without evidence writes`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val ingress = sourceIngress(0L, 7L, emptyList())

		StepsSessionFactProjectionLane(database, ingress).drainThrough(7L) shouldBe
			StepsSessionFactDrainResult.Complete(7L, factsInserted = 0, eventsValidated = 0)

		activeLane()?.contiguousAdmissionOrdinal shouldBe 7L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		database.stepFactRevisionDao().countAll() shouldBe 0L
	}

	@Test
	fun `capture cutoff bounds the drain and a foreign writer binding stays inert`() = runTest {
		installLane(
			stage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			cutoffOrdinal = 2L,
		)
		val ingress = sourceIngress(0L, 2L, listOf(stepEvent(2L)))

		StepsSessionFactProjectionLane(database, ingress).drainThrough(9L) shouldBe
			StepsSessionFactDrainResult.Complete(2L, factsInserted = 0, eventsValidated = 1)
		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L

		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().installProductLane(
			productLane(
				stage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				projectionId = "foreign-steps-writer",
			),
		)
		val foreignIngress = mockk<DurableSourceIngress>()
		StepsSessionFactProjectionLane(database, foreignIngress).drainThrough(9L) shouldBe
			StepsSessionFactDrainResult.Inactive
		coVerify(exactly = 0) {
			foreignIngress.committedSourceBatch(any(), any(), any(), any())
		}
	}

	private fun sourceIngress(
		afterOrdinal: Long,
		throughOrdinal: Long,
		events: List<AdmittedSourceEvent<StepCounterWindowPayload>>,
	): DurableSourceIngress = mockk<DurableSourceIngress>().also { ingress ->
		coEvery {
			ingress.committedSourceBatch(
				SourceKind.STEPS,
				afterOrdinal,
				throughOrdinal,
				64,
			)
		} returns events
	}

	private suspend fun installLane(
		stage: String,
		cutoffOrdinal: Long? = null,
		manifestOwner: String = if (stage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		} else {
			SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		},
	) {
		if (stage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = requireNotNull(database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			))
			if (owner.owner == SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL) {
				check(database.sourceDestinationOwnerDao().compareAndSetOwner(
					sourceKind = owner.sourceKind,
					destination = owner.destination,
					expectedOwner = owner.owner,
					expectedOwnerGeneration = owner.ownerGeneration,
					newOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
					newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = 1_000L,
				) == 1)
			}
		}
		installManifestBinding(manifestOwner)
		database.sourceProjectionStateDao().installProductLane(
			productLane(stage = stage, cutoffOrdinal = cutoffOrdinal),
		)
	}

	private suspend fun installManifestBinding(writerOwner: String) {
		if (database.sourceSessionDao().manifest(LOGICAL_TRACKING_ID, MANIFEST_REVISION) != null) return
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		))
		val isCandidate = writerOwner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = SourceKind.STEPS.stableCode,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = 4L,
			persistenceEligible = true,
			qosCode = 0,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = writerOwner,
			writerOwnerGeneration = if (isCandidate) {
				owner.ownerGeneration
			} else {
				SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
			},
			writerProjectionId = StepsSessionFactProjectionLane.WRITER_ID.takeIf { isCandidate },
			writerProjectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION.takeIf { isCandidate },
			writerBindingGeneration = StepsSessionFactProjectionLane.BINDING_GENERATION.takeIf { isCandidate },
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = 3L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 2L,
			startOrigin = "MANUAL_UI",
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = 0L,
			effectiveWallTimeMs = 10_000L,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun legacyStepsOwner() = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
		ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
		updatedAtMs = 0L,
	)

	private fun productLane(
		stage: String,
		cutoffOrdinal: Long? = null,
		projectionId: String = StepsSessionFactProjectionLane.WRITER_ID,
	) = SourceProductProjectionLaneEntity(
		sourceKind = SourceKind.STEPS.stableCode,
		bindingGeneration = StepsSessionFactProjectionLane.BINDING_GENERATION,
		projectionId = projectionId,
		projectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
		captureModeMask = StepsSessionFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
		productStage = stage,
		activatedRolloutRevision = 2L,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = 0L,
		captureAdmissionCutoffOrdinal = cutoffOrdinal,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		installedAtMs = 1_000L,
		updatedAtMs = 1_000L,
	)

	private suspend fun activeLane(): SourceProductProjectionLaneEntity? =
		database.sourceProjectionStateDao().activeProductLane(SourceKind.STEPS.stableCode)

	private suspend fun fact(ordinal: Long): StepFactRevisionEntity? =
		database.stepFactRevisionDao().revision(
			StepsSessionFactProjectionLane.WRITER_ID,
			StepsSessionFactProjectionLane.WRITER_VERSION,
			"${StepsSessionFactProjectionLane.WRITER_ID}:steps-event-$ordinal",
			1L,
		)

	private fun clearLaneFactsAndFailures() {
		database.stepFactRevisionDao().deleteAll()
		database.sourceProjectionStateDao().deleteAllFailures()
		database.sourceProjectionStateDao().deleteAllProductLanes()
	}

	private suspend fun insertRawStepsRow(
		ordinal: Long,
		wallTimeMs: Long?,
		acquiredAtMs: Long,
		corruptIntegrity: Boolean = false,
	) {
		val raw = SourceEventWalEntity(
				admissionOrdinal = ordinal,
				eventId = "raw-steps-event-$ordinal",
				providerDedupKey = "raw-steps-dedup-$ordinal",
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = "run-1",
				sourceKind = SourceKind.STEPS.stableCode,
				sourceInstanceId = "steps-provider",
				registrationGeneration = 1L,
				physicalConfigurationFingerprint = "steps-config",
				authorizationRevision = 1L,
				authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				authorizationFingerprint = "steps-capture",
				sourceSequence = ordinal,
				configRevision = 1L,
				planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
				clockDomainId = "boot-1",
				observedElapsedNanos = ordinal * 2_000_000L,
				receivedElapsedNanos = ordinal * 2_000_000L + 1_000L,
				wallTimeMs = wallTimeMs,
				wallTimeUncertaintyMs = 1L,
				capturedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
				sourcePolicyRevision = 3L,
				captureConsentEpoch = 4L,
				sessionManifestRevision = 5L,
				lifecycleLeaseGeneration = 6L,
				acquiredAtMs = acquiredAtMs,
				qualityFlags = 0L,
				qualityConfidence = null,
				payloadVersion = 3,
				payload = byteArrayOf(1),
				payloadChecksum = "pending",
				integrityIdentity = "pending",
				createdAtMs = acquiredAtMs,
			)
		val stored = if (corruptIntegrity) {
			raw.copy(payloadChecksum = "corrupt", integrityIdentity = "corrupt")
		} else {
			raw.copy(
				payloadChecksum = raw.calculatedPayloadChecksum(),
				integrityIdentity = raw.calculatedIntegrityIdentity(),
			)
		}
		database.sourceEventWalDao().insertIgnoringDuplicate(stored)
	}

	private fun stepEvent(
		ordinal: Long,
		boundary: StepBoundaryKind = StepBoundaryKind.COVERED,
		firstCount: Long = 100L,
		lastCount: Long = 102L,
		delta: Long = 2L,
		collectedDataEpoch: Long = COLLECTED_DATA_EPOCH,
		wallTimeMs: Long = 10_000L + ordinal,
		acquiredAtMs: Long = 10_000L + ordinal,
	): AdmittedSourceEvent<StepCounterWindowPayload> {
		val endNanos = ordinal * 2_000_000L
		val startNanos = if (boundary == StepBoundaryKind.BASELINE) endNanos else endNanos - 1_000_000L
		return AdmittedSourceEvent(
			eventId = SourceEventId("steps-event-$ordinal"),
			admissionOrdinal = ordinal,
			evidence = SourceEvidenceCandidate(
				providerDedupKey = "steps-dedup-$ordinal",
				logicalTrackingId = LogicalTrackingId(LOGICAL_TRACKING_ID),
				serviceRunId = ServiceRunId("run-1"),
				source = SourceKind.STEPS,
				sourceInstanceId = SourceInstanceId("steps-provider"),
				registrationGeneration = 1L,
				physicalConfigurationFingerprint = "steps-config",
				authorizationRevision = 1L,
				registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				registrationEligibilityFingerprint = "steps-capture",
				sourceSequence = ordinal,
				configRevision = 1L,
				planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
				clockDomainId = "boot-1",
				observedElapsedRealtimeNanos = endNanos,
				receivedElapsedRealtimeNanos = endNanos + 1_000L,
				wallTimeMs = wallTimeMs,
				wallTimeUncertaintyMs = 1L,
				capturedCollectedDataEpoch = collectedDataEpoch,
				sourcePolicyRevision = 3L,
				captureConsentEpoch = 4L,
				sessionManifestRevision = 5L,
				lifecycleLeaseGeneration = 6L,
				acquiredAtMs = acquiredAtMs,
				quality = SourceQuality(),
				payloadVersion = 3,
				payload = StepCounterWindowPayload(
					bootClockDomainId = "boot-1",
					firstCumulativeCount = firstCount,
					lastCumulativeCount = lastCount,
					deltaCount = delta,
					windowStartElapsedRealtimeNanos = startNanos,
					windowEndElapsedRealtimeNanos = endNanos,
					firstProviderSequence = ordinal,
					lastProviderSequence = ordinal,
					boundaryKind = boundary,
				),
			),
		)
	}

	private companion object {
		const val COLLECTED_DATA_EPOCH = 2L
		const val LOGICAL_TRACKING_ID = "session-1"
		const val SERVICE_RUN_ID = "run-1"
		const val MANIFEST_REVISION = 5L
	}
}
