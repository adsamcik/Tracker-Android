package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainMaintenanceResult
import com.adsamcik.tracker.shared.base.database.StepsCountDomainRetirementEvidence
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchema
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchemaState
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.dao.RawSourceRunWalGenerationEvidence
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalDao
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.PreviousExitSourceSessionFinalizer
import com.adsamcik.tracker.tracker.source.ingress.AdmissionResult
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityTier
import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityRequest
import com.adsamcik.tracker.tracker.source.catalog.SourceCatalogAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceImplementationCatalog
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailabilityEvidence
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderPermission
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartActionRepository
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartServiceValidation
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainSignal
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEffectConsumer
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEffectValidator
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.OwnedSourceShutdown
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.RuntimeAdmissionSnapshot
import com.adsamcik.tracker.tracker.source.runtime.RuntimeCheckpointLifecycle
import com.adsamcik.tracker.tracker.source.runtime.SensorRuntimeCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.SENSOR_RUNTIME_CHECKPOINT_VERSION
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementOutcome
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementRetryReason
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeClaim
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import com.adsamcik.tracker.tracker.source.runtime.encodeSensorRuntimeCheckpoint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthoritativeSessionCoordinatorTest {
	private lateinit var database: AppDatabase
	private lateinit var runtime: FakeStepsRuntime
	private lateinit var locationRuntime: FakeLocationRuntime
	private lateinit var subject: AuthoritativeSessionCoordinator
	private lateinit var policy: RoomSourcePolicyRepository
	private lateinit var activityAutomationDrainSignal: RecordingActivityAutomationDrainSignal
	private lateinit var activityAutomationEpochAuthority: ActivityAutomationEpochAuthority
	private lateinit var sourceProductDrainRouter: RecordingSourceProductDrainRouter
	private lateinit var leaseClock: FixedClock
	private lateinit var rolloutSnapshot: TrackingRolloutState
	private var currentBootId = "boot-1"
	private var activityLocked = false

	@Before
	@Suppress("LongMethod") // Shared exact-authority fixture for the coordinator test matrix.
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		activityLocked = false
		currentBootId = "boot-1"
		rolloutSnapshot = fixedEventRollout()
		leaseClock = FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 1_000_000L)
		database = AppDatabase.testDatabase(context)
		policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", 1_000_000, 1_000)
		}
		runBlocking {
			policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
			database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 0L))
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 0L,
				),
			)
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 0L,
				),
			)
		}
		runtime = FakeStepsRuntime(database)
		locationRuntime = FakeLocationRuntime()
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.admit(any()) } returns
			AdmissionResult.Admitted(SourceEventId("test-admitted-event"), 1L)
		coEvery { ingress.committedBatch(any(), any()) } returns emptyList()
		val dispatcher = ProjectionDispatcher(database, emptySet())
		activityAutomationDrainSignal = RecordingActivityAutomationDrainSignal()
		val lockManager = mockk<LockManager>(relaxed = true)
		every { lockManager.isLocked } answers { activityLocked }
		every { lockManager.isLockedFlow } returns MutableStateFlow(false)
		activityAutomationEpochAuthority = ActivityAutomationEpochAuthority(
			database,
			context,
			lockManager,
			leaseClock,
			BootClockDomainProvider { currentBootId },
		)
		sourceProductDrainRouter = RecordingSourceProductDrainRouter()
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime, locationRuntime)),
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, dispatcher),
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			FakeSourceCallerDemandDispatcher(database, SourceBroker(database)),
			rolloutStore = fixedEventRolloutStore(),
			sourceProductDrainRouter = sourceProductDrainRouter,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `session is durable before source start and closes after source drain`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()
		started.sourceCallerAuthorityReference shouldBe
			com.adsamcik.tracker.tracker.api.SourceCallerReplayReference(
				"test:${started.logicalTrackingId}:1",
			)

		runtime.stateObservedAtStart shouldBe SessionLifecycleState.STARTING.name
		runtime.manifestRevisionObservedAtStart shouldBe 1L
		runtime.intentDesiredStateObservedAtStart shouldBe LifecycleDesiredState.ACTIVE.name
		runtime.actionStatusObservedAtStart shouldBe LifecycleActionStatus.APPLYING.name
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe started.serviceRunId
		val manifest = requireNotNull(database.sourceSessionDao().manifest(started.logicalTrackingId, 1L))
		manifest.serviceRunId shouldBe started.serviceRunId
		database.sourceSessionDao().lifecycleIntent(started.logicalTrackingId, 1L)
			?.sourceCallerAuthorityReference shouldBe
			"test:${started.logicalTrackingId}:1"
		val callerAuthority = database.sourceCallerAuthorityDao()
			.rows("test:${started.logicalTrackingId}:1")
		callerAuthority.size shouldBe 1
		com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEffectChecksum
			.isAuthentic(callerAuthority) shouldBe true
		val stepsBinding = database.sourceSessionDao().manifestSources(started.logicalTrackingId, 1L).single()
		stepsBinding.outputDestination shouldBe SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		stepsBinding.writerOwner shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		stepsBinding.writerOwnerGeneration shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		stepsBinding.writerProjectionId shouldBe null
		database.sourcePlanStateDao().latestRevision()?.status shouldBe DesiredPlanStatus.EFFECTIVE.name
		val activeDemand = database.sourceBrokerDao()
			.currentDemands("session:${started.logicalTrackingId}")
			.single()
		activeDemand.sourceKind shouldBe SourceKind.STEPS.stableCode
		activeDemand.purpose shouldBe SourceBrokerPurpose.SESSION_CAPTURE
		activeDemand.manifestRevision shouldBe 1L
		activeDemand.status shouldBe SourceDemandEntity.STATUS_ACTIVE
		activeDemand.sourceCallerAuthorityReference shouldBe
			"test:${started.logicalTrackingId}:1"

		val stopped = subject.stop(
			SessionStopRequest("test-owner", "manual", 2_000, 2_000_000, "boot-1", perSourceTimeoutMs = 100),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.finalAdmissionOrdinal shouldBe 0L
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe null
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single { completeness -> completeness.sourceKind == SourceKind.STEPS.stableCode }
			.appDrainComplete shouldBe true
		database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().let { receipt ->
			receipt.sourceInstanceId shouldBe "steps-instance"
			receipt.registrationGeneration shouldBe 1L
			receipt.state shouldBe
				com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.STATE_ACKNOWLEDGED
			receipt.stopStatus shouldBe SourceStopStatus.COMPLETE.name
		}

		database.sourceBrokerDao().currentDemands("session:${started.logicalTrackingId}") shouldBe emptyList()
		database.sourceBrokerDao().demandHistory("session:${started.logicalTrackingId}")
			.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
		database.sourceCallerAuthorityDao().rows("test:${started.logicalTrackingId}:1")
			.map { it.status }.distinct() shouldBe listOf("RETIRED")
		runtime.closed shouldBe true
		sourceProductDrainRouter.requests shouldBe emptyList()
	}

	@Test
	fun `session start rejects catalog permission failure before provider start`() = runTest {
		val catalog = mockk<SourceImplementationCatalog>()
		coEvery { catalog.availability(any()) } returns SourceCatalogAvailability.Executable(
			SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(SourceKind.STEPS)),
			),
		)
		var providerCalls = 0
		val providers: Map<
			SourceKind,
			Provider<ClaimedSourceRuntime<out SourcePlan>>,
		> = mapOf(
			SourceKind.STEPS to Provider {
				providerCalls++
				runtime
			},
		)
		replaceRuntimeRegistry(SourceRuntimeRegistry(providers, catalog, requireAllSources = false))

		subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.InvalidIntent>().code shouldBe
			"SOURCE_CATALOG_STEPS_SESSION_CAPTURE_PERMISSION_REQUIRED"
		providerCalls shouldBe 0
		runtime.startCount shouldBe 0
		database.sourceSessionDao().activeSession() shouldBe null
		coVerify(exactly = 1) {
			catalog.availability(
				match { request ->
					request.source == SourceKind.STEPS &&
						request.purpose == TrackingPurpose.SESSION_CAPTURE &&
						request.plan.source == SourceKind.STEPS &&
						request.tier == SourceAvailabilityTier.MANUAL_FOREGROUND_START
				},
			)
		}
	}

	@Test
	fun `missing optional sensors are typed blocked while location starts without resolving them`() =
		runTest {
			val catalog = mockk<SourceImplementationCatalog>()
			coEvery { catalog.availability(any()) } answers {
				val request = firstArg<SourceAvailabilityRequest>()
				when (request.source) {
					SourceKind.LOCATION -> SourceCatalogAvailability.Executable(
						SourceProviderAvailability.Available(),
					)
					SourceKind.STEPS,
					SourceKind.PRESSURE,
					-> SourceCatalogAvailability.Executable(
						SourceProviderAvailability.HardwareUnavailable(
							SourceProviderAvailabilityEvidence.Runtime(
								request.source,
								setOf(
									SourceDegradedReason.HARDWARE_UNAVAILABLE,
								),
							),
						),
					)
					else -> error("Unexpected source")
				}
			}
			var locationProviderCalls = 0
			var stepsProviderCalls = 0
			var pressureProviderCalls = 0
			val providers: Map<
				SourceKind,
				Provider<ClaimedSourceRuntime<out SourcePlan>>,
			> = mapOf(
				SourceKind.LOCATION to Provider {
					locationProviderCalls++
					locationRuntime
				},
				SourceKind.STEPS to Provider {
					stepsProviderCalls++
					runtime
				},
				SourceKind.PRESSURE to Provider {
					pressureProviderCalls++
					error("Unavailable Pressure runtime must stay unresolved")
				},
			)
			replaceRuntimeRegistry(SourceRuntimeRegistry(providers, catalog, requireAllSources = false))
			val base = stepsAndLocationPlan(1L, stepsEnabled = true)
			val plan = base.copy(
				planId = "location-with-missing-sensors",
				plans = base.plans + (
					SourceKind.PRESSURE to PressurePlan(
						1L,
						enabled = true,
						hardwareSamplePeriodMicros = 200_000,
						maximumReportLatencyMicros = 10_000_000,
						aggregationWindowMs = 10_000L,
					)
				),
			)

			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "partial-availability-logical",
					serviceRunId = "partial-availability-run",
					plan = plan,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()

			started.status shouldBe DesiredPlanStatus.DEGRADED
			started.applied.associateBy(AppliedSourcePlan::source).let { outcomes ->
				outcomes.getValue(SourceKind.LOCATION).status shouldBe SourceApplyStatus.APPLIED
				outcomes.getValue(SourceKind.STEPS).let { blocked ->
					blocked.status shouldBe SourceApplyStatus.BLOCKED
					blocked.degradedReasons shouldBe
						setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE)
				}
				outcomes.getValue(SourceKind.PRESSURE).let { blocked ->
					blocked.status shouldBe SourceApplyStatus.BLOCKED
					blocked.degradedReasons shouldBe
						setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE)
				}
			}
			database.sourceSessionDao()
				.manifestSources(started.logicalTrackingId, 1L)
				.filter { binding ->
					binding.purpose == SessionManifestPurpose.SESSION_CAPTURE.name
				}
				.map(SessionManifestSourceEntity::sourceKind)
				.toSet() shouldBe setOf(SourceKind.LOCATION.stableCode)
			database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
				.mapNotNull(LifecycleDesiredActionEntity::sourceKind)
				.toSet() shouldBe setOf(SourceKind.LOCATION.stableCode)
			database.sourceBrokerDao()
				.currentDemands("session:${started.logicalTrackingId}")
				.map(SourceDemandEntity::sourceKind)
				.toSet() shouldBe setOf(SourceKind.LOCATION.stableCode)
			locationProviderCalls shouldBe 1
			stepsProviderCalls shouldBe 0
			pressureProviderCalls shouldBe 0
			runtime.startCount shouldBe 0
			locationRuntime.isActive shouldBe true
		}

	@Test
	fun `prepared apply blocks newly unavailable accepted source without resolving its provider`() =
		runTest {
			val catalog = mockk<SourceImplementationCatalog>()
			var stepsAvailable = true
			coEvery { catalog.availability(any()) } answers {
				when (firstArg<SourceAvailabilityRequest>().source) {
					SourceKind.LOCATION -> SourceCatalogAvailability.Executable(
						SourceProviderAvailability.Available(),
					)
					SourceKind.STEPS -> SourceCatalogAvailability.Executable(if (stepsAvailable) {
						SourceProviderAvailability.Available()
					} else {
						SourceProviderAvailability.HardwareUnavailable(
							SourceProviderAvailabilityEvidence.Runtime(
								SourceKind.STEPS,
								setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE),
							),
						)
					})
					else -> error("Unexpected source")
				}
			}
			var locationProviderCalls = 0
			var stepsProviderCalls = 0
			val providers: Map<
				SourceKind,
				Provider<ClaimedSourceRuntime<out SourcePlan>>,
			> = mapOf(
				SourceKind.LOCATION to Provider {
					locationProviderCalls++
					locationRuntime
				},
				SourceKind.STEPS to Provider {
					stepsProviderCalls++
					runtime
				},
			)
			replaceRuntimeRegistry(SourceRuntimeRegistry(providers, catalog, requireAllSources = false))
			val token = PreparedTrackingStartToken("partial-prepared-token")
			val request = startRequest().copy(
				logicalTrackingId = "partial-prepared-logical",
				serviceRunId = "partial-prepared-run",
				plan = stepsAndLocationPlan(1L, stepsEnabled = true),
			)

			val prepared = subject.prepareAndroidStart(
				request,
				AndroidStartDeliveryMetadata(token, 1L, true, false),
			).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

			locationProviderCalls shouldBe 0
			stepsProviderCalls shouldBe 0
			database.sourceSessionDao()
				.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
				.filter { binding ->
					binding.purpose == SessionManifestPurpose.SESSION_CAPTURE.name
				}
				.map(SessionManifestSourceEntity::sourceKind)
				.toSet() shouldBe setOf(
					SourceKind.LOCATION.stableCode,
					SourceKind.STEPS.stableCode,
				)
			database.sourceSessionDao().lifecycleActions(prepared.logicalTrackingId)
				.mapNotNull(LifecycleDesiredActionEntity::sourceKind)
				.toSet() shouldBe setOf(
					SourceKind.LOCATION.stableCode,
					SourceKind.STEPS.stableCode,
				)

			subject.markAndroidStartEnqueued(token, 1L, 1_050L) shouldBe true
			subject.claimAndroidStart(
				token,
				1L,
				"boot-1",
				1_100_000L,
				1_100L,
			).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
				.start.acceptedSources shouldBe setOf(SourceKind.LOCATION, SourceKind.STEPS)
			stepsAvailable = false
			subject.markPreparedForegroundAccepted(
				token,
				1L,
				"boot-1",
				1_200_000L,
				1_200L,
			) shouldBe true
			database.sourceBrokerDao()
				.currentDemands("session:${prepared.logicalTrackingId}")
				.map(SourceDemandEntity::sourceKind)
				.toSet() shouldBe setOf(SourceKind.LOCATION.stableCode)
			locationProviderCalls shouldBe 0
			stepsProviderCalls shouldBe 0

			val started = subject.applyPreparedAndroidStart(
				token,
				1L,
				"boot-1",
				1_300_000L,
				1_300L,
			).shouldBeInstanceOf<SessionStartResult.Started>()

			started.status shouldBe DesiredPlanStatus.DEGRADED
			started.applied.associateBy(AppliedSourcePlan::source).let { outcomes ->
				outcomes.getValue(SourceKind.LOCATION).status shouldBe SourceApplyStatus.APPLIED
				outcomes.getValue(SourceKind.STEPS).let { blocked ->
					blocked.status shouldBe SourceApplyStatus.BLOCKED
					blocked.degradedReasons shouldBe
						setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE)
				}
			}
			locationProviderCalls shouldBe 1
			stepsProviderCalls shouldBe 0
			runtime.startCount shouldBe 0
		}

	@Test
	fun `candidate source settlement observes frozen STOPPING state before finalization`() = runTest {
		val binding = installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val drainOrder = mutableListOf<String>()
		val controlCoordinator = mockk<TrackingCoordinator>()
		coEvery { controlCoordinator.drainAvailable(any(), any()) } coAnswers {
			drainOrder += "control"
			CoordinatorDrainResult.Complete(0L, 0)
		}
		replaceEventCoordinator(controlCoordinator)
		sourceProductDrainRouter.onDrain = { request ->
			drainOrder += "source:${request.source.name}"
			database.sourceSessionDao().session(request.logicalTrackingId)?.let { session ->
				session.state shouldBe SessionLifecycleState.STOPPING.name
				session.currentServiceRunId shouldBe request.serviceRunId
				session.cutoffElapsedNanos shouldBe request.cutoffElapsedRealtimeNanos
				session.finalAdmissionOrdinal shouldBe request.settlementHighWaterAdmissionOrdinal
			}
			SourceProductDrainResult.Complete(
				request,
				request.sourceHighWaterAdmissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "source-drain-order",
				serviceRunId = "source-drain-order-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.stop(
			SessionStopRequest(
				"source-drain-order-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		sourceProductDrainRouter.requests.single().let { request ->
			request.source shouldBe SourceKind.STEPS
			request.logicalTrackingId shouldBe started.logicalTrackingId
			request.serviceRunId shouldBe started.serviceRunId
			(request.target as SourceProductDrainTarget.SourceLocalWriter).let { target ->
				target.projectionId shouldBe binding.projectionId
				target.projectionVersion shouldBe binding.projectionVersion
				target.bindingGeneration shouldBe binding.bindingGeneration
			}
		}
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		drainOrder shouldBe listOf("control", "source:STEPS")
	}

	@Test
	fun `terminal unavailable is a sibling drain result contract`() {
		val request = SourceProductDrainRequest(
			source = SourceKind.STEPS,
			logicalTrackingId = "unavailable-contract",
			serviceRunId = "unavailable-contract-run",
			cutoffElapsedRealtimeNanos = 1L,
			cutoffWallTimeMs = 1L,
			settlementHighWaterAdmissionOrdinal = 0L,
			sourceHighWaterAdmissionOrdinal = 0L,
			memberships = listOf(
				SourceDrainMembership(
					sourceInstanceId = "unavailable-steps",
					registrationGeneration = 0L,
					lastAdmissionOrdinal = null,
					lastSourceSequence = null,
					appDrainComplete = true,
					providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE.name,
					stopStatus = SourceStopStatus.PROVIDER_FAILED.name,
					unresolvedSequenceStart = null,
					unresolvedSequenceEndInclusive = null,
				),
			),
			target = SourceProductDrainTarget.SourceLocalWriter(
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				writerOwnerGeneration = 1L,
				projectionId = StepsSessionFactProjectionLane.WRITER_ID,
				projectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
				bindingGeneration = 1L,
			),
			runManifestRevisions = listOf(1L),
		)

		val result: SourceProductDrainResult =
			SourceProductDrainResult.Unavailable(request, "TERMINAL_UNAVAILABLE")

		result.shouldBeInstanceOf<SourceProductDrainResult.Unavailable>().reason shouldBe
			"TERMINAL_UNAVAILABLE"
	}

	@Test
	fun `recorded source poison defers finalization without suppressing settlement evidence`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		sourceProductDrainRouter.onDrain = { request ->
			SourceProductDrainResult.Failed(
				request,
				lastMaterializedAdmissionOrdinal = 0L,
				failedAdmissionOrdinal = request.sourceHighWaterAdmissionOrdinal.takeIf { it > 0L },
				failureCode = "TEST_TERMINAL_SOURCE_POISON",
				terminalFailureRecorded = true,
			)
		}
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "source-poison",
				serviceRunId = "source-poison-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		val pending = subject.stop(
			SessionStopRequest("source-poison-owner", "USER_STOP", 2_000L, 2_000_000L, "boot-1"),
		).shouldBeInstanceOf<SessionStopResult.DrainPending>()

		pending.source shouldBe SourceKind.STEPS
		pending.reason shouldBe "TEST_TERMINAL_SOURCE_POISON"
		pending.sourceResults.single().shouldBeInstanceOf<SourceProductDrainResult.Failed>()
			.terminalFailureRecorded shouldBe true
		database.sourceSessionDao().session(started.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.STOPPING.name
			session.finalAdmissionOrdinal shouldBe pending.requiredOrdinal
		}
	}

	@Test
	fun `one poisoned source does not suppress an independent captured source drain`() = runTest {
		val stepsBinding = installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS, SourceKind.LOCATION),
			revision = 2L,
			captureModes = mapOf(
				SourceKind.STEPS to stepsBinding.captureModes,
				SourceKind.LOCATION to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 2L))
		sourceProductDrainRouter.onDrain = { request ->
			if (request.source == SourceKind.STEPS) {
				SourceProductDrainResult.Failed(
					request,
					lastMaterializedAdmissionOrdinal = 0L,
					failedAdmissionOrdinal = null,
					failureCode = "TEST_STEPS_POISON",
					terminalFailureRecorded = true,
				)
			} else {
				SourceProductDrainResult.Complete(
					request,
					request.sourceHighWaterAdmissionOrdinal,
					factsInserted = 0,
					eventsValidated = 0,
				)
			}
		}
		val request = startRequest().copy(
			logicalTrackingId = "partial-source-poison",
			serviceRunId = "partial-source-poison-run",
			rolloutRevision = rolloutSnapshot.revision,
			plan = AcquisitionPlanRevision(
				revision = 1L,
				planId = "steps-and-location",
				createdAtMs = 1_000L,
				plans = mapOf(
					SourceKind.STEPS to StepsPlan(1L, true, 60_000L, 15_000L, false),
					SourceKind.LOCATION to LocationPlan(
						revision = 1L,
						backend = LocationBackend.FUSED,
						mode = LocationMode.BALANCED,
						requestedIntervalMs = 2_000L,
						minimumUpdateIntervalMs = 2_000L,
						minimumDisplacementMeters = 10f,
						maximumBatchDelayMs = 10_000L,
						preciseLocationAvailable = true,
					),
				),
				sourcePolicyRevision = 1L,
			),
		)
		subject.start(request).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.stop(
			SessionStopRequest("partial-source-owner", "USER_STOP", 2_000L, 2_000_000L, "boot-1"),
		).shouldBeInstanceOf<SessionStopResult.DrainPending>()

		sourceProductDrainRouter.requests.map(SourceProductDrainRequest::source) shouldBe
			listOf(SourceKind.LOCATION, SourceKind.STEPS)
	}

	@Test
	fun `source drain cancellation preserves the frozen run for retry`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		sourceProductDrainRouter.onDrain = { throw CancellationException("test cancellation") }
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "source-drain-cancel",
				serviceRunId = "source-drain-cancel-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		runCatching {
			subject.stop(
				SessionStopRequest("source-drain-cancel-owner", "USER_STOP", 2_000L, 2_000_000L, "boot-1"),
			)
		}.exceptionOrNull().shouldBeInstanceOf<CancellationException>()

		database.sourceSessionDao().session(started.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.STOPPING.name
			session.currentServiceRunId shouldBe started.serviceRunId
			session.finalAdmissionOrdinal shouldBe 0L
		}
	}

	@Test
	fun `process death settlement finalizes with durable unavailable Steps product`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "retirement-crash-gap",
				serviceRunId = "retirement-crash-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.cancelAfterPhysicalShutdown = true

		assertFailsWith<CancellationException> {
			subject.stop(
				SessionStopRequest(
					"retirement-crash-owner",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			)
		}

		database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().state shouldBe
			com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.STATE_REQUESTED
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "steps-instance",
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "retired-steps",
				collectedDataEpoch = 0L,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "prior-process",
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = 1_000L,
				reservedElapsedRealtimeNanos = 1_000_000L,
				acceptedAtMs = 1_000L,
				acceptedElapsedRealtimeNanos = 1_000_000L,
				retiredAtMs = 2_000L,
				retiredElapsedRealtimeNanos = 2_000_000L,
				failureCode = "PRIOR_PROCESS_ENDED",
			),
		)
		replaceRuntime(FakeStepsRuntime(database))

		val stopped = subject.stop(
			SessionStopRequest(
				"retirement-crash-retry",
				"USER_STOP",
				2_100L,
				2_100_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.acknowledgements.single().status shouldBe SourceStopStatus.PROCESS_RESTARTED
		sourceProductDrainRouter.requests shouldBe emptyList()
		database.sourceSessionDao().session(started.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.FINALIZED.name
			session.failureCode shouldBe "STOP_PARTIAL"
		}
		database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().state shouldBe
			com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.STATE_INTERRUPTED
	}

	@Test
	fun `terminal partial Steps settlement finalizes without invoking a queryable product lane`() =
		runTest {
			installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "terminal-partial-product",
					serviceRunId = "terminal-partial-product-run",
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()
			val admissionOrdinal = insertTerminalStepsWal(started)
			runtime.lastAdmissionOrdinal = admissionOrdinal
			runtime.stopStatus = SourceStopStatus.PARTIAL_UNOBSERVABLE
			replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))

			val stopped = subject.stop(
				SessionStopRequest(
					"terminal-partial-product-owner",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.Stopped>()

			stopped.acknowledgements.single().status shouldBe
				SourceStopStatus.PARTIAL_UNOBSERVABLE
			sourceProductDrainRouter.requests shouldBe emptyList()
			database.sourceSessionDao().session(started.logicalTrackingId)?.let { session ->
				session.state shouldBe SessionLifecycleState.FINALIZED.name
				session.failureCode shouldBe "STOP_PARTIAL"
			}
			database.openHelper.writableDatabase.query(
				"SELECT operation FROM steps_count_domain_owner_revision " +
					"WHERE owner_kind = 'SESSION_COMPLETENESS'",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(0) shouldBe "UNPROVEN"
			}
		}

	@Test
	fun `raw WAL high-water rejects malformed aggregate inputs during drain planning`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "raw-wal-plan",
				serviceRunId = "raw-wal-plan-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val admissionOrdinal = insertTerminalStepsWal(started)
		runtime.lastAdmissionOrdinal = admissionOrdinal
		replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = 'capture' " +
				"WHERE admission_ordinal = ?",
			arrayOf(admissionOrdinal),
		)

		val pending = subject.stop(
			SessionStopRequest(
				"raw-wal-plan-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.DrainPending>()

		pending.reason shouldBe "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE"
		sourceProductDrainRouter.requests shouldBe emptyList()
	}

	@Test
	fun `exact run cannot hide a cleared middle capture row behind later completeness`() = runTest {
		val logicalTrackingId = "cleared-middle-purpose-logical"
		val serviceRunId = "cleared-middle-purpose-run"
		val sourceInstanceId = "cleared-middle-purpose-instance"
		insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val clearedOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 2L,
			recordStepsFact = false,
		)
		val terminalOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 3L,
			recordStepsFact = false,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
				"WHERE admission_ordinal = ?",
			arrayOf(SourceBrokerPurpose.MASK_CONTROL_AUTOSTART, clearedOrdinal),
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = terminalOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(sourceInstanceId, 1L, terminalOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `exact run cannot hide a cleared terminal capture row`() = runTest {
		val logicalTrackingId = "cleared-terminal-purpose-logical"
		val serviceRunId = "cleared-terminal-purpose-run"
		val sourceInstanceId = "cleared-terminal-purpose-instance"
		insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val terminalOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 2L,
			recordStepsFact = false,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = 0 " +
				"WHERE admission_ordinal = ?",
			arrayOf(terminalOrdinal),
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = terminalOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(sourceInstanceId, 1L, terminalOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `capture-owned WAL rejects malformed purpose mask storage and values`() = runTest {
		val logicalTrackingId = "malformed-purpose-logical"
		val serviceRunId = "malformed-purpose-run"
		val sourceInstanceId = "malformed-purpose-instance"
		val terminalOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			recordStepsFact = false,
		)
		val corruptions = listOf<Any>(
			"SESSION_CAPTURE",
			byteArrayOf(1, 2),
			-1L,
			SourceBrokerPurpose.ALL_MASK + 1L,
		)

		for (corruption in corruptions) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
					"WHERE admission_ordinal = ?",
				arrayOf(corruption, terminalOrdinal),
			)

			sourceRunHighWater(
				database = database,
				source = SourceKind.STEPS,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				runManifestRevisions = listOf(1L),
				throughOrdinal = terminalOrdinal,
				productMemberships = listOf(
					terminalDrainMembership(sourceInstanceId, 1L, terminalOrdinal),
				),
				retirementClaims = listOf(
					terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
				),
			) shouldBe SourceRunHighWaterRead.Unverifiable
		}
	}

	@Test
	fun `exact-run non-member WAL must prove valid control-only masks before exclusion`() = runTest {
		val logicalTrackingId = "non-member-purpose-logical"
		val serviceRunId = "non-member-purpose-run"
		val captureInstanceId = "non-member-purpose-capture"
		val controlInstanceId = "non-member-purpose-control"
		val captureOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = captureInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = controlInstanceId,
			sourceSequence = 2L,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			recordStepsFact = false,
		)
		suspend fun read() = sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = controlOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(captureInstanceId, 1L, captureOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(captureInstanceId, 1L, cleanupOnly = false),
			),
		)
		read() shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
		val malformedMasks = listOf<Any>(
			0L,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or
				SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE or
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			1.5,
			byteArrayOf(1, 2),
			"CONTROL_ONLY",
			-1L,
			SourceBrokerPurpose.ALL_MASK + 1L,
		)

		for (malformedMask in malformedMasks) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
					"WHERE admission_ordinal = ?",
				arrayOf(malformedMask, controlOrdinal),
			)

			read() shouldBe SourceRunHighWaterRead.Unverifiable
		}

		for (
			controlMask in listOf(
				SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				SourceBrokerPurpose.MASK_CONTROL_CONTINUATION,
				SourceBrokerPurpose.CONTROL_MASK,
			)
		) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
					"WHERE admission_ordinal = ?",
				arrayOf(controlMask, controlOrdinal),
			)
			read() shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
		}

		allowNullWalPurposeMaskForCorruptionTest()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = NULL " +
				"WHERE admission_ordinal = ?",
			arrayOf(controlOrdinal),
		)
		read() shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `product member control mask with out-of-run revision fails closed`() = runTest {
		val logicalTrackingId = "member-control-revision-logical"
		val serviceRunId = "member-control-revision-run"
		val sourceInstanceId = "member-control-revision-instance"
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevision = 3L,
			sourceInstanceId = sourceInstanceId,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			recordStepsFact = false,
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L, 2L),
			throughOrdinal = controlOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(sourceInstanceId, 1L, controlOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `retirement-claimed control mask with out-of-run revision fails closed`() = runTest {
		val logicalTrackingId = "claimed-control-revision-logical"
		val serviceRunId = "claimed-control-revision-run"
		val sourceInstanceId = "claimed-control-revision-instance"
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevision = 3L,
			sourceInstanceId = sourceInstanceId,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_CONTINUATION,
			recordStepsFact = false,
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L, 2L),
			throughOrdinal = controlOrdinal,
			productMemberships = emptyList(),
			retirementClaims = listOf(
				terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = true),
			),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `non-member control-only row after cutoff does not advance product high-water`() = runTest {
		val logicalTrackingId = "control-after-cutoff-logical"
		val serviceRunId = "control-after-cutoff-run"
		val captureInstanceId = "control-after-cutoff-capture"
		val captureOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = captureInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = "control-after-cutoff-non-member",
			sourceSequence = 2L,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_CONTINUATION,
			recordStepsFact = false,
		)
		database.sourceEventWalDao().rawExactRunSourceManifestAssociations(
			sourceKind = SourceKind.STEPS.stableCode,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = captureOrdinal,
			capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			controlPurposeMask = SourceBrokerPurpose.CONTROL_MASK,
			allowedPurposeMask = SourceBrokerPurpose.ALL_MASK,
			limit = 3,
		).single { row ->
			row.sourceInstanceId == "control-after-cutoff-non-member"
		}.let { row ->
			row.malformedRowCount shouldBe 0L
			row.productEligibleRowCount shouldBe 0L
			row.controlOnlyRowCount shouldBe 1L
			row.highWaterAdmissionOrdinal shouldBe null
		}
		(controlOrdinal > captureOrdinal) shouldBe true

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = captureOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(captureInstanceId, 1L, captureOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(captureInstanceId, 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
	}

	@Test
	fun `product member control-only row after cutoff fails closed`() = runTest {
		val logicalTrackingId = "member-control-after-cutoff-logical"
		val serviceRunId = "member-control-after-cutoff-run"
		val sourceInstanceId = "member-control-after-cutoff-instance"
		val captureOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 2L,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			recordStepsFact = false,
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = captureOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(sourceInstanceId, 1L, controlOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `non-member product and malformed rows after cutoff fail closed`() = runTest {
		val logicalTrackingId = "invalid-after-cutoff-logical"
		val serviceRunId = "invalid-after-cutoff-run"
		val captureInstanceId = "invalid-after-cutoff-capture"
		val captureOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = captureInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val invalidOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = "invalid-after-cutoff-non-member",
			sourceSequence = 2L,
			purposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			recordStepsFact = false,
		)
		suspend fun read() = sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = captureOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(captureInstanceId, 1L, captureOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(captureInstanceId, 1L, cleanupOnly = false),
			),
		)

		read() shouldBe SourceRunHighWaterRead.Unverifiable
		for (
			malformedMask in listOf(
				0L,
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or
					SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				SourceBrokerPurpose.ALL_MASK + 1L,
			)
		) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
					"WHERE admission_ordinal = ?",
				arrayOf(malformedMask, invalidOrdinal),
			)
			read() shouldBe SourceRunHighWaterRead.Unverifiable
		}
	}

	@Test
	fun `control-only-only provider group has zero product high-water`() = runTest {
		val logicalTrackingId = "control-only-group-logical"
		val serviceRunId = "control-only-group-run"
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = "control-only-group-instance",
			purposeMask = SourceBrokerPurpose.CONTROL_MASK,
			recordStepsFact = false,
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = controlOrdinal,
			productMemberships = emptyList(),
			retirementClaims = emptyList(),
		) shouldBe SourceRunHighWaterRead.Ready(0L)
	}

	@Test
	fun `exact-run non-member control rejects ASCII blank event and service run ids`() = runTest {
		val logicalTrackingId = "exact-control-identifiers-logical"
		val serviceRunId = "exact-control-identifiers-run"
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = "exact-control-identifiers-instance",
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			recordStepsFact = false,
		)
		val originalEventId =
			requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(controlOrdinal)).eventId
		suspend fun read(runId: String = serviceRunId) = sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = runId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = controlOrdinal,
			productMemberships = emptyList(),
			retirementClaims = emptyList(),
		)
		val asciiBlankIds = listOf("\t", "\n", "\u000B", "\u000C", "\r", " \t\n\r")

		read() shouldBe SourceRunHighWaterRead.Ready(0L)
		for (blankId in asciiBlankIds) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET event_id = ? WHERE admission_ordinal = ?",
				arrayOf(blankId, controlOrdinal),
			)
			read() shouldBe SourceRunHighWaterRead.Unverifiable
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET event_id = ? WHERE admission_ordinal = ?",
			arrayOf(originalEventId, controlOrdinal),
		)
		for (blankId in asciiBlankIds) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET service_run_id = ? WHERE admission_ordinal = ?",
				arrayOf(blankId, controlOrdinal),
			)
			read(blankId) shouldBe SourceRunHighWaterRead.Unverifiable
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET service_run_id = ? WHERE admission_ordinal = ?",
			arrayOf(serviceRunId, controlOrdinal),
		)
		read() shouldBe SourceRunHighWaterRead.Ready(0L)
	}

	@Test
	fun `wrong-run non-member WAL must prove valid control-only masks before exclusion`() = runTest {
		val logicalTrackingId = "wrong-run-non-member-logical"
		val serviceRunId = "wrong-run-non-member-current"
		val captureInstanceId = "wrong-run-non-member-capture"
		val controlInstanceId = "wrong-run-non-member-control"
		val captureOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = captureInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = "wrong-run-non-member-other",
			sourceInstanceId = controlInstanceId,
			sourceSequence = 2L,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			recordStepsFact = false,
		)
		suspend fun read() = sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = controlOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(captureInstanceId, 1L, captureOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(captureInstanceId, 1L, cleanupOnly = false),
			),
		)
		read() shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
		val malformedMasks = listOf<Any>(
			0L,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or
				SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE or
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			1.5,
			byteArrayOf(1, 2),
			"CONTROL_ONLY",
			-1L,
			SourceBrokerPurpose.ALL_MASK + 1L,
		)

		for (malformedMask in malformedMasks) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
					"WHERE admission_ordinal = ?",
				arrayOf(malformedMask, controlOrdinal),
			)

			read() shouldBe SourceRunHighWaterRead.Unverifiable
		}

		for (
			controlMask in listOf(
				SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				SourceBrokerPurpose.MASK_CONTROL_CONTINUATION,
				SourceBrokerPurpose.CONTROL_MASK,
			)
		) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
					"WHERE admission_ordinal = ?",
				arrayOf(controlMask, controlOrdinal),
			)
			read() shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
		}

		allowNullWalPurposeMaskForCorruptionTest()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = NULL " +
				"WHERE admission_ordinal = ?",
			arrayOf(controlOrdinal),
		)
		read() shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `wrong-run non-member control rejects ASCII blank event and service run ids`() = runTest {
		val logicalTrackingId = "wrong-control-identifiers-logical"
		val serviceRunId = "wrong-control-identifiers-current"
		val wrongServiceRunId = "wrong-control-identifiers-other"
		val captureInstanceId = "wrong-control-identifiers-capture"
		val captureOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = captureInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val controlOrdinal = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = wrongServiceRunId,
			sourceInstanceId = "wrong-control-identifiers-control",
			sourceSequence = 2L,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			recordStepsFact = false,
		)
		val originalEventId =
			requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(controlOrdinal)).eventId
		suspend fun read() = sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = controlOrdinal,
			productMemberships = listOf(
				terminalDrainMembership(captureInstanceId, 1L, captureOrdinal),
			),
			retirementClaims = listOf(
				terminalDrainClaim(captureInstanceId, 1L, cleanupOnly = false),
			),
		)
		val asciiBlankIds = listOf("\t", "\n", "\u000B", "\u000C", "\r", " \t\n\r")

		read() shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
		for (blankId in asciiBlankIds) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET event_id = ? WHERE admission_ordinal = ?",
				arrayOf(blankId, controlOrdinal),
			)
			read() shouldBe SourceRunHighWaterRead.Unverifiable
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET event_id = ? WHERE admission_ordinal = ?",
			arrayOf(originalEventId, controlOrdinal),
		)
		for (blankId in asciiBlankIds) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET service_run_id = ? WHERE admission_ordinal = ?",
				arrayOf(blankId, controlOrdinal),
			)
			read() shouldBe SourceRunHighWaterRead.Unverifiable
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET service_run_id = ? WHERE admission_ordinal = ?",
			arrayOf(wrongServiceRunId, controlOrdinal),
		)
		read() shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
	}

	@Test
	fun `non-member association rejects unavailable malformed count before exclusion`() = runTest {
		for (wrongRun in listOf(false, true)) {
			val mockedDatabase = mockk<AppDatabase>()
			val walDao = mockk<SourceEventWalDao>()
			every { mockedDatabase.sourceEventWalDao() } returns walDao
			coEvery {
				walDao.rawExactRunSourceManifestRevisionAssociations(
					SourceKind.STEPS.stableCode,
					"unavailable-malformed-logical",
					"unavailable-malformed-run",
					1L,
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
					SourceBrokerPurpose.CONTROL_MASK,
					SourceBrokerPurpose.ALL_MASK,
					null,
					any(),
				)
			} returns emptyList()
			val association = RawSourceRunWalGenerationEvidence(
				sourceInstanceId = "unavailable-malformed-control",
				registrationGeneration = 2L,
				lifecycleLeaseGeneration = 2L,
				associatedRowCount = 1L,
				malformedRowCount = null,
				productEligibleRowCount = 0L,
				controlOnlyRowCount = 1L,
				highWaterAdmissionOrdinal = 1L,
			)
			coEvery {
				walDao.rawExactRunSourceManifestAssociations(
					SourceKind.STEPS.stableCode,
					"unavailable-malformed-logical",
					"unavailable-malformed-run",
					listOf(1L),
					1L,
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
					SourceBrokerPurpose.CONTROL_MASK,
					SourceBrokerPurpose.ALL_MASK,
					any(),
				)
			} returns if (wrongRun) emptyList() else listOf(association)
			coEvery {
				walDao.rawWrongRunSourceManifestAssociations(
					SourceKind.STEPS.stableCode,
					"unavailable-malformed-logical",
					"unavailable-malformed-run",
					listOf(1L),
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
					SourceBrokerPurpose.CONTROL_MASK,
					SourceBrokerPurpose.ALL_MASK,
					any(),
				)
			} returns if (wrongRun) listOf(association) else emptyList()

			sourceRunHighWater(
				database = mockedDatabase,
				source = SourceKind.STEPS,
				logicalTrackingId = "unavailable-malformed-logical",
				serviceRunId = "unavailable-malformed-run",
				runManifestRevisions = listOf(1L),
				throughOrdinal = 1L,
				productMemberships = emptyList(),
				retirementClaims = emptyList(),
			) shouldBe SourceRunHighWaterRead.Unverifiable
		}
	}

	@Test
	fun `mixed capture and control WAL keeps control-only evidence outside product high-water`() =
		runTest {
			val logicalTrackingId = "mixed-purpose-logical"
			val serviceRunId = "mixed-purpose-run"
			val captureInstanceId = "mixed-purpose-capture-instance"
			val captureOrdinal = insertTerminalStepsWal(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				sourceInstanceId = captureInstanceId,
				sourceSequence = 1L,
				purposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE or
					SourceBrokerPurpose.MASK_CONTROL_CONTINUATION,
				recordStepsFact = false,
			)
			val controlOrdinal = insertTerminalStepsWal(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				sourceInstanceId = "mixed-purpose-control-instance",
				sourceSequence = 2L,
				purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				recordStepsFact = false,
			)

			sourceRunHighWater(
				database = database,
				source = SourceKind.STEPS,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				runManifestRevisions = listOf(1L),
				throughOrdinal = controlOrdinal,
				productMemberships = listOf(
					terminalDrainMembership(captureInstanceId, 1L, captureOrdinal),
				),
				retirementClaims = listOf(
					terminalDrainClaim(captureInstanceId, 1L, cleanupOnly = false),
				),
			) shouldBe SourceRunHighWaterRead.Ready(captureOrdinal)
		}

	@Test
	fun `raw WAL high-water exposes a logical owner mismatch on the globally unique service run`() =
		runTest {
			installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "raw-wal-logical-owner",
					serviceRunId = "raw-wal-logical-owner-run",
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()
			val admissionOrdinal = insertTerminalStepsWal(started)
			runtime.lastAdmissionOrdinal = admissionOrdinal
			replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET logical_tracking_id = 'foreign-logical-owner' " +
					"WHERE admission_ordinal = ?",
				arrayOf(admissionOrdinal),
			)

			val pending = subject.stop(
				SessionStopRequest(
					"raw-wal-logical-owner-stop",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.DrainPending>()

			pending.reason shouldBe "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE"
			sourceProductDrainRouter.requests shouldBe emptyList()
		}

	@Test
	fun `historical other-run nonblank identity on another revision cannot poison high-water`() =
		runTest {
			installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "raw-wal-historical-owner",
					serviceRunId = "raw-wal-current-run",
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()
			val currentOrdinal = insertTerminalStepsWal(started)
			val current = requireNotNull(
				database.sourceEventWalDao().getByAdmissionOrdinal(currentOrdinal),
			)
			val historicalUnsigned = current.copy(
				admissionOrdinal = 0L,
				eventId = "historical-malformed-service-run",
				providerDedupKey = "historical-malformed-service-run-dedup",
				serviceRunId = "historical-other-run",
				sourceSequence = current.sourceSequence + 1L,
				sessionManifestRevision = current.sessionManifestRevision?.plus(100L),
				integrityIdentity = "",
			)
			val historicalOrdinal = database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(
				historicalUnsigned.copy(
					integrityIdentity = historicalUnsigned.calculatedIntegrityIdentity(),
				),
			)
			runtime.lastAdmissionOrdinal = currentOrdinal
			replaceEventCoordinator(completedEventCoordinator(historicalOrdinal))

			subject.stop(
				SessionStopRequest(
					"raw-wal-historical-owner-stop",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.Stopped>()

			sourceProductDrainRouter.requests.single { request ->
				request.source == SourceKind.STEPS
			}.sourceHighWaterAdmissionOrdinal shouldBe currentOrdinal
		}

	@Test
	fun `current-run wrong null blob blank and whitespace service identities fail closed`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "raw-wal-current-malformed",
				serviceRunId = "raw-wal-current-malformed-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val admissionOrdinal = insertTerminalStepsWal(started)
		runtime.lastAdmissionOrdinal = admissionOrdinal
		replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))
		val corruptions = listOf<Any?>(
			"wrong-current-run",
			null,
			byteArrayOf(1, 2),
			"",
			" \t\n\r",
		)

		for ((index, corruption) in corruptions.withIndex()) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET service_run_id = ? WHERE admission_ordinal = ?",
				arrayOf(corruption, admissionOrdinal),
			)

			val pending = subject.stop(
				SessionStopRequest(
					"raw-wal-current-malformed-stop-$index",
					"USER_STOP",
					2_000L + index,
					2_000_000L + index,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.DrainPending>()

			pending.reason shouldBe "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE"
			sourceProductDrainRouter.requests shouldBe emptyList()
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET service_run_id = ? WHERE admission_ordinal = ?",
				arrayOf(started.serviceRunId, admissionOrdinal),
			)
		}

		subject.stop(
			SessionStopRequest(
				"raw-wal-current-malformed-restored",
				"USER_STOP",
				2_100L,
				2_100_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `exact run WAL accepts 101 301 and 2048 authenticated revisions across chunks`() = runTest {
		for (revisionCount in listOf(101, 301, MAX_RUN_DRAIN_MANIFEST_REVISIONS)) {
			val logicalTrackingId = "chunked-wal-logical-$revisionCount"
			val serviceRunId = "chunked-wal-run-$revisionCount"
			val sourceInstanceId = "chunked-wal-instance-$revisionCount"
			val runManifestRevisions = (1L..revisionCount.toLong()).toList()
			val walRevisions = listOf(
				1L,
				100L,
				101L,
				200L,
				201L,
				revisionCount.toLong(),
			).filter { revision -> revision <= revisionCount }.distinct()
			var highWater = 0L
			for ((index, revision) in walRevisions.withIndex()) {
				highWater = insertTerminalStepsWal(
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					manifestRevision = revision,
					sourceInstanceId = sourceInstanceId,
					sourceSequence = index.toLong() + 1L,
					recordStepsFact = false,
				)
			}

			sourceRunHighWater(
				database = database,
				source = SourceKind.STEPS,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				runManifestRevisions = runManifestRevisions,
				throughOrdinal = highWater,
				productMemberships = listOf(
					terminalDrainMembership(sourceInstanceId, 1L, highWater),
				),
				retirementClaims = listOf(
					terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
				),
			) shouldBe SourceRunHighWaterRead.Ready(highWater)
		}
	}

	@Test
	fun `exact run WAL rejects missing and out-of-run manifest revisions`() = runTest {
		for ((identity, manifestRevision) in listOf(
			"missing" to null,
			"out-of-run" to 3L,
		)) {
			val logicalTrackingId = "$identity-revision-logical"
			val serviceRunId = "$identity-revision-run"
			val sourceInstanceId = "$identity-revision-instance"
			val highWater = insertTerminalStepsWal(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifestRevision,
				sourceInstanceId = sourceInstanceId,
				recordStepsFact = false,
			)

			sourceRunHighWater(
				database = database,
				source = SourceKind.STEPS,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				runManifestRevisions = listOf(1L, 2L),
				throughOrdinal = highWater,
				productMemberships = listOf(
					terminalDrainMembership(sourceInstanceId, 1L, highWater),
				),
				retirementClaims = listOf(
					terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
				),
			) shouldBe SourceRunHighWaterRead.Unverifiable
		}
	}

	@Test
	fun `wrong nonblank service run with cleared capture bit is rejected across 301 revisions`() =
		runTest {
		val logicalTrackingId = "wrong-service-run-logical"
		val serviceRunId = "expected-service-run"
		val sourceInstanceId = "wrong-service-run-instance"
		insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevision = 1L,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val highWater = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = "wrong-service-run",
			manifestRevision = 301L,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 2L,
			recordStepsFact = false,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
				"WHERE admission_ordinal = ?",
			arrayOf(SourceBrokerPurpose.MASK_CONTROL_CONTINUATION, highWater),
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = (1L..301L).toList(),
			throughOrdinal = highWater,
			productMemberships = listOf(
				terminalDrainMembership(sourceInstanceId, 1L, highWater),
			),
			retirementClaims = listOf(
				terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `duplicate authenticated manifest revisions fail before WAL attribution`() = runTest {
		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = "duplicate-revision-logical",
			serviceRunId = "duplicate-revision-run",
			runManifestRevisions = listOf(1L, 1L),
			throughOrdinal = 1L,
			productMemberships = listOf(
				terminalDrainMembership("duplicate-revision-instance", 1L, 1L),
			),
			retirementClaims = listOf(
				terminalDrainClaim("duplicate-revision-instance", 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `duplicate WAL manifest revisions are each retained in the exact run high-water`() = runTest {
		val logicalTrackingId = "duplicate-wal-revision-logical"
		val serviceRunId = "duplicate-wal-revision-run"
		val sourceInstanceId = "duplicate-wal-revision-instance"
		insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevision = 1L,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val highWater = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevision = 1L,
			sourceInstanceId = sourceInstanceId,
			sourceSequence = 2L,
			recordStepsFact = false,
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = listOf(1L),
			throughOrdinal = highWater,
			productMemberships = listOf(
				terminalDrainMembership(sourceInstanceId, 1L, highWater),
			),
			retirementClaims = listOf(
				terminalDrainClaim(sourceInstanceId, 1L, cleanupOnly = false),
			),
		) shouldBe SourceRunHighWaterRead.Ready(highWater)
	}

	@Test
	fun `cleanup-only generation WAL remains visible across manifest chunks`() = runTest {
		val logicalTrackingId = "chunked-cleanup-generation-logical"
		val serviceRunId = "chunked-cleanup-generation-run"
		val productInstanceId = "chunked-product-instance"
		val cleanupInstanceId = "chunked-cleanup-instance"
		val productHighWater = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			registrationGeneration = 1L,
			manifestRevision = 1L,
			sourceInstanceId = productInstanceId,
			sourceSequence = 1L,
			recordStepsFact = false,
		)
		val highWater = insertTerminalStepsWal(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			registrationGeneration = 2L,
			manifestRevision = 101L,
			sourceInstanceId = cleanupInstanceId,
			lifecycleLeaseGeneration = 2L,
			sourceSequence = 1L,
			recordStepsFact = false,
		)

		sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = (1L..101L).toList(),
			throughOrdinal = highWater,
			productMemberships = listOf(
				terminalDrainMembership(productInstanceId, 1L, productHighWater),
			),
			retirementClaims = listOf(
				terminalDrainClaim(productInstanceId, 1L, cleanupOnly = false),
				terminalDrainClaim(
					cleanupInstanceId,
					2L,
					cleanupOnly = true,
					lifecycleLeaseGeneration = 2L,
				),
			),
		) shouldBe SourceRunHighWaterRead.CleanupOnlyProductEvidence
	}

	@Test
	fun `raw WAL high-water recheck rejects corruption after plan creation`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "raw-wal-recheck",
				serviceRunId = "raw-wal-recheck-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val admissionOrdinal = insertTerminalStepsWal(started)
		runtime.lastAdmissionOrdinal = admissionOrdinal
		replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))
		sourceProductDrainRouter.onDrain = { request ->
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET registration_generation = 'broken' " +
					"WHERE admission_ordinal = ?",
				arrayOf(admissionOrdinal),
			)
			authenticateSourceProductDrainRequest(database, request) shouldBe
				"SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE"
			SourceProductDrainResult.AuthorityChanged(
				request,
				"SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE",
			)
		}

		val pending = subject.stop(
			SessionStopRequest(
				"raw-wal-recheck-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.DrainPending>()

		pending.reason shouldBe "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE"
	}

	@Test
	fun `Steps WAL maintenance rejects corrupt active-run lifecycle protection`() = runTest {
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "maintenance-run-state",
				serviceRunId = "maintenance-run-state-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val wal = insertTerminalStepsWal(started)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET state = 1 WHERE service_run_id = ?",
			arrayOf(started.serviceRunId),
		)

		StepsCountDomainStore(database).removeSessionWalOwnersForPrune(
			safeOrdinal = wal,
			createdBeforeMs = 3_000L,
			limit = 1,
		) shouldBe StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
		requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(wal))
	}

	@Test
	fun `raw Steps completeness overflow returns authentication blocked instead of throwing`() =
		runTest {
			StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.ValidV2
			installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "raw-completeness-overflow",
					serviceRunId = "raw-completeness-overflow-run",
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()
			repeat(65) { index ->
				database.sourceSessionDao().saveCompleteness(
					SourceSessionCompletenessEntity(
						logicalTrackingId = started.logicalTrackingId,
						serviceRunId = started.serviceRunId,
						sourceKind = SourceKind.STEPS.stableCode,
						sourceInstanceId = "historical-steps-$index",
						registrationGeneration = index.toLong() + 2L,
						lastAdmissionOrdinal = null,
						lastSourceSequence = null,
						appDrainComplete = false,
						providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE.name,
						stopStatus = SourceStopStatus.PROCESS_RESTARTED.name,
						unresolvedSequenceStart = null,
						unresolvedSequenceEnd = null,
						updatedAtMs = index.toLong() + 1L,
					),
				)
			}

			subject.stop(
				SessionStopRequest(
					"raw-completeness-overflow-owner",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			) shouldBe SessionStopResult.InvalidIntent(
				"SOURCE_COMPLETENESS_AUTHENTICATION_BLOCKED",
			)
		}

	@Test
	fun `requested replay promotes authenticated terminal Steps checkpoint without weakening BIND`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "retirement-checkpoint-recovery",
				serviceRunId = "retirement-checkpoint-recovery-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val admissionOrdinal = prepareTerminalStepsCrashEvidence(started)

		assertFailsWith<CancellationException> {
			subject.stop(
				SessionStopRequest(
					"retirement-checkpoint-crash",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			)
		}
		insertRetiredStepsRegistration()
		replaceRuntime(FakeStepsRuntime(database))
		replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))

		val stopped = subject.stop(
			SessionStopRequest(
				"retirement-checkpoint-retry",
				"USER_STOP",
				2_100L,
				2_100_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.acknowledgements.single().let { acknowledgement ->
			acknowledgement.status shouldBe SourceStopStatus.COMPLETE
			acknowledgement.registrationGeneration shouldBe 1L
			acknowledgement.lastAdmissionOrdinal shouldBe 1L
		}
		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().let { receipt ->
			receipt.state shouldBe
				com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.STATE_ACKNOWLEDGED
			receipt.stopStatus shouldBe SourceStopStatus.COMPLETE.name
			receipt.lastAdmissionOrdinal shouldBe 1L
		}
		database.openHelper.writableDatabase.query(
			"SELECT operation FROM steps_count_domain_owner_revision " +
				"WHERE owner_kind = 'SESSION_COMPLETENESS' ORDER BY owner_revision",
		).use { cursor ->
			val operations = buildList {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
			operations shouldBe listOf("BIND")
		}
	}

	@Test
	fun `stale terminal Steps owner evidence leaves requested replay payload free`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("stale-terminal-owner")
		val exact = database.sourceSessionDao().completenessForServiceRun(
			started.logicalTrackingId,
			started.serviceRunId,
		).single { it.sourceKind == SourceKind.STEPS.stableCode }
		database.sourceSessionDao().saveCompleteness(exact.copy(updatedAtMs = exact.updatedAtMs + 1L))

		assertTerminalStepsRecoveryBlocked(started, "stale-terminal-owner-retry")
	}

	@Test
	fun `wrong generation terminal Steps checkpoint leaves requested replay payload free`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("wrong-generation-checkpoint")
		val state = requireNotNull(database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			"source-broker:${SourceKind.STEPS.stableCode}",
		))
		database.sourceRuntimeStateDao().save(state.copy(registrationGeneration = 2L))

		assertTerminalStepsRecoveryBlocked(started, "wrong-generation-checkpoint-retry")
	}

	@Test
	fun `malformed terminal Steps checkpoint leaves requested replay payload free`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("malformed-checkpoint")
		val state = requireNotNull(database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			"source-broker:${SourceKind.STEPS.stableCode}",
		))
		database.sourceRuntimeStateDao().save(state.copy(payload = byteArrayOf(0)))

		assertTerminalStepsRecoveryBlocked(started, "malformed-checkpoint-retry")
	}

	@Test
	fun `requested replay without its exact action claim remains blocked`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("missing-requested-owner")
		val retirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		database.sourceSessionDao().updateRunRetirement(
			retirement.copy(actionId = "missing-requested-owner-action"),
		) shouldBe 1

		assertTerminalStepsRecoveryBlocked(started, "missing-requested-owner-retry")
	}

	@Test
	fun `requested replay cannot move its action claim to another physical provider`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("wrong-requested-provider")
		val retirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		val owner = requireNotNull(
			database.sourceSessionDao().lifecycleAction(retirement.actionId),
		)
		database.sourceSessionDao().updateLifecycleAction(
			owner.copy(sourceInstanceId = "different-steps-instance"),
		) shouldBe 1

		assertTerminalStepsRecoveryBlocked(started, "wrong-requested-provider-retry")
	}

	@Test
	fun `requested replay cannot move its action claim to another provider generation`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("wrong-requested-generation")
		val retirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		val owner = requireNotNull(
			database.sourceSessionDao().lifecycleAction(retirement.actionId),
		)
		database.sourceSessionDao().updateLifecycleAction(
			owner.copy(registrationGeneration = retirement.registrationGeneration + 1L),
		) shouldBe 1

		assertTerminalStepsRecoveryBlocked(started, "wrong-requested-generation-retry")
	}

	@Test
	fun `requested replay authenticates desired plan revision against immutable manifest`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("requested-plan-revision-tamper")
		val retirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		val action = requireNotNull(database.sourceSessionDao().lifecycleAction(retirement.actionId))
		database.sourceSessionDao().updateLifecycleAction(
			action.copy(desiredPlanRevision = action.desiredPlanRevision + 1L),
		) shouldBe 1

		subject.stop(
			SessionStopRequest(
				"requested-plan-revision-tamper-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
			"RUN_RETIREMENT_ACTION_MANIFEST_MISMATCH"
		runtime.shutdownClaims shouldBe emptyList()

		database.sourceSessionDao().updateLifecycleAction(action) shouldBe 1
		subject.stop(
			SessionStopRequest(
				"requested-plan-revision-restored",
				"USER_STOP",
				2_600L,
				2_600_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `requested replay rejects immutable action identity field tampering`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("requested-action-envelope-tamper")
		val retirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		val action = requireNotNull(database.sourceSessionDao().lifecycleAction(retirement.actionId))
		val replayMutations =
			listOf<Pair<String, (LifecycleDesiredActionEntity) -> LifecycleDesiredActionEntity>>(
			"action revision" to { it.copy(actionRevision = it.actionRevision + 10_000L) },
			"consent epoch" to { it.copy(consentEpoch = requireNotNull(it.consentEpoch) + 1L) },
			"start origin" to { it.copy(startOrigin = SessionStartOrigin.RECOVERY.name) },
			"boot" to { it.copy(bootId = "other-boot") },
			"requested wall time" to { it.copy(requestedAtMs = it.requestedAtMs + 1L) },
			"requested elapsed time" to {
				it.copy(requestedElapsedRealtimeNanos = it.requestedElapsedRealtimeNanos + 1L)
			},
		)

		for ((field, mutate) in replayMutations) {
			database.sourceSessionDao().updateLifecycleAction(mutate(action)) shouldBe 1
			assertTerminalStepsRecoveryBlocked(
				started,
				"requested-action-envelope-$field-retry",
			)
			database.sourceSessionDao().updateLifecycleAction(action) shouldBe 1
		}
		val manifestLinkMutations = listOf(
			"action family" to action.copy(actionFamily = "OTHER_RUNTIME"),
			"source policy" to action.copy(
				sourcePolicyRevision = action.sourcePolicyRevision + 1L,
			),
		)
		for ((field, mutation) in manifestLinkMutations) {
			database.sourceSessionDao().updateLifecycleAction(mutation) shouldBe 1
			subject.stop(
				SessionStopRequest(
					"requested-action-envelope-$field-retry",
					"USER_STOP",
					2_600L,
					2_600_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.InvalidIntent>()
			runtime.shutdownClaims shouldBe emptyList()
			database.sourceSessionDao().updateLifecycleAction(action) shouldBe 1
		}

		subject.stop(
			SessionStopRequest(
				"requested-action-envelope-restored",
				"USER_STOP",
				2_800L,
				2_800_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `retirement reads referenced manifests without scanning long irrelevant history`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("retirement-long-manifest-history")
		appendIrrelevantManifestHistory(started, count = 300)

		subject.stop(
			SessionStopRequest(
				"retirement-long-manifest-history-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
		runtime.shutdownClaims shouldBe emptyList()
		sourceProductDrainRouter.requests.single().source shouldBe SourceKind.STEPS
	}

	@Test
	fun `suspend drains after more than three hundred irrelevant manifests`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "suspend-long-manifest-history",
				serviceRunId = "suspend-long-manifest-history-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		appendIrrelevantManifestHistory(started, count = 320)

		subject.suspendForRestart(
			SessionSuspendRequest(
				"suspend-long-manifest-history-owner",
				"ANDROID_RESTART",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()
		sourceProductDrainRouter.requests.single().source shouldBe SourceKind.STEPS
	}

	@Test
	fun `retirement action query exposes a logical id mismatch on the exact service run`() = runTest {
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "retirement-logical-mismatch",
				serviceRunId = "retirement-logical-mismatch-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE lifecycle_desired_action SET logical_tracking_id = 'foreign-logical' " +
				"WHERE service_run_id = ? AND desired_state = 'STARTED'",
			arrayOf(started.serviceRunId),
		)

		subject.stop(
			SessionStopRequest(
				"retirement-logical-mismatch-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
			"RUN_RETIREMENT_ACTION_AUTHENTICATION_BLOCKED"
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `retirement blocks unknown and overflowed referenced manifest revisions`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("retirement-referenced-manifest")
		val retirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		val action = requireNotNull(database.sourceSessionDao().lifecycleAction(retirement.actionId))

		database.sourceSessionDao().updateLifecycleAction(
			action.copy(manifestRevision = 9_999L),
		) shouldBe 1
		subject.stop(
			SessionStopRequest(
				"retirement-unknown-manifest-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
			"RUN_RETIREMENT_ACTION_MANIFEST_MISMATCH"
		database.sourceSessionDao().updateLifecycleAction(action) shouldBe 1

		database.openHelper.writableDatabase.execSQL(
			"UPDATE lifecycle_desired_action SET manifest_revision = 9223372036854775808 " +
				"WHERE action_id = ?",
			arrayOf(action.actionId),
		)
		subject.stop(
			SessionStopRequest(
				"retirement-overflowed-manifest-retry",
				"USER_STOP",
				2_600L,
				2_600_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
			"RUN_RETIREMENT_ACTION_AUTHENTICATION_BLOCKED"
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `retirement raw action projection blocks malformed ids enums types and ranges`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("retirement-raw-action")
		val retirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		val action = requireNotNull(database.sourceSessionDao().lifecycleAction(retirement.actionId))
		val mutations = listOf(
			RawActionMutation("action_id", "''", action.actionId),
			RawActionMutation("action_id", "'malformed'", action.actionId),
			RawActionMutation("logical_tracking_id", "''", action.logicalTrackingId),
			RawActionMutation("service_run_id", "''", action.serviceRunId),
			RawActionMutation("manifest_revision", "'1x'", action.manifestRevision),
			RawActionMutation("action_revision", "1.5", action.actionRevision),
			RawActionMutation("action_family", "'UNKNOWN'", action.actionFamily),
			RawActionMutation("source_kind", "2147483648", action.sourceKind),
			RawActionMutation("source_kind", "NULL", action.sourceKind),
			RawActionMutation("desired_state", "'UNKNOWN'", action.desiredState),
			RawActionMutation("desired_plan_revision", "X'01'", action.desiredPlanRevision),
			RawActionMutation("source_policy_revision", "1.5", action.sourcePolicyRevision),
			RawActionMutation("consent_epoch", "-1", action.consentEpoch),
			RawActionMutation("start_origin", "'UNKNOWN'", action.startOrigin),
			RawActionMutation("boot_id", "''", action.bootId),
			RawActionMutation("lease_generation", "0", action.leaseGeneration),
			RawActionMutation("requested_at_ms", "-1", action.requestedAtMs),
			RawActionMutation(
				"requested_elapsed_realtime_nanos",
				"NULL",
				action.requestedElapsedRealtimeNanos,
			),
			RawActionMutation("status", "'UNKNOWN'", action.status),
			RawActionMutation("attempt_count", "2147483648", action.attemptCount),
			RawActionMutation("acknowledged_at_ms", "'2000x'", action.acknowledgedAtMs),
			RawActionMutation(
				"acknowledged_elapsed_realtime_nanos",
				"X'01'",
				action.acknowledgedElapsedRealtimeNanos,
			),
			RawActionMutation("failure_code", "1", action.failureCode),
			RawActionMutation("retry_trigger", "1", action.retryTrigger),
			RawActionMutation("source_instance_id", "''", action.sourceInstanceId),
			RawActionMutation("registration_generation", "0", action.registrationGeneration),
		)
		for ((index, mutation) in mutations.withIndex()) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE lifecycle_desired_action SET " +
					"${mutation.column} = ${mutation.corruptSql} WHERE action_id IS ?",
				arrayOf(action.actionId),
			)
			subject.stop(
				SessionStopRequest(
					"retirement-raw-action-$index",
					"USER_STOP",
					2_600L + index,
					2_600_000L + index,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
				"RUN_RETIREMENT_ACTION_AUTHENTICATION_BLOCKED"
			runtime.shutdownClaims shouldBe emptyList()
			database.openHelper.writableDatabase.execSQL(
				"UPDATE lifecycle_desired_action SET ${mutation.column} = ? " +
					"WHERE action_id IS ? OR " +
					"(action_revision IS ? AND service_run_id IS ?)",
				arrayOf(
					mutation.originalValue,
					action.actionId,
					action.actionRevision,
					action.serviceRunId,
				),
			)
		}

		subject.stop(
			SessionStopRequest(
				"retirement-raw-action-restored",
				"USER_STOP",
				2_900L,
				2_900_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `retirement manifest source overflow blocks before binding materialization`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("retirement-manifest-source-overflow")
		repeat(MAX_RUN_RETIREMENT_MANIFEST_SOURCES) { index ->
			database.openHelper.writableDatabase.execSQL(
				"INSERT INTO session_manifest_source(" +
					"logical_tracking_id, manifest_revision, source_kind, purpose, consent_epoch, " +
					"persistence_eligible, qos_code) VALUES (?, ?, ?, ?, ?, ?, ?)",
				arrayOf(
					started.logicalTrackingId,
					1L,
					10_000 + index,
					"OVERFLOW_$index",
					1L,
					0,
					0,
				),
			)
		}

		subject.stop(
			SessionStopRequest(
				"retirement-manifest-source-overflow-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
			"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED"
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `malformed raw manifest bindings block authentication before runtime shutdown`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("raw-retirement-manifest-binding")
		val original = database.sourceSessionDao()
			.manifestSources(started.logicalTrackingId, 1L)
			.single()
		val mutations = listOf(
			"blob" to "source_kind = X'03'",
			"real" to "consent_epoch = 1.5",
			"nullable provenance" to "writer_owner = NULL",
			"unknown writer" to "writer_owner = 'UNKNOWN'",
			"unknown enum" to "purpose = 'UNKNOWN'",
			"invalid range" to "qos_code = 4",
			"projection overflow" to "writer_projection_version = 2147483648",
		)
		for ((identity, assignment) in mutations) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE session_manifest_source SET $assignment " +
					"WHERE logical_tracking_id = ? AND manifest_revision = ?",
				arrayOf(started.logicalTrackingId, 1L),
			)

			subject.stop(
				SessionStopRequest(
					"raw-retirement-manifest-$identity",
					"USER_STOP",
					2_500L,
					2_500_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
				"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED"
			runtime.shutdownClaims shouldBe emptyList()
			database.sourceSessionDao().deleteAllManifestSources()
			database.sourceSessionDao().insertManifestSources(listOf(original))
		}
	}

	@Test
	fun `real manifest header value cannot authenticate retirement`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("raw-retirement-manifest-header")
		appendIrrelevantManifestHistory(started, count = 1)
		val session = requireNotNull(database.sourceSessionDao().session(started.logicalTrackingId))
		database.sourceSessionDao().updateSession(
			session.copy(currentManifestRevision = 2L),
		) shouldBe 1
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET effective_wall_time_ms = 1.5 " +
				"WHERE service_run_id = ? AND manifest_revision = 1",
			arrayOf(started.serviceRunId),
		)

		subject.stop(
			SessionStopRequest(
				"raw-retirement-manifest-header-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
			"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED"
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `real lifecycle intent value cannot authenticate requested Steps retirement`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("raw-retirement-intent")
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_lifecycle_intent_version SET requested_wall_time_ms = 1.5 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = 1 " +
				"AND desired_state = 'ACTIVE'",
			arrayOf(started.logicalTrackingId),
		)
		subject.stop(
			SessionStopRequest(
				"raw-retirement-intent-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `real completeness value cannot authenticate terminal Steps replay`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("raw-retirement-completeness")
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_session_completeness SET last_admission_ordinal = 1.5 " +
				"WHERE service_run_id = ? AND source_kind = ?",
			arrayOf(started.serviceRunId, SourceKind.STEPS.stableCode),
		)
		subject.stop(
			SessionStopRequest(
				"raw-retirement-completeness-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `retirement action replay fails closed on bounded overflow`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("retirement-action-overflow")
		insertSyntheticRetirementClaims(
			started,
			count = MAX_RUN_RETIREMENT_ACTIONS,
			includeReceipts = false,
		)

		subject.stop(
			SessionStopRequest(
				"retirement-action-overflow-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.InvalidIntent>().code shouldBe
			"RUN_RETIREMENT_ACTION_HISTORY_OVERFLOW"
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `raw retirement replay fails closed on bounded overflow`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("retirement-receipt-overflow")
		insertSyntheticRetirementClaims(
			started,
			count = MAX_RUN_RETIREMENT_RECEIPTS,
			includeReceipts = true,
			includeActions = false,
		)

		subject.stop(
			SessionStopRequest(
				"retirement-receipt-overflow-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `bounded retirement indexing handles a large exact ownership set once`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("retirement-indexed-batch")
		val syntheticCount = MAX_RUN_RETIREMENT_RECEIPTS / 2
		insertSyntheticRetirementClaims(
			started,
			count = syntheticCount,
			includeReceipts = true,
		)

		subject.stop(
			SessionStopRequest(
				"retirement-indexed-batch-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		runtime.shutdownAttemptCount shouldBe 0
		runtime.shutdownClaims shouldBe emptyList()
	}

	@Test
	fun `candidate plan reports an exact incomplete membership instead of Ready empty`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "incomplete-candidate-plan",
				serviceRunId = "incomplete-candidate-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.stopStatus = SourceStopStatus.TIMED_OUT

		subject.stop(
			SessionStopRequest(
				"incomplete-candidate-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		val session = requireNotNull(database.sourceSessionDao().session(started.logicalTrackingId))
		val plan = buildSourceProductDrainPlan(
			database = database,
			logicalTrackingId = started.logicalTrackingId,
			serviceRunId = started.serviceRunId,
			cutoffElapsedRealtimeNanos = requireNotNull(session.cutoffElapsedNanos),
			cutoffWallTimeMs = requireNotNull(session.cutoffAtMs),
			settlementHighWaterAdmissionOrdinal = requireNotNull(session.finalAdmissionOrdinal),
			authenticatedAuthority = SourceProductDrainAuthority(
				mapOf(
					SourceKind.STEPS.stableCode to database.sourceSessionDao().manifestSources(
						started.logicalTrackingId,
						requireNotNull(session.currentManifestRevision),
					),
				),
			),
		).shouldBeInstanceOf<SourceProductDrainPlan.Failed>()

		plan.source shouldBe SourceKind.STEPS
		plan.reason shouldBe "SOURCE_DRAIN_SETTLEMENT_INCOMPLETE"
		plan.memberships.single().let { membership ->
			membership.sourceInstanceId shouldBe "steps-instance"
			membership.registrationGeneration shouldBe 1L
			membership.stopStatus shouldBe SourceStopStatus.TIMED_OUT.name
		}
	}

	@Test
	fun `suspend settles source products then clears the temporary run cutoff for replacement`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "source-suspend",
				serviceRunId = "source-suspend-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		val suspended = subject.suspendForRestart(
			SessionSuspendRequest(
				"source-suspend-owner",
				"ANDROID_RESTART",
				2_000L,
				2_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()

		sourceProductDrainRouter.requests.single().let { request ->
			request.logicalTrackingId shouldBe started.logicalTrackingId
			request.serviceRunId shouldBe started.serviceRunId
			request.cutoffElapsedRealtimeNanos shouldBe 2_000_000L
			request.settlementHighWaterAdmissionOrdinal shouldBe 0L
		}
		database.sourceSessionDao().session(started.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.ACTIVE.name
			session.currentServiceRunId shouldBe null
			session.cutoffAtMs shouldBe null
			session.cutoffElapsedNanos shouldBe null
			session.finalAdmissionOrdinal shouldBe null
		}
		database.sourceSessionDao().serviceRun(started.serviceRunId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		val suspendIntent = database.sourceSessionDao()
			.lifecycleIntents(started.logicalTrackingId)
			.last()
		suspendIntent.desiredState shouldBe LifecycleDesiredState.ACTIVE.name
		suspendIntent.sourceCallerAuthorityReference shouldBe
			requireNotNull(suspended.sourceCallerAuthorityReference).value
		(suspended.sourceCallerAuthorityReference == started.sourceCallerAuthorityReference) shouldBe false
		suspendIntent.intentChecksum shouldBe stableLifecycleChecksum(
			suspendIntent.logicalTrackingId,
			suspendIntent.intentRevision,
			suspendIntent.manifestRevision,
			LifecycleDesiredState.ACTIVE,
			suspendIntent.stopReason,
			suspendIntent.requestBootId,
			suspendIntent.requestedElapsedRealtimeNanos,
			suspendIntent.sourceCallerAuthorityReference,
		)
		val oldReference = requireNotNull(started.sourceCallerAuthorityReference)
		val currentReference = requireNotNull(suspended.sourceCallerAuthorityReference)
		database.sourceCallerAuthorityDao().rows(oldReference.value)
			.map { it.status }.distinct() shouldBe listOf("ACTIVE")
		subject.retireSupersededSourceCallerAuthority(
			started.logicalTrackingId,
			currentReference,
			oldReference,
			2_100L,
		) shouldBe SourceCallerAuthorityRetirementOutcome.Completed
		database.sourceCallerAuthorityDao().rows(oldReference.value)
			.map { it.status }.distinct() shouldBe listOf("RETIRED")
		database.sourceCallerAuthorityDao().rows(currentReference.value)
			.map { it.status }.distinct() shouldBe listOf("ACTIVE")
		subject.retireSupersededSourceCallerAuthority(
			started.logicalTrackingId,
			oldReference,
			currentReference,
			2_200L,
		) shouldBe SourceCallerAuthorityRetirementOutcome.Retryable(
			SourceCallerAuthorityRetirementRetryReason.CURRENT_AUTHORITY_CHANGED,
		)
		subject.retireSupersededSourceCallerAuthority(
			"missing-logical",
			currentReference,
			oldReference,
			2_200L,
		) shouldBe SourceCallerAuthorityRetirementOutcome.TerminalMissing
		subject.retireSupersededSourceCallerAuthority(
			started.logicalTrackingId,
			currentReference,
			currentReference,
			2_200L,
		) shouldBe SourceCallerAuthorityRetirementOutcome.TerminalInvariant
	}

	@Test
	fun `manual Pressure manifest records the exact legacy destination owner`() = runTest {
		val prepared = subject.prepareAndroidStart(
			pressureStartRequest(
				logicalTrackingId = "legacy-pressure-logical",
				serviceRunId = "legacy-pressure-run",
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("legacy-pressure-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val binding = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
			.single()
		binding.sourceKind shouldBe SourceKind.PRESSURE.stableCode
		binding.outputDestination shouldBe SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE
		binding.writerOwner shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
		binding.writerOwnerGeneration shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		binding.writerProjectionId shouldBe null
	}

	@Test
	fun `manual Pressure candidate manifest records only the exact official writer binding`() = runTest {
		val expected = installPressureCandidateRollout()
		val prepared = subject.prepareAndroidStart(
			pressureStartRequest(
				logicalTrackingId = "candidate-pressure-logical",
				serviceRunId = "candidate-pressure-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("candidate-pressure-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val binding = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
			.single()
		binding.sourceKind shouldBe SourceKind.PRESSURE.stableCode
		binding.outputDestination shouldBe SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE
		binding.writerOwner shouldBe SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
		binding.writerOwnerGeneration shouldBe SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		binding.writerProjectionId shouldBe expected.projectionId
		binding.writerProjectionVersion shouldBe expected.projectionVersion
		binding.writerBindingGeneration shouldBe expected.bindingGeneration
	}

	@Test
	fun `manual Activity candidate manifest records exact writer provenance`() = runTest {
		val expected = installExactCandidateRollout(SourceWriterTransitionSpec.ACTIVITY)
		val prepared = subject.prepareAndroidStart(
			manualSourceStartRequest(
				source = SourceKind.ACTIVITY,
				logicalTrackingId = "candidate-activity-logical",
				serviceRunId = "candidate-activity-run",
				sourcePolicyRevision = 1L,
			),
			AndroidStartDeliveryMetadata(
				PreparedTrackingStartToken("candidate-activity-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		assertExactCandidateManifestSource(prepared, SourceWriterTransitionSpec.ACTIVITY, expected)
	}

	@Test
	fun `manual Wi-Fi candidate manifest records exact writer provenance`() = runTest {
		val policyRevision = enableRadioPolicy(wifi = true, cell = false)
		val expected = installExactCandidateRollout(SourceWriterTransitionSpec.WIFI)
		val prepared = subject.prepareAndroidStart(
			manualSourceStartRequest(
				source = SourceKind.WIFI,
				logicalTrackingId = "candidate-wifi-logical",
				serviceRunId = "candidate-wifi-run",
				sourcePolicyRevision = policyRevision,
			),
			AndroidStartDeliveryMetadata(
				PreparedTrackingStartToken("candidate-wifi-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		assertExactCandidateManifestSource(prepared, SourceWriterTransitionSpec.WIFI, expected)
	}

	@Test
	fun `manual Cell candidate manifest records exact writer provenance`() = runTest {
		val policyRevision = enableRadioPolicy(wifi = false, cell = true)
		val expected = installExactCandidateRollout(SourceWriterTransitionSpec.CELL)
		val prepared = subject.prepareAndroidStart(
			manualSourceStartRequest(
				source = SourceKind.CELL,
				logicalTrackingId = "candidate-cell-logical",
				serviceRunId = "candidate-cell-run",
				sourcePolicyRevision = policyRevision,
			),
			AndroidStartDeliveryMetadata(
				PreparedTrackingStartToken("candidate-cell-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		assertExactCandidateManifestSource(prepared, SourceWriterTransitionSpec.CELL, expected)
	}

	@Test
	fun `Pressure generation 1 candidate cannot be attributed to an automatic session`() = runTest {
		installPressureCandidateRollout()
		val trigger = automaticTrigger()

		subject.prepareAndroidStart(
			pressureStartRequest(
				logicalTrackingId = "automatic-pressure-rejected",
				serviceRunId = "automatic-pressure-rejected-run",
				rolloutRevision = rolloutSnapshot.revision,
			).copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("automatic-pressure-rejected-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		) shouldBe SessionStartPreparationResult.Rejected("EVENT_CAPTURE_MODE_NOT_REACHABLE")

		database.sourceSessionDao().session("automatic-pressure-rejected") shouldBe null
		database.sourceBrokerDao().currentDemands("session:automatic-pressure-rejected") shouldBe
			emptyList()
	}

	@Test
	fun `stale rollout loaded before lease cannot create a new service run`() = runTest {
		database.trackingRolloutStateDao().save(
			fixedEventRollout().copy(revision = 2L).toEntity(updatedAtMs = 2L),
		)

		val rejected = subject.start(startRequest())
			.shouldBeInstanceOf<SessionStartResult.InvalidRollout>()

		rejected.code shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().incompleteSessions() shouldBe emptyList()
		database.sourceSessionDao().hasIncompleteServiceRun() shouldBe false
		runtime.startCount shouldBe 0
	}

	@Test
	fun `fresh Android preparation revalidates rollout before creating its service run`() = runTest {
		persistRolloutRevision(2L)

		val rejected = subject.prepareAndroidStart(
			startRequest(),
			AndroidStartDeliveryMetadata(
				PreparedTrackingStartToken("stale-fresh-preparation"),
				1L,
				true,
				false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Rejected>()

		rejected.failureCode shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().incompleteSessions() shouldBe emptyList()
		database.sourceSessionDao().hasIncompleteServiceRun() shouldBe false
		runtime.startCount shouldBe 0
	}

	@Test
	fun `direct recovery revalidates rollout before creating its replacement run`() = runTest {
		subject.start(
			startRequest().copy(logicalTrackingId = "stale-direct-logical", serviceRunId = "stale-direct-run-1"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.suspendForRestart(
			SessionSuspendRequest(
				"stale-direct-owner",
				"ANDROID_RESTART",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()
		persistRolloutRevision(2L)
		val recoveryPlan = startRequest().plan.copy(
			revision = 2L,
			planId = "stale-direct-recovery-plan",
			createdAtMs = 3_000L,
			plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
		)

		val rejected = subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.RECOVERY,
				plan = recoveryPlan,
				logicalTrackingId = "stale-direct-logical",
				serviceRunId = "stale-direct-run-2",
				continuationAuthority = ServiceRunContinuationAuthority("stale-direct-run-1"),
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = 3_000_000L,
			),
		).shouldBeInstanceOf<SessionStartResult.InvalidRollout>()

		rejected.code shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().serviceRun("stale-direct-run-2") shouldBe null
	}

	@Test
	fun `Android recovery preparation revalidates rollout before creating its replacement run`() = runTest {
		val old = activatePreparedAndroidRun(
			tokenValue = "stale-prepared-old-token",
			commandGeneration = 41L,
			logicalTrackingId = "stale-prepared-logical",
			serviceRunId = "stale-prepared-run-1",
		)
		persistRolloutRevision(2L)
		val recoveryPlan = startRequest().plan.copy(
			revision = 2L,
			planId = "stale-prepared-recovery-plan",
			createdAtMs = 3_000L,
			plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
		)

		val rejected = subject.prepareAndroidStart(
			startRequest().copy(
				origin = SessionStartOrigin.RECOVERY,
				plan = recoveryPlan,
				logicalTrackingId = old.logicalTrackingId,
				serviceRunId = "stale-prepared-run-2",
				continuationAuthority = ServiceRunContinuationAuthority(
					old.serviceRunId,
					old.token,
					41L,
				),
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = expiredPreparedLeaseElapsedNanos(),
			),
			AndroidStartDeliveryMetadata(
				PreparedTrackingStartToken("stale-prepared-recovery-token"),
				42L,
				true,
				false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Rejected>()

		rejected.failureCode shouldBe "ROLLOUT_REVISION_MISMATCH"
		database.sourceSessionDao().serviceRun("stale-prepared-run-2") shouldBe null
	}

	@Test
	fun `failed automatic provider attempt remains nonterminal until its exact cleanup obligation is retired`() = runTest {
		runtime.startReturnsRetryableFailure = true
		runtime.cleanupOnlyShutdown = true
		runtime.closeFailure = IllegalStateException("provider still resident")
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			logicalTrackingId = "cleanup-required-logical",
			serviceRunId = "cleanup-required-run",
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
		)

		val failed = subject.start(request).shouldBeInstanceOf<SessionStartResult.Failed>()

		failed.code shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
		val startAction = database.sourceSessionDao().lifecycleActions(failed.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		startAction.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
		startAction.attemptCount shouldBe 1
		startAction.retryTrigger shouldBe "RUNTIME_CLEANUP_RETRY"
		database.sourceSessionDao().session(failed.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STARTING.name
		database.sourceSessionDao().serviceRun(failed.serviceRunId)?.state shouldBe
			SessionLifecycleState.STARTING.name
		runtime.isActive shouldBe true
		runtime.shutdownClaims.single().attemptCount shouldBe 1

		subject.start(
			request.copy(serviceRunId = "replacement-run"),
		).shouldBeInstanceOf<SessionStartResult.AlreadyActive>()
		runtime.startCount shouldBe 1
		database.sourceSessionDao().lifecycleAction(startAction.actionId)?.attemptCount shouldBe 1
		database.sourceSessionDao().session(failed.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.STARTING.name
			session.currentServiceRunId shouldBe failed.serviceRunId
		}
		database.sourceSessionDao().serviceRun(failed.serviceRunId)?.completedAtMs shouldBe null
		database.sourceSessionDao().serviceRun("replacement-run") shouldBe null

		runtime.cleanupOnlyReady = true
		runtime.closeFailure = null
		val stopped = subject.stop(
			SessionStopRequest(
				ownerToken = "cleanup-stop-owner",
				reason = "failed-start-cleanup",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.acknowledgements shouldBe emptyList()
		runtime.isActive shouldBe false
		sourceProductDrainRouter.requests shouldBe emptyList()
		database.sourceSessionDao().completenessForServiceRun(
			failed.logicalTrackingId,
			failed.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().session(failed.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().lifecycleAction(startAction.actionId)?.status shouldBe
			LifecycleActionStatus.SUPERSEDED.name
	}

	@Test
	fun `cleanup-only completion is durable before a later coordinator crash`() = runTest {
		val (started, receipt) = prepareCleanupOnlyReceiptAfterCoordinatorCrash(
			"cleanup-only-crash",
		)

		receipt.state shouldBe SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED
		receipt.acknowledgementFieldsForTest().all { value -> value == null } shouldBe true
		database.sourceSessionDao().completenessForServiceRun(
			started.logicalTrackingId,
			started.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
	}

	@Test
	fun `process death after physical cleanup recovers a null acknowledgement closure`() = runTest {
		runtime.startReturnsRetryableFailure = true
		runtime.cleanupOnlyShutdown = true
		runtime.closeFailure = IllegalStateException("provider still resident")
		val failed = subject.start(
			startRequest().copy(
				logicalTrackingId = "cleanup-only-physical-crash-logical",
				serviceRunId = "cleanup-only-physical-crash-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.cleanupOnlyReady = true
		runtime.cancelAfterCleanupOnlyShutdown = true
		runtime.closeFailure = null

		shouldThrow<CancellationException> {
			subject.stop(
				SessionStopRequest(
					"cleanup-only-physical-crash-stop",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
					perSourceTimeoutMs = 100L,
				),
			)
		}
		database.sourceSessionDao().runRetirements(
			failed.logicalTrackingId,
			failed.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().state shouldBe SourceRunRetirementEntity.STATE_REQUESTED

		replaceRuntime(FakeStepsRuntime(database))
		replaceEventCoordinator(completedEventCoordinator(0L))
		val stopped = subject.stop(
			SessionStopRequest(
				"cleanup-only-physical-crash-replay",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.acknowledgements shouldBe emptyList()
		runtime.shutdownClaims shouldBe emptyList()
		sourceProductDrainRouter.requests shouldBe emptyList()
		database.sourceSessionDao().completenessForServiceRun(
			failed.logicalTrackingId,
			failed.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().runRetirements(
			failed.logicalTrackingId,
			failed.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().state shouldBe SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED
	}

	@Test
	fun `cleanup-only terminal receipt replays a null acknowledgement without product evidence`() =
		runTest {
			val (started, receipt) = prepareCleanupOnlyReceiptAfterCoordinatorCrash(
				"cleanup-only-replay",
			)
			replaceRuntime(FakeStepsRuntime(database))
			replaceEventCoordinator(completedEventCoordinator(0L))

			val stopped = subject.stop(
				SessionStopRequest(
					"cleanup-only-replay-retry",
					"USER_STOP",
					2_500L,
					2_500_000L,
					"boot-1",
					perSourceTimeoutMs = 100L,
				),
			).shouldBeInstanceOf<SessionStopResult.Stopped>()

			stopped.acknowledgements shouldBe emptyList()
			runtime.shutdownClaims shouldBe emptyList()
			sourceProductDrainRouter.requests shouldBe emptyList()
			database.sourceSessionDao().completenessForServiceRun(
				started.logicalTrackingId,
				started.serviceRunId,
			) shouldBe emptyList()
			database.sourceSessionDao().runRetirements(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			).single() shouldBe receipt
			database.sourceSessionDao().session(started.logicalTrackingId)?.failureCode shouldBe null
		}

	@Test
	fun `cleanup-only retirement cannot remove drain authority while its generation has WAL`() =
		runTest {
			installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
			runtime.startReturnsRetryableFailure = true
			runtime.cleanupOnlyShutdown = true
			runtime.cleanupOnlyReady = false
			val failed = subject.start(
				startRequest().copy(
					logicalTrackingId = "cleanup-only-wal-logical",
					serviceRunId = "cleanup-only-wal-run",
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Failed>()
			val admissionOrdinal = insertTerminalStepsWal(
				logicalTrackingId = failed.logicalTrackingId,
				serviceRunId = failed.serviceRunId,
			)
			runtime.cleanupOnlyReady = true
			replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))

			val pending = subject.stop(
				SessionStopRequest(
					"cleanup-only-wal-stop",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.DrainPending>()

			pending.reason shouldBe "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE"
			sourceProductDrainRouter.requests.none { request ->
				request.source == SourceKind.STEPS
			} shouldBe true
			database.sourceSessionDao().runRetirements(
				failed.logicalTrackingId,
				failed.serviceRunId,
				SourceKind.STEPS.stableCode,
			).single().state shouldBe SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED
		}

	@Test
	fun `later cleanup-only WAL cannot borrow an earlier product generation completeness`() =
		runTest {
			installStepsAndLocationCandidateRollout()
			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "mixed-generation-wal-logical",
					serviceRunId = "mixed-generation-wal-run",
					plan = stepsAndLocationPlan(1L, stepsEnabled = true),
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()
			val productOrdinal = insertTerminalStepsWal(started)
			runtime.lastAdmissionOrdinal = productOrdinal
			subject.reconfigure(
				stepsAndLocationReconfigure(2L, stepsEnabled = false),
			).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
			val productCompleteness = database.sourceSessionDao()
				.completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
				.single { row ->
					row.sourceKind == SourceKind.STEPS.stableCode &&
						row.registrationGeneration == 1L
				}
			insertRetiredStepsRegistration()
			persistStepsRuntimeCheckpoint(productCompleteness)

			runtime.startReturnsRetryableFailure = true
			runtime.cleanupOnlyShutdown = true
			runtime.cleanupOnlyReady = false
			subject.reconfigure(
				stepsAndLocationReconfigure(3L, stepsEnabled = true),
			).shouldBeInstanceOf<SessionReconfigureResult.Failed>()
			val cleanupAction = database.sourceSessionDao()
				.lifecycleActions(started.logicalTrackingId)
				.single { action ->
					action.sourceKind == SourceKind.STEPS.stableCode &&
						action.registrationGeneration == 2L &&
						action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name
				}
			val cleanupOrdinal = insertTerminalStepsWal(
				logicalTrackingId = started.logicalTrackingId,
				serviceRunId = started.serviceRunId,
				registrationGeneration = 2L,
				manifestRevision = 3L,
				sourceInstanceId = requireNotNull(cleanupAction.sourceInstanceId),
				lifecycleLeaseGeneration = cleanupAction.leaseGeneration,
			)
			runtime.cleanupOnlyReady = true
			replaceEventCoordinator(completedEventCoordinator(cleanupOrdinal))

			val pending = subject.stop(
				SessionStopRequest(
					"mixed-generation-wal-stop",
					"USER_STOP",
					4_000L,
					4_000_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.DrainPending>()

			pending.reason shouldBe "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE"
			sourceProductDrainRouter.requests.none { request ->
				request.source == SourceKind.STEPS
			} shouldBe true
			database.sourceSessionDao().completenessForServiceRun(
				started.logicalTrackingId,
				started.serviceRunId,
			).filter { row -> row.sourceKind == SourceKind.STEPS.stableCode }
				.map(SourceSessionCompletenessEntity::registrationGeneration) shouldBe listOf(1L)
		}

	@Test
	fun `product generation drains past its high-water while later cleanup-only generation has no evidence`() =
		runTest {
		installStepsAndLocationCandidateRollout()
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "product-cleanup-no-wal-logical",
				serviceRunId = "product-cleanup-no-wal-run",
				plan = stepsAndLocationPlan(1L, stepsEnabled = true),
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val productOrdinal = insertTerminalStepsWal(started)
		runtime.lastAdmissionOrdinal = productOrdinal
		subject.reconfigure(
			stepsAndLocationReconfigure(2L, stepsEnabled = false),
		).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
		val productCompleteness = database.sourceSessionDao()
			.completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single { row ->
				row.sourceKind == SourceKind.STEPS.stableCode &&
					row.registrationGeneration == 1L
			}
		insertRetiredStepsRegistration()
		persistStepsRuntimeCheckpoint(productCompleteness)

		runtime.startReturnsRetryableFailure = true
		runtime.cleanupOnlyShutdown = true
		runtime.cleanupOnlyReady = false
		subject.reconfigure(
			stepsAndLocationReconfigure(3L, stepsEnabled = true),
		).shouldBeInstanceOf<SessionReconfigureResult.Failed>()
		runtime.cleanupOnlyReady = true
		replaceEventCoordinator(completedEventCoordinator(productOrdinal))
		sourceProductDrainRouter.onDrain = { request ->
			SourceProductDrainResult.Complete(
				request = request,
				lastMaterializedAdmissionOrdinal = productOrdinal + 10L,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}

		subject.stop(
			SessionStopRequest(
				"product-cleanup-no-wal-stop",
				"USER_STOP",
				4_000L,
				4_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		sourceProductDrainRouter.requests.single { request ->
			request.source == SourceKind.STEPS
		}.also { request ->
			request.sourceHighWaterAdmissionOrdinal shouldBe productOrdinal
			request.memberships.map(SourceDrainMembership::registrationGeneration) shouldBe
				listOf(1L)
			request.retirementClaims.filterNot(SourceDrainRetirementClaim::cleanupOnly)
				.map(SourceDrainRetirementClaim::registrationGeneration).toSet() shouldBe setOf(1L)
			request.retirementClaims.filter(SourceDrainRetirementClaim::cleanupOnly)
				.map(SourceDrainRetirementClaim::registrationGeneration).toSet() shouldBe setOf(2L)
		}
	}

	@Test
	fun `cleanup-only replay preserves an earlier product generation across coordinator crash`() =
		runTest {
			installStepsAndLocationCandidateRollout()
			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "product-then-cleanup-logical",
					serviceRunId = "product-then-cleanup-run",
					plan = stepsAndLocationPlan(1L, stepsEnabled = true),
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()
			val admissionOrdinal = insertTerminalStepsWal(started)
			runtime.lastAdmissionOrdinal = admissionOrdinal
			subject.reconfigure(
				stepsAndLocationReconfigure(2L, stepsEnabled = false),
			).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
			val productCompleteness = database.sourceSessionDao()
				.completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
				.single { row ->
					row.sourceKind == SourceKind.STEPS.stableCode &&
						row.registrationGeneration == 1L
				}
			insertRetiredStepsRegistration()
			persistStepsRuntimeCheckpoint(productCompleteness)

			runtime.startReturnsRetryableFailure = true
			runtime.cleanupOnlyShutdown = true
			runtime.cleanupOnlyReady = false
			subject.reconfigure(
				stepsAndLocationReconfigure(3L, stepsEnabled = true),
			).shouldBeInstanceOf<SessionReconfigureResult.Failed>()
			runtime.cleanupOnlyReady = true
			val crashingCoordinator = mockk<TrackingCoordinator>()
			coEvery { crashingCoordinator.drainAvailable(any(), any()) } throws
				CancellationException("simulated crash after mixed-generation retirement")
			replaceEventCoordinator(crashingCoordinator)

			shouldThrow<CancellationException> {
				subject.stop(
					SessionStopRequest(
						"product-then-cleanup-stop",
						"USER_STOP",
						4_000L,
						4_000_000L,
						"boot-1",
					),
				)
			}
			val persistedRetirements = database.sourceSessionDao().runRetirements(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			)
			persistedRetirements.associate { receipt ->
				receipt.registrationGeneration to receipt.state
			} shouldBe mapOf(
				1L to SourceRunRetirementEntity.STATE_ACKNOWLEDGED,
				2L to SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
			)

			replaceRuntime(FakeStepsRuntime(database))
			replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))
			val stopped = subject.stop(
				SessionStopRequest(
					"product-then-cleanup-replay",
					"USER_STOP",
					4_500L,
					4_500_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.Stopped>()

			stopped.acknowledgements
				.filter { acknowledgement -> acknowledgement.source == SourceKind.STEPS }
				.map(SourceStopAck::registrationGeneration) shouldBe listOf(1L)
			runtime.shutdownClaims shouldBe emptyList()
			sourceProductDrainRouter.requests.single { request ->
				request.source == SourceKind.STEPS
			}.also { request ->
				request.memberships.map(SourceDrainMembership::registrationGeneration) shouldBe
					listOf(1L)
				request.retirementClaims.filterNot(SourceDrainRetirementClaim::cleanupOnly)
					.map(SourceDrainRetirementClaim::registrationGeneration).toSet() shouldBe setOf(1L)
				val cleanupReceipt = persistedRetirements.single { receipt ->
					receipt.registrationGeneration == 2L
				}
				request.retirementClaims.filter(SourceDrainRetirementClaim::cleanupOnly) shouldBe
					listOf(
						SourceDrainRetirementClaim(
							source = SourceKind.STEPS,
							sourceInstanceId = cleanupReceipt.sourceInstanceId,
							registrationGeneration = cleanupReceipt.registrationGeneration,
							actionId = cleanupReceipt.actionId,
							attemptCount = cleanupReceipt.attemptCount,
							leaseGeneration = cleanupReceipt.leaseGeneration,
							cleanupOnly = true,
						),
					)
			}
			database.sourceSessionDao().runRetirements(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			) shouldBe persistedRetirements
		}

	@Test
	fun `cleanup-only completeness corruption cannot authorize null acknowledgement removal`() =
		runTest {
			val (started, receipt) = prepareCleanupOnlyReceiptAfterCoordinatorCrash(
				"cleanup-only-completeness-corruption",
			)
			database.sourceSessionDao().saveCompleteness(
				SourceSessionCompletenessEntity(
					logicalTrackingId = started.logicalTrackingId,
					serviceRunId = started.serviceRunId,
					sourceKind = SourceKind.STEPS.stableCode,
					sourceInstanceId = receipt.sourceInstanceId,
					registrationGeneration = receipt.registrationGeneration,
					lastAdmissionOrdinal = null,
					lastSourceSequence = null,
					appDrainComplete = true,
					providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER.name,
					stopStatus = SourceStopStatus.COMPLETE.name,
					unresolvedSequenceStart = null,
					unresolvedSequenceEnd = null,
					updatedAtMs = 2_100L,
				),
			)
			replaceRuntime(FakeStepsRuntime(database))
			replaceEventCoordinator(completedEventCoordinator(0L))

			subject.stop(
				SessionStopRequest(
					"cleanup-only-completeness-corruption-retry",
					"USER_STOP",
					2_500L,
					2_500_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

			sourceProductDrainRouter.requests.none { request ->
				request.source == SourceKind.STEPS
			} shouldBe true
			database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
				SessionLifecycleState.STOPPING.name
		}

	@Test
	fun `cleanup-only orphan fact corruption cannot authorize null acknowledgement removal`() =
		runTest {
			val (started, _) = prepareCleanupOnlyReceiptAfterCoordinatorCrash(
				"cleanup-only-fact-corruption",
			)
			insertOrphanStepsFact(started.logicalTrackingId, started.serviceRunId)
			replaceRuntime(FakeStepsRuntime(database))
			replaceEventCoordinator(completedEventCoordinator(0L))

			val pending = subject.stop(
				SessionStopRequest(
					"cleanup-only-fact-corruption-retry",
					"USER_STOP",
					2_500L,
					2_500_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.DrainPending>()

			pending.reason shouldBe "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
			sourceProductDrainRouter.requests.none { request ->
				request.source == SourceKind.STEPS
			} shouldBe true
		}

	@Test
	fun `multiple cleanup-only generations replay without product evidence or repeated cleanup`() =
		runTest {
			installStepsAndLocationCandidateRollout()
			val started = subject.start(
				startRequest().copy(
					logicalTrackingId = "multiple-cleanup-logical",
					serviceRunId = "multiple-cleanup-run",
					plan = stepsAndLocationPlan(1L, stepsEnabled = true),
					rolloutRevision = rolloutSnapshot.revision,
				),
			).shouldBeInstanceOf<SessionStartResult.Started>()
			subject.reconfigure(
				stepsAndLocationReconfigure(2L, stepsEnabled = false),
			).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
			runtime.startReturnsRetryableFailure = true
			runtime.cleanupOnlyShutdown = true
			runtime.cleanupOnlyReady = false
			subject.reconfigure(
				stepsAndLocationReconfigure(3L, stepsEnabled = true),
			).shouldBeInstanceOf<SessionReconfigureResult.Failed>()

			database.sourceSessionDao().deleteAllCompleteness()
			database.sourceRuntimeStateDao().deleteAll()
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM steps_count_domain_completeness_marker",
			)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM steps_count_domain_owner_revision",
			)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM steps_count_domain_receipt",
			)
			insertRetiredStepsRegistration()
			val firstGenerationAction = database.sourceSessionDao()
				.lifecycleActions(started.logicalTrackingId)
				.single { action ->
					action.sourceKind == SourceKind.STEPS.stableCode &&
						action.manifestRevision == 1L &&
						action.desiredState == "STARTED"
				}
			database.sourceSessionDao().updateLifecycleAction(
				firstGenerationAction.copy(
					status = LifecycleActionStatus.CLEANUP_REQUIRED.name,
					failureCode = SOURCE_RUNTIME_CLEANUP_PENDING,
					retryTrigger = RUNTIME_CLEANUP_RETRY,
				),
			) shouldBe 1
			runtime.cleanupOnlyReady = true
			val crashingCoordinator = mockk<TrackingCoordinator>()
			coEvery { crashingCoordinator.drainAvailable(any(), any()) } throws
				CancellationException("simulated crash after all cleanup-only generations")
			replaceEventCoordinator(crashingCoordinator)

			shouldThrow<CancellationException> {
				subject.stop(
					SessionStopRequest(
						"multiple-cleanup-stop",
						"USER_STOP",
						4_000L,
						4_000_000L,
						"boot-1",
					),
				)
			}
			val cleanupReceipts = database.sourceSessionDao().runRetirements(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			)
			cleanupReceipts.map(SourceRunRetirementEntity::state) shouldBe listOf(
				SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
				SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
			)
			database.sourceSessionDao().completenessForServiceRun(
				started.logicalTrackingId,
				started.serviceRunId,
			).none { row -> row.sourceKind == SourceKind.STEPS.stableCode } shouldBe true

			replaceRuntime(FakeStepsRuntime(database))
			replaceEventCoordinator(completedEventCoordinator(0L))
			val stopped = subject.stop(
				SessionStopRequest(
					"multiple-cleanup-replay",
					"USER_STOP",
					4_500L,
					4_500_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.Stopped>()

			stopped.acknowledgements.none { acknowledgement ->
				acknowledgement.source == SourceKind.STEPS
			} shouldBe true
			runtime.shutdownClaims shouldBe emptyList()
			sourceProductDrainRouter.requests.none { request ->
				request.source == SourceKind.STEPS
			} shouldBe true
			database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
				.filter { action ->
					action.sourceKind == SourceKind.STEPS.stableCode &&
						action.status == LifecycleActionStatus.SUPERSEDED.name
				}.mapNotNull(LifecycleDesiredActionEntity::registrationGeneration)
				.toSet() shouldBe setOf(1L, 2L)
			database.sourceSessionDao().runRetirements(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			) shouldBe cleanupReceipts
		}

	@Test
	fun `malformed cleanup-only receipt cannot authorize replay`() = runTest {
		val (started, receipt) = prepareCleanupOnlyReceiptAfterCoordinatorCrash(
			"cleanup-only-malformed",
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_run_retirement SET stop_status = 'COMPLETE' " +
				"WHERE logical_tracking_id = ? AND service_run_id = ? AND source_kind = ?",
			arrayOf(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			),
		)
		replaceRuntime(FakeStepsRuntime(database))
		replaceEventCoordinator(completedEventCoordinator(0L))

		subject.stop(
			SessionStopRequest(
				"cleanup-only-malformed-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		database.sourceSessionDao().rawRunRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
			2,
		).single().validatedOrNull() shouldBe null
		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().completenessForServiceRun(
			started.logicalTrackingId,
			started.serviceRunId,
		) shouldBe emptyList()

		database.sourceSessionDao().updateRunRetirement(receipt) shouldBe 1
		subject.stop(
			SessionStopRequest(
				"cleanup-only-malformed-restored",
				"USER_STOP",
				2_600L,
				2_600_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `providerless exact claim closes as complete not registered after all runtimes reject ownership`() = runTest {
		StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) shouldBe
			StepsCountDomainSchemaState.ValidV2
		runtime.startReturnsRetryableFailure = true
		runtime.failedStartWithoutProviderEvidence = true
		runtime.abandonProviderlessClaimAfterIncompleteShutdown = true
		val request = startRequest().copy(
			logicalTrackingId = "providerless-logical",
			serviceRunId = "providerless-run",
		)

		val failed = subject.start(request).shouldBeInstanceOf<SessionStartResult.Failed>()
		val startAction = database.sourceSessionDao().lifecycleActions(failed.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		startAction.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
		startAction.sourceInstanceId shouldBe null
		startAction.registrationGeneration shouldBe null

		val stopped = subject.stop(
			SessionStopRequest(
				ownerToken = "providerless-stop-owner",
				reason = "providerless-cleanup",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.acknowledgements.single().let { acknowledgement ->
			acknowledgement.status shouldBe SourceStopStatus.COMPLETE
			acknowledgement.registrationRemovalOutcome shouldBe
				RegistrationRemovalOutcome.NOT_REGISTERED
			acknowledgement.providerCoverage shouldBe
				ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE
		}
		database.sourceSessionDao().completenessForServiceRun(
			failed.logicalTrackingId,
			failed.serviceRunId,
		).single().let { completeness ->
			completeness.stopStatus shouldBe SourceStopStatus.COMPLETE.name
			completeness.sourceInstanceId shouldBe "not-owned-steps"
			completeness.registrationGeneration shouldBe 0L
		}
		database.openHelper.writableDatabase.query(
			"SELECT terminal_state, registration_removal_outcome " +
				"FROM steps_count_domain_completeness_marker",
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldBe "UNPROVEN"
			cursor.getString(1) shouldBe RegistrationRemovalOutcome.NOT_REGISTERED.name
		}
	}

	@Test
	fun `failed provider cleanup rejects acknowledgement from another service run`() = runTest {
		runtime.startReturnsRetryableFailure = true
		runtime.acknowledgementLogicalTrackingId = "other-logical"
		runtime.acknowledgementServiceRunId = "other-run"
		val request = startRequest().copy(
			logicalTrackingId = "membership-logical",
			serviceRunId = "membership-run",
		)

		val failed = subject.start(request).shouldBeInstanceOf<SessionStartResult.Failed>()

		failed.code shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
		database.sourceSessionDao().completenessForServiceRun(
			failed.logicalTrackingId,
			failed.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().lifecycleActions(failed.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `stop without acknowledgement membership remains cleanup pending and cannot write completeness`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "missing-ack-membership", serviceRunId = "missing-ack-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.omitAcknowledgementMembership = true

		subject.stop(
			SessionStopRequest(
				"missing-ack-owner",
				"manual",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
		database.sourceSessionDao().completenessForServiceRun(
			started.logicalTrackingId,
			started.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `suspend rejects provider acknowledgement from another service run`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "suspend-membership", serviceRunId = "suspend-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.acknowledgementLogicalTrackingId = "other-logical"
		runtime.acknowledgementServiceRunId = "other-run"

		subject.suspendForRestart(
			SessionSuspendRequest(
				"suspend-membership-owner",
				"ANDROID_RESTART",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.CleanupPending>()

		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe
			started.serviceRunId
		database.sourceSessionDao().completenessForServiceRun(
			started.logicalTrackingId,
			started.serviceRunId,
		) shouldBe emptyList()
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `unobservable provider removal cannot finalize a stopping run`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "unobservable-stop", serviceRunId = "unobservable-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.UNOBSERVABLE
		runtime.retainProviderOnIncompleteShutdown = true

		subject.stop(
			SessionStopRequest(
				"unobservable-owner",
				"manual",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		runtime.isActive shouldBe true
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
	}

	@Test
	fun `stopping a steps run leaves an unrelated location provider untouched`() = runTest {
		locationRuntime.start(
			SourceRuntimeClaim(
				source = SourceKind.LOCATION,
				actionId = "unrelated-location-action",
				attemptCount = 1,
				leaseGeneration = 1L,
				logicalTrackingId = "unrelated-location-logical",
				serviceRunId = "unrelated-location-run",
			),
			LocationPlan(
				revision = 1L,
				backend = LocationBackend.FUSED,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 2_000L,
				minimumUpdateIntervalMs = 2_000L,
				minimumDisplacementMeters = 10f,
				maximumBatchDelayMs = 10_000L,
				preciseLocationAvailable = true,
			),
			mockk(relaxed = true),
		)
		locationRuntime.failRetirement = true
		subject.start(
			startRequest().copy(logicalTrackingId = "steps-only-logical", serviceRunId = "steps-only-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.stop(
			SessionStopRequest(
				"steps-only-owner",
				"manual",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		locationRuntime.quiesceCount shouldBe 0
		locationRuntime.closeCount shouldBe 0
		locationRuntime.isActive shouldBe true
	}

	@Test
	fun `stopping retry resolves requested provider row past newer cleanup claim to start owner`() = runTest {
		val eventCoordinator = mockk<TrackingCoordinator>()
		coEvery { eventCoordinator.drainAvailable(any(), any()) } returnsMany listOf(
			CoordinatorDrainResult.LeaseUnavailable,
			CoordinatorDrainResult.Complete(0L, 0),
		)
		val ingress = mockk<DurableSourceIngress>(relaxed = true)
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime, locationRuntime)),
			DurableSourceEventSinkFactory(ingress),
			eventCoordinator,
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			FakeSourceCallerDemandDispatcher(database, SourceBroker(database)),
			rolloutStore = fixedEventRolloutStore(),
			sourceProductDrainRouter = sourceProductDrainRouter,
		)
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "retry-stop-logical", serviceRunId = "retry-stop-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.stopStatus = SourceStopStatus.TIMED_OUT
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED

		subject.stop(
			SessionStopRequest("retry-stop-owner", "manual", 2_000L, 2_000_000L, "boot-1", perSourceTimeoutMs = 100L),
		).shouldBeInstanceOf<SessionStopResult.DrainPending>()
		database.sourceSessionDao()
			.completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.none { completeness -> completeness.sourceKind == SourceKind.STEPS.stableCode } shouldBe true
		val requestedRetirement = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		requestedRetirement.state shouldBe
			com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.STATE_REQUESTED
		requestedRetirement.appliedRevision shouldBe null
		requestedRetirement.callbackEntryBarrierSequence shouldBe null
		requestedRetirement.lastSourceSequence shouldBe null
		requestedRetirement.lastAdmissionOrdinal shouldBe null
		requestedRetirement.failedAdmissionCount shouldBe null
		requestedRetirement.unresolvedSequenceStart shouldBe null
		requestedRetirement.unresolvedSequenceEnd shouldBe null
		requestedRetirement.registrationRemovalOutcome shouldBe null
		requestedRetirement.providerFlushOutcome shouldBe null
		requestedRetirement.providerCoverage shouldBe null
		requestedRetirement.appDrainComplete shouldBe null
		requestedRetirement.stopStatus shouldBe null
		val requestedOwner = requireNotNull(
			database.sourceSessionDao().lifecycleAction(requestedRetirement.actionId),
		)
		requestedOwner.desiredState shouldBe "STARTED"
		val newerCleanupClaim = database.sourceSessionDao()
			.lifecycleActions(started.logicalTrackingId)
			.filter { action ->
				action.sourceInstanceId == requestedRetirement.sourceInstanceId &&
					action.registrationGeneration == requestedRetirement.registrationGeneration &&
					action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name
			}
			.maxBy { action -> action.actionRevision }
		newerCleanupClaim.desiredState shouldBe "STOPPED"
		(newerCleanupClaim.actionRevision > requestedOwner.actionRevision) shouldBe true
		runtime.stopStatus = SourceStopStatus.COMPLETE
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED
		subject.stop(
			SessionStopRequest("retry-stop-owner", "manual", 2_500L, 2_500_000L, "boot-1", perSourceTimeoutMs = 100L),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		val stopActions = database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.filter { action -> action.desiredState == "STOPPED" }
		stopActions.size shouldBe 2
		stopActions.map { action -> action.leaseGeneration }.distinct().size shouldBe 2
		stopActions.first().status shouldBe LifecycleActionStatus.SUPERSEDED.name
		stopActions.last().status shouldBe LifecycleActionStatus.STOP_ACCEPTED.name
		val run = requireNotNull(database.sourceSessionDao().serviceRun(started.serviceRunId))
		run.runtimeAcknowledgement shouldBe LifecycleActionStatus.STOP_ACCEPTED.name
		run.runtimeFailureCode shouldBe null
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single { completeness -> completeness.sourceKind == SourceKind.STEPS.stableCode }
			.stopStatus shouldBe SourceStopStatus.COMPLETE.name
	}

	@Test
	fun `timeout acknowledgement persists a null provider pair and remains retryable`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "timeout-provider-pair",
				serviceRunId = "timeout-provider-pair-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.shutdownDelayMs = 1_000L

		subject.stop(
			SessionStopRequest(
				"timeout-provider-pair-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 1L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.desiredState == "STOPPED" }
			.let { action ->
				action.sourceInstanceId shouldBe null
				action.registrationGeneration shouldBe null
				action.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
			}

		runtime.shutdownDelayMs = 0L
		subject.stop(
			SessionStopRequest(
				"timeout-provider-pair-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
		database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().let { receipt ->
			receipt.state shouldBe
				com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.STATE_ACKNOWLEDGED
			receipt.callbackEntryBarrierSequence shouldBe 4L
			receipt.lastSourceSequence shouldBe 4L
			receipt.registrationRemovalOutcome shouldBe RegistrationRemovalOutcome.REMOVED.name
			receipt.providerFlushOutcome shouldBe ProviderFlushOutcome.COMPLETE.name
			receipt.appDrainComplete shouldBe true
			receipt.stopStatus shouldBe SourceStopStatus.COMPLETE.name
		}
		runtime.shutdownClaims.last().actionId shouldBe requestedOwner.actionId
	}

	@Test
	fun `terminal receipt replay resolves exact older owner past cleanup claim after process death`() = runTest {
		val (started, terminalReceipt) =
			prepareTerminalReceiptWithCleanup("terminal-replay-cleanup")
		val startOwner = database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		terminalReceipt.state shouldBe SourceRunRetirementEntity.STATE_ACKNOWLEDGED
		terminalReceipt.actionId shouldBe startOwner.actionId
		val cleanupClaim = database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.single { action -> action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name }
		cleanupClaim.sourceInstanceId shouldBe terminalReceipt.sourceInstanceId
		cleanupClaim.registrationGeneration shouldBe terminalReceipt.registrationGeneration
		(cleanupClaim.actionRevision > startOwner.actionRevision) shouldBe true

		replaceRuntime(FakeStepsRuntime(database))
		subject.stop(
			SessionStopRequest(
				"terminal-replay-cleanup-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().lifecycleAction(cleanupClaim.actionId)?.let { resolved ->
			resolved.status shouldBe LifecycleActionStatus.SUPERSEDED.name
			resolved.failureCode shouldBe "RUNTIME_CLEANUP_CONFIRMED_BY_STOP"
			resolved.retryTrigger shouldBe null
		}
		database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single() shouldBe terminalReceipt
	}

	@Test
	fun `terminal receipt replay rejects failed settlement evidence`() = runTest {
		val (started, terminalReceipt) =
			prepareTerminalReceiptWithCleanup("terminal-replay-failed")
		database.sourceSessionDao().updateRunRetirement(
			terminalReceipt.copy(stopStatus = SourceStopStatus.PROVIDER_FAILED.name),
		) shouldBe 1
		replaceRuntime(FakeStepsRuntime(database))

		subject.stop(
			SessionStopRequest(
				"terminal-replay-failed-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
	}

	@Test
	fun `terminal Steps receipt replay authenticates every settlement payload field`() = runTest {
		val mutations = listOf<Pair<String, (SourceRunRetirementEntity) -> SourceRunRetirementEntity>>(
			"applied revision" to { receipt ->
				receipt.copy(appliedRevision = requireNotNull(receipt.appliedRevision) + 1L)
			},
			"barrier" to { receipt ->
				receipt.copy(
					callbackEntryBarrierSequence =
						requireNotNull(receipt.callbackEntryBarrierSequence) + 1L,
				)
			},
			"removal" to { receipt ->
				receipt.copy(
					registrationRemovalOutcome = RegistrationRemovalOutcome.NOT_REGISTERED.name,
				)
			},
			"flush" to { receipt ->
				receipt.copy(providerFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED.name)
			},
			"coverage" to { receipt ->
				receipt.copy(
					providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE.name,
				)
			},
			"drain" to { receipt ->
				receipt.copy(
					appDrainComplete = false,
					stopStatus = SourceStopStatus.PARTIAL_UNOBSERVABLE.name,
					unresolvedSequenceStart = 4L,
					unresolvedSequenceEnd = 4L,
				)
			},
			"last source sequence" to { receipt ->
				receipt.copy(lastSourceSequence = requireNotNull(receipt.lastSourceSequence) + 1L)
			},
			"last admission ordinal" to { receipt ->
				receipt.copy(lastAdmissionOrdinal = requireNotNull(receipt.lastAdmissionOrdinal) + 1L)
			},
			"failed admission count" to { receipt ->
				receipt.copy(failedAdmissionCount = requireNotNull(receipt.failedAdmissionCount) + 1L)
			},
			"unresolved range" to { receipt ->
				receipt.copy(
					appDrainComplete = false,
					stopStatus = SourceStopStatus.PARTIAL_UNOBSERVABLE.name,
					unresolvedSequenceStart = 3L,
					unresolvedSequenceEnd = 4L,
				)
			},
			"status" to { receipt ->
				receipt.copy(stopStatus = SourceStopStatus.PARTIAL_UNOBSERVABLE.name)
			},
		)

		val (started, terminalReceipt) =
			prepareTerminalReceiptWithCleanup("terminal-replay-altered")
		replaceRuntime(FakeStepsRuntime(database))
		for ((field, mutate) in mutations) {
			database.sourceSessionDao().updateRunRetirement(mutate(terminalReceipt)) shouldBe 1

			subject.stop(
				SessionStopRequest(
					"terminal-replay-altered-$field-retry",
					"USER_STOP",
					2_500L,
					2_500_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

			database.sourceSessionDao().updateRunRetirement(terminalReceipt) shouldBe 1
			runtime.shutdownClaims shouldBe emptyList()
		}
		subject.stop(
			SessionStopRequest(
				"terminal-replay-restored",
				"USER_STOP",
				2_600L,
				2_600_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
	}

	@Test
	fun `raw retirement storage classes reject blob real wrong type and invalid boolean`() = runTest {
		val (started, terminalReceipt) =
			prepareTerminalReceiptWithCleanup("raw-retirement-storage-class")
		replaceRuntime(FakeStepsRuntime(database))
		val mutations = listOf(
			"blob" to "action_id = X'01'",
			"real" to "attempt_count = 1.5",
			"wrong storage class" to "callback_entry_barrier_sequence = 'wrong'",
			"invalid boolean" to "app_drain_complete = 2",
			"unknown enum" to "provider_flush_outcome = 'UNKNOWN'",
		)
		for ((identity, assignment) in mutations) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_run_retirement SET $assignment " +
					"WHERE logical_tracking_id = ? AND service_run_id = ? AND source_kind = ?",
				arrayOf(
					started.logicalTrackingId,
					started.serviceRunId,
					SourceKind.STEPS.stableCode,
				),
			)

			subject.stop(
				SessionStopRequest(
					"raw-retirement-storage-$identity",
					"USER_STOP",
					2_500L,
					2_500_000L,
					"boot-1",
				),
			).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
			database.sourceSessionDao().rawRunRetirements(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
				2,
			).single().validatedOrNull() shouldBe null
			database.sourceSessionDao().updateRunRetirement(terminalReceipt) shouldBe 1
			runtime.shutdownClaims shouldBe emptyList()
		}
		database.sourceSessionDao().rawRunRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
			2,
		).single().validatedOrNull() shouldBe terminalReceipt

		subject.stop(
			SessionStopRequest(
				"raw-retirement-storage-restored",
				"USER_STOP",
				2_600L,
				2_600_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `contradictory Steps receipt state cannot authenticate terminal replay`() = runTest {
		val (started, _) = prepareTerminalReceiptWithCleanup("contradictory-steps-receipt")
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_run_retirement SET stop_status = 'PROCESS_RESTARTED' " +
				"WHERE logical_tracking_id = ? AND service_run_id = ? AND source_kind = ?",
			arrayOf(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			),
		)
		replaceRuntime(FakeStepsRuntime(database))

		subject.stop(
			SessionStopRequest(
				"contradictory-steps-receipt-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		database.sourceSessionDao().rawRunRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
			2,
		).single().validatedOrNull() shouldBe null
		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
	}

	@Test
	fun `contradictory non-Steps receipt state cannot resolve runtime cleanup`() = runTest {
		val (started, _) = prepareLocationReceiptWithCleanup("contradictory-location-receipt")
		val quiesceCountBeforeReplay = locationRuntime.quiesceCount
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_run_retirement SET state = 'INTERRUPTED' " +
				"WHERE logical_tracking_id = ? AND service_run_id = ? AND source_kind = ?",
			arrayOf(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.LOCATION.stableCode,
			),
		)

		subject.stop(
			SessionStopRequest(
				"contradictory-location-receipt-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		database.sourceSessionDao().rawRunRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.LOCATION.stableCode,
			2,
		).single().validatedOrNull() shouldBe null
		locationRuntime.quiesceCount shouldBe quiesceCountBeforeReplay
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
	}

	@Test
	fun `malformed requested retirement row fails closed and resolves after repair`() = runTest {
		val started = prepareBlockedTerminalStepsRecovery("malformed-requested-retirement")
		val receipt = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_run_retirement SET callback_entry_barrier_sequence = 4 " +
				"WHERE logical_tracking_id = ? AND service_run_id = ? AND source_kind = ?",
			arrayOf(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			),
		)

		subject.stop(
			SessionStopRequest(
				"malformed-requested-retirement-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		database.sourceSessionDao().rawRunRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
			2,
		).single().validatedOrNull() shouldBe null

		database.sourceSessionDao().updateRunRetirement(receipt) shouldBe 1
		subject.stop(
			SessionStopRequest(
				"malformed-requested-retirement-restored",
				"USER_STOP",
				2_600L,
				2_600_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `malformed terminal retirement row fails closed and resolves after repair`() = runTest {
		val (started, terminalReceipt) =
			prepareTerminalReceiptWithCleanup("malformed-terminal-retirement")
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_run_retirement SET provider_flush_outcome = NULL " +
				"WHERE logical_tracking_id = ? AND service_run_id = ? AND source_kind = ?",
			arrayOf(
				started.logicalTrackingId,
				started.serviceRunId,
				SourceKind.STEPS.stableCode,
			),
		)
		replaceRuntime(FakeStepsRuntime(database))

		subject.stop(
			SessionStopRequest(
				"malformed-terminal-retirement-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		database.sourceSessionDao().rawRunRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
			2,
		).single().validatedOrNull() shouldBe null

		database.sourceSessionDao().updateRunRetirement(terminalReceipt) shouldBe 1
		subject.stop(
			SessionStopRequest(
				"malformed-terminal-retirement-restored",
				"USER_STOP",
				2_600L,
				2_600_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
	}

	@Test
	fun `terminal receipt replay without one exact owner remains blocked`() = runTest {
		val (started, terminalReceipt) =
			prepareTerminalReceiptWithCleanup("terminal-replay-owner-missing")
		database.sourceSessionDao().updateRunRetirement(
			terminalReceipt.copy(actionId = "missing-terminal-replay-owner"),
		) shouldBe 1
		replaceRuntime(FakeStepsRuntime(database))

		subject.stop(
			SessionStopRequest(
				"terminal-replay-owner-missing-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
	}

	@Test
	fun `terminal receipt replay rejects owner moved to another provider generation`() = runTest {
		val (started, terminalReceipt) =
			prepareTerminalReceiptWithCleanup("terminal-replay-provider-moved")
		val owner = requireNotNull(
			database.sourceSessionDao().lifecycleAction(terminalReceipt.actionId),
		)
		database.sourceSessionDao().updateLifecycleAction(
			owner.copy(
				sourceInstanceId = "different-terminal-provider",
				registrationGeneration = terminalReceipt.registrationGeneration + 1L,
			),
		) shouldBe 1
		replaceRuntime(FakeStepsRuntime(database))

		subject.stop(
			SessionStopRequest(
				"terminal-replay-provider-moved-retry",
				"USER_STOP",
				2_500L,
				2_500_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
	}

	@Test
	fun `shared manifest integrity detects run and writer provenance tampering`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()
		val manifest = database.sourceSessionDao().manifests(started.logicalTrackingId).single()
		val sources = database.sourceSessionDao()
			.manifestSources(started.logicalTrackingId, manifest.manifestRevision)

		SessionManifestIntegrity.verify(manifest, sources) shouldBe true
		SessionManifestIntegrity.verify(manifest.copy(serviceRunId = "tampered-run"), sources) shouldBe false
		SessionManifestIntegrity.verify(
			manifest,
			sources.map { source -> source.copy(writerOwnerGeneration = source.writerOwnerGeneration?.plus(1L)) },
		) shouldBe false
		SessionManifestIntegrity.verify(manifest, emptyList()) shouldBe false
		SessionManifestIntegrity.verify(manifest, sources + sources.single()) shouldBe false
		SessionManifestIntegrity.verify(
			manifest,
			sources.map { source -> source.copy(logicalTrackingId = "foreign-logical") },
		) shouldBe false
		SessionManifestIntegrity.compute(manifest, sources.reversed()) shouldBe manifest.manifestChecksum
	}

	@Test
	fun `prepared claim rejects raw binding tampering before delivery acceptance`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "tampered-claim-token",
			commandGeneration = 16L,
			logicalTrackingId = "tampered-claim-logical",
			serviceRunId = "tampered-claim-run",
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = qos_code + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any>(prepared.logicalTrackingId, prepared.manifestRevision),
		)

		subject.claimAndroidStart(
			prepared.token,
			16L,
			"boot-1",
			1_100_000L,
			1_100L,
		) shouldBe PreparedSessionClaimResult.Rejected("PREPARED_START_ROOM_ENVELOPE_STALE")
		runtime.startCount shouldBe 0
	}

	@Test
	fun `prepared apply rejects persisted writer provenance tampering before provider start`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "tampered-apply-token",
			commandGeneration = 17L,
			logicalTrackingId = "tampered-apply-logical",
			serviceRunId = "tampered-apply-run",
		)
		subject.claimAndroidStart(
			prepared.token,
			17L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		subject.markPreparedForegroundAccepted(
			prepared.token,
			17L,
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe true
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET writer_owner_generation = writer_owner_generation + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any>(prepared.logicalTrackingId, prepared.manifestRevision),
		)

		subject.applyPreparedAndroidStart(
			prepared.token,
			17L,
			"boot-1",
			1_300_000L,
			1_300L,
		) shouldBe SessionStartResult.InvalidIntent("PREPARED_START_MANIFEST_INTEGRITY_FAILED")
		runtime.startCount shouldBe 0
	}

	@Test
	fun `reserved legacy completeness run id cannot be admitted as a live run`() = runTest {
		subject.start(startRequest().copy(serviceRunId = LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID)) shouldBe
			SessionStartResult.InvalidIntent("SERVICE_RUN_ID_RESERVED")
	}

	@Test
	fun `blank service run id is rejected with a typed result before any durable row`() = runTest {
		subject.start(startRequest().copy(serviceRunId = "   ")) shouldBe
			SessionStartResult.InvalidIntent("SERVICE_RUN_ID_INVALID")
		database.sourceSessionDao().incompleteSessions() shouldBe emptyList()
		runtime.startCount shouldBe 0
	}

	@Test
	fun `old boot cleanup allows exactly one fresh manual logical session`() = runTest {
		val oldRequest = startRequest().copy(
			logicalTrackingId = "old-boot-manual",
			serviceRunId = "old-boot-run",
		)
		subject.start(oldRequest).shouldBeInstanceOf<SessionStartResult.Started>()

		PreviousExitSourceSessionFinalizer(
			Provider { database },
			FixedClock(2_000L, 2_000_000L),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-2"
			},
			mockk(relaxed = true),
		).finalizeStaleSessions()
		currentBootId = "boot-2"

		val freshRequest = startRequest().copy(
			ownerToken = "fresh-owner",
			plan = startRequest().plan.copy(
				revision = 2L,
				planId = "fresh-manual-plan",
				createdAtMs = 2_000L,
				plans = mapOf(
					SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false),
				),
			),
			clockDomainId = "boot-2",
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = 2_000_000L,
			logicalTrackingId = "fresh-manual",
			serviceRunId = "fresh-run",
		)
		subject.start(freshRequest).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.start(
			freshRequest.copy(
				logicalTrackingId = "duplicate-fresh-manual",
				serviceRunId = "duplicate-fresh-run",
			),
		) shouldBe SessionStartResult.AlreadyActive
		database.sourceSessionDao().session("old-boot-manual")?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().incompleteSessions().map { it.logicalTrackingId } shouldBe
			listOf("fresh-manual")
		database.sourceSessionDao().session("duplicate-fresh-manual") shouldBe null
	}

	@Test
	fun `source start cancellation propagates without terminal cleanup`() = runTest {
		runtime.startFailure = CancellationException("test cancellation")
		var propagated = false

		try {
			subject.start(startRequest())
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.closed shouldBe false
		database.sourceSessionDao().activeSession()?.state shouldBe SessionLifecycleState.STARTING.name
		database.sourceSessionDao().activeSession()?.let { session ->
			database.sourceSessionDao().lifecycleActions(session.logicalTrackingId).last().status
		} shouldBe LifecycleActionStatus.APPLYING.name
	}

	@Test
	fun `cancelled start after provider publication retires applying claim before finalization`() = runTest {
		runtime.startFailureAfterSideEffect = CancellationException("cancel after provider publication")
		runtime.stopStatus = SourceStopStatus.PROVIDER_FAILED
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.FAILED
		runtime.retainProviderOnIncompleteShutdown = true
		val request = startRequest().copy(
			logicalTrackingId = "cancelled-active-start",
			serviceRunId = "cancelled-active-run",
		)

		var propagated = false
		try {
			subject.start(request)
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.isActive shouldBe true
		val applyingStart = database.sourceSessionDao()
			.lifecycleActions("cancelled-active-start")
			.single { action -> action.desiredState == "STARTED" }
		applyingStart.status shouldBe LifecycleActionStatus.APPLYING.name
		applyingStart.attemptCount shouldBe 1

		val pending = subject.stop(
			SessionStopRequest(
				ownerToken = "cancelled-active-stop",
				reason = "SERVICE_DESTROYED",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()

		pending.logicalTrackingId shouldBe "cancelled-active-start"
		runtime.isActive shouldBe true
		runtime.shutdownClaims.single().let { claim ->
			claim.actionId shouldBe applyingStart.actionId
			claim.attemptCount shouldBe applyingStart.attemptCount
			claim.leaseGeneration shouldBe applyingStart.leaseGeneration
			claim.logicalTrackingId shouldBe applyingStart.logicalTrackingId
			claim.serviceRunId shouldBe applyingStart.serviceRunId
		}
		database.sourceSessionDao().session("cancelled-active-start")?.let { session ->
			session.state shouldBe SessionLifecycleState.STOPPING.name
			session.currentServiceRunId shouldBe "cancelled-active-run"
		}
		database.sourceSessionDao().lifecycleAction(applyingStart.actionId)?.status shouldBe
			LifecycleActionStatus.APPLYING.name
		database.sourceSessionDao().lifecycleActions("cancelled-active-start")
			.last { action -> action.desiredState == "STOPPED" }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name

		runtime.startFailureAfterSideEffect = null
		runtime.stopStatus = SourceStopStatus.COMPLETE
		runtime.registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.COMPLETE

		subject.stop(
			SessionStopRequest(
				ownerToken = "cancelled-active-stop-retry",
				reason = "SERVICE_DESTROYED_RETRY",
				wallTimeMs = 2_500L,
				elapsedRealtimeNanos = 2_500_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		runtime.isActive shouldBe false
		runtime.shutdownClaims.map(SourceRuntimeClaim::actionId) shouldBe
			listOf(applyingStart.actionId, applyingStart.actionId)
		database.sourceSessionDao().session("cancelled-active-start")?.let { session ->
			session.state shouldBe SessionLifecycleState.FINALIZED.name
			session.currentServiceRunId shouldBe null
		}
		database.sourceSessionDao().lifecycleAction(applyingStart.actionId)?.let { action ->
			action.status shouldBe LifecycleActionStatus.SUPERSEDED.name
			action.failureCode shouldBe "RUNTIME_CLEANUP_CONFIRMED_BY_STOP"
		}
	}

	@Test
	fun `stale automatic replacement cannot finalize an applying provider attempt`() = runTest {
		runtime.startFailureAfterSideEffect = CancellationException("cancel automatic start after publication")
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val original = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-applying-logical",
			serviceRunId = "automatic-applying-run",
		)

		var propagated = false
		try {
			subject.start(original)
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.isActive shouldBe true
		val applying = database.sourceSessionDao()
			.lifecycleActions("automatic-applying-logical")
			.single { action -> action.desiredState == "STARTED" }
		applying.status shouldBe LifecycleActionStatus.APPLYING.name
		applying.attemptCount shouldBe 1
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED

		subject.start(
			original.copy(
				ownerToken = "automatic-replacement-owner",
				serviceRunId = "automatic-replacement-run",
				wallTimeMs = 1_100L,
				elapsedRealtimeNanos = 1_100_000L,
			),
		) shouldBe SessionStartResult.AlreadyActive

		runtime.startCount shouldBe 1
		runtime.isActive shouldBe true
		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session("automatic-applying-logical")?.let { session ->
			session.state shouldBe SessionLifecycleState.STARTING.name
			session.currentServiceRunId shouldBe "automatic-applying-run"
			session.completedAtMs shouldBe null
		}
		database.sourceSessionDao().serviceRun("automatic-applying-run")?.let { run ->
			run.completedAtMs shouldBe null
			run.completionReason shouldBe null
		}
		database.sourceSessionDao().serviceRun("automatic-replacement-run") shouldBe null
		database.sourceSessionDao().lifecycleAction(applying.actionId)?.status shouldBe
			LifecycleActionStatus.APPLYING.name
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.let { action ->
			action.status shouldBe ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED
			action.acceptedLogicalTrackingId shouldBe "automatic-applying-logical"
		}
	}

	@Test
	fun `stale automatic replacement cannot finalize a start accepted provider`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val original = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-accepted-logical",
			serviceRunId = "automatic-accepted-run",
		)

		val started = subject.start(original).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.isActive shouldBe true
		val acceptedStart = database.sourceSessionDao()
			.lifecycleActions(started.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		acceptedStart.status shouldBe LifecycleActionStatus.START_ACCEPTED.name

		subject.start(
			original.copy(
				ownerToken = "automatic-accepted-replay",
				serviceRunId = "automatic-accepted-replacement-run",
				wallTimeMs = 1_100L,
				elapsedRealtimeNanos = 1_100_000L,
			),
		) shouldBe SessionStartResult.AlreadyActive

		runtime.startCount shouldBe 1
		runtime.isActive shouldBe true
		runtime.shutdownClaims shouldBe emptyList()
		database.sourceSessionDao().session("automatic-accepted-logical")?.let { session ->
			session.state shouldBe SessionLifecycleState.ACTIVE.name
			session.currentServiceRunId shouldBe "automatic-accepted-run"
			session.completedAtMs shouldBe null
		}
		database.sourceSessionDao().serviceRun("automatic-accepted-run")?.completedAtMs shouldBe null
		database.sourceSessionDao().serviceRun("automatic-accepted-replacement-run") shouldBe null
		database.sourceSessionDao().lifecycleAction(acceptedStart.actionId)?.status shouldBe
			LifecycleActionStatus.START_ACCEPTED.name
	}

	@Test
	fun `reconfiguration appends an immutable manifest and leaves the prior revision intact`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "logical-manifest", serviceRunId = "run-manifest"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val firstManifest = database.sourceSessionDao().manifests(started.logicalTrackingId).single()
		val firstBindings = database.sourceSessionDao().manifestSources(started.logicalTrackingId, 1L)

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "test-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "balanced-reconfigured",
					createdAtMs = 2_000L,
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000, 15_000, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		).shouldBeInstanceOf<SessionReconfigureResult.Applied>()

		result.revision shouldBe 2L
		result.sourceCallerAuthorityReference.value shouldBe
			"test:${started.logicalTrackingId}:2"
		val manifests = database.sourceSessionDao().manifests(started.logicalTrackingId)
		manifests.map { it.manifestRevision } shouldBe listOf(1L, 2L)
		manifests.first() shouldBe firstManifest
		database.sourceSessionDao().manifestSources(started.logicalTrackingId, 1L) shouldBe firstBindings
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentManifestRevision shouldBe 2L
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId).map { it.intentRevision } shouldBe
			listOf(1L, 2L)
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId)
			.map { it.sourceCallerAuthorityReference } shouldBe listOf(
			"test:${started.logicalTrackingId}:1",
			"test:${started.logicalTrackingId}:2",
		)
		database.sourceCallerAuthorityDao().rows("test:${started.logicalTrackingId}:1")
			.map { it.status }.distinct() shouldBe listOf("ACTIVE")
		database.sourceCallerAuthorityDao().rows("test:${started.logicalTrackingId}:2")
			.map { it.status }.distinct() shouldBe listOf("ACTIVE")
		subject.retireSupersededSourceCallerAuthority(
			logicalTrackingId = started.logicalTrackingId,
			currentReference = result.sourceCallerAuthorityReference,
			supersededReference = requireNotNull(started.sourceCallerAuthorityReference),
			wallTimeMs = 2_100L,
		) shouldBe SourceCallerAuthorityRetirementOutcome.Completed
		database.sourceCallerAuthorityDao().rows("test:${started.logicalTrackingId}:1")
			.map { it.status }.distinct() shouldBe listOf("RETIRED")
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId).map { it.actionRevision } shouldBe
			listOf(1L, 2L)
		val demandHistory = database.sourceBrokerDao().demandHistory("session:${started.logicalTrackingId}")
		demandHistory.map { it.manifestRevision } shouldBe listOf(1L, 2L)
		demandHistory.map { it.status } shouldBe listOf(
			SourceDemandEntity.STATUS_RETIRED,
			SourceDemandEntity.STATUS_ACTIVE,
		)
		demandHistory.map { it.sourceCallerAuthorityReference } shouldBe listOf(
			"test:${started.logicalTrackingId}:1",
			"test:${started.logicalTrackingId}:2",
		)
	}

	@Test
	fun `reconfiguration rejects persisted manifest tampering before runtime action`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "tampered-reconfigure-logical", serviceRunId = "tampered-reconfigure-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET writer_owner_generation = writer_owner_generation + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = 1",
			arrayOf(started.logicalTrackingId),
		)
		val reconfigureCountBefore = runtime.reconfigureCount

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "tampered-reconfigure-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "tampered-reconfigure-plan",
					createdAtMs = 2_000L,
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		)

		result shouldBe SessionReconfigureResult.InvalidIntent("CURRENT_MANIFEST_INTEGRITY_FAILED")
		runtime.reconfigureCount shouldBe reconfigureCountBefore
		database.sourceSessionDao().manifests(started.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `cancelled reconfiguration retires newest applying claim instead of predecessor`() = runTest {
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "cancelled-reconfigure-logical",
				serviceRunId = "cancelled-reconfigure-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val acceptedStart = database.sourceSessionDao()
			.lifecycleActions(started.logicalTrackingId)
			.single { action -> action.desiredState == "STARTED" }
		acceptedStart.status shouldBe LifecycleActionStatus.START_ACCEPTED.name
		runtime.reconfigureFailure = CancellationException("test cancellation")
		var propagated = false

		try {
			subject.reconfigure(
				SessionReconfigureRequest(
					ownerToken = "test-owner",
					plan = startRequest().plan.copy(
						revision = 2L,
						planId = "cancelled-reconfigure",
						createdAtMs = 2_000L,
						plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
					),
					wallTimeMs = 2_000L,
					elapsedRealtimeNanos = 2_000_000L,
					clockDomainId = "boot-1",
					zoneId = "Europe/Prague",
					foregroundCapabilityFlags = 0L,
				),
			)
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		runtime.isActive shouldBe true
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.RECONFIGURING.name
		val applyingReconfigure = database.sourceSessionDao()
			.lifecycleActions(started.logicalTrackingId)
			.last()
		applyingReconfigure.status shouldBe LifecycleActionStatus.APPLYING.name
		applyingReconfigure.actionRevision shouldBe 2L

		runtime.reconfigureFailure = null
		subject.stop(
			SessionStopRequest(
				ownerToken = "cancelled-reconfigure-stop",
				reason = "SERVICE_DESTROYED",
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = 3_000_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		runtime.shutdownClaims.single().let { claim ->
			claim.actionId shouldBe applyingReconfigure.actionId
			claim.attemptCount shouldBe applyingReconfigure.attemptCount
			claim.leaseGeneration shouldBe applyingReconfigure.leaseGeneration
			claim.logicalTrackingId shouldBe started.logicalTrackingId
			claim.serviceRunId shouldBe started.serviceRunId
			(claim.actionId == acceptedStart.actionId) shouldBe false
		}
		runtime.isActive shouldBe false
		database.sourceSessionDao().lifecycleAction(acceptedStart.actionId)?.status shouldBe
			LifecycleActionStatus.START_ACCEPTED.name
		database.sourceSessionDao().lifecycleAction(applyingReconfigure.actionId)?.let { action ->
			action.status shouldBe LifecycleActionStatus.SUPERSEDED.name
			action.failureCode shouldBe "RUNTIME_CLEANUP_CONFIRMED_BY_STOP"
		}
		database.sourceSessionDao().session(started.logicalTrackingId)?.let { session ->
			session.state shouldBe SessionLifecycleState.FINALIZED.name
			session.currentServiceRunId shouldBe null
		}
	}

	@Test
	fun `reconfiguration cannot move effective time backwards`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "test-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "regressed",
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 999_999L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		)

		result shouldBe SessionReconfigureResult.InvalidIntent("RECONFIGURE_EFFECTIVE_TIME_REGRESSED")
		database.sourceSessionDao().manifests(started.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `disabling the last source cannot leave the session active`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()

		val result = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "test-owner",
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "all-disabled",
					plans = mapOf(SourceKind.STEPS to StepsPlan(2L, false, 60_000L, 15_000L, false)),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		).shouldBeInstanceOf<SessionReconfigureResult.Failed>()

		result.failureCode shouldBe "NO_SOURCE_ACTIVE_AFTER_RECONFIGURE"
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FAILED.name
		database.sourceSessionDao().lifecycleIntents(started.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single().registrationGeneration shouldBe 1L
	}

	@Test
	fun `Steps disable and reenable receipts each physical generation under the same run`() = runTest {
		val stepsBinding =
			installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS, SourceKind.LOCATION),
			revision = 3L,
			captureModes = mapOf(
				SourceKind.STEPS to stepsBinding.captureModes,
				SourceKind.LOCATION to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 2L))
		fun plan(revision: Long, stepsEnabled: Boolean) = AcquisitionPlanRevision(
			revision = revision,
			planId = "steps-cycle-$revision",
			createdAtMs = revision * 1_000L,
			plans = mapOf(
				SourceKind.STEPS to StepsPlan(revision, stepsEnabled, 60_000L, 15_000L, false),
				SourceKind.LOCATION to LocationPlan(
					revision = revision,
					backend = LocationBackend.FUSED,
					mode = LocationMode.BALANCED,
					requestedIntervalMs = 2_000L,
					minimumUpdateIntervalMs = 2_000L,
					minimumDisplacementMeters = 10f,
					maximumBatchDelayMs = 10_000L,
					probeDurationMs = null,
					preciseLocationAvailable = true,
				),
			),
			sourcePolicyRevision = 1L,
		)
		val started = subject.start(
			startRequest().copy(
				plan = plan(1L, true),
				rolloutRevision = rolloutSnapshot.revision,
			),
		)
			.shouldBeInstanceOf<SessionStartResult.Started>()
		fun request(revision: Long, enabled: Boolean) = SessionReconfigureRequest(
			ownerToken = "test-owner",
			plan = plan(revision, enabled),
			wallTimeMs = revision * 1_000L,
			elapsedRealtimeNanos = revision * 1_000_000L,
			clockDomainId = "boot-1",
			zoneId = "Europe/Prague",
			foregroundCapabilityFlags = 0L,
		)

		subject.reconfigure(request(2L, false)).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe
			started.serviceRunId
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.map { it.registrationGeneration } shouldBe listOf(1L)
		subject.reconfigure(request(3L, true)).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
		subject.reconfigure(request(4L, false)).shouldBeInstanceOf<SessionReconfigureResult.Applied>()
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.map { it.registrationGeneration }.sorted() shouldBe listOf(1L, 2L)

		sourceProductDrainRouter.requests.clear()
		subject.stop(
			SessionStopRequest(
				"steps-cycle-final-stop",
				"USER_STOP",
				5_000L,
				5_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()
		sourceProductDrainRouter.requests.single { request ->
			request.source == SourceKind.STEPS
		}.memberships.map(SourceDrainMembership::registrationGeneration) shouldBe listOf(1L, 2L)
	}

	@Test
	fun `failed source disable blocks active and final states until predecessor provider retires`() = runTest {
		fun plan(revision: Long, locationEnabled: Boolean) = AcquisitionPlanRevision(
			revision = revision,
			planId = "location-retirement-$revision",
			createdAtMs = revision * 1_000L,
			plans = mapOf(
				SourceKind.STEPS to StepsPlan(revision, true, 60_000L, 15_000L, false),
				SourceKind.LOCATION to (LocationPlan(
					revision = revision,
					backend = LocationBackend.FUSED,
					mode = LocationMode.BALANCED,
					requestedIntervalMs = 2_000L,
					minimumUpdateIntervalMs = 2_000L,
					minimumDisplacementMeters = 10f,
					maximumBatchDelayMs = 10_000L,
					probeDurationMs = null,
					preciseLocationAvailable = true,
				).takeIf { locationEnabled } ?: LocationPlan(
					revision = revision,
					backend = LocationBackend.FUSED,
					mode = LocationMode.DISABLED,
					requestedIntervalMs = 60_000L,
					minimumUpdateIntervalMs = 60_000L,
					minimumDisplacementMeters = 0f,
					maximumBatchDelayMs = 0L,
					probeDurationMs = null,
					preciseLocationAvailable = false,
				)),
			),
			sourcePolicyRevision = 1L,
		)
		val started = subject.start(startRequest().copy(plan = plan(1L, locationEnabled = true)))
			.shouldBeInstanceOf<SessionStartResult.Started>()
		locationRuntime.failRetirement = true

		val reconfigured = subject.reconfigure(
			SessionReconfigureRequest(
				ownerToken = "location-disable-owner",
				plan = plan(2L, locationEnabled = false),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-1",
				zoneId = "Europe/Prague",
				foregroundCapabilityFlags = 0L,
			),
		).shouldBeInstanceOf<SessionReconfigureResult.Failed>()

		reconfigured.failureCode shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.RECONFIGURING.name
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId)
			.last { action -> action.sourceKind == SourceKind.LOCATION.stableCode }
			.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
		locationRuntime.isActive shouldBe true

		subject.stop(
			SessionStopRequest(
				"location-cleanup-owner",
				"provider-retirement",
				3_000L,
				3_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STOPPING.name
		locationRuntime.quiesceCount shouldBe 1
		locationRuntime.isActive shouldBe true

		locationRuntime.failRetirement = false
		subject.stop(
			SessionStopRequest(
				"location-cleanup-owner",
				"provider-retirement-retry",
				4_000L,
				4_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		locationRuntime.quiesceCount shouldBe 2
		locationRuntime.isActive shouldBe false
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
	}

	@Test
	fun `released lease reacquisition increments generation and fences the old token`() = runTest {
		val dao = database.sourceProjectionStateDao()
		dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = "test-lease",
				ownerToken = "owner",
				acquiredAtMs = 1,
				expiresAtMs = 2,
				bootId = "boot",
				generation = 1,
				acquiredElapsedRealtimeNanos = 100,
				expiresElapsedRealtimeNanos = 200,
			),
		) shouldBe 1L
		dao.releaseLease("test-lease", "owner", "boot", 1L, 1, 150L) shouldBe 1

		dao.acquireOrRenewLease("test-lease", "owner", "boot", 2, 3, 151L, 300L) shouldBe 1

		dao.lease("test-lease")?.generation shouldBe 2L
		dao.releaseLease("test-lease", "owner", "boot", 1L, 2, 160L) shouldBe 0
	}

	@Test
	fun `stale boot direct start is rejected before any durable row or lease`() = runTest {
		val request = startRequest().copy(
			ownerToken = "stale-boot-owner",
			logicalTrackingId = "stale-boot-logical",
			serviceRunId = "stale-boot-run",
			clockDomainId = "boot-2",
		)

		subject.start(request) shouldBe SessionStartResult.InvalidIntent("BOOT_ID_STALE")

		val dao = database.sourceSessionDao()
		dao.session("stale-boot-logical") shouldBe null
		dao.serviceRun("stale-boot-run") shouldBe null
		dao.manifests("stale-boot-logical") shouldBe emptyList()
		dao.lifecycleIntents("stale-boot-logical") shouldBe emptyList()
		dao.lifecycleActions("stale-boot-logical") shouldBe emptyList()
		database.sourceProjectionStateDao().lease("tracking-session-coordinator") shouldBe null
		runtime.startCount shouldBe 0
	}

	@Test
	fun `stale boot prepared start is rejected before any durable row or lease`() = runTest {
		val token = PreparedTrackingStartToken("stale-boot-prepared-token")
		val request = startRequest().copy(
			ownerToken = "stale-boot-prepared-owner",
			logicalTrackingId = "stale-boot-prepared-logical",
			serviceRunId = "stale-boot-prepared-run",
			clockDomainId = "boot-2",
		)

		subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = token,
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		) shouldBe SessionStartPreparationResult.Rejected("BOOT_ID_STALE")

		val dao = database.sourceSessionDao()
		dao.session("stale-boot-prepared-logical") shouldBe null
		dao.serviceRun("stale-boot-prepared-run") shouldBe null
		dao.manifests("stale-boot-prepared-logical") shouldBe emptyList()
		dao.lifecycleActions("stale-boot-prepared-logical") shouldBe emptyList()
		database.sourceProjectionStateDao().lease("tracking-session-coordinator") shouldBe null
	}

	@Test
	fun `stale boot cannot mutate an active session through reconfigure stop or suspend`() = runTest {
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "stale-mutation-logical",
				serviceRunId = "stale-mutation-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val dao = database.sourceSessionDao()
		val sessionBefore = requireNotNull(dao.session(started.logicalTrackingId))
		val runBefore = requireNotNull(dao.serviceRun(started.serviceRunId))
		val manifestsBefore = dao.manifests(started.logicalTrackingId)
		val intentsBefore = dao.lifecycleIntents(started.logicalTrackingId)
		val actionsBefore = dao.lifecycleActions(started.logicalTrackingId)
		val leaseBefore = database.sourceProjectionStateDao().lease("tracking-session-coordinator")
		val reconfigureCountBefore = runtime.reconfigureCount
		val shutdownCountBefore = runtime.shutdownClaims.size

		val reconfigure = SessionReconfigureRequest(
			ownerToken = "stale-reconfigure-owner",
			plan = startRequest().plan.copy(
				revision = 2L,
				planId = "stale-boot-reconfigure",
				createdAtMs = 2_000L,
				plans = mapOf(SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false)),
			),
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = 2_000_000L,
			clockDomainId = "boot-2",
			zoneId = "Europe/Prague",
			foregroundCapabilityFlags = 0L,
		)
		subject.reconfigure(reconfigure) shouldBe
			SessionReconfigureResult.InvalidIntent("BOOT_ID_STALE")
		subject.stop(
			SessionStopRequest(
				ownerToken = "stale-stop-owner",
				reason = "STALE_BOOT_TEST",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-2",
			),
		) shouldBe SessionStopResult.InvalidIntent("BOOT_ID_STALE")
		subject.suspendForRestart(
			SessionSuspendRequest(
				ownerToken = "stale-suspend-owner",
				reason = "STALE_BOOT_TEST",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
				clockDomainId = "boot-2",
			),
		) shouldBe SessionSuspendResult.InvalidIntent("BOOT_ID_STALE")

		dao.session(started.logicalTrackingId) shouldBe sessionBefore
		dao.serviceRun(started.serviceRunId) shouldBe runBefore
		dao.manifests(started.logicalTrackingId) shouldBe manifestsBefore
		dao.lifecycleIntents(started.logicalTrackingId) shouldBe intentsBefore
		dao.lifecycleActions(started.logicalTrackingId) shouldBe actionsBefore
		database.sourceProjectionStateDao().lease("tracking-session-coordinator") shouldBe leaseBefore
		runtime.reconfigureCount shouldBe reconfigureCountBefore
		runtime.shutdownClaims.size shouldBe shutdownCountBefore
	}

	@Test
	fun `extreme request elapsed metadata cannot poison the canonical session lease`() = runTest {
		val incumbent = incumbentSessionLease()
		database.sourceProjectionStateDao().insertLeaseIfAbsent(incumbent) shouldBe 1L

		subject.start(
			startRequest().copy(
				ownerToken = "challenger",
				elapsedRealtimeNanos = Long.MAX_VALUE,
			),
		) shouldBe SessionStartResult.Busy

		database.sourceProjectionStateDao().lease(incumbent.leaseName) shouldBe incumbent
	}

	@Test
	fun `prepared demand is not callback authority until foreground acceptance`() = runTest {
		seedActiveStepsRegistration()
		val prepared = prepareAndroidStart(
			tokenValue = "blocked-demand-token",
			commandGeneration = 11L,
			logicalTrackingId = "blocked-demand-logical",
			serviceRunId = "blocked-demand-run",
		)
		val consumerId = "session:${prepared.logicalTrackingId}"
		val brokerDao = database.sourceBrokerDao()

		brokerDao.currentDemands(consumerId) shouldBe emptyList()
		brokerDao.demandHistory(consumerId).single().status shouldBe SourceDemandEntity.STATUS_BLOCKED
		brokerDao.authorizationDemands(SourceKind.STEPS.stableCode) shouldBe emptyList()
		brokerDao.latestAuthorization(SourceKind.STEPS.stableCode, 1L)
			.any { authorization ->
				authorization.purpose == SourceBrokerPurpose.SESSION_CAPTURE
			} shouldBe false
		database.sourceSessionDao().lifecycleActions(prepared.logicalTrackingId)
			.map { action -> action.status }.distinct() shouldBe
			listOf(LifecycleActionStatus.AWAITING_FOREGROUND.name)

		subject.claimAndroidStart(
			prepared.token,
			11L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		subject.markPreparedForegroundAccepted(
			prepared.token,
			11L,
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe true

		brokerDao.currentDemands(consumerId).single().status shouldBe SourceDemandEntity.STATUS_ACTIVE
		val captureAuthorization = brokerDao.latestAuthorization(SourceKind.STEPS.stableCode, 1L)
			.single { authorization ->
				authorization.purpose == SourceBrokerPurpose.SESSION_CAPTURE
			}
		captureAuthorization.logicalTrackingId shouldBe prepared.logicalTrackingId
		captureAuthorization.serviceRunId shouldBe prepared.serviceRunId
		captureAuthorization.manifestRevision shouldBe prepared.manifestRevision
		database.sourceSessionDao().lifecycleActions(prepared.logicalTrackingId)
			.map { action -> action.status }.distinct() shouldBe
			listOf(LifecycleActionStatus.PENDING.name)
	}

	@Test
	fun `active manual redelivery prepares one distinct recovery run after old lease expiry`() = runTest {
		val old = activatePreparedAndroidRun(
			tokenValue = "active-redelivery-old-token",
			commandGeneration = 31L,
			logicalTrackingId = "active-redelivery-logical",
			serviceRunId = "active-redelivery-old-run",
		)
		val recoveryToken = PreparedTrackingStartToken("active-redelivery-recovery-token")
		val recoveryElapsed = expiredPreparedLeaseElapsedNanos()
		val recoveryRequest = startRequest().copy(
			origin = SessionStartOrigin.RECOVERY,
			plan = startRequest().plan.copy(
				revision = 2L,
				planId = "active-redelivery-recovery-plan",
				createdAtMs = 2_000L,
				plans = mapOf(
					SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false),
				),
			),
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = recoveryElapsed,
			logicalTrackingId = old.logicalTrackingId,
			serviceRunId = "active-redelivery-recovery-run",
			continuationAuthority = ServiceRunContinuationAuthority(
				previousServiceRunId = old.serviceRunId,
				previousDeliveryToken = old.token,
				previousCommandGeneration = 31L,
			),
		)

		val recovery = subject.prepareAndroidStart(
			recoveryRequest,
			AndroidStartDeliveryMetadata(recoveryToken, 32L, true, false),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		recovery.logicalTrackingId shouldBe old.logicalTrackingId
		recovery.serviceRunId shouldBe "active-redelivery-recovery-run"
		recovery.token shouldBe recoveryToken
		recovery.sourceCallerAuthorityReference shouldBe
			com.adsamcik.tracker.tracker.api.SourceCallerReplayReference(
				"test:${old.logicalTrackingId}:2",
			)
		database.sourceSessionDao().serviceRun(old.serviceRunId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().serviceRun(old.serviceRunId)?.completionReason shouldBe
			"PROCESS_DEATH_RECOVERY"
		database.sourceSessionDao().incompleteServiceRuns(old.logicalTrackingId)
			.map { it.serviceRunId } shouldBe listOf(recovery.serviceRunId)
		database.sourceSessionDao().session(old.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STARTING.name
		database.sourceSessionDao().manifests(old.logicalTrackingId).size shouldBe 2
		database.sourceCallerAuthorityDao().rows("test:${old.logicalTrackingId}:1")
			.map { it.status }.distinct() shouldBe listOf("RETIRED")
		database.sourceCallerAuthorityDao().rows("test:${old.logicalTrackingId}:2")
			.map { it.status }.distinct() shouldBe listOf("ACTIVE")
	}

	@Test
	fun `interrupted manual reconfiguration prepares a replacement recovery run`() = runTest {
		val old = activatePreparedAndroidRun(
			tokenValue = "reconfiguring-old-token",
			commandGeneration = 51L,
			logicalTrackingId = "reconfiguring-logical",
			serviceRunId = "reconfiguring-old-run",
		)
		val session = requireNotNull(database.sourceSessionDao().session(old.logicalTrackingId))
		database.sourceSessionDao().updateSession(
			session.copy(state = SessionLifecycleState.RECONFIGURING.name),
		) shouldBe 1
		val recoveryToken = PreparedTrackingStartToken("reconfiguring-recovery-token")

		val recovery = subject.prepareAndroidStart(
			startRequest().copy(
				origin = SessionStartOrigin.RECOVERY,
				plan = startRequest().plan.copy(
					revision = 2L,
					planId = "reconfiguring-recovery-plan",
					createdAtMs = 2_000L,
					plans = mapOf(
						SourceKind.STEPS to StepsPlan(2L, true, 60_000L, 15_000L, false),
					),
				),
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = expiredPreparedLeaseElapsedNanos(),
				logicalTrackingId = old.logicalTrackingId,
				serviceRunId = "reconfiguring-recovery-run",
				continuationAuthority = ServiceRunContinuationAuthority(
					previousServiceRunId = old.serviceRunId,
					previousDeliveryToken = old.token,
					previousCommandGeneration = 51L,
				),
			),
			AndroidStartDeliveryMetadata(recoveryToken, 52L, true, false),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		recovery.logicalTrackingId shouldBe old.logicalTrackingId
		recovery.serviceRunId shouldBe "reconfiguring-recovery-run"
		database.sourceSessionDao().serviceRun(old.serviceRunId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().session(old.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.STARTING.name
	}

	@Test
	fun `different boot terminalizes exact active redelivery without creating a run`() = runTest {
		val old = activatePreparedAndroidRun(
			tokenValue = "old-boot-redelivery-token",
			commandGeneration = 41L,
			logicalTrackingId = "old-boot-redelivery-logical",
			serviceRunId = "old-boot-redelivery-run",
		)

		subject.finalizeUnrecoverableContinuation(
			logicalTrackingId = old.logicalTrackingId,
			authority = ServiceRunContinuationAuthority(
				old.serviceRunId,
				old.token,
				41L,
			),
			currentBootId = "boot-2",
			elapsedRealtimeNanos = 10_000_000L,
			wallTimeMs = 2_000L,
			failureCode = "REDELIVERY_RECOVERY_OLD_BOOT",
		) shouldBe true

		database.sourceSessionDao().session(old.logicalTrackingId)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().session(old.logicalTrackingId)?.failureCode shouldBe
			"REDELIVERY_RECOVERY_OLD_BOOT"
		database.sourceSessionDao().incompleteServiceRuns(old.logicalTrackingId) shouldBe emptyList()
		database.sourceSessionDao().manifests(old.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `expired prepared delivery terminalizes only the untouched envelope`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "expired-prepared-token",
			commandGeneration = 21L,
			logicalTrackingId = "expired-prepared-logical",
			serviceRunId = "expired-prepared-run",
		)

		val result = subject.claimAndroidStart(
			prepared.token,
			21L,
			"boot-1",
			expiredPreparedLeaseElapsedNanos(),
			2_000L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Rejected>()

		result.failureCode shouldBe "PREPARED_START_LEASE_EXPIRED"
		assertPreparedStartTerminalized(prepared, "PREPARED_START_LEASE_EXPIRED")
		database.sourceSessionDao().hasLifecycleBoundaryBlocker() shouldBe false
		database.sourceSessionDao().hasIncompleteServiceRun() shouldBe false
		database.sourceSessionDao().hasNonterminalLatestLifecycleAction() shouldBe false
	}

	@Test
	fun `expired enqueued automatic delivery terminalizes its accepted action`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val prepared = prepareAndroidStart(
			tokenValue = "expired-enqueued-token",
			commandGeneration = 22L,
			logicalTrackingId = "expired-enqueued-logical",
			serviceRunId = "expired-enqueued-run",
			request = startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
			),
		)
		subject.markAndroidStartEnqueued(prepared.token, 22L, 1_100L) shouldBe true
		database.sourceSessionDao().serviceRun(prepared.serviceRunId)?.androidDeliveryState shouldBe
			AndroidStartDeliveryState.ENQUEUED.name
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED

		val result = subject.claimAndroidStart(
			prepared.token,
			22L,
			"boot-1",
			expiredPreparedLeaseElapsedNanos(),
			2_000L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Rejected>()

		result.failureCode shouldBe "PREPARED_START_LEASE_EXPIRED"
		assertPreparedStartTerminalized(prepared, "PREPARED_START_LEASE_EXPIRED")
		val action = requireNotNull(database.activityAutomaticStartActionDao().action(trigger.triggerId))
		action.status shouldBe ActivityAutomaticStartActionEntity.STATUS_TERMINAL
		action.terminalReason shouldBe "PREPARED_START_LEASE_EXPIRED"
		action.acceptedLogicalTrackingId shouldBe prepared.logicalTrackingId
		action.acceptedIntentRevision shouldBe prepared.intentRevision
	}

	@Test
	fun `lease expiry after claim but before foreground terminalizes delivered envelope`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "expired-delivered-token",
			commandGeneration = 23L,
			logicalTrackingId = "expired-delivered-logical",
			serviceRunId = "expired-delivered-run",
		)
		subject.markAndroidStartEnqueued(prepared.token, 23L, 1_050L) shouldBe true
		subject.claimAndroidStart(
			prepared.token,
			23L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		database.sourceSessionDao().serviceRun(prepared.serviceRunId)?.androidDeliveryState shouldBe
			AndroidStartDeliveryState.DELIVERED.name

		subject.markPreparedForegroundAccepted(
			prepared.token,
			23L,
			"boot-1",
			expiredPreparedLeaseElapsedNanos(),
			2_000L,
		) shouldBe false

		assertPreparedStartTerminalized(prepared, "PREPARED_START_LEASE_EXPIRED")
	}

	@Test
	fun `advanced envelope refuses old claim and compensation without touching newer rows`() = runTest {
		val prepared = prepareAndroidStart(
			tokenValue = "advanced-old-token",
			commandGeneration = 24L,
			logicalTrackingId = "advanced-logical",
			serviceRunId = "advanced-old-run",
		)
		subject.markAndroidStartEnqueued(prepared.token, 24L, 1_050L) shouldBe true
		advancePreparedEnvelope(prepared)
		val sessionDao = database.sourceSessionDao()
		val newerSessionBefore = requireNotNull(sessionDao.session(prepared.logicalTrackingId))
		val newerRunBefore = requireNotNull(sessionDao.serviceRun("advanced-new-run"))
		val newerActionBefore = sessionDao.lifecycleActions(prepared.logicalTrackingId)
			.single { action -> action.serviceRunId == newerRunBefore.serviceRunId }
		val demandHistoryBefore = database.sourceBrokerDao()
			.demandHistory("session:${prepared.logicalTrackingId}")

		val claim = subject.claimAndroidStart(
			prepared.token,
			24L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Rejected>()
		claim.failureCode shouldBe "PREPARED_START_ROOM_ENVELOPE_STALE"
		subject.compensatePreparedAndroidStart(
			prepared.token,
			24L,
			"OLD_ENQUEUE_FAILED",
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe false

		sessionDao.session(prepared.logicalTrackingId) shouldBe newerSessionBefore
		sessionDao.serviceRun("advanced-new-run") shouldBe newerRunBefore
		sessionDao.lifecycleActions(prepared.logicalTrackingId)
			.single { action -> action.serviceRunId == newerRunBefore.serviceRunId } shouldBe newerActionBefore
		database.sourceBrokerDao().demandHistory("session:${prepared.logicalTrackingId}") shouldBe
			demandHistoryBefore
		sessionDao.serviceRun(prepared.serviceRunId)?.androidDeliveryState shouldBe
			AndroidStartDeliveryState.ENQUEUED.name
	}

	@Test
	fun `zero-source and stale automatic starts fail before durable intent`() = runTest {
		val zeroSource = startRequest().copy(
			plan = startRequest().plan.copy(
				plans = mapOf(SourceKind.STEPS to StepsPlan(1L, false, 60_000, 15_000, false)),
			),
		)
		subject.start(zeroSource) shouldBe SessionStartResult.InvalidIntent("ZERO_CAPTURE_SOURCES")

		val staleAutomatic = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = AutomaticTrackingStartTrigger(
				triggerId = "motion-1",
				kind = "ON_FOOT",
				bootId = "old-boot",
				observedElapsedRealtimeNanos = 500_000L,
				receivedElapsedRealtimeNanos = 600_000L,
				expiresElapsedRealtimeNanos = 2_000_000L,
				automationEpoch = 9L,
				startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
				sourcePolicyRevision = 1L,
				requestedCaptureSourceMask = 1L shl (SourceKind.STEPS.stableCode - 1),
				intendedCaptureSourceMask = 1L shl (SourceKind.STEPS.stableCode - 1),
				intendedForegroundServiceTypeMask = 0L,
				collectedDataEpoch = 0L,
			),
		)
		subject.start(staleAutomatic) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_TRIGGER_BOOT_STALE")

		val oldPolicyRevision = staleAutomatic.copy(
			automaticTrigger = requireNotNull(staleAutomatic.automaticTrigger).copy(
				bootId = "boot-1",
				sourcePolicyRevision = 2L,
			),
		)
		subject.start(oldPolicyRevision) shouldBe
			SessionStartResult.InvalidIntent("AUTOMATIC_TRIGGER_EPOCH_STALE")

		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
	}

	@Test
	fun `automatic action and lifecycle intent are accepted atomically with distinct epochs`() = runTest {
		val trigger = automaticTrigger(automationEpoch = 9L, sourcePolicyRevision = 1L)
		seedAutomaticStartAction(trigger)

		val started = subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				logicalTrackingId = "automatic-logical",
				serviceRunId = "automatic-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		started.logicalTrackingId shouldBe "automatic-logical"
		val action = requireNotNull(database.activityAutomaticStartActionDao().current())
		action.status shouldBe ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED
		action.acceptedLogicalTrackingId shouldBe started.logicalTrackingId
		val intent = requireNotNull(
			database.sourceSessionDao().lifecycleIntent(started.logicalTrackingId, 1L),
		)
		intent.automationEpoch shouldBe 9L
		intent.triggerCollectedDataEpoch shouldBe trigger.collectedDataEpoch
		activityAutomationDrainSignal.requestCount shouldBe 1
	}

	@Test
	fun `coordinator synchronously reconciles lock before automatic lifecycle acceptance`() = runTest {
		val trigger = automaticTrigger(automationEpoch = 17L, sourcePolicyRevision = 1L)
		seedAutomaticStartAction(trigger)
		activityLocked = true

		subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				logicalTrackingId = "locked-automatic-logical",
				serviceRunId = "locked-automatic-run",
			),
		) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_START_ACTION_TERMINAL")

		database.activityAutomationEpochDao().current()?.let { it.epoch to it.lockSuppressed } shouldBe
			(18L to true)
		database.sourceSessionDao().session("locked-automatic-logical") shouldBe null
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_TERMINAL
	}

	@Test
	fun `automatic action foreground envelope mismatch creates no lifecycle intent`() = runTest {
		val trigger = automaticTrigger(intendedForegroundServiceTypeMask = 256L)
		seedAutomaticStartAction(trigger)

		subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				foregroundCapabilityFlags = 0L,
			),
		) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_START_FGS_MASK_MISMATCH")

		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
		database.activityAutomaticStartActionDao().current()?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_TERMINAL
		database.activityAutomaticStartActionDao().current()?.terminalReason shouldBe
			"AUTOMATIC_START_FGS_MASK_MISMATCH"
	}

	@Test
	fun `automatic start without the exact requested action creates no lifecycle intent`() = runTest {
		val trigger = automaticTrigger()
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = trigger.collectedDataEpoch),
		)

		subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
			),
		) shouldBe SessionStartResult.InvalidIntent("AUTOMATIC_START_ACTION_MISSING")

		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
	}

	@Test
	fun `accepted automatic action cannot recreate its finalized logical session`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-logical",
			serviceRunId = "automatic-run",
		)
		subject.start(request).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.stop(
			SessionStopRequest(
				ownerToken = "test-owner",
				reason = "test",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 1_100_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		subject.start(
			request.copy(
				wallTimeMs = 3_000L,
				elapsedRealtimeNanos = 1_200_000L,
			),
		) shouldBe SessionStartResult.InvalidIntent(
			"AUTOMATIC_START_ACCEPTED_SESSION_TERMINAL",
		)
		database.sourceSessionDao().manifests("automatic-logical").size shouldBe 1
	}

	@Test
	fun `crash after coordinator acceptance then finalize still acknowledges the durable outbox`() = runTest {
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "activity-automatic-effect",
				projectionId = ActivityAutomationProjection.ID,
				projectionVersion = ActivityAutomationProjection.VERSION,
				admissionOrdinal = 1L,
				effectKind = ActivityAutomationProjection.OUTBOX_KIND,
				payloadVersion = ActivityAutomationProjection.PAYLOAD_VERSION,
				payload = automaticOutboxPayload(trigger),
				createdAtMs = 500L,
				deliveredAtMs = null,
			),
		) shouldBe 1L
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-logical",
			serviceRunId = "automatic-run",
		)

		// Model a process crash after the coordinator transaction commits but before the
		// process-local drain hint is serviced, followed by terminal session recovery.
		subject.start(request).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.stop(
			SessionStopRequest(
				ownerToken = "test-owner",
				reason = "process-exit-recovery",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 1_100_000L,
				clockDomainId = "boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		val actionRepository = ActivityAutomaticStartActionRepository(
			database,
			ReadyTrackingStartupGate,
		)
		val consumer = mockk<ActivityAutomationEffectConsumer>(relaxed = true)
		val dispatcher = ActivityAutomationOutboxDispatcher(
			database,
			ActivityAutomationEffectValidator(
				database,
				object : BootClockDomainProvider {
					override fun current(): String = "boot-1"
				},
				actionRepository,
				mockk<ActivityAutomationEpochAuthority>(relaxed = true),
			),
			consumer,
		)

		actionRepository.validateForService(trigger)
			.shouldBeInstanceOf<ActivityAutomaticStartServiceValidation.Rejected>()
			.reason shouldBe "AUTOMATIC_START_ACCEPTED_SESSION_TERMINAL"
		dispatcher.drain() shouldBe 1
		val outbox = requireNotNull(
			database.sourceProjectionStateDao().outbox("activity-automatic-effect"),
		)
		(outbox.deliveredAtMs != null) shouldBe true
		outbox.terminalDisposition shouldBe null
		coVerify(exactly = 0) { consumer.deliver(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `a finalized logical identity cannot be recovered or recreated`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "terminal-logical", serviceRunId = "terminal-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		subject.stop(
			SessionStopRequest("test-owner", "manual", 2_000, 2_000_000, "boot-1", perSourceTimeoutMs = 100),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		val recovery = startRequest().copy(
			origin = SessionStartOrigin.RECOVERY,
			logicalTrackingId = started.logicalTrackingId,
			serviceRunId = "terminal-run-2",
			continuationAuthority = ServiceRunContinuationAuthority(started.serviceRunId),
			wallTimeMs = 3_000L,
			elapsedRealtimeNanos = 3_000_000L,
		)
		subject.start(recovery) shouldBe SessionStartResult.InvalidIntent("RECOVERY_SESSION_MISSING")
		database.sourceSessionDao().manifests(started.logicalTrackingId).size shouldBe 1
	}

	@Test
	fun `service run suspension preserves logical session and recovery resumes exact identity`() = runTest {
		val first = subject.start(
			startRequest().copy(logicalTrackingId = "logical-1", serviceRunId = "run-1"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		first.logicalTrackingId shouldBe "logical-1"
		first.serviceRunId shouldBe "run-1"
		val lifecycleRevisionBeforeSuspend = requireNotNull(
			database.sourceSessionDao().session("logical-1"),
		).lifecycleRevision

		subject.suspendForRestart(
			SessionSuspendRequest(
				"test-owner",
				"ANDROID_RESTART",
				2_000,
				2_000_000,
				"boot-1",
				perSourceTimeoutMs = 100,
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()

		database.sourceSessionDao().session("logical-1")?.state shouldBe SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().session("logical-1")?.currentServiceRunId shouldBe null
		database.sourceSessionDao().session("logical-1")?.lifecycleRevision shouldBe
			lifecycleRevisionBeforeSuspend + 2L
		database.sourceSessionDao().serviceRun("run-1")?.state shouldBe SessionLifecycleState.FINALIZED.name

		val restored = subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.RECOVERY,
				plan = AcquisitionPlanRevision(
					revision = 2,
					planId = "balanced-restore",
					createdAtMs = 3_000,
					plans = mapOf(SourceKind.STEPS to StepsPlan(2, true, 60_000, 15_000, false)),
					sourcePolicyRevision = 1,
				),
				wallTimeMs = 3_000,
				elapsedRealtimeNanos = 3_000_000,
				logicalTrackingId = "logical-1",
				serviceRunId = "run-2",
				continuationAuthority = ServiceRunContinuationAuthority("run-1"),
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		restored.logicalTrackingId shouldBe "logical-1"
		restored.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().serviceRun("run-2")?.logicalTrackingId shouldBe "logical-1"
		database.sourceSessionDao().session("logical-1")?.currentServiceRunId shouldBe "run-2"
		database.sourceSessionDao().session("logical-1")?.desiredPlanRevision shouldBe 2L
	}

	@Test
	fun `service run suspension stays nonterminal until provider flush retry succeeds`() = runTest {
		val started = subject.start(
			startRequest().copy(logicalTrackingId = "suspend-incomplete-logical", serviceRunId = "suspend-incomplete-run"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.FAILED

		val pending = subject.suspendForRestart(
			SessionSuspendRequest(
				"suspend-incomplete-owner",
				"ANDROID_RESTART",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.CleanupPending>()

		pending.requiredOrdinal shouldBe 0L
		val pendingRun = requireNotNull(database.sourceSessionDao().serviceRun(started.serviceRunId))
		pendingRun.state shouldBe SessionLifecycleState.STOPPING.name
		pendingRun.runtimeAcknowledgement shouldBe LifecycleActionStatus.CLEANUP_REQUIRED.name
		pendingRun.runtimeFailureCode shouldBe "STOP_INCOMPLETE"
		pendingRun.completionReason shouldBe null
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe started.serviceRunId
		database.sourceSessionDao().completenessForServiceRun(started.logicalTrackingId, started.serviceRunId)
			.single { completeness -> completeness.sourceKind == SourceKind.STEPS.stableCode }
			.stopStatus shouldBe SourceStopStatus.COMPLETE.name
		database.sourceSessionDao().lifecycleActions(started.logicalTrackingId).last().status shouldBe
			LifecycleActionStatus.CLEANUP_REQUIRED.name

		runtime.stopProviderFlushOutcome = ProviderFlushOutcome.COMPLETE
		subject.suspendForRestart(
			SessionSuspendRequest(
				"suspend-incomplete-owner",
				"ANDROID_RESTART",
				2_500L,
				2_500_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()
		val completedRun = requireNotNull(database.sourceSessionDao().serviceRun(started.serviceRunId))
		completedRun.state shouldBe SessionLifecycleState.FINALIZED.name
		completedRun.runtimeAcknowledgement shouldBe LifecycleActionStatus.STOP_ACCEPTED.name
		completedRun.runtimeFailureCode shouldBe null
		database.sourceSessionDao().session(started.logicalTrackingId)?.currentServiceRunId shouldBe null
	}

	@Test
	fun `ordinary duplicate start cannot replace a live manual session`() = runTest {
		subject.start(
			startRequest().copy(
				logicalTrackingId = "active-manual",
				serviceRunId = "active-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		subject.start(
			startRequest().copy(
				logicalTrackingId = "duplicate-manual",
				serviceRunId = "duplicate-run",
				wallTimeMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000L,
			),
		) shouldBe SessionStartResult.AlreadyActive

		database.sourceSessionDao().activeSession()?.logicalTrackingId shouldBe "active-manual"
		database.sourceSessionDao().session("active-manual")?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().serviceRun("active-run")?.completionReason shouldBe null
		database.sourceSessionDao().session("duplicate-manual") shouldBe null
	}

	@Test
	fun `plan from a revoked policy revision is rejected before provider start`() = runTest {
		val initialSettings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		val initial = policy.bootstrapFromLegacy(initialSettings)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = initial.revision,
			settings = initialSettings.copy(
				stepsEnabled = false,
				sourceCollectionSettings = initialSettings.sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_REVOKE",
		)

		val result = subject.start(
			startRequest().copy(
				plan = startRequest().plan.copy(sourcePolicyRevision = initial.revision),
			),
		)

		result shouldBe SessionStartResult.InvalidPolicy("SOURCE_POLICY_REVISION_STALE")
		runtime.stateObservedAtStart shouldBe null
		(database.sourcePolicyDao().authority()?.currentPolicyRevision) shouldBe 2L
	}

	@Test
	fun `unbound new plan is rejected without durable lifecycle intent`() = runTest {
		val result = subject.start(startRequest().copy(plan = startRequest().plan.copy(sourcePolicyRevision = null)))

		result shouldBe SessionStartResult.InvalidPolicy("SOURCE_POLICY_BINDING_MISSING")
		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
	}

	@Test
	fun `plan stronger than current policy QoS is rejected`() = runTest {
		val forged = startRequest().plan.copy(
			plans = mapOf(SourceKind.STEPS to StepsPlan(1, true, 5_000, 2_000, true)),
		)

		subject.start(startRequest().copy(plan = forged)) shouldBe
			SessionStartResult.InvalidPolicy("SOURCE_PLAN_EXCEEDS_POLICY")
		runtime.stateObservedAtStart shouldBe null
	}

	@Test
	fun `manual location capture keeps its consent epoch after unrelated wifi policy change`() = runTest {
		val unchanged = advancePolicyForUnrelatedWifiQos()
		val request = startRequest().copy(
			plan = locationOnlyPlan(unchanged.policyRevision),
			wallTimeMs = 2_000L,
			elapsedRealtimeNanos = 2_000_000L,
			logicalTrackingId = "location-only-manual",
			serviceRunId = "location-only-manual-run",
		)

		val prepared = subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("location-only-manual-token"),
				commandGeneration = 1L,
				isUserInitiated = true,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val binding = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
			.single()
		binding.sourceKind shouldBe SourceKind.LOCATION.stableCode
		binding.purpose shouldBe SessionManifestPurpose.SESSION_CAPTURE.name
		binding.consentEpoch shouldBe unchanged.locationCaptureEpoch
	}

	@Test
	fun `automatic location start keeps capture and activity control epochs after wifi change`() = runTest {
		val unchanged = advancePolicyForUnrelatedWifiQos()
		val locationMask = 1L shl (SourceKind.LOCATION.stableCode - 1)
		val trigger = automaticTrigger(sourcePolicyRevision = unchanged.policyRevision).copy(
			requestedCaptureSourceMask = locationMask,
			intendedCaptureSourceMask = locationMask,
		)
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			plan = locationOnlyPlan(unchanged.policyRevision),
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			wallTimeMs = 1_000L,
			elapsedRealtimeNanos = 1_000_000L,
			logicalTrackingId = "location-only-automatic",
			serviceRunId = "location-only-automatic-run",
		)

		val prepared = subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("location-only-automatic-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val bindings = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
		bindings.size shouldBe 2
		val capture = bindings.single { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
		capture.sourceKind shouldBe SourceKind.LOCATION.stableCode
		capture.consentEpoch shouldBe unchanged.locationCaptureEpoch
		val control = bindings.single { it.purpose == SessionManifestPurpose.CONTROL.name }
		control.sourceKind shouldBe SourceKind.ACTIVITY.stableCode
		control.consentEpoch shouldBe unchanged.activityControlEpoch
		database.activityAutomaticStartActionDao().action(trigger.triggerId)?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED
	}

	@Test
	fun `automatic generation 2 Steps persists exact immutable capture and control attribution`() = runTest {
		val binding = installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2)
		val ingress = useDurableStepsIngress()
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)
		val request = startRequest().copy(
			origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			rolloutRevision = rolloutSnapshot.revision,
			controlDependencies = setOf(SourceKind.ACTIVITY),
			automaticTrigger = trigger,
			logicalTrackingId = "automatic-steps-v2",
			serviceRunId = "automatic-steps-v2-run",
		)

		val prepared = subject.prepareAndroidStart(
			request,
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("automatic-steps-v2-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start

		val sessionDao = database.sourceSessionDao()
		val manifest = requireNotNull(
			sessionDao.manifest(prepared.logicalTrackingId, prepared.manifestRevision),
		)
		val manifestSources = sessionDao
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
		val stepsPolicy = requireNotNull(
			database.sourcePolicyDao().policyAtRevision(
				request.plan.sourcePolicyRevision,
				SourceKind.STEPS.stableCode,
			),
		)
		val activityPolicy = requireNotNull(
			database.sourcePolicyDao().policyAtRevision(
				request.plan.sourcePolicyRevision,
				SourceKind.ACTIVITY.stableCode,
			),
		)

		manifest.logicalTrackingId shouldBe prepared.logicalTrackingId
		manifest.manifestRevision shouldBe prepared.manifestRevision
		manifest.serviceRunId shouldBe prepared.serviceRunId
		manifest.sessionMode shouldBe SessionMode.AUTOMATIC.name
		manifest.sourcePolicyRevision shouldBe request.plan.sourcePolicyRevision
		manifest.acquisitionPlanRevision shouldBe request.plan.revision
		manifest.rolloutRevision shouldBe rolloutSnapshot.revision
		manifest.startOrigin shouldBe SessionStartOrigin.AUTOMATIC_BACKGROUND_START.name
		manifest.effectiveBootId shouldBe request.clockDomainId
		manifest.effectiveElapsedRealtimeNanos shouldBe request.elapsedRealtimeNanos
		manifest.effectiveWallTimeMs shouldBe request.wallTimeMs
		manifest.zoneId shouldBe request.zoneId
		manifest.automationEpoch shouldBe trigger.automationEpoch
		manifest.changeReason shouldBe "SESSION_START"
		manifestSources.size shouldBe 2
		SessionManifestIntegrity.verify(manifest, manifestSources) shouldBe true
		manifestSources.filter { source ->
			source.purpose == SessionManifestPurpose.SESSION_CAPTURE.name
		}.let { capture ->
			capture.map(SessionManifestSourceEntity::sourceKind).toSet() shouldBe
				setOf(SourceKind.STEPS.stableCode)
			capture.single().consentEpoch shouldBe stepsPolicy.captureConsentEpoch
			capture.single().persistenceEligible shouldBe true
			capture.single().qosCode shouldBe stepsPolicy.qosCode
			capture.single().outputDestination shouldBe
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
			capture.single().writerOwner shouldBe SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
			capture.single().writerOwnerGeneration shouldBe
				SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			capture.single().writerProjectionId shouldBe binding.projectionId
			capture.single().writerProjectionVersion shouldBe binding.projectionVersion
			capture.single().writerBindingGeneration shouldBe binding.bindingGeneration
		}
		manifestSources.filter { source ->
			source.purpose == SessionManifestPurpose.CONTROL.name
		}.let { control ->
			control.map(SessionManifestSourceEntity::sourceKind).toSet() shouldBe
				setOf(SourceKind.ACTIVITY.stableCode)
			control.single().consentEpoch shouldBe activityPolicy.controlConsentEpoch
			control.single().persistenceEligible shouldBe false
			control.single().qosCode shouldBe activityPolicy.qosCode
			control.single().outputDestination shouldBe null
			control.single().writerOwner shouldBe null
			control.single().writerOwnerGeneration shouldBe null
			control.single().writerProjectionId shouldBe null
			control.single().writerProjectionVersion shouldBe null
			control.single().writerBindingGeneration shouldBe null
		}

		val demands = database.sourceBrokerDao().demandHistory("session:${prepared.logicalTrackingId}")
		demands.map { demand -> demand.sourceKind to demand.purpose }.toSet() shouldBe setOf(
			SourceKind.STEPS.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
			SourceKind.ACTIVITY.stableCode to SourceBrokerPurpose.CONTROL_CONTINUATION,
		)
		demands.all { demand -> demand.status == SourceDemandEntity.STATUS_BLOCKED } shouldBe true
		database.sourceBrokerDao().currentDemands("session:${prepared.logicalTrackingId}") shouldBe
			emptyList()
		demands.single { demand -> demand.sourceKind == SourceKind.ACTIVITY.stableCode }
			.persistenceEligible shouldBe false
		setOf(
			SourceKind.LOCATION,
			SourceKind.PRESSURE,
			SourceKind.WIFI,
			SourceKind.CELL,
		).all { source -> demands.none { demand -> demand.sourceKind == source.stableCode } } shouldBe true
		database.sourceSessionDao().lifecycleActions(prepared.logicalTrackingId)
			.mapNotNull { action -> action.sourceKind }
			.toSet() shouldBe setOf(SourceKind.STEPS.stableCode)

		subject.markAndroidStartEnqueued(prepared.token, 1L, 1_050L) shouldBe true
		val claimed = subject.claimAndroidStart(
			prepared.token,
			1L,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>().start
		claimed.acceptedSources shouldBe setOf(SourceKind.STEPS)
		claimed.automaticTrigger shouldBe trigger
		subject.markPreparedForegroundAccepted(
			prepared.token,
			1L,
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe true

		val activeDemands = database.sourceBrokerDao()
			.currentDemands("session:${prepared.logicalTrackingId}")
		activeDemands.map { demand -> demand.sourceKind to demand.purpose }.toSet() shouldBe setOf(
			SourceKind.STEPS.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
			SourceKind.ACTIVITY.stableCode to SourceBrokerPurpose.CONTROL_CONTINUATION,
		)
		activeDemands.all { demand -> demand.status == SourceDemandEntity.STATUS_ACTIVE } shouldBe true
		activeDemands.single { demand -> demand.sourceKind == SourceKind.ACTIVITY.stableCode }
			.persistenceEligible shouldBe false

		val started = subject.applyPreparedAndroidStart(
			prepared.token,
			1L,
			"boot-1",
			1_300_000L,
			1_300L,
		).shouldBeInstanceOf<SessionStartResult.Started>()
		started.applied.map(AppliedSourcePlan::source).toSet() shouldBe setOf(SourceKind.STEPS)
		runtime.startCount shouldBe 1
		locationRuntime.isActive shouldBe false
		val sink = requireNotNull(runtime.sinkAtStart)
		sink.admit(automaticStepsCandidate(sequence = 1L, delta = 0L)) shouldBe
			SourceAdmissionHandoff.Durable(1L)
		sink.admit(automaticStepsCandidate(sequence = 2L, delta = 5L)) shouldBe
			SourceAdmissionHandoff.Durable(2L)

		StepsSessionFactProjectionLane(database, ingress).drainThrough(2L) shouldBe
			StepsSessionFactDrainResult.Complete(
				lastCompletedOrdinal = 2L,
				factsInserted = 2,
				eventsValidated = 2,
			)
		val positive = requireNotNull(database.stepFactRevisionDao().writerAdmission(
			StepsSessionFactProjectionLane.WRITER_ID,
			StepsSessionFactProjectionLane.WRITER_VERSION,
			2L,
		))
		positive.logicalTrackingId shouldBe prepared.logicalTrackingId
		positive.serviceRunId shouldBe prepared.serviceRunId
		positive.effectiveStepCount shouldBe 5L
	}

	@Test
	fun `generation 1 candidate remains manual only at the lifecycle boundary`() = runTest {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val trigger = automaticTrigger()
		seedAutomaticStartAction(trigger)

		subject.prepareAndroidStart(
			startRequest().copy(
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				rolloutRevision = rolloutSnapshot.revision,
				controlDependencies = setOf(SourceKind.ACTIVITY),
				automaticTrigger = trigger,
				logicalTrackingId = "automatic-steps-v1-rejected",
				serviceRunId = "automatic-steps-v1-rejected-run",
			),
			AndroidStartDeliveryMetadata(
				token = PreparedTrackingStartToken("automatic-steps-v1-rejected-token"),
				commandGeneration = 1L,
				isUserInitiated = false,
				isAmbient = false,
			),
		) shouldBe SessionStartPreparationResult.Rejected("EVENT_CAPTURE_MODE_NOT_REACHABLE")

		database.sourceSessionDao().session("automatic-steps-v1-rejected") shouldBe null
		database.sourceBrokerDao().currentDemands("session:automatic-steps-v1-rejected") shouldBe
			emptyList()
	}

	@Test
	fun `revocation committed during provider start rolls registration back`() = runTest {
		runtime.blockStart = true
		val start = async {
			subject.start(
				startRequest().copy(logicalTrackingId = "logical-session", serviceRunId = "service-run"),
			)
		}
		runtime.startEntered.await()
		val currentSettings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = 1,
			settings = currentSettings.copy(
				stepsEnabled = false,
				sourceCollectionSettings = currentSettings.sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_CONCURRENT_REVOKE",
		)
		// The candidate predates the revocation boundary and remains a durable observation.
		// Provider ownership is still rolled back after start because the current policy changed.
		val durable = requireNotNull(runtime.sinkAtStart).admit(stepsCandidate())
			.shouldBeInstanceOf<SourceAdmissionHandoff.Durable>()
		durable.admissionOrdinal shouldBe 1L
		runtime.releaseStart.complete(Unit)

		start.await().shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.closed shouldBe true
		database.sourceEventWalDao().maximumAdmissionOrdinal() shouldBe null
		val failed = requireNotNull(database.sourceSessionDao().session("logical-session"))
		failed.currentServiceRunId shouldBe null
		database.sourceSessionDao().completenessForServiceRun("logical-session", "service-run")
			.single().registrationGeneration shouldBe 1L
		database.sourceSessionDao().lifecycleIntents(failed.logicalTrackingId).last().desiredState shouldBe
			LifecycleDesiredState.FINALIZED.name
	}

	@Test
	fun `manifest tampering committed during provider start rolls exact runtime claim back`() = runTest {
		runtime.blockStart = true
		val start = async {
			subject.start(
				startRequest().copy(
					logicalTrackingId = "manifest-race-logical",
					serviceRunId = "manifest-race-run",
				),
			)
		}
		runtime.startEntered.await()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = qos_code + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = 1",
			arrayOf("manifest-race-logical"),
		)
		runtime.releaseStart.complete(Unit)

		start.await().shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.closed shouldBe true
		database.sourceSessionDao().session("manifest-race-logical")?.state shouldBe
			SessionLifecycleState.FAILED.name
		database.sourceSessionDao().session("manifest-race-logical")?.currentServiceRunId shouldBe null
		database.sourceSessionDao().serviceRun("manifest-race-run")?.runtimeAcknowledgement shouldBe
			LifecycleActionStatus.TERMINAL_FAILURE.name
	}

	@Test
	fun `stale start compensation cannot close a successor runtime claim`() = runTest {
		runtime.blockStart = true
		val start = async {
			subject.start(
				startRequest().copy(
					logicalTrackingId = "stale-cleanup-logical",
					serviceRunId = "stale-cleanup-run",
				),
			)
		}
		runtime.startEntered.await()
		val currentSettings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = 1,
			settings = currentSettings.copy(
				stepsEnabled = false,
				sourceCollectionSettings = currentSettings.sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_SUCCESSOR_RUNTIME_CLAIM",
		)
		runtime.supersedeRuntimeClaim(
			SourceRuntimeClaim(
				source = SourceKind.STEPS,
				actionId = "successor-action",
				attemptCount = 1,
				leaseGeneration = 2L,
				logicalTrackingId = "foreign-logical",
				serviceRunId = "foreign-run",
			),
		)
		runtime.releaseStart.complete(Unit)

		start.await().shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.closed shouldBe false
		database.sourceSessionDao().completenessForServiceRun(
			"stale-cleanup-logical",
			"stale-cleanup-run",
		) shouldBe emptyList()
	}

	private fun stepsCandidate() = SourceEvidenceCandidate(
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = 1,
		sourceSequence = 1,
		configRevision = 1,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
		clockDomainId = "boot-1",
		observedElapsedRealtimeNanos = 1,
		receivedElapsedRealtimeNanos = 2,
		wallTimeMs = 1,
		wallTimeUncertaintyMs = 0,
		capturedCollectedDataEpoch = 0,
		acquiredAtMs = 1,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = StepCounterWindowPayload("boot-1", 100, 101, 1, 1, 2, 1, 1, false),
	)

	private fun useDurableStepsIngress(): RoomDurableSourceIngress {
		val lifecycleStore = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycleStore.snapshot() } returns
			CollectedDataLifecycleSnapshot(epoch = 0L, retainedFromMs = null)
		val ingress = RoomDurableSourceIngress(
			database = database,
			lifecycleStore = lifecycleStore,
			payloadCodec = DefaultSourcePayloadCodec(),
			executableLaneCatalog = ExecutableSourceLaneCatalog(),
			trackingStartupGateProvider = Provider<TrackingStartupGate> { ReadyTrackingStartupGate },
		)
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime, locationRuntime)),
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, ProjectionDispatcher(database, emptySet())),
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			FakeSourceCallerDemandDispatcher(database, SourceBroker(database)),
			rolloutStore = fixedEventRolloutStore(),
			sourceProductDrainRouter = sourceProductDrainRouter,
		)
		return ingress
	}

	private suspend fun automaticStepsCandidate(
		sequence: Long,
		delta: Long,
	): SourceEvidenceCandidate<StepCounterWindowPayload> {
		require(sequence in 1L..2L)
		require(delta == 0L || sequence == 2L)
		val registration = requireNotNull(
			database.sourceBrokerDao().currentPhysicalRegistration(SourceKind.STEPS.stableCode),
		)
		val authorization = database.sourceBrokerDao().latestAuthorization(
			SourceKind.STEPS.stableCode,
			registration.registrationGeneration,
		).single { row -> row.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		val baselineAt = maxOf(
			authorization.effectiveElapsedRealtimeNanos,
			requireNotNull(registration.acceptedElapsedRealtimeNanos),
		) + 1_000L
		val observedAt = baselineAt + (sequence - 1L) * 1_000L
		val firstCount = 100L
		val lastCount = firstCount + delta
		val boundary = if (sequence == 1L) StepBoundaryKind.BASELINE else StepBoundaryKind.COVERED
		return SourceEvidenceCandidate(
			providerDedupKey = "automatic-steps-$sequence",
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.STEPS,
			sourceInstanceId = SourceInstanceId(registration.sourceInstanceId),
			registrationGeneration = registration.registrationGeneration,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			authorizationRevision = authorization.authorizationRevision,
			registrationPurposeEligibilityMask = authorization.purposeEligibilityMask,
			registrationEligibilityFingerprint = authorization.authorizationFingerprint,
			sourceSequence = sequence,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = registration.clockDomainId,
			observedElapsedRealtimeNanos = observedAt,
			receivedElapsedRealtimeNanos = observedAt + 1L,
			wallTimeMs = 1_300L + sequence,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = registration.collectedDataEpoch,
			acquiredAtMs = 1_300L + sequence,
			quality = SourceQuality(),
			payloadVersion = 3,
			payload = StepCounterWindowPayload(
				bootClockDomainId = registration.clockDomainId,
				firstCumulativeCount = firstCount,
				lastCumulativeCount = lastCount,
				deltaCount = delta,
				windowStartElapsedRealtimeNanos = baselineAt,
				windowEndElapsedRealtimeNanos = observedAt,
				firstProviderSequence = 1L,
				lastProviderSequence = sequence,
				boundaryKind = boundary,
			),
		)
	}

	private suspend fun prepareAndroidStart(
		tokenValue: String,
		commandGeneration: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		request: SessionStartRequest = startRequest(),
	): PreparedSessionStart {
		val token = PreparedTrackingStartToken(tokenValue)
		return subject.prepareAndroidStart(
			request.copy(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			),
			AndroidStartDeliveryMetadata(
				token = token,
				commandGeneration = commandGeneration,
				isUserInitiated = request.origin == SessionStartOrigin.MANUAL_FOREGROUND_START,
				isAmbient = false,
			),
		).shouldBeInstanceOf<SessionStartPreparationResult.Prepared>().start
	}

	private suspend fun activatePreparedAndroidRun(
		tokenValue: String,
		commandGeneration: Long,
		logicalTrackingId: String,
		serviceRunId: String,
	): PreparedSessionStart {
		val prepared = prepareAndroidStart(
			tokenValue = tokenValue,
			commandGeneration = commandGeneration,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		subject.markAndroidStartEnqueued(prepared.token, commandGeneration, 1_050L) shouldBe true
		subject.claimAndroidStart(
			prepared.token,
			commandGeneration,
			"boot-1",
			1_100_000L,
			1_100L,
		).shouldBeInstanceOf<PreparedSessionClaimResult.Claimed>()
		subject.markPreparedForegroundAccepted(
			prepared.token,
			commandGeneration,
			"boot-1",
			1_200_000L,
			1_200L,
		) shouldBe true
		subject.applyPreparedAndroidStart(
			prepared.token,
			commandGeneration,
			"boot-1",
			1_300_000L,
			1_300L,
		).shouldBeInstanceOf<SessionStartResult.Started>()
		return prepared
	}

	private suspend fun expiredPreparedLeaseElapsedNanos(): Long {
		val lease = requireNotNull(
			database.sourceProjectionStateDao().lease("tracking-session-coordinator"),
		)
		val expiredElapsedNanos = lease.expiresElapsedRealtimeNanos + 1L
		leaseClock.setTime(lease.expiresAtMs + 1L, expiredElapsedNanos)
		return expiredElapsedNanos
	}

	private fun incumbentSessionLease() = SourceCoordinatorLeaseEntity(
		leaseName = "tracking-session-coordinator",
		ownerToken = "incumbent",
		acquiredAtMs = 900L,
		expiresAtMs = 2_000L,
		bootId = "boot-1",
		generation = 7L,
		acquiredElapsedRealtimeNanos = 900_000L,
		expiresElapsedRealtimeNanos = 2_000_000L,
	)

	private suspend fun assertPreparedStartTerminalized(
		prepared: PreparedSessionStart,
		failureCode: String,
	) {
		val sessionDao = database.sourceSessionDao()
		val session = requireNotNull(sessionDao.session(prepared.logicalTrackingId))
		val run = requireNotNull(sessionDao.serviceRun(prepared.serviceRunId))
		val exactActions = sessionDao.lifecycleActions(prepared.logicalTrackingId).filter { action ->
			action.serviceRunId == prepared.serviceRunId &&
				action.manifestRevision == prepared.manifestRevision
		}
		val demandHistory = database.sourceBrokerDao()
			.demandHistory("session:${prepared.logicalTrackingId}")
		session.state shouldBe SessionLifecycleState.FAILED.name
		session.failureCode shouldBe failureCode
		run.state shouldBe SessionLifecycleState.FAILED.name
		run.androidDeliveryState shouldBe AndroidStartDeliveryState.TERMINAL_FAILURE.name
		run.runtimeFailureCode shouldBe failureCode
		run.completionReason shouldBe failureCode
		exactActions.isNotEmpty() shouldBe true
		exactActions.all { action ->
			action.status == LifecycleActionStatus.SUPERSEDED.name &&
				action.failureCode == failureCode
		} shouldBe true
		demandHistory.isNotEmpty() shouldBe true
		demandHistory.all { demand -> demand.status == SourceDemandEntity.STATUS_RETIRED } shouldBe true
	}

	private suspend fun seedActiveStepsRegistration() {
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "prepared-steps-instance",
				ownerScope = "shared:steps",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "prepared-steps-physical",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 500L,
				reservedElapsedRealtimeNanos = 500_000L,
				acceptedAtMs = 600L,
				acceptedElapsedRealtimeNanos = 600_000L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
	}

	private suspend fun prepareBlockedTerminalStepsRecovery(
		identity: String,
	): SessionStartResult.Started {
		installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = identity,
				serviceRunId = "$identity-run",
				rolloutRevision = rolloutSnapshot.revision,
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val admissionOrdinal = prepareTerminalStepsCrashEvidence(started)
		assertFailsWith<CancellationException> {
			subject.stop(
				SessionStopRequest(
					"$identity-crash",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
				),
			)
		}
		insertRetiredStepsRegistration()
		replaceRuntime(FakeStepsRuntime(database))
		replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))
		return started
	}

	private suspend fun appendIrrelevantManifestHistory(
		started: SessionStartResult.Started,
		count: Int,
	) {
		val originalManifest = requireNotNull(
			database.sourceSessionDao().manifest(started.logicalTrackingId, 1L),
		)
		val originalBindings = database.sourceSessionDao()
			.manifestSources(started.logicalTrackingId, 1L)
		repeat(count) { index ->
			val revision = index.toLong() + 2L
			val bindings = originalBindings.map { binding ->
				binding.copy(manifestRevision = revision)
			}
			val unsigned = originalManifest.copy(
				manifestRevision = revision,
				effectiveElapsedRealtimeNanos =
					originalManifest.effectiveElapsedRealtimeNanos + revision,
				effectiveWallTimeMs = originalManifest.effectiveWallTimeMs + revision,
				startOrigin = SessionStartOrigin.POLICY_RECONCILIATION.name,
				changeReason = SessionStartOrigin.POLICY_RECONCILIATION.name,
				manifestChecksum = "",
			)
			database.sourceSessionDao().insertManifest(
				unsigned.copy(
					manifestChecksum = SessionManifestIntegrity.compute(unsigned, bindings),
				),
			)
			database.sourceSessionDao().insertManifestSources(bindings)
		}
	}

	private suspend fun insertSyntheticRetirementClaims(
		started: SessionStartResult.Started,
		count: Int,
		includeReceipts: Boolean,
		includeActions: Boolean = true,
	) {
		require(count > 0)
		val dao = database.sourceSessionDao()
		val retirement = dao.runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		val action = requireNotNull(dao.lifecycleAction(retirement.actionId))
		val firstActionRevision = dao.maximumActionRevision(started.logicalTrackingId) + 1L
		val actions = List(count) { offset ->
			val ordinal = offset + 1
			action.copy(
				actionId = stableLifecycleChecksum(
					"synthetic-retirement",
					started.serviceRunId,
					ordinal,
				),
				actionRevision = firstActionRevision + offset,
				status = LifecycleActionStatus.START_ACCEPTED.name,
				attemptCount = retirement.attemptCount,
				sourceInstanceId = "synthetic-steps-${started.serviceRunId}-$ordinal",
				registrationGeneration = 10_000L + ordinal,
			)
		}
		database.withTransaction {
			if (includeActions) dao.insertLifecycleActions(actions)
			if (includeReceipts) {
				actions.forEach { synthetic ->
					dao.insertRunRetirementIntent(
						retirement.copy(
							actionId = synthetic.actionId,
							sourceInstanceId = requireNotNull(synthetic.sourceInstanceId),
							registrationGeneration =
								requireNotNull(synthetic.registrationGeneration),
						),
					)
				}
			}
		}
	}

	private suspend fun prepareTerminalReceiptWithCleanup(
		identity: String,
	): Pair<SessionStartResult.Started, SourceRunRetirementEntity> {
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "$identity-logical",
				serviceRunId = "$identity-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		val admissionOrdinal = insertTerminalStepsWal(started)
		runtime.lastAdmissionOrdinal = admissionOrdinal
		runtime.acknowledgementServiceRunId = "$identity-foreign-run"
		subject.stop(
			SessionStopRequest(
				"$identity-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
				perSourceTimeoutMs = 100L,
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		val receipt = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		persistTerminalStepsEvidence(receipt.toTerminalAcknowledgement())
		insertRetiredStepsRegistration()
		replaceEventCoordinator(completedEventCoordinator(admissionOrdinal))
		return started to receipt
	}

	private suspend fun prepareCleanupOnlyReceiptAfterCoordinatorCrash(
		identity: String,
	): Pair<SessionStartResult.Failed, SourceRunRetirementEntity> {
		runtime.startReturnsRetryableFailure = true
		runtime.cleanupOnlyShutdown = true
		runtime.closeFailure = IllegalStateException("provider still resident")
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "$identity-logical",
				serviceRunId = "$identity-run",
			),
		).shouldBeInstanceOf<SessionStartResult.Failed>()
		runtime.cleanupOnlyReady = true
		runtime.closeFailure = null
		val crashingCoordinator = mockk<TrackingCoordinator>()
		coEvery { crashingCoordinator.drainAvailable(any(), any()) } throws
			CancellationException("simulated crash after cleanup-only completion")
		replaceEventCoordinator(crashingCoordinator)

		shouldThrow<CancellationException> {
			subject.stop(
				SessionStopRequest(
					"$identity-stop",
					"USER_STOP",
					2_000L,
					2_000_000L,
					"boot-1",
					perSourceTimeoutMs = 100L,
				),
			)
		}
		val receipt = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single()
		return started to receipt
	}

	private suspend fun prepareLocationReceiptWithCleanup(
		identity: String,
	): Pair<SessionStartResult.Started, SourceRunRetirementEntity> {
		val locationPlan = LocationPlan(
			revision = 1L,
			backend = LocationBackend.FUSED,
			mode = LocationMode.BALANCED,
			requestedIntervalMs = 2_000L,
			minimumUpdateIntervalMs = 2_000L,
			minimumDisplacementMeters = 10f,
			maximumBatchDelayMs = 10_000L,
			preciseLocationAvailable = true,
		)
		val started = subject.start(
			startRequest().copy(
				logicalTrackingId = "$identity-logical",
				serviceRunId = "$identity-run",
				plan = AcquisitionPlanRevision(
					revision = 1L,
					planId = "$identity-plan",
					createdAtMs = 1_000L,
					plans = mapOf(SourceKind.LOCATION to locationPlan),
					sourcePolicyRevision = 1L,
				),
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		locationRuntime.acknowledgementServiceRunId = "$identity-foreign-run"
		subject.stop(
			SessionStopRequest(
				"$identity-owner",
				"USER_STOP",
				2_000L,
				2_000_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		locationRuntime.acknowledgementServiceRunId = null
		val receipt = database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.LOCATION.stableCode,
		).single()
		return started to receipt
	}

	private fun SourceRunRetirementEntity.toTerminalAcknowledgement(): SourceStopAck =
		SourceStopAck(
			source = SourceKind.STEPS,
			sourceInstanceId = SourceInstanceId(sourceInstanceId),
			registrationGeneration = registrationGeneration,
			appliedRevision = appliedRevision,
			callbackEntryBarrierSequence = requireNotNull(callbackEntryBarrierSequence),
			lastDurablyAdmittedSequence = lastSourceSequence,
			lastAdmissionOrdinal = lastAdmissionOrdinal,
			failedAdmissionCount = requireNotNull(failedAdmissionCount),
			unresolvedSequenceStart = unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = unresolvedSequenceEnd,
			registrationRemovalOutcome = RegistrationRemovalOutcome.valueOf(
				requireNotNull(registrationRemovalOutcome),
			),
			providerFlushOutcome = ProviderFlushOutcome.valueOf(
				requireNotNull(providerFlushOutcome),
			),
			providerCoverage = ProviderCoverage.valueOf(requireNotNull(providerCoverage)),
			appDrainComplete = requireNotNull(appDrainComplete),
			status = SourceStopStatus.valueOf(requireNotNull(stopStatus)),
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)

	private fun SourceRunRetirementEntity.acknowledgementFieldsForTest(): List<Any?> = listOf(
		appliedRevision,
		callbackEntryBarrierSequence,
		lastSourceSequence,
		lastAdmissionOrdinal,
		failedAdmissionCount,
		unresolvedSequenceStart,
		unresolvedSequenceEnd,
		registrationRemovalOutcome,
		providerFlushOutcome,
		providerCoverage,
		appDrainComplete,
		stopStatus,
	)

	private suspend fun assertTerminalStepsRecoveryBlocked(
		started: SessionStartResult.Started,
		ownerToken: String,
	) {
		subject.stop(
			SessionStopRequest(
				ownerToken,
				"USER_STOP",
				2_100L,
				2_100_000L,
				"boot-1",
			),
		).shouldBeInstanceOf<SessionStopResult.CleanupPending>()
		database.sourceSessionDao().runRetirements(
			started.logicalTrackingId,
			started.serviceRunId,
			SourceKind.STEPS.stableCode,
		).single().let { receipt ->
			receipt.state shouldBe
				com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.STATE_REQUESTED
			receipt.appliedRevision shouldBe null
			receipt.callbackEntryBarrierSequence shouldBe null
			receipt.lastSourceSequence shouldBe null
			receipt.lastAdmissionOrdinal shouldBe null
			receipt.failedAdmissionCount shouldBe null
			receipt.unresolvedSequenceStart shouldBe null
			receipt.unresolvedSequenceEnd shouldBe null
			receipt.registrationRemovalOutcome shouldBe null
			receipt.providerFlushOutcome shouldBe null
			receipt.providerCoverage shouldBe null
			receipt.appDrainComplete shouldBe null
			receipt.stopStatus shouldBe null
		}
		database.openHelper.writableDatabase.query(
			"SELECT operation FROM steps_count_domain_owner_revision " +
				"WHERE owner_kind = 'SESSION_COMPLETENESS' ORDER BY owner_revision",
		).use { cursor ->
			val operations = buildList {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
			operations shouldBe listOf("BIND")
		}
	}

	private suspend fun prepareTerminalStepsCrashEvidence(
		started: SessionStartResult.Started,
	): Long {
		val admissionOrdinal = insertTerminalStepsWal(started)
		runtime.lastAdmissionOrdinal = admissionOrdinal
		runtime.beforePhysicalShutdownCancellation = { acknowledgement ->
			persistTerminalStepsEvidence(acknowledgement)
		}
		runtime.cancelAfterPhysicalShutdown = true
		return admissionOrdinal
	}

	private fun completedEventCoordinator(admissionOrdinal: Long): TrackingCoordinator =
		mockk<TrackingCoordinator>().also { coordinator ->
			coEvery { coordinator.drainAvailable(any(), any()) } returns
				CoordinatorDrainResult.Complete(admissionOrdinal, 1)
		}

	private fun terminalDrainMembership(
		sourceInstanceId: String,
		registrationGeneration: Long,
		lastAdmissionOrdinal: Long,
	) = SourceDrainMembership(
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = null,
		appDrainComplete = true,
		providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER.name,
		stopStatus = SourceStopStatus.COMPLETE.name,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
	)

	private fun terminalDrainClaim(
		sourceInstanceId: String,
		registrationGeneration: Long,
		cleanupOnly: Boolean,
		lifecycleLeaseGeneration: Long = 1L,
	) = SourceDrainRetirementClaim(
		source = SourceKind.STEPS,
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
		actionId = "terminal-drain-$sourceInstanceId-$registrationGeneration",
		attemptCount = 1,
		leaseGeneration = lifecycleLeaseGeneration,
		cleanupOnly = cleanupOnly,
	)

	private suspend fun insertTerminalStepsWal(started: SessionStartResult.Started): Long {
		return insertTerminalStepsWal(
			logicalTrackingId = started.logicalTrackingId,
			serviceRunId = started.serviceRunId,
		)
	}

	private fun allowNullWalPurposeMaskForCorruptionTest() {
		val writableDatabase = database.openHelper.writableDatabase
		val tableSql = writableDatabase.query(
			"SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'source_event_wal'",
		).use { cursor ->
			check(cursor.moveToFirst())
			requireNotNull(cursor.getString(0))
		}
		val nullableTableSql = tableSql.replace(
			"`authorization_purpose_eligibility_mask` INTEGER NOT NULL DEFAULT 0",
			"`authorization_purpose_eligibility_mask` INTEGER DEFAULT 0",
		)
		check(nullableTableSql != tableSql)
		writableDatabase.execSQL("PRAGMA writable_schema = ON")
		try {
			writableDatabase.execSQL(
				"UPDATE sqlite_master SET sql = ? WHERE type = 'table' AND name = 'source_event_wal'",
				arrayOf(nullableTableSql),
			)
		} finally {
			writableDatabase.execSQL("PRAGMA writable_schema = OFF")
		}
		val schemaVersion = writableDatabase.query("PRAGMA schema_version").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getInt(0)
		}
		writableDatabase.execSQL("PRAGMA schema_version = ${Math.addExact(schemaVersion, 1)}")
	}

	private suspend fun insertTerminalStepsWal(
		logicalTrackingId: String,
		serviceRunId: String,
		registrationGeneration: Long = 1L,
		manifestRevision: Long? = 1L,
		sourceInstanceId: String = "steps-instance",
		lifecycleLeaseGeneration: Long = 1L,
		sourceSequence: Long = 4L,
		purposeMask: Long = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		recordStepsFact: Boolean = true,
	): Long {
		if (recordStepsFact) {
			StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.ValidV2
		}
		val token = StepsCounterDomainToken.opaque("sha256:${"a".repeat(64)}")
		val payload = StepCounterWindowPayload(
			bootClockDomainId = "boot-1",
			firstCumulativeCount = 10L,
			lastCumulativeCount = 14L,
			deltaCount = 4L,
			windowStartElapsedRealtimeNanos = 1_000_000_000L,
			windowEndElapsedRealtimeNanos = 2_000_000_000L,
			firstProviderSequence = sourceSequence,
			lastProviderSequence = sourceSequence,
			boundaryKind = StepBoundaryKind.COVERED,
			counterDomainToken = token,
			counterEpochGeneration = 1L,
		)
		val encoded = DefaultSourcePayloadCodec().encode(
			payload,
			StepsCounterDomainToken.COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION,
		)
		val walIdentity =
			"$serviceRunId-$registrationGeneration-$manifestRevision-$sourceSequence"
		val unsigned = SourceEventWalEntity(
			eventId = "terminal-steps-$walIdentity",
			providerDedupKey = "terminal-steps-dedup-$walIdentity",
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceKind = SourceKind.STEPS.stableCode,
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = "terminal-steps",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = purposeMask,
			authorizationFingerprint = "b".repeat(64),
			sourceSequence = sourceSequence,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = "boot-1",
			observedElapsedNanos = 2_000_000_000L,
			receivedElapsedNanos = 2_000_000_000L,
			wallTimeMs = 2_000L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = manifestRevision,
			lifecycleLeaseGeneration = lifecycleLeaseGeneration,
			acquiredAtMs = 2_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = StepsCounterDomainToken.COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			createdAtMs = 2_000L,
		)
		val signed = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		val admissionOrdinal =
			database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(signed)
		if (recordStepsFact) {
			StepsCountDomainStore(database).recordSessionWal(
				signed.copy(admissionOrdinal = admissionOrdinal),
				token,
			) shouldBe StepsCountDomainWriteResult.INSERTED
		}
		return admissionOrdinal
	}

	private suspend fun insertOrphanStepsFact(
		logicalTrackingId: String,
		serviceRunId: String,
	) {
		val unsigned = StepFactRevisionEntity(
			logicalFactId = "orphan-cleanup-fact:$serviceRunId",
			semanticRevision = 1L,
			mutationId = "orphan-cleanup-mutation:$serviceRunId",
			stepIntervalId = null,
			sourceEventId = "orphan-cleanup-event:$serviceRunId",
			sourceAdmissionOrdinal = 9_999L,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = "orphan-cleanup-event:$serviceRunId",
			writerProjectionId = StepsSessionFactProjectionLane.WRITER_ID,
			writerProjectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
			writerBindingGeneration = StepsSessionFactProjectionLane.BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = 1_000L,
			intervalEndTimeMs = 2_000L,
			intervalStartElapsedRealtimeNanos = 1_000_000L,
			intervalEndElapsedRealtimeNanos = 2_000_000L,
			clockDomainId = "boot-1",
			bootClockDomainId = "boot-1",
			cumulativeStepCountStart = 10L,
			cumulativeStepCountEnd = 14L,
			wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = 4L,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			effectChecksum = "unsigned",
			appliedAtMs = 2_000L,
		)
		database.stepFactRevisionDao().insert(
			unsigned.copy(
				effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
			),
		) shouldBe 1L
	}

	private suspend fun persistTerminalStepsEvidence(acknowledgement: SourceStopAck) {
		val completeness = SourceSessionCompletenessEntity(
			logicalTrackingId = requireNotNull(acknowledgement.logicalTrackingId),
			serviceRunId = requireNotNull(acknowledgement.serviceRunId),
			sourceKind = SourceKind.STEPS.stableCode,
			sourceInstanceId = acknowledgement.sourceInstanceId.value,
			registrationGeneration = acknowledgement.registrationGeneration,
			lastAdmissionOrdinal = acknowledgement.lastAdmissionOrdinal,
			lastSourceSequence = acknowledgement.lastDurablyAdmittedSequence,
			appDrainComplete = acknowledgement.appDrainComplete,
			providerCoverage = acknowledgement.providerCoverage.name,
			stopStatus = acknowledgement.status.name,
			unresolvedSequenceStart = acknowledgement.unresolvedSequenceStart,
			unresolvedSequenceEnd = acknowledgement.unresolvedSequenceEndInclusive,
			updatedAtMs = 2_000L,
		)
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = acknowledgement.lastDurablyAdmittedSequence,
				lastAdmissionOrdinal = acknowledgement.lastAdmissionOrdinal,
				failedAdmissionCount = acknowledgement.failedAdmissionCount,
				unresolvedSequenceStart = acknowledgement.unresolvedSequenceStart,
				unresolvedSequenceEndInclusive = acknowledgement.unresolvedSequenceEndInclusive,
				gapClassifications = emptySet(),
			),
			componentStateVersion = 1,
			componentPayload = ByteArray(0),
			causalOrderElapsedRealtimeNanos = 2_000_000L,
		)
		database.withTransaction {
			database.sourceRuntimeStateDao().save(
				SourceRuntimeStateEntity(
					sourceKind = SourceKind.STEPS.stableCode,
					ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
					sourceInstanceId = acknowledgement.sourceInstanceId.value,
					clockDomainId = "boot-1",
					registrationGeneration = acknowledgement.registrationGeneration,
					lastProviderSequence = acknowledgement.callbackEntryBarrierSequence,
					lastAdmittedSourceSequence = acknowledgement.lastDurablyAdmittedSequence,
					lastAdmissionOrdinal = acknowledgement.lastAdmissionOrdinal,
					stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
					payload = encodeSensorRuntimeCheckpoint(checkpoint),
					updatedAtMs = 2_000L,
				),
			)
			database.sourceSessionDao().saveCompleteness(completeness)
			StepsCountDomainStore(database).recordSessionCompleteness(
				completeness,
				StepsCountDomainRetirementEvidence(
					providerFlushOutcome = acknowledgement.providerFlushOutcome.name,
					registrationRemovalOutcome = acknowledgement.registrationRemovalOutcome.name,
				),
			) shouldBe StepsCountDomainWriteResult.INSERTED
		}
	}

	private suspend fun insertRetiredStepsRegistration() {
		if (database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L) != null) {
			return
		}
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "steps-instance",
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "retired-steps",
				collectedDataEpoch = 0L,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "prior-process",
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = 1_000L,
				reservedElapsedRealtimeNanos = 1_000_000L,
				acceptedAtMs = 1_000L,
				acceptedElapsedRealtimeNanos = 1_000_000L,
				retiredAtMs = 2_000L,
				retiredElapsedRealtimeNanos = 2_000_000L,
				failureCode = "PRIOR_PROCESS_ENDED",
			),
		)
	}

	private suspend fun persistStepsRuntimeCheckpoint(
		completeness: SourceSessionCompletenessEntity,
	) {
		val lastProviderSequence = maxOf(4L, completeness.lastSourceSequence ?: 0L)
		database.sourceRuntimeStateDao().save(
			SourceRuntimeStateEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				sourceInstanceId = completeness.sourceInstanceId,
				clockDomainId = "boot-1",
				registrationGeneration = completeness.registrationGeneration,
				lastProviderSequence = lastProviderSequence,
				lastAdmittedSourceSequence = completeness.lastSourceSequence,
				lastAdmissionOrdinal = completeness.lastAdmissionOrdinal,
				stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
				payload = encodeSensorRuntimeCheckpoint(
					SensorRuntimeCheckpoint(
						lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
						metrics = RuntimeAdmissionSnapshot(
							lastDurablyAdmittedSequence = completeness.lastSourceSequence,
							lastAdmissionOrdinal = completeness.lastAdmissionOrdinal,
							failedAdmissionCount = 0L,
							unresolvedSequenceStart = completeness.unresolvedSequenceStart,
							unresolvedSequenceEndInclusive = completeness.unresolvedSequenceEnd,
							gapClassifications = emptySet(),
						),
						componentStateVersion = 1,
						componentPayload = ByteArray(0),
						causalOrderElapsedRealtimeNanos = 2_000_000L,
					),
				),
				updatedAtMs = 2_000L,
			),
		)
	}

	private suspend fun advancePreparedEnvelope(prepared: PreparedSessionStart) {
		val sessionDao = database.sourceSessionDao()
		val oldSession = requireNotNull(sessionDao.session(prepared.logicalTrackingId))
		val oldManifest = requireNotNull(
			sessionDao.manifest(prepared.logicalTrackingId, prepared.manifestRevision),
		)
		val oldIntent = requireNotNull(
			sessionDao.lifecycleIntent(prepared.logicalTrackingId, prepared.intentRevision),
		)
		val oldRun = requireNotNull(sessionDao.serviceRun(prepared.serviceRunId))
		val oldAction = sessionDao.lifecycleActions(prepared.logicalTrackingId)
			.single { action -> action.serviceRunId == prepared.serviceRunId }
		val oldDemands = database.sourceBrokerDao()
			.demandHistory("session:${prepared.logicalTrackingId}")
		val newManifestRevision = prepared.manifestRevision + 1L
		val newIntentRevision = prepared.intentRevision + 1L
		val newServiceRunId = "advanced-new-run"

		sessionDao.insertManifest(
			oldManifest.copy(
				manifestRevision = newManifestRevision,
				serviceRunId = newServiceRunId,
				effectiveElapsedRealtimeNanos = oldManifest.effectiveElapsedRealtimeNanos + 1L,
				effectiveWallTimeMs = oldManifest.effectiveWallTimeMs + 1L,
				changeReason = "TEST_ENVELOPE_ADVANCED",
				manifestChecksum = "test-advanced-manifest-checksum",
			),
		)
		sessionDao.insertManifestSources(
			sessionDao.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
				.map { binding -> binding.copy(manifestRevision = newManifestRevision) },
		)
		sessionDao.insertLifecycleIntent(
			oldIntent.copy(
				intentRevision = newIntentRevision,
				manifestRevision = newManifestRevision,
				requestedElapsedRealtimeNanos = oldIntent.requestedElapsedRealtimeNanos + 1L,
				requestedWallTimeMs = oldIntent.requestedWallTimeMs + 1L,
				intentChecksum = "test-advanced-intent-checksum",
			),
		)
		sessionDao.insertLifecycleActions(
			listOf(
				oldAction.copy(
					actionId = "advanced-new-action",
					serviceRunId = newServiceRunId,
					manifestRevision = newManifestRevision,
					actionRevision = oldAction.actionRevision + 1L,
					requestedAtMs = oldAction.requestedAtMs + 1L,
					requestedElapsedRealtimeNanos = oldAction.requestedElapsedRealtimeNanos + 1L,
				),
			),
		)
		sessionDao.insertServiceRun(
			oldRun.copy(
				serviceRunId = newServiceRunId,
				startedAtMs = oldRun.startedAtMs + 1L,
				startedElapsedNanos = oldRun.startedElapsedNanos + 1L,
				runRevision = 1L,
				startDeliveryToken = "advanced-new-token",
				startCommandGeneration = oldRun.startCommandGeneration + 1L,
				preparedManifestRevision = newManifestRevision,
				preparedIntentRevision = newIntentRevision,
			),
		)
		if (oldDemands.isNotEmpty()) {
			database.sourceBrokerDao().insertDemands(
				oldDemands.mapIndexed { index, demand ->
					demand.copy(
						demandId = "advanced-new-demand-$index",
						serviceRunId = newServiceRunId,
						manifestRevision = newManifestRevision,
						requestedElapsedRealtimeNanos = demand.requestedElapsedRealtimeNanos + 1L,
						requestedAtMs = demand.requestedAtMs + 1L,
					)
				},
			)
		}
		sessionDao.updateSession(
			oldSession.copy(
				lifecycleRevision = oldSession.lifecycleRevision + 1L,
				currentManifestRevision = newManifestRevision,
				currentIntentRevision = newIntentRevision,
				currentServiceRunId = newServiceRunId,
			),
		) shouldBe 1
	}

	private fun startRequest() = SessionStartRequest(
		ownerToken = "test-owner",
		origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
		plan = AcquisitionPlanRevision(
			revision = 1,
			planId = "balanced",
			createdAtMs = 1_000,
			plans = mapOf(SourceKind.STEPS to StepsPlan(1, true, 60_000, 15_000, false)),
			sourcePolicyRevision = 1,
		),
		rolloutRevision = 1,
		clockDomainId = "boot-1",
		foregroundCapabilityFlags = 0,
		wallTimeMs = 1_000,
		elapsedRealtimeNanos = 1_000_000,
		zoneId = "Europe/Prague",
	)

	private fun stepsAndLocationPlan(
		revision: Long,
		stepsEnabled: Boolean,
	) = AcquisitionPlanRevision(
		revision = revision,
		planId = "steps-cleanup-aggregation-$revision",
		createdAtMs = revision * 1_000L,
		plans = mapOf(
			SourceKind.STEPS to StepsPlan(
				revision,
				stepsEnabled,
				60_000L,
				15_000L,
				false,
			),
			SourceKind.LOCATION to LocationPlan(
				revision = revision,
				backend = LocationBackend.FUSED,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 2_000L,
				minimumUpdateIntervalMs = 2_000L,
				minimumDisplacementMeters = 10f,
				maximumBatchDelayMs = 10_000L,
				probeDurationMs = null,
				preciseLocationAvailable = true,
			),
		),
		sourcePolicyRevision = 1L,
	)

	private fun stepsAndLocationReconfigure(
		revision: Long,
		stepsEnabled: Boolean,
	) = SessionReconfigureRequest(
		ownerToken = "steps-cleanup-aggregation-owner",
		plan = stepsAndLocationPlan(revision, stepsEnabled),
		wallTimeMs = revision * 1_000L,
		elapsedRealtimeNanos = revision * 1_000_000L,
		clockDomainId = "boot-1",
		zoneId = "Europe/Prague",
		foregroundCapabilityFlags = 0L,
	)

	private fun pressureStartRequest(
		logicalTrackingId: String,
		serviceRunId: String,
		rolloutRevision: Long = 1L,
	) = startRequest().copy(
		plan = AcquisitionPlanRevision(
			revision = 1L,
			planId = "pressure-only",
			createdAtMs = 1_000L,
			plans = mapOf(
				SourceKind.PRESSURE to PressurePlan(
					revision = 1L,
					enabled = true,
					hardwareSamplePeriodMicros = 200_000,
					maximumReportLatencyMicros = 10_000_000,
					aggregationWindowMs = 10_000L,
				),
			),
			sourcePolicyRevision = 1L,
		),
		rolloutRevision = rolloutRevision,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)

	private fun manualSourceStartRequest(
		source: SourceKind,
		logicalTrackingId: String,
		serviceRunId: String,
		sourcePolicyRevision: Long,
	): SessionStartRequest {
		val sourcePlan: SourcePlan = when (source) {
			SourceKind.ACTIVITY -> ActivityPlan(
				revision = 1L,
				mode = ActivityMode.TRANSITIONS_ONLY,
				desiredDetectionLatencyMs = 30_000L,
				confidenceThresholdPercent = 65,
				transitionTypes = setOf(
					ActivityTransitionType.ENTER.value,
					ActivityTransitionType.EXIT.value,
				),
			)
			SourceKind.WIFI -> WifiPlan(
				revision = 1L,
				mode = WifiMode.BROADCAST_DRIVEN,
				minimumAttemptIntervalMs = 5 * 60_000L,
				maximumAcceptableResultAgeMs = 5 * 60_000L,
				unchangedResultDedupeWindowMs = 10 * 60_000L,
				backoff = RetryBackoff(30_000L, 30 * 60_000L),
			)
			SourceKind.CELL -> CellPlan(
				revision = 1L,
				mode = CellMode.OBSERVE_CHANGES,
				minimumRefreshAttemptIntervalMs = 5 * 60_000L,
				maximumAcceptableCachedAgeMs = 5 * 60_000L,
				subscriptionIds = emptySet(),
				backoff = RetryBackoff(30_000L, 30 * 60_000L),
			)
			else -> error("Unsupported manifest provenance source $source")
		}
		return startRequest().copy(
			plan = AcquisitionPlanRevision(
				revision = 1L,
				planId = "${source.name.lowercase()}-candidate-only",
				createdAtMs = 1_000L,
				plans = mapOf(source to sourcePlan),
				sourcePolicyRevision = sourcePolicyRevision,
			),
			rolloutRevision = rolloutSnapshot.revision,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
	}

	private suspend fun enableRadioPolicy(wifi: Boolean, cell: Boolean): Long {
		val settings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		val updated = policy.replaceCaptureSettings(
			expectedPolicyRevision = 1L,
			settings = settings.copy(
				wifiEnabled = wifi,
				cellEnabled = cell,
				sourceCollectionSettings = settings.sourceCollectionSettings.copy(
					wifi = if (wifi) SourceCollectionFrequency.BALANCED else SourceCollectionFrequency.OFF,
					cell = if (cell) SourceCollectionFrequency.BALANCED else SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_RADIO_MANIFEST_PROVENANCE",
		)
		return updated.revision
	}

	private fun locationOnlyPlan(policyRevision: Long) = AcquisitionPlanRevision(
		revision = 2L,
		planId = "location-only-after-wifi-policy-change",
		createdAtMs = 2_000L,
		plans = mapOf(
			SourceKind.LOCATION to LocationPlan(
				revision = 2L,
				backend = LocationBackend.FUSED,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 2_000L,
				minimumUpdateIntervalMs = 2_000L,
				minimumDisplacementMeters = 10f,
				maximumBatchDelayMs = 10_000L,
				preciseLocationAvailable = true,
			),
		),
		sourcePolicyRevision = policyRevision,
	)

	private suspend fun advancePolicyForUnrelatedWifiQos(): UnchangedConsentEpochs {
		val dao = database.sourcePolicyDao()
		val revisionOneLocation = requireNotNull(
			dao.policyAtRevision(1L, SourceKind.LOCATION.stableCode),
		)
		val revisionOneActivity = requireNotNull(
			dao.policyAtRevision(1L, SourceKind.ACTIVITY.stableCode),
		)
		val settings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		val revisionTwo = policy.replaceCaptureSettings(
			expectedPolicyRevision = 1L,
			settings = settings.copy(
				wifiEnabled = true,
				sourceCollectionSettings = settings.sourceCollectionSettings.copy(
					wifi = SourceCollectionFrequency.BATTERY_SAVER,
				),
			),
			reason = "TEST_UNRELATED_WIFI_QOS",
		)
		val revisionTwoLocation = requireNotNull(
			dao.policyAtRevision(revisionTwo.revision, SourceKind.LOCATION.stableCode),
		)
		val revisionTwoActivity = requireNotNull(
			dao.policyAtRevision(revisionTwo.revision, SourceKind.ACTIVITY.stableCode),
		)
		revisionTwo.revision shouldBe 2L
		revisionTwoLocation.captureConsentEpoch shouldBe revisionOneLocation.captureConsentEpoch
		revisionTwoActivity.controlConsentEpoch shouldBe revisionOneActivity.controlConsentEpoch
		dao.consentEpoch(
			SourceKind.LOCATION.stableCode,
			"SESSION_CAPTURE",
			requireNotNull(revisionTwoLocation.captureConsentEpoch),
		)?.policyRevision shouldBe 1L
		dao.consentEpoch(
			SourceKind.ACTIVITY.stableCode,
			"CONTROL",
			requireNotNull(revisionTwoActivity.controlConsentEpoch),
		)?.policyRevision shouldBe 1L
		dao.latestConsentEpoch(SourceKind.LOCATION.stableCode, "SESSION_CAPTURE")?.epoch shouldBe
			revisionOneLocation.captureConsentEpoch
		dao.latestConsentEpoch(SourceKind.ACTIVITY.stableCode, "CONTROL")?.epoch shouldBe
			revisionOneActivity.controlConsentEpoch
		return UnchangedConsentEpochs(
			policyRevision = revisionTwo.revision,
			locationCaptureEpoch = requireNotNull(revisionOneLocation.captureConsentEpoch),
			activityControlEpoch = requireNotNull(revisionOneActivity.controlConsentEpoch),
		)
	}

	private fun automaticTrigger(
		automationEpoch: Long = 9L,
		sourcePolicyRevision: Long = 1L,
		intendedForegroundServiceTypeMask: Long = 0L,
	): AutomaticTrackingStartTrigger {
		val stepsMask = 1L shl (SourceKind.STEPS.stableCode - 1)
		return AutomaticTrackingStartTrigger(
			triggerId = "activity-transition:boot-1:1",
			kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
			bootId = "boot-1",
			observedElapsedRealtimeNanos = 500_000L,
			receivedElapsedRealtimeNanos = 600_000L,
			expiresElapsedRealtimeNanos = 2_000_000L,
			automationEpoch = automationEpoch,
			startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
			sourcePolicyRevision = sourcePolicyRevision,
			requestedCaptureSourceMask = stepsMask,
			intendedCaptureSourceMask = stepsMask,
			intendedForegroundServiceTypeMask = intendedForegroundServiceTypeMask,
			collectedDataEpoch = 0L,
		)
	}

	private suspend fun seedAutomaticStartAction(trigger: AutomaticTrackingStartTrigger) {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = trigger.automationEpoch,
				automaticControlEnabled = true,
				bootClockDomainId = trigger.bootId,
				effectiveElapsedRealtimeNanos = 0L,
				lastRotationReason = "TEST_SEED",
			),
		)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = trigger.collectedDataEpoch),
		)
		val controlConsentEpoch = requireNotNull(
			database.sourcePolicyDao()
				.policyAtRevision(trigger.sourcePolicyRevision, SourceKind.ACTIVITY.stableCode)
				?.controlConsentEpoch,
		)
		val authorizationFingerprint = "activity-control-authorization"
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-automatic-provider",
				ownerScope = "activity-automatic-control",
				clockDomainId = trigger.bootId,
				physicalConfigurationFingerprint = "activity-automatic-physical",
				collectedDataEpoch = trigger.collectedDataEpoch,
				providerResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 1L,
				reservedElapsedRealtimeNanos = 1L,
				acceptedAtMs = 1L,
				acceptedElapsedRealtimeNanos = 1L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "demand:activity-control",
					authorizationFingerprint = authorizationFingerprint,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
					demandId = "activity-control",
					consumerId = "automatic-control",
					purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					sourcePolicyRevision = trigger.sourcePolicyRevision,
					consentEpoch = controlConsentEpoch,
					persistenceEligible = false,
					effectiveBootId = trigger.bootId,
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 1L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
		database.activityAutomaticStartActionDao().insertIfSlotFree(
			ActivityAutomaticStartActionEntity(
				triggerId = trigger.triggerId,
				effectStableId = "activity-automatic-effect",
				admissionOrdinal = 1L,
				triggerKind = trigger.kind,
				bootId = trigger.bootId,
				observedElapsedRealtimeNanos = trigger.observedElapsedRealtimeNanos,
				receivedElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
				expiresElapsedRealtimeNanos = trigger.expiresElapsedRealtimeNanos,
				automationEpoch = trigger.automationEpoch,
				sourcePolicyRevision = trigger.sourcePolicyRevision,
				controlConsentEpoch = controlConsentEpoch,
				collectedDataEpoch = trigger.collectedDataEpoch,
				requestedCaptureSourceMask = trigger.requestedCaptureSourceMask,
				intendedCaptureSourceMask = trigger.intendedCaptureSourceMask,
				intendedForegroundServiceTypeMask = trigger.intendedForegroundServiceTypeMask,
				registrationGeneration = 1L,
				authorizationRevision = 1L,
				authorizationFingerprint = authorizationFingerprint,
				startOrigin = trigger.startContext.name,
				status = ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED,
				reservedAtMs = 500L,
				startRequestedAtMs = 900L,
				lifecycleIntentAcceptedAtMs = null,
				acceptedLogicalTrackingId = null,
				acceptedIntentRevision = null,
				terminalAtMs = null,
				terminalReason = null,
			),
		) shouldBe 1L
	}

	private fun automaticOutboxPayload(trigger: AutomaticTrackingStartTrigger): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(ActivityAutomationProjection.KIND_TRANSITION)
				output.writeInt(1) // WALKING stable code
				output.writeInt(100)
				output.writeInt(ActivityTransitionType.ENTER.value)
				output.writeUTF(trigger.bootId)
				output.writeLong(trigger.observedElapsedRealtimeNanos)
				output.writeLong(trigger.receivedElapsedRealtimeNanos)
				output.writeLong(1L)
				output.writeLong(1L)
				output.writeUTF("activity-control-authorization")
				output.writeLong(trigger.collectedDataEpoch)
				output.writeLong(trigger.automationEpoch)
			}
			bytes.toByteArray()
		}

	private suspend fun installStepsCandidateRollout(
		binding: ExecutableSourceLaneBinding,
	): ExecutableSourceLaneBinding {
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		))
		check(database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = owner.sourceKind,
			destination = owner.destination,
			expectedOwner = owner.owner,
			expectedOwnerGeneration = owner.ownerGeneration,
			newOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			updatedAtMs = 1L,
		) == 1)
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS),
			controlSources = setOf(SourceKind.ACTIVITY),
			revision = 2L,
			captureModes = mapOf(SourceKind.STEPS to binding.captureModes),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				captureModeMask = binding.captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = rolloutSnapshot.revision,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 1L))
		return binding
	}

	private suspend fun installStepsAndLocationCandidateRollout() {
		val stepsBinding =
			installStepsCandidateRollout(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1)
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS, SourceKind.LOCATION),
			revision = 3L,
			captureModes = mapOf(
				SourceKind.STEPS to stepsBinding.captureModes,
				SourceKind.LOCATION to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 2L))
	}

	private suspend fun installPressureCandidateRollout(): ExecutableSourceLaneBinding {
		val binding = ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		))
		check(database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = owner.sourceKind,
			destination = owner.destination,
			expectedOwner = owner.owner,
			expectedOwnerGeneration = owner.ownerGeneration,
			newOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			updatedAtMs = 1L,
		) == 1)
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.PRESSURE),
			revision = 2L,
			captureModes = mapOf(SourceKind.PRESSURE to binding.captureModes),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.PRESSURE.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				captureModeMask = binding.captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = rolloutSnapshot.revision,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 1L))
		return binding
	}

	private suspend fun installExactCandidateRollout(
		spec: SourceWriterTransitionSpec,
	): ExecutableSourceLaneBinding {
		if (spec.initialOwner == null) {
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = spec.source.stableCode,
					destination = spec.destination,
					owner = spec.candidateOwner,
					ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = 1L,
				),
			)
		} else {
			val initialOwner = requireNotNull(spec.initialOwner)
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = spec.source.stableCode,
					destination = spec.destination,
					owner = initialOwner,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 0L,
				),
			)
			val owner = requireNotNull(
				database.sourceDestinationOwnerDao().get(spec.source.stableCode, spec.destination),
			)
			check(database.sourceDestinationOwnerDao().compareAndSetOwner(
				sourceKind = spec.source.stableCode,
				destination = spec.destination,
				expectedOwner = initialOwner,
				expectedOwnerGeneration = owner.ownerGeneration,
				newOwner = spec.candidateOwner,
				newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				updatedAtMs = 1L,
			) == 1)
		}
		rolloutSnapshot = TrackingRolloutState.eventCanonical(
			sources = setOf(spec.source),
			revision = 2L,
			captureModes = mapOf(spec.source to spec.binding.captureModes),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = spec.source.stableCode,
				bindingGeneration = spec.binding.bindingGeneration,
				projectionId = spec.binding.projectionId,
				projectionVersion = spec.binding.projectionVersion,
				captureModeMask = spec.binding.captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = rolloutSnapshot.revision,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
		database.trackingRolloutStateDao().save(rolloutSnapshot.toEntity(updatedAtMs = 1L))
		return spec.binding
	}

	private suspend fun assertExactCandidateManifestSource(
		prepared: PreparedSessionStart,
		spec: SourceWriterTransitionSpec,
		binding: ExecutableSourceLaneBinding,
	) {
		val source = database.sourceSessionDao()
			.manifestSources(prepared.logicalTrackingId, prepared.manifestRevision)
			.single()
		source.sourceKind shouldBe spec.source.stableCode
		source.purpose shouldBe SessionManifestPurpose.SESSION_CAPTURE.name
		source.outputDestination shouldBe spec.destination
		source.writerOwner shouldBe spec.candidateOwner
		source.writerOwnerGeneration shouldBe SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		source.writerProjectionId shouldBe binding.projectionId
		source.writerProjectionVersion shouldBe binding.projectionVersion
		source.writerBindingGeneration shouldBe binding.bindingGeneration
	}

	private fun replaceEventCoordinator(eventCoordinator: TrackingCoordinator) {
		val ingress = mockk<DurableSourceIngress>(relaxed = true)
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime, locationRuntime)),
			DurableSourceEventSinkFactory(ingress),
			eventCoordinator,
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			FakeSourceCallerDemandDispatcher(database, SourceBroker(database)),
			rolloutStore = fixedEventRolloutStore(),
			sourceProductDrainRouter = sourceProductDrainRouter,
		)
	}

	private fun replaceRuntime(replacement: FakeStepsRuntime) {
		val ingress = mockk<DurableSourceIngress>(relaxed = true)
		runtime = replacement
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime, locationRuntime)),
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, ProjectionDispatcher(database, emptySet())),
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			FakeSourceCallerDemandDispatcher(database, SourceBroker(database)),
			rolloutStore = fixedEventRolloutStore(),
			sourceProductDrainRouter = sourceProductDrainRouter,
		)
	}

	private fun replaceRuntimeRegistry(registry: SourceRuntimeRegistry) {
		val ingress = mockk<DurableSourceIngress>(relaxed = true)
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			registry,
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, ProjectionDispatcher(database, emptySet())),
			ActivityAutomaticStartActionRepository(database, ReadyTrackingStartupGate),
			activityAutomationDrainSignal,
			activityAutomationEpochAuthority,
			BootClockDomainProvider { currentBootId },
			leaseClock,
			FakeSourceCallerDemandDispatcher(database, SourceBroker(database)),
			rolloutStore = fixedEventRolloutStore(),
			sourceProductDrainRouter = sourceProductDrainRouter,
		)
	}

	private fun fixedEventRolloutStore() = object : TrackingRolloutStateStore {
		override suspend fun load() = rolloutSnapshot

		override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) = Unit
	}

	private suspend fun persistRolloutRevision(revision: Long) {
		database.trackingRolloutStateDao().save(
			fixedEventRollout().copy(revision = revision).toEntity(updatedAtMs = revision),
		)
	}

	private fun fixedEventRollout() = TrackingRolloutState(
			revision = 1,
			schemaVersion = TrackingRolloutState.CURRENT_SCHEMA_VERSION,
			coordinatorMode = CoordinatorMode.EVENT,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.EVENT },
			productProjectionStages = SourceKind.entries.associateWith {
				ProductProjectionStage.EVENT_CANONICAL
			},
			captureModeMasks = SourceKind.entries.associateWith {
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask or
					CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE.mask
			},
			semanticSettingsEnabled = false,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)
}

private data class RawActionMutation(
		val column: String,
		val corruptSql: String,
		val originalValue: Any?,
)

private data class UnchangedConsentEpochs(
		val policyRevision: Long,
	val locationCaptureEpoch: Long,
	val activityControlEpoch: Long,
)

private class RecordingSourceProductDrainRouter : SourceProductDrainRouter {
	val requests = mutableListOf<SourceProductDrainRequest>()
	var onDrain: suspend (SourceProductDrainRequest) -> SourceProductDrainResult = { request ->
		SourceProductDrainResult.Complete(
			request,
			request.sourceHighWaterAdmissionOrdinal,
			factsInserted = 0,
			eventsValidated = 0,
		)
	}

	override suspend fun drainThrough(request: SourceProductDrainRequest): SourceProductDrainResult {
		requests += request
		return onDrain(request)
	}
}

private class RecordingActivityAutomationDrainSignal : ActivityAutomationDrainSignal {
	var requestCount: Int = 0
		private set

	override fun requestDrain() {
		requestCount++
	}
}

private class FakeStepsRuntime(private val database: AppDatabase) : ClaimedSourceRuntime<StepsPlan> {
	override val source = SourceKind.STEPS
	override val capabilities = MutableStateFlow(SourceCapabilities(true, true, true, 100, 1))
	var stateObservedAtStart: String? = null
	var manifestRevisionObservedAtStart: Long? = null
	var intentDesiredStateObservedAtStart: String? = null
	var actionStatusObservedAtStart: String? = null
	var closed = false
	var blockStart = false
	var startFailure: Throwable? = null
	var startFailureAfterSideEffect: Throwable? = null
	var startReturnsRetryableFailure = false
	var reconfigureFailure: Throwable? = null
	var sinkAtStart: SourceEventSink? = null
	var startCount = 0
	var reconfigureCount = 0
	var stopStatus = SourceStopStatus.COMPLETE
	var registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED
	var stopProviderFlushOutcome = ProviderFlushOutcome.COMPLETE
	var retainProviderOnIncompleteShutdown = false
	var shutdownDelayMs = 0L
	var cancelAfterPhysicalShutdown = false
	var cancelAfterCleanupOnlyShutdown = false
	var beforePhysicalShutdownCancellation: suspend (SourceStopAck) -> Unit = {}
	var closeFailure: Throwable? = null
	var cleanupOnlyShutdown = false
	var cleanupOnlyReady = false
	var failedStartWithoutProviderEvidence = false
	var abandonProviderlessClaimAfterIncompleteShutdown = false
	var acknowledgementLogicalTrackingId: String? = null
	var acknowledgementServiceRunId: String? = null
	var omitAcknowledgementMembership = false
	var lastAdmissionOrdinal: Long? = null
	val shutdownClaims = mutableListOf<SourceRuntimeClaim>()
	var shutdownAttemptCount = 0
	val startEntered = CompletableDeferred<Unit>()
	val releaseStart = CompletableDeferred<Unit>()
	private var active = false
	val isActive: Boolean get() = active
	private var registrationGeneration = 0L
	private var ownedClaim: SourceRuntimeClaim? = null

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		ownedClaim = claim
		return start(plan, sink)
	}

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult {
		startCount += 1
		startFailure?.let { throw it }
		val session = database.sourceSessionDao().activeSession()
		stateObservedAtStart = session?.state
		manifestRevisionObservedAtStart = session?.currentManifestRevision
		intentDesiredStateObservedAtStart = session?.currentIntentRevision?.let { revision ->
			database.sourceSessionDao().lifecycleIntent(requireNotNull(session).logicalTrackingId, revision)?.desiredState
		}
		actionStatusObservedAtStart = session?.let { current ->
			database.sourceSessionDao().lifecycleActions(current.logicalTrackingId).lastOrNull()?.status
		}
		sinkAtStart = sink
		if (blockStart) {
			startEntered.complete(Unit)
			releaseStart.await()
		}
		if (plan.enabled && !active && !failedStartWithoutProviderEvidence) registrationGeneration += 1L
		active = plan.enabled && !failedStartWithoutProviderEvidence
		startFailureAfterSideEffect?.let { throw it }
		val applied = applied(plan)
		return if (startReturnsRetryableFailure) {
			SourceStartResult.Failed(applied, retryable = true)
		} else {
			SourceStartResult.Started(applied)
		}
	}

	override suspend fun reconfigure(plan: StepsPlan, sink: SourceEventSink): SourceApplyResult {
		reconfigureCount += 1
		reconfigureFailure?.let { throw it }
		val stopAck = if (active && !plan.enabled) stopAck() else null
		if (plan.enabled && !active) registrationGeneration += 1L
		active = plan.enabled
		return SourceApplyResult.Applied(applied(plan), stopAck)
	}

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		ownedClaim = claim
		return reconfigure(plan, sink).also {
			if (!plan.enabled) ownedClaim = null
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck {
		val acknowledgement = stopAck()
		if (isComplete(acknowledgement) || !retainProviderOnIncompleteShutdown) active = false
		return acknowledgement
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		shutdownAttemptCount += 1
		if (ownedClaim != claim) return OwnedSourceShutdown.NotOwned
		if (shutdownDelayMs > 0L) delay(shutdownDelayMs)
		shutdownClaims += claim
		if (abandonProviderlessClaimAfterIncompleteShutdown) {
			abandonProviderlessClaimAfterIncompleteShutdown = false
			active = false
			ownedClaim = null
			return OwnedSourceShutdown.Incomplete(provider = null, stopAck = null)
		}
		if (cleanupOnlyShutdown) {
			if (!cleanupOnlyReady) {
				return OwnedSourceShutdown.Incomplete(provider = null, stopAck = null)
			}
			persistCleanupOnlyProviderRetirement(cutoff)
			active = false
			closed = true
			ownedClaim = null
			if (cancelAfterCleanupOnlyShutdown) {
				throw CancellationException("simulated process death after cleanup-only retirement")
			}
			return OwnedSourceShutdown.Released(provider = null, stopAck = null)
		}
		val acknowledgement = stopAck()
		val complete = isComplete(acknowledgement)
		return if (complete) {
			active = false
			closed = true
			ownedClaim = null
			if (cancelAfterPhysicalShutdown) {
				beforePhysicalShutdownCancellation(acknowledgement)
				throw CancellationException("simulated process death after provider retirement")
			}
			OwnedSourceShutdown.Released(
				provider = null,
				stopAck = acknowledgement,
			)
		} else {
			if (!retainProviderOnIncompleteShutdown) active = false
			OwnedSourceShutdown.Incomplete(
				provider = null,
				stopAck = acknowledgement,
			)
		}
	}

	private suspend fun persistCleanupOnlyProviderRetirement(cutoff: SessionCutoff) {
		val existing = database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			registrationGeneration,
		)
		if (existing == null) {
			database.sourceBrokerDao().insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = SourceKind.STEPS.stableCode,
					registrationGeneration = registrationGeneration,
					sourceInstanceId = "steps-instance",
					ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
					clockDomainId = "boot-1",
					physicalConfigurationFingerprint = "failed-start-cleanup",
					collectedDataEpoch = 0L,
					providerResidency =
						ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
					providerProcessIncarnationId = "test-process",
					status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
					reservedAtMs = 1_000L,
					reservedElapsedRealtimeNanos = 1_000_000L,
					acceptedAtMs = 1_000L,
					acceptedElapsedRealtimeNanos = 1_000_000L,
					retiredAtMs = cutoff.wallTimeMs,
					retiredElapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
					failureCode = "FAILED_START_CLEANUP",
				),
			)
		}
	}

	fun supersedeRuntimeClaim(claim: SourceRuntimeClaim) {
		ownedClaim = claim
	}

	private fun isComplete(acknowledgement: SourceStopAck): Boolean =
		acknowledgement.status == SourceStopStatus.COMPLETE &&
			acknowledgement.registrationRemovalOutcome == RegistrationRemovalOutcome.REMOVED &&
			acknowledgement.providerFlushOutcome !in setOf(
				ProviderFlushOutcome.FAILED,
				ProviderFlushOutcome.TIMED_OUT,
			) && acknowledgement.appDrainComplete

	private fun stopAck() = SourceStopAck(
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = registrationGeneration,
		appliedRevision = 1,
		callbackEntryBarrierSequence = 4,
		lastDurablyAdmittedSequence = 4,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		failedAdmissionCount = 0,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		registrationRemovalOutcome = registrationRemovalOutcome,
		providerFlushOutcome = stopProviderFlushOutcome,
		providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER,
		appDrainComplete = true,
		status = stopStatus,
		logicalTrackingId = if (omitAcknowledgementMembership) {
			null
		} else {
			acknowledgementLogicalTrackingId ?: ownedClaim?.logicalTrackingId
		},
		serviceRunId = if (omitAcknowledgementMembership) {
			null
		} else {
			acknowledgementServiceRunId ?: ownedClaim?.serviceRunId
		},
	)

	override suspend fun close() {
		closeFailure?.let { throw it }
		closed = true
		active = false
		ownedClaim = null
	}

	private fun applied(plan: SourcePlan) = AppliedSourcePlan(
		desiredRevision = plan.revision,
		appliedRevision = plan.revision,
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance")
			.takeIf { plan.enabled && !failedStartWithoutProviderEvidence },
		registrationGeneration = registrationGeneration
			.takeIf { plan.enabled && !failedStartWithoutProviderEvidence },
		appliedAtElapsedRealtimeNanos = 1_000_000,
		status = SourceApplyStatus.APPLIED,
	)
}

private class FakeLocationRuntime : ClaimedSourceRuntime<LocationPlan> {
	override val source = SourceKind.LOCATION
	override val capabilities = MutableStateFlow(SourceCapabilities(true, true, true, 100, 1))
	var failRetirement = false
	var quiesceCount = 0
	var closeCount = 0
	var acknowledgementServiceRunId: String? = null
	private var active = false
	private var ownedClaim: SourceRuntimeClaim? = null
	val isActive: Boolean get() = active

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		ownedClaim = claim
		return start(plan, sink)
	}

	override suspend fun start(plan: LocationPlan, sink: SourceEventSink): SourceStartResult {
		active = plan.enabled
		return SourceStartResult.Started(applied(plan))
	}

	override suspend fun reconfigure(plan: LocationPlan, sink: SourceEventSink): SourceApplyResult {
		if (!plan.enabled && failRetirement) {
			return SourceApplyResult.Failed(
				state = applied(plan).copy(status = SourceApplyStatus.FAILED),
				retryable = true,
				stopAck = stopAck(),
			)
		}
		active = plan.enabled
		return SourceApplyResult.Applied(applied(plan))
	}

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		if (plan.enabled || !failRetirement) ownedClaim = claim
		return reconfigure(plan, sink).also {
			if (!plan.enabled && !failRetirement) ownedClaim = null
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck {
		quiesceCount += 1
		val acknowledgement = stopAck()
		if (!acknowledgement.hasIncompleteTerminalRetirementForTest()) {
			active = false
			ownedClaim = null
		}
		return acknowledgement
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		if (ownedClaim != claim) return OwnedSourceShutdown.NotOwned
		val acknowledgement = quiesce(cutoff)
		return if (acknowledgement.hasIncompleteTerminalRetirementForTest()) {
			OwnedSourceShutdown.Incomplete(provider = null, stopAck = acknowledgement)
		} else {
			OwnedSourceShutdown.Released(provider = null, stopAck = acknowledgement)
		}
	}

	private fun stopAck() = SourceStopAck(
			source = source,
			sourceInstanceId = SourceInstanceId("location-instance"),
			registrationGeneration = 1L,
			appliedRevision = 1L,
			callbackEntryBarrierSequence = 0L, lastDurablyAdmittedSequence = null,
			lastAdmissionOrdinal = null, failedAdmissionCount = 0L,
			unresolvedSequenceStart = null, unresolvedSequenceEndInclusive = null,
			registrationRemovalOutcome = if (failRetirement) {
				RegistrationRemovalOutcome.FAILED
			} else {
				RegistrationRemovalOutcome.REMOVED
			},
			providerFlushOutcome = if (failRetirement) {
				ProviderFlushOutcome.FAILED
			} else {
				ProviderFlushOutcome.COMPLETE
			},
			providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER,
			appDrainComplete = !failRetirement,
			status = if (failRetirement) SourceStopStatus.PROVIDER_FAILED else SourceStopStatus.COMPLETE,
			logicalTrackingId = ownedClaim?.logicalTrackingId,
			serviceRunId = acknowledgementServiceRunId ?: ownedClaim?.serviceRunId,
		)

	override suspend fun close() {
		closeCount += 1
		if (failRetirement) error("location provider still resident")
		active = false
		ownedClaim = null
	}

	private fun applied(plan: LocationPlan) = AppliedSourcePlan(
		plan.revision, plan.revision, source,
		SourceInstanceId("location-instance").takeIf { active }, 1L.takeIf { active },
		1_000_000L, SourceApplyStatus.APPLIED,
	)
}

private fun SourceStopAck.hasIncompleteTerminalRetirementForTest(): Boolean =
	status != SourceStopStatus.COMPLETE ||
		registrationRemovalOutcome == RegistrationRemovalOutcome.FAILED ||
		providerFlushOutcome in setOf(ProviderFlushOutcome.FAILED, ProviderFlushOutcome.TIMED_OUT) ||
		!appDrainComplete
