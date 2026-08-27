package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingRolloutStateStoreTest {
	private lateinit var database: AppDatabase
	private lateinit var store: RoomTrackingRolloutStateStore

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		store = RoomTrackingRolloutStateStore(database, TEST_CATALOG)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `missing row initializes contained without authorizing acquisition`() = runTest {
		store.load() shouldBe TrackingRolloutState.contained()
	}

	@Test
	fun `unreleased global canonical marker is contained without source promotion`() = runTest {
		database.trackingRolloutStateDao().save(
			TrackingRolloutStateEntity(
				revision = 7L,
				schemaVersion = 2,
				coordinatorMode = CoordinatorMode.EVENT.name,
				projectionMode = "EVENT_CANONICAL",
				sourceOwners = SourceKind.entries.joinToString(",") { source ->
					"${source.stableCode}:${SourceOwner.EVENT.name}"
				},
				semanticSettingsEnabled = true,
				batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE.name,
				updatedAtMs = 1_000L,
			),
		)

		val contained = store.load()
		contained.revision shouldBe 8L
		contained.sourceOwners.values.toSet() shouldBe setOf(SourceOwner.CONTAINED)
		contained.productProjectionStages.values.toSet() shouldBe
			setOf(ProductProjectionStage.LEGACY_CANONICAL)
	}

	@Test
	fun `installed shadow lane stays product inert across repeated repair loads`() = runTest {
		val expected = TrackingRolloutState.contained(revision = 3)

		val activation = installSteps()

		activation.rollout shouldBe expected
		activation.lane.sourceKind shouldBe SourceKind.STEPS.stableCode
		activation.lane.projectionId shouldBe STEPS_OUTPUT_CONTRACT
		activation.lane.projectionVersion shouldBe 1
		activation.lane.activatedRolloutRevision shouldBe 3L
		activation.lane.activationOrdinal shouldBe 1L
		activation.lane.contiguousAdmissionOrdinal shouldBe 0L
		activation.lane.retentionRequired shouldBe true
		activation.rollout.sourceOwners.getValue(SourceKind.STEPS) shouldBe SourceOwner.CONTAINED
		activation.rollout.productProjectionStages.getValue(SourceKind.STEPS) shouldBe
			ProductProjectionStage.LEGACY_CANONICAL
		activation.rollout.captureModeMasks.getValue(SourceKind.STEPS) shouldBe 0L
		activation.rollout.isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		) shouldBe false
		database.sourceProjectionStateDao().isProductLaneReachable(
			SourceKind.STEPS.stableCode,
			ProductProjectionStage.EVENT_SHADOW.name,
			3L,
		) shouldBe true
		database.sourceProjectionStateDao().isProductLaneReachable(
			SourceKind.STEPS.stableCode,
			ProductProjectionStage.EVENT_CANONICAL.name,
			3L,
		) shouldBe false
		database.sourceProjectionStateDao().isProductLaneReachable(
			SourceKind.STEPS.stableCode,
			ProductProjectionStage.EVENT_SHADOW.name,
			2L,
		) shouldBe false
		store.load() shouldBe expected
		store.load() shouldBe expected
		database.sourceProjectionStateDao().activeProductLane(SourceKind.STEPS.stableCode)
			?.status shouldBe SourceProductProjectionLaneEntity.STATUS_ACTIVE
	}

	@Test
	fun `capture rollout cannot be saved before its exact source lane is installed`() = runTest {
		shouldThrow<IllegalArgumentException> {
			store.save(
				TrackingRolloutState.eventCanonical(setOf(SourceKind.STEPS), revision = 3),
				1_000L,
			)
		}

		database.trackingRolloutStateDao().get() shouldBe null
	}

	@Test
	fun `exact canonical lane is the only product stage that authorizes public capture`() = runTest {
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.installProductLane(
			productLane(STEPS_V1, rolloutRevision = 3L).copy(
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			),
		)
		val canonical = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS),
			revision = 3L,
		)

		store.save(canonical, updatedAtMs = 1_000L)

		store.load() shouldBe canonical
		store.load().isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		) shouldBe true
	}

	@Test
	fun `arbitrary or typo projection identity cannot promote acquisition`() = runTest {
		val typo = STEPS_V1.copy(projectionId = "$STEPS_OUTPUT_CONTRACT-typo")

		shouldThrow<IllegalArgumentException> {
			store.installInertShadowLane(typo, 3L, 1_000L)
		}

		database.sourceProjectionStateDao().activeProductLanes() shouldBe emptyList()
		store.load().isAcquisitionReachable(SourceKind.STEPS) shouldBe false
	}

	@Test
	fun `persisted capture marker without a durable lane is repaired before provider use`() = runTest {
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		database.trackingRolloutStateDao().get()?.revision shouldBe 8L
	}

	@Test
	fun `persisted shadow capture marker repairs once without retiring its executable inert lane`() =
		runTest {
			val projectionDao = database.sourceProjectionStateDao()
			projectionDao.installProductLane(productLane(STEPS_V1, rolloutRevision = 7L))
			database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))

			val repaired = TrackingRolloutState.contained(revision = 8L)
			store.load() shouldBe repaired
			store.load() shouldBe repaired
			projectionDao.activeProductLane(SourceKind.STEPS.stableCode)?.let { inert ->
				inert.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW
				inert.status shouldBe SourceProductProjectionLaneEntity.STATUS_ACTIVE
				inert.retentionRequired shouldBe true
				inert.captureAdmissionCutoffOrdinal shouldBe null
			}
			database.trackingRolloutStateDao().get()?.revision shouldBe 8L
		}

	@Test
	fun `lane with an uninitialized source cursor cannot authorize acquisition`() = runTest {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				bindingGeneration = 1L,
				projectionId = STEPS_OUTPUT_CONTRACT,
				projectionVersion = 1,
				captureModeMask = MANUAL_MASK,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 7L,
				activationOrdinal = 5L,
				contiguousAdmissionOrdinal = 3L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
	}

	@Test
	fun `duplicate active writer generations are contained and both retention pins retire`() = runTest {
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.installProductLane(productLane(STEPS_V1, rolloutRevision = 7L))
		projectionDao.installProductLane(productLane(STEPS_V2, rolloutRevision = 7L))
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		projectionDao.allActiveProductLanes() shouldBe emptyList()
		projectionDao.latestProductLane(SourceKind.STEPS.stableCode)?.let { latest ->
			latest.bindingGeneration shouldBe STEPS_V2.bindingGeneration
			latest.status shouldBe SourceProductProjectionLaneEntity.STATUS_RETIRED
			latest.retentionRequired shouldBe false
		}
	}

	@Test
	fun `conflicting non-retaining lane is still terminally retired on load`() = runTest {
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.installProductLane(
			productLane(STEPS_V1, rolloutRevision = 7L).copy(retentionRequired = false),
		)
		projectionDao.register(
			SourceProjectionRegistrationEntity(
				projectionId = STEPS_V1.projectionId,
				projectionVersion = STEPS_V1.projectionVersion,
				activationOrdinal = 1L,
				retentionRequired = true,
				status = "ACTIVE",
				createdAtMs = 500L,
			),
		)
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		projectionDao.allActiveProductLanes() shouldBe emptyList()
		projectionDao.latestProductLane(SourceKind.STEPS.stableCode)?.status shouldBe
			SourceProductProjectionLaneEntity.STATUS_RETIRED
	}

	@Test
	fun `containment repair pins an unexecutable shadow without discarding unread wal`() = runTest {
		val unknownBinding = STEPS_V1.copy(projectionId = "unknown-steps-writer")
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.installProductLane(productLane(unknownBinding, rolloutRevision = 7L))
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))
		appendWalEvents(1)

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		projectionDao.activeProductLane(SourceKind.STEPS.stableCode)?.let { pinned ->
			pinned.status shouldBe SourceProductProjectionLaneEntity.STATUS_ACTIVE
			pinned.retentionRequired shouldBe true
			pinned.contiguousAdmissionOrdinal shouldBe 0L
			pinned.captureAdmissionCutoffOrdinal shouldBe 1L
			pinned.terminalDisposition shouldBe null
			pinned.terminalAtMs shouldBe null
		}
	}

	@Test
	fun `unexecutable shadow remains pinned while a capture demand has not crossed its barrier`() = runTest {
		val unknownBinding = STEPS_V1.copy(projectionId = "unknown-steps-writer")
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.installProductLane(productLane(unknownBinding, rolloutRevision = 7L))
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))
		database.sourceBrokerDao().insertDemands(listOf(stepsSessionDemand()))

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		projectionDao.activeProductLane(SourceKind.STEPS.stableCode)?.let { pinned ->
			pinned.status shouldBe SourceProductProjectionLaneEntity.STATUS_ACTIVE
			pinned.retentionRequired shouldBe true
			pinned.captureAdmissionCutoffOrdinal shouldBe null
			pinned.terminalDisposition shouldBe null
		}
	}

	@Test
	fun `containment includes a delayed callback before the old capture generation retires`() = runTest {
		val unknownBinding = STEPS_V1.copy(projectionId = "unknown-steps-writer")
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.installProductLane(productLane(unknownBinding, rolloutRevision = 7L))
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))
		val captureDemand = stepsSessionDemand().copy(
			status = SourceDemandEntity.STATUS_RETIRED,
			retireBootId = "boot-1",
			retireElapsedRealtimeNanos = 20L,
			retiredAtMs = 20L,
		)
		val brokerDao = database.sourceBrokerDao()
		brokerDao.insertDemands(listOf(captureDemand))
		brokerDao.insertRegistration(activeStepsRegistration())
		brokerDao.insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				authorizationRevision = 1L,
				demands = listOf(captureDemand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = 10L,
				effectiveWallTimeMs = 10L,
			),
		)

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		projectionDao.activeProductLane(SourceKind.STEPS.stableCode)
			?.captureAdmissionCutoffOrdinal shouldBe null
		appendWalEvents(1) // Callback already accepted by generation 1, delivered after rollout containment.
		brokerDao.markRegistrationRetiring(
			SourceKind.STEPS.stableCode,
			1L,
			"steps-instance",
			30L,
			30L,
			"TEST_CAPTURE_BARRIER",
		) shouldBe 1
		brokerDao.completeRegistrationRetirement(
			SourceKind.STEPS.stableCode,
			1L,
			"steps-instance",
		) shouldBe 1

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		projectionDao.activeProductLane(SourceKind.STEPS.stableCode)?.let { pinned ->
			pinned.captureAdmissionCutoffOrdinal shouldBe 1L
			pinned.retentionRequired shouldBe true
			pinned.contiguousAdmissionOrdinal shouldBe 0L
		}
	}

	@Test
	fun `unexecutable canonical lane is fenced but never abandoned by shadow repair`() = runTest {
		val unknownBinding = STEPS_V1.copy(projectionId = "unknown-canonical-writer")
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.installProductLane(
			productLane(unknownBinding, rolloutRevision = 7L).copy(
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			),
		)
		database.trackingRolloutStateDao().save(
			eventShadowEntity(
				revision = 7L,
				stepsStage = ProductProjectionStage.EVENT_CANONICAL,
			),
		)

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		projectionDao.activeProductLane(SourceKind.STEPS.stableCode)?.let { canonical ->
			canonical.status shouldBe SourceProductProjectionLaneEntity.STATUS_ACTIVE
			canonical.retentionRequired shouldBe true
			canonical.captureAdmissionCutoffOrdinal shouldBe 0L
			canonical.terminalDisposition shouldBe null
		}
	}

	@Test
	fun `one source and one output contract cannot acquire competing writer generations`() = runTest {
		installSteps()

		shouldThrow<IllegalArgumentException> {
			store.installInertShadowLane(
				binding = STEPS_V2,
				rolloutRevision = 4,
				updatedAtMs = 2_000L,
			)
		}
		shouldThrow<IllegalArgumentException> {
			store.installInertShadowLane(
				binding = ExecutableSourceLaneBinding(
					SourceKind.PRESSURE,
					1L,
					STEPS_OUTPUT_CONTRACT,
					1,
					setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
				),
				rolloutRevision = 4,
				updatedAtMs = 2_000L,
			)
		}

		store.load() shouldBe TrackingRolloutState.contained(revision = 3)
		database.sourceProjectionStateDao().activeProductLanes().size shouldBe 1
	}

	@Test
	fun `catalog rejects one writer identity assigned to multiple sources`() {
		shouldThrow<IllegalArgumentException> {
			ExecutableSourceLaneCatalog.explicit(
				STEPS_V1,
				ExecutableSourceLaneBinding(
					SourceKind.PRESSURE,
					1L,
					STEPS_OUTPUT_CONTRACT,
					1,
					setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
				),
			)
		}
	}

	@Test
	fun `global projection identity cannot be reused as a source writer generation`() = runTest {
		database.sourceProjectionStateDao().register(
			SourceProjectionRegistrationEntity(
				projectionId = STEPS_OUTPUT_CONTRACT,
				projectionVersion = 1,
				activationOrdinal = 1,
				retentionRequired = true,
				status = "ACTIVE",
				createdAtMs = 500L,
			),
		)

		shouldThrow<IllegalArgumentException> { installSteps() }
		database.sourceProjectionStateDao().activeProductLanes() shouldBe emptyList()
		database.trackingRolloutStateDao().get() shouldBe null
	}

	@Test
	fun `loading one inert source lane does not promote unrelated sources`() = runTest {
		val expected = TrackingRolloutState.contained(revision = 3)

		installSteps()

		store.load() shouldBe expected
		store.load().sourceOwners.getValue(SourceKind.LOCATION) shouldBe SourceOwner.CONTAINED
	}

	@Test
	fun `loading one inert shadow projection does not force canonical writers`() = runTest {
		val expected = TrackingRolloutState.contained(revision = 3)

		installSteps()

		store.load() shouldBe expected
		store.load().productProjectionStages.getValue(SourceKind.LOCATION) shouldBe
			ProductProjectionStage.LEGACY_CANONICAL
	}

	@Test
	fun `contain retire and rearm use a new binding generation while sibling continues`() = runTest {
		installSteps()
		store.installInertShadowLane(PRESSURE_V1, 4L, 1_100L)
		appendWalEvents(4)
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.advanceProductLaneCursor(
			sourceKind = SourceKind.STEPS.stableCode,
			bindingGeneration = STEPS_V1.bindingGeneration,
			projectionId = STEPS_V1.projectionId,
			projectionVersion = STEPS_V1.projectionVersion,
			expectedCurrentOrdinal = 0L,
			throughOrdinal = 4L,
			updatedAtMs = 1_200L,
		) shouldBe 1

		val fence = store.beginLaneContainment(STEPS_V1, 5L, 1_300L)
		fence.cutoffOrdinal shouldBe 4L
		val contained = store.finishLaneRetirement(STEPS_V1, 4L, 1_301L)
		contained.isAcquisitionReachable(SourceKind.STEPS) shouldBe false
		contained.isAcquisitionReachable(SourceKind.PRESSURE) shouldBe false
		projectionDao.latestProductLane(SourceKind.STEPS.stableCode)?.let { retired ->
			retired.status shouldBe SourceProductProjectionLaneEntity.STATUS_RETIRED
			retired.retentionRequired shouldBe false
		}
		projectionDao.advanceProductLaneCursor(
			sourceKind = SourceKind.STEPS.stableCode,
			bindingGeneration = STEPS_V1.bindingGeneration,
			projectionId = STEPS_V1.projectionId,
			projectionVersion = STEPS_V1.projectionVersion,
			expectedCurrentOrdinal = 4L,
			throughOrdinal = 5L,
			updatedAtMs = 1_400L,
		) shouldBe 0

		val rearmed = store.rearmInertShadowLane(STEPS_V2, 6L, 1_500L)
		rearmed.lane.bindingGeneration shouldBe 2L
		rearmed.lane.projectionVersion shouldBe STEPS_V1.projectionVersion
		rearmed.lane.contiguousAdmissionOrdinal shouldBe rearmed.lane.activationOrdinal - 1L
		rearmed.rollout.isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		) shouldBe false
		rearmed.rollout.isAcquisitionReachable(SourceKind.PRESSURE) shouldBe false
		projectionDao.activeProductLane(SourceKind.STEPS.stableCode)?.bindingGeneration shouldBe 2L
	}

	@Test
	fun `generic retirement cannot strand a candidate owned canonical Steps lane`() = runTest {
		installSteps()
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.promoteExactProductLaneToCanonical(
			sourceKind = SourceKind.STEPS.stableCode,
			bindingGeneration = STEPS_V1.bindingGeneration,
			projectionId = STEPS_V1.projectionId,
			projectionVersion = STEPS_V1.projectionVersion,
			captureModeMask = STEPS_V1.captureModeMask,
			expectedShadowRolloutRevision = 3L,
			activationOrdinal = 1L,
			expectedCurrentOrdinal = 0L,
			canonicalRolloutRevision = 4L,
			updatedAtMs = 1_100L,
		) shouldBe 1
		database.trackingRolloutStateDao().save(
			TrackingRolloutState.contained(revision = 5L).toEntity(1_200L),
		)
		projectionDao.fenceProductLaneCaptureAdmission(
			sourceKind = SourceKind.STEPS.stableCode,
			bindingGeneration = STEPS_V1.bindingGeneration,
			projectionId = STEPS_V1.projectionId,
			projectionVersion = STEPS_V1.projectionVersion,
			cutoffOrdinal = 0L,
			updatedAtMs = 1_200L,
		) shouldBe 1
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				owner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				ownerGeneration = 2L,
				updatedAtMs = 1_200L,
			),
		)

		shouldThrow<IllegalArgumentException> {
			store.finishLaneRetirement(STEPS_V1, expectedCurrentOrdinal = 0L, updatedAtMs = 1_300L)
		}

		projectionDao.activeProductLane(SourceKind.STEPS.stableCode)
			?.status shouldBe SourceProductProjectionLaneEntity.STATUS_ACTIVE
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		)?.owner shouldBe SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
	}

	@Test
	fun `containment refuses an active session capture demand`() = runTest {
		installSteps()
		database.sourceBrokerDao().insertDemands(listOf(stepsSessionDemand()))

		shouldThrow<IllegalArgumentException> {
			store.beginLaneContainment(STEPS_V1, 4L, 1_100L)
		}

		store.load() shouldBe TrackingRolloutState.contained(revision = 3L)
		database.sourceProjectionStateDao().activeProductLane(SourceKind.STEPS.stableCode)
			?.retentionRequired shouldBe true
	}

	@Test
	fun `containment waits for durable capture callback barrier while same generation continues control only`() = runTest {
		installSteps()
		val captureDemand = stepsSessionDemand().copy(
			status = SourceDemandEntity.STATUS_RETIRED,
			retireBootId = "boot-1",
			retireElapsedRealtimeNanos = 20L,
			retiredAtMs = 20L,
		)
		val brokerDao = database.sourceBrokerDao()
		brokerDao.insertDemands(listOf(captureDemand))
		brokerDao.insertRegistration(activeStepsRegistration())
		brokerDao.insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				authorizationRevision = 1L,
				demands = listOf(captureDemand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = 10L,
				effectiveWallTimeMs = 10L,
			),
		)

		shouldThrow<IllegalArgumentException> {
			store.beginLaneContainment(STEPS_V1, 4L, 20L)
		}

		val controlDemand = stepsControlDemand()
		brokerDao.insertDemands(listOf(controlDemand))
		brokerDao.insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				authorizationRevision = 2L,
				demands = listOf(controlDemand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = 20L,
				effectiveWallTimeMs = 20L,
			),
		)

		shouldThrow<IllegalArgumentException> {
			store.beginLaneContainment(STEPS_V1, 4L, 20L)
		}

		brokerDao.acknowledgeCaptureCallbackBarrier(
			SourceKind.STEPS.stableCode,
			1L,
			"steps-instance",
			1L,
		) shouldBe 1

		val fence = store.beginLaneContainment(STEPS_V1, 4L, 22L)
		fence.cutoffOrdinal shouldBe 0L
		store.finishLaneRetirement(STEPS_V1, 0L, 23L)
		database.sourceProjectionStateDao().activeProductLane(SourceKind.STEPS.stableCode) shouldBe null
		brokerDao.currentPhysicalRegistration(SourceKind.STEPS.stableCode)?.let { control ->
			control.registrationGeneration shouldBe 1L
			control.sourceInstanceId shouldBe "steps-instance"
			control.captureCallbackBarrierAuthorizationRevision shouldBe 1L
			control.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		}
	}

	@Test
	fun `persisted compatibility rollout upgrades on load`() = runTest {
		store.save(TrackingRolloutState.legacy(revision = 3), 1_000L)

		store.load() shouldBe TrackingRolloutState.contained(revision = 4)
	}

	@Test
	fun `rollout revision cannot move backward or be overwritten`() = runTest {
		store.save(TrackingRolloutState.legacy(revision = 2), 1_000L)

		shouldThrow<IllegalArgumentException> {
			store.save(TrackingRolloutState.legacy(revision = 2), 2_000L)
		}
	}

	@Test
	fun `event coordinator refuses a source that persisted rollout does not own`() = runTest {
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", 1L, 1L)
		}.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val ingress = io.mockk.mockk<com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress>()
		val coordinator = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry(emptySet()),
			com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher(database, emptySet())),
			com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartActionRepository(
				database,
				ReadyTrackingStartupGate,
			),
			NoOpActivityAutomationDrainSignal,
			io.mockk.mockk(relaxed = true),
			BootClockDomainProvider { "boot-1" },
			FixedClock(fixedTimeMillis = 1L, fixedRealtimeNanos = 1L),
			rolloutStore = store,
		)
		val plan = com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision(
			revision = 1,
			planId = "guard-test",
			createdAtMs = 1,
			plans = mapOf(
				SourceKind.STEPS to com.adsamcik.tracker.tracker.source.model.StepsPlan(1, true, 60_000, 15_000, false),
			),
			sourcePolicyRevision = 1,
		)

		coordinator.start(
			SessionStartRequest(
				ownerToken = "guard-test",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				plan = plan,
				rolloutRevision = 0,
				clockDomainId = "boot-1",
				foregroundCapabilityFlags = 0,
				wallTimeMs = 1,
				elapsedRealtimeNanos = 1,
				zoneId = "Europe/Prague",
			),
		).shouldBeInstanceOf<SessionStartResult.InvalidRollout>()
	}

	private suspend fun installSteps(): SourceProductLaneActivation =
		store.installInertShadowLane(
			binding = STEPS_V1,
			rolloutRevision = 3,
			updatedAtMs = 1_000L,
		)

	private fun productLane(
		binding: ExecutableSourceLaneBinding,
		rolloutRevision: Long,
	) = SourceProductProjectionLaneEntity(
		sourceKind = binding.source.stableCode,
		bindingGeneration = binding.bindingGeneration,
		projectionId = binding.projectionId,
		projectionVersion = binding.projectionVersion,
		captureModeMask = binding.captureModeMask,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
		activatedRolloutRevision = rolloutRevision,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = 0L,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		installedAtMs = 1_000L,
		updatedAtMs = 1_000L,
	)

	private suspend fun appendWalEvents(count: Int) {
		repeat(count) { index ->
			database.sourceEventWalDao().insertIgnoringDuplicate(
				SourceEventWalEntity(
					eventId = "event-${index + 1}",
					providerDedupKey = null,
					logicalTrackingId = null,
					serviceRunId = null,
					sourceKind = SourceKind.STEPS.stableCode,
					sourceInstanceId = "steps-instance",
					registrationGeneration = 1L,
					sourceSequence = (index + 1).toLong(),
					configRevision = 1L,
					planAttribution = 0,
					clockDomainId = "boot-1",
					observedElapsedNanos = (index + 1).toLong(),
					receivedElapsedNanos = (index + 1).toLong(),
					wallTimeMs = (index + 1).toLong(),
					wallTimeUncertaintyMs = 0L,
					capturedCollectedDataEpoch = 0L,
					acquiredAtMs = (index + 1).toLong(),
					qualityFlags = 0L,
					qualityConfidence = null,
					payloadVersion = 1,
					payload = byteArrayOf(1),
					payloadChecksum = "checksum-${index + 1}",
					createdAtMs = (index + 1).toLong(),
				),
			)
		}
	}

	private fun stepsSessionDemand() = SourceDemandEntity(
		demandId = "steps-session-demand",
		consumerId = "session:tracking-1",
		sourceKind = SourceKind.STEPS.stableCode,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = "tracking-1",
		serviceRunId = "run-1",
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		maximumAgeMs = 60_000L,
		desiredLatencyMs = 1_000L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 10L,
		requestedAtMs = 10L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun stepsControlDemand() = SourceDemandEntity(
		demandId = "steps-control-demand",
		consumerId = "app:automation",
		sourceKind = SourceKind.STEPS.stableCode,
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = false,
		qosCode = 1,
		maximumAgeMs = 60_000L,
		desiredLatencyMs = 1_000L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 21L,
		requestedAtMs = 21L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun activeStepsRegistration() = ProviderRegistrationGenerationEntity(
		sourceKind = SourceKind.STEPS.stableCode,
		registrationGeneration = 1L,
		sourceInstanceId = "steps-instance",
		ownerScope = "process",
		clockDomainId = "boot-1",
		physicalConfigurationFingerprint = "steps-config",
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-1",
		status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 1L,
		reservedElapsedRealtimeNanos = 1L,
		acceptedAtMs = 2L,
		acceptedElapsedRealtimeNanos = 2L,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = null,
	)

	private fun eventShadowEntity(
		revision: Long,
		stepsStage: ProductProjectionStage = ProductProjectionStage.EVENT_SHADOW,
	) = TrackingRolloutStateEntity(
		revision = revision,
		schemaVersion = TrackingRolloutState.CURRENT_SCHEMA_VERSION,
		coordinatorMode = CoordinatorMode.EVENT.name,
		projectionMode = SourceKind.entries.joinToString(",") { source ->
			val stage = if (source == SourceKind.STEPS) {
				stepsStage
			} else {
				ProductProjectionStage.LEGACY_CANONICAL
			}
			val mask = if (source == SourceKind.STEPS) MANUAL_MASK else 0L
			"${source.stableCode}:${stage.name}:$mask"
		},
		sourceOwners = SourceKind.entries.joinToString(",") { source ->
			val owner = if (source == SourceKind.STEPS) SourceOwner.EVENT else SourceOwner.CONTAINED
			"${source.stableCode}:${owner.name}"
		},
		semanticSettingsEnabled = true,
		batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE.name,
		updatedAtMs = 1_000L,
	)

	private companion object {
		const val STEPS_OUTPUT_CONTRACT = "steps-interval"
		val MANUAL_MASK = CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask
		val STEPS_V1 = ExecutableSourceLaneBinding(
			SourceKind.STEPS,
			1L,
			STEPS_OUTPUT_CONTRACT,
			1,
			setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
		)
		val STEPS_V2 = STEPS_V1.copy(
			bindingGeneration = 2L,
			captureModes = setOf(
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
				CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
			),
		)
		val PRESSURE_V1 = ExecutableSourceLaneBinding(
			SourceKind.PRESSURE,
			1L,
			"pressure-samples",
			1,
			setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
		)
		val TEST_CATALOG = ExecutableSourceLaneCatalog.explicit(STEPS_V1, STEPS_V2, PRESSURE_V1)
	}
}
