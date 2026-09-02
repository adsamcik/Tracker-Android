package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
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
@Suppress("LargeClass")
class PressureSessionFactProjectionLaneTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(legacyPressureOwner())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `canonical lane preserves complete and partial v4 facts and publishes once`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val complete = pressureEvent(1L)
		val partial = pressureEvent(
			ordinal = 2L,
			closure = PressureWindowClosureKind.SOURCE_BOUNDARY,
			qualityFlags = setOf(SourceQualityFlag.BATCHED, SourceQualityFlag.INCOMPLETE_WINDOW),
		)

		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 2L, listOf(complete, partial)),
		).drainThrough(2L) shouldBe PressureSessionFactDrainResult.Complete(
			lastCompletedOrdinal = 2L,
			factsInserted = 2,
			eventsValidated = 2,
		)

		val first = requireNotNull(fact(1L))
		first.payloadVersion shouldBe PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
		first.qualification shouldBe PressureFactRevisionEntity.QUALIFICATION_COMPLETE
		first.sampleCount shouldBe 4
		first.meanHectopascals shouldBe 1_001.5
		first.sumSquaredDeviations shouldBe 5.0
		first.minimumHectopascals shouldBe 1_000f
		first.maximumHectopascals shouldBe 1_003f
		first.firstHectopascals shouldBe 1_000f
		first.lastHectopascals shouldBe 1_003f
		first.slopeHectopascalsPerSecond shouldBe 20.0
		first.rSquared shouldBe 1.0
		first.sensorAccuracy shouldBe PressureSensorAccuracy.HIGH.name
		first.effectiveSamplePeriodMicros shouldBe 50_000
		first.effectiveMaximumReportLatencyMicros shouldBe 200_000
		first.targetWindowDurationNanos shouldBe 200_000_000L
		first.expectedSampleCount shouldBe 4
		first.maximumInterSampleGapNanos shouldBe 50_000_000L
		first.closureKind shouldBe PressureWindowClosureKind.TARGET_ELAPSED.name
		first.sourceQualityFlags shouldBe SourceQualityFlag.BATCHED.bit
		first.sourceQualityConfidence shouldBe 0.75f
		first.writerBindingGeneration shouldBe PressureSessionFactProjectionLane.BINDING_GENERATION

		val second = requireNotNull(fact(2L))
		second.qualification shouldBe PressureFactRevisionEntity.QUALIFICATION_PARTIAL
		second.closureKind shouldBe PressureWindowClosureKind.SOURCE_BOUNDARY.name
		second.sourceQualityFlags shouldBe (
			SourceQualityFlag.BATCHED.bit or SourceQualityFlag.INCOMPLETE_WINDOW.bit
		)
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L
		database.pressureSampleDao().getAllBetween(0L, Long.MAX_VALUE).shouldBeEmpty()
	}

	@Test
	fun `shadow validates and advances without facts or evidence publication`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW)

		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 2L, listOf(pressureEvent(1L), pressureEvent(2L))),
		).drainThrough(2L) shouldBe
			PressureSessionFactDrainResult.Complete(2L, factsInserted = 0, eventsValidated = 2)

		database.pressureFactRevisionDao().count() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L
	}

	@Test
	fun `candidate manifest is manual only and canonical requires its exact owner`() = runTest {
		installLane(
			stage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			sessionMode = "AUTOMATIC",
		)
		val automatic = PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(pressureEvent(1L))),
		).drainThrough(1L)

		automatic shouldBe PressureSessionFactDrainResult.Failed(
			lastCompletedOrdinal = 0L,
			failedOrdinal = 1L,
			failureCode = "PRESSURE_MANIFEST_CAPTURE_MODE_MISMATCH",
			terminal = true,
		)
		database.pressureFactRevisionDao().count() shouldBe 0L
	}

	@Test
	fun `canonical lane rejects evidence attributed to the legacy Pressure writer`() = runTest {
		installLane(
			stage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			manifestOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
		)

		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(pressureEvent(1L))),
		).drainThrough(1L) shouldBe PressureSessionFactDrainResult.Failed(
			lastCompletedOrdinal = 0L,
			failedOrdinal = 1L,
			failureCode = "PRESSURE_MANIFEST_WRITER_NOT_ACTIVE",
			terminal = true,
		)
		database.pressureFactRevisionDao().count() shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
	}

	@Test
	fun `stale epoch control evidence and retained product time advance without facts`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = COLLECTED_DATA_EPOCH,
			retainedFromMs = 10_000L,
			updatedAtMs = 20_000L,
		) shouldBe 1
		val stale = pressureEvent(1L, collectedDataEpoch = COLLECTED_DATA_EPOCH - 1L)
		val controlBase = pressureEvent(2L)
		val control = controlBase.copy(evidence = controlBase.evidence.copy(
			logicalTrackingId = null,
			serviceRunId = null,
			registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			registrationEligibilityFingerprint = "control-only",
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			sessionManifestRevision = null,
			lifecycleLeaseGeneration = null,
		))
		val retained = pressureEvent(3L, wallTimeMs = 9_999L, acquiredAtMs = 10_001L)

		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 3L, listOf(stale, control, retained)),
		).drainThrough(3L) shouldBe
			PressureSessionFactDrainResult.Complete(3L, factsInserted = 0, eventsValidated = 3)

		database.pressureFactRevisionDao().count() shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 3L
	}

	@Test
	fun `deleted session scope advances as a validated no effect`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		insertSessionDeletionFence()

		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(pressureEvent(1L))),
		).drainThrough(1L) shouldBe
			PressureSessionFactDrainResult.Complete(1L, factsInserted = 0, eventsValidated = 1)

		database.pressureFactRevisionDao().count() shouldBe 0L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
	}

	@Test
	fun `v4 is mandatory and a terminal poison is not reread`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		insertRawPressureRow(1L, wallTimeMs = 10_001L, acquiredAtMs = 10_001L)
		val ingress = sourceIngress(0L, 1L, listOf(pressureEvent(1L, payloadVersion = 3)))
		val subject = PressureSessionFactProjectionLane(database, ingress)
		val expected = PressureSessionFactDrainResult.Failed(
			lastCompletedOrdinal = 0L,
			failedOrdinal = 1L,
			failureCode = "PRESSURE_PAYLOAD_VERSION_UNSUPPORTED",
			terminal = true,
		)

		subject.drainThrough(1L) shouldBe expected
		subject.drainThrough(1L) shouldBe expected

		database.pressureFactRevisionDao().count() shouldBe 0L
		coVerify(exactly = 1) {
			ingress.committedSourceBatch(SourceKind.PRESSURE, 0L, 1L, 64)
		}
	}

	@Test
	fun `valid prefix commits before qualification poison blocks its ordinal`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val partialWithoutFlag = pressureEvent(
			ordinal = 2L,
			closure = PressureWindowClosureKind.SOURCE_BOUNDARY,
		)

		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 3L, listOf(
				pressureEvent(1L),
				partialWithoutFlag,
				pressureEvent(3L),
			)),
		).drainThrough(3L) shouldBe PressureSessionFactDrainResult.Failed(
			lastCompletedOrdinal = 1L,
			failedOrdinal = 2L,
			failureCode = "PRESSURE_QUALIFICATION_MISMATCH",
			terminal = true,
		)

		requireNotNull(fact(1L)).qualification shouldBe
			PressureFactRevisionEntity.QUALIFICATION_COMPLETE
		fact(2L) shouldBe null
		fact(3L) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
	}

	@Test
	fun `exact replay advances without a second fact or evidence mutation`() = runTest {
		val event = pressureEvent(1L)
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(event)),
		).drainThrough(1L)
		val original = requireNotNull(fact(1L))
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		database.sourceProjectionStateDao().deleteAllProductLanes()
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(event)),
		).drainThrough(1L) shouldBe
			PressureSessionFactDrainResult.Complete(1L, factsInserted = 0, eventsValidated = 1)

		database.pressureFactRevisionDao().count() shouldBe 1L
		fact(1L) shouldBe original
		database.sourceEvidenceStateDao().get()?.revision shouldBe evidenceRevision
	}

	@Test
	fun `identity collision durably blocks its exact ordinal`() = runTest {
		val event = pressureEvent(1L)
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		PressureSessionFactProjectionLane(
			database,
			sourceIngress(0L, 1L, listOf(event)),
		).drainThrough(1L)
		val exact = requireNotNull(fact(1L))
		database.pressureFactRevisionDao().deleteAll()
		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().deleteAllFailures()
		insertRawPressureRow(1L, wallTimeMs = 10_001L, acquiredAtMs = 10_001L)
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.pressureFactRevisionDao().insert(
			exact.copy(sourceEventId = "foreign-event", effectChecksum = "foreign"),
		) shouldBe 1L
		val ingress = sourceIngress(0L, 1L, listOf(event))
		val subject = PressureSessionFactProjectionLane(database, ingress)
		val expected = PressureSessionFactDrainResult.Failed(
			lastCompletedOrdinal = 0L,
			failedOrdinal = 1L,
			failureCode = "PRESSURE_IDENTITY_COLLISION",
			terminal = true,
		)

		subject.drainThrough(1L) shouldBe expected
		subject.drainThrough(1L) shouldBe expected

		database.pressureFactRevisionDao().count() shouldBe 1L
		activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
		coVerify(exactly = 1) {
			ingress.committedSourceBatch(SourceKind.PRESSURE, 0L, 1L, 64)
		}
	}

	@Test
	fun `retention releases a terminal corrupt row without publishing evidence`() = runTest {
		installLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		insertRawPressureRow(1L, wallTimeMs = 10_001L, acquiredAtMs = 10_001L)
		val ingress = mockk<DurableSourceIngress>()
		coEvery {
			ingress.committedSourceBatch(SourceKind.PRESSURE, 0L, 1L, 64)
		} throws CorruptSourceEventException(
			admissionOrdinal = 1L,
			sourceKind = SourceKind.PRESSURE.stableCode,
			failureCode = "RAW_PAYLOAD_INTEGRITY",
		)
		val subject = PressureSessionFactProjectionLane(database, ingress)

		subject.drainThrough(1L) shouldBe PressureSessionFactDrainResult.Failed(
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
			PressureSessionFactDrainResult.Complete(1L, factsInserted = 0, eventsValidated = 0)

		database.sourceProjectionStateDao().failure(
			PressureSessionFactProjectionLane.WRITER_ID,
			PressureSessionFactProjectionLane.WRITER_VERSION,
			1L,
		) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe evidenceRevision
		activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
	}

	@Test
	fun `cutoff bounds the source cursor and a foreign Pressure binding is inert`() = runTest {
		installLane(
			stage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			cutoffOrdinal = 2L,
		)
		val ingress = sourceIngress(0L, 2L, listOf(pressureEvent(2L)))

		PressureSessionFactProjectionLane(database, ingress).drainThrough(9L) shouldBe
			PressureSessionFactDrainResult.Complete(2L, factsInserted = 0, eventsValidated = 1)
		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L

		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().installProductLane(
			productLane(
				stage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				projectionId = "foreign-pressure-writer",
			),
		)
		val foreignIngress = mockk<DurableSourceIngress>()
		PressureSessionFactProjectionLane(database, foreignIngress).drainThrough(9L) shouldBe
			PressureSessionFactDrainResult.Inactive
		coVerify(exactly = 0) {
			foreignIngress.committedSourceBatch(any(), any(), any(), any())
		}
	}

	private fun sourceIngress(
		afterOrdinal: Long,
		throughOrdinal: Long,
		events: List<AdmittedSourceEvent<PressureWindowPayload>>,
	): DurableSourceIngress = mockk<DurableSourceIngress>().also { ingress ->
		coEvery {
			ingress.committedSourceBatch(
				SourceKind.PRESSURE,
				afterOrdinal,
				throughOrdinal,
				64,
			)
		} returns events
	}

	private suspend fun installLane(
		stage: String,
		cutoffOrdinal: Long? = null,
		sessionMode: String = "MANUAL",
		manifestOwner: String = if (stage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
		} else {
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
		},
	) {
		if (stage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = requireNotNull(database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			))
			if (owner.owner == SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE) {
				check(database.sourceDestinationOwnerDao().compareAndSetOwner(
					sourceKind = owner.sourceKind,
					destination = owner.destination,
					expectedOwner = owner.owner,
					expectedOwnerGeneration = owner.ownerGeneration,
					newOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
					newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = 1_000L,
				) == 1)
			}
		}
		installManifestBinding(manifestOwner, sessionMode)
		database.sourceProjectionStateDao().installProductLane(
			productLane(stage = stage, cutoffOrdinal = cutoffOrdinal),
		)
	}

	private suspend fun installManifestBinding(writerOwner: String, sessionMode: String) {
		if (database.sourceSessionDao().manifest(LOGICAL_TRACKING_ID, MANIFEST_REVISION) != null) return
		val candidate = writerOwner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = SourceKind.PRESSURE.stableCode,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = 4L,
			persistenceEligible = true,
			qosCode = 0,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			writerOwner = writerOwner,
			writerOwnerGeneration = if (candidate) {
				SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			} else {
				SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
			},
			writerProjectionId = PressureSessionFactProjectionLane.WRITER_ID.takeIf { candidate },
			writerProjectionVersion = PressureSessionFactProjectionLane.WRITER_VERSION
				.takeIf { candidate },
			writerBindingGeneration = PressureSessionFactProjectionLane.BINDING_GENERATION
				.takeIf { candidate },
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = sessionMode,
			sourcePolicyRevision = 3L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 2L,
			startOrigin = if (sessionMode == "AUTOMATIC") {
				"AUTOMATIC_BACKGROUND_START"
			} else {
				"MANUAL_UI"
			},
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = 0L,
			effectiveWallTimeMs = 10_000L,
			zoneId = "UTC",
			automationEpoch = 1L.takeIf { sessionMode == "AUTOMATIC" },
			changeReason = "TEST",
			manifestChecksum = "",
		)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun legacyPressureOwner() = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
		ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
		updatedAtMs = 0L,
	)

	private fun productLane(
		stage: String,
		cutoffOrdinal: Long? = null,
		projectionId: String = PressureSessionFactProjectionLane.WRITER_ID,
	) = SourceProductProjectionLaneEntity(
		sourceKind = SourceKind.PRESSURE.stableCode,
		bindingGeneration = PressureSessionFactProjectionLane.BINDING_GENERATION,
		projectionId = projectionId,
		projectionVersion = PressureSessionFactProjectionLane.WRITER_VERSION,
		captureModeMask = PressureSessionFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
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
		database.sourceProjectionStateDao().activeProductLane(SourceKind.PRESSURE.stableCode)

	private suspend fun fact(ordinal: Long): PressureFactRevisionEntity? =
		database.pressureFactRevisionDao().latest(
			PressureSessionFactProjectionLane.WRITER_ID,
			PressureSessionFactProjectionLane.WRITER_VERSION,
			"${PressureSessionFactProjectionLane.WRITER_ID}:pressure-event-$ordinal",
		)

	private suspend fun insertRawPressureRow(
		ordinal: Long,
		wallTimeMs: Long?,
		acquiredAtMs: Long,
	) {
		val raw = SourceEventWalEntity(
			admissionOrdinal = ordinal,
			eventId = "raw-pressure-event-$ordinal",
			providerDedupKey = "raw-pressure-dedup-$ordinal",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = SourceKind.PRESSURE.stableCode,
			sourceInstanceId = "pressure-provider",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "pressure-config",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "pressure-capture",
			sourceSequence = ordinal,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = "boot-1",
			observedElapsedNanos = ordinal * 1_000_000_000L + 150_000_000L,
			receivedElapsedNanos = ordinal * 1_000_000_000L + 150_001_000L,
			wallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
			sourcePolicyRevision = 3L,
			captureConsentEpoch = 4L,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = 6L,
			acquiredAtMs = acquiredAtMs,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
			payload = byteArrayOf(1),
			payloadChecksum = "corrupt",
			integrityIdentity = "corrupt",
			createdAtMs = acquiredAtMs,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(raw)
	}

	private suspend fun insertSessionDeletionFence() {
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceKind.PRESSURE.stableCode,
				purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				fenceGeneration = 1L,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				deletedAtMs = 20_000L,
			),
		)
	}

	private fun pressureEvent(
		ordinal: Long,
		closure: PressureWindowClosureKind = PressureWindowClosureKind.TARGET_ELAPSED,
		qualityFlags: Set<SourceQualityFlag> = setOf(SourceQualityFlag.BATCHED),
		payloadVersion: Int = PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
		collectedDataEpoch: Long = COLLECTED_DATA_EPOCH,
		wallTimeMs: Long = 10_000L + ordinal,
		acquiredAtMs: Long = 10_000L + ordinal,
	): AdmittedSourceEvent<PressureWindowPayload> {
		val startNanos = ordinal * 1_000_000_000L
		val endNanos = startNanos + 150_000_000L
		val firstSequence = ordinal * 10L + 1L
		return AdmittedSourceEvent(
			eventId = SourceEventId("pressure-event-$ordinal"),
			admissionOrdinal = ordinal,
			evidence = SourceEvidenceCandidate(
				providerDedupKey = "pressure-dedup-$ordinal",
				logicalTrackingId = LogicalTrackingId(LOGICAL_TRACKING_ID),
				serviceRunId = ServiceRunId(SERVICE_RUN_ID),
				source = SourceKind.PRESSURE,
				sourceInstanceId = SourceInstanceId("pressure-provider"),
				registrationGeneration = 1L,
				physicalConfigurationFingerprint = "pressure-config",
				authorizationRevision = 1L,
				registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				registrationEligibilityFingerprint = "pressure-capture",
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
				sessionManifestRevision = MANIFEST_REVISION,
				lifecycleLeaseGeneration = 6L,
				acquiredAtMs = acquiredAtMs,
				quality = SourceQuality(confidence = 0.75f, flags = qualityFlags),
				payloadVersion = payloadVersion,
				payload = PressureWindowPayload(
					sampleCount = 4,
					meanHectopascals = 1_001.5,
					sumSquaredDeviations = 5.0,
					minimumHectopascals = 1_000f,
					maximumHectopascals = 1_003f,
					windowStartElapsedRealtimeNanos = startNanos,
					windowEndElapsedRealtimeNanos = endNanos,
					firstProviderSequence = firstSequence,
					lastProviderSequence = firstSequence + 3L,
					firstHectopascals = 1_000f,
					lastHectopascals = 1_003f,
					slopeHectopascalsPerSecond = 20.0,
					rSquared = 1.0,
					sensorAccuracy = PressureSensorAccuracy.HIGH,
					effectiveSamplePeriodMicros = 50_000,
					effectiveMaximumReportLatencyMicros = 200_000,
					targetWindowDurationNanos = 200_000_000L,
					expectedSampleCount = 4,
					maximumInterSampleGapNanos = 50_000_000L,
					closureKind = closure,
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
