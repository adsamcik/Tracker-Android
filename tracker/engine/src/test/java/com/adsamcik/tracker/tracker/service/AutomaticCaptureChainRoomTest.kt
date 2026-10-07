package com.adsamcik.tracker.tracker.service

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.tracking.*
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.api.*
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.policy.BatteryPressure
import com.adsamcik.tracker.tracker.resilience.*
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.coordinator.*
import com.adsamcik.tracker.tracker.source.ingress.*
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.*
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecovery
import com.adsamcik.tracker.tracker.source.runtime.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * Composed production control/capture chain with real Room and durable command reservation.
 * Android receiver delivery, CONTROL provider registration/Ready publication, sensor hardware,
 * startup readiness and foreground enqueue/promotion are controlled fixtures, not device proof.
 * No Start API, preparation/claim/acceptance, source runtime, writer or saved product is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticCaptureChainRoomTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var commands: SharedPreferencesTrackingLifecycleCommandAuthority
    private lateinit var gate: TrackingStartupGate
    private lateinit var starts: DefaultTrackingStartRequestCoordinator
    private lateinit var sourceSession: TrackerServiceSourceSession
    private lateinit var activityIngress: RoomActivityRecognitionEventIngress
    private lateinit var stepsLane: StepsSessionFactProjectionLane
    private lateinit var effects: ActivityAutomationOutboxDispatcher
    private lateinit var controlRegistration: ActivityRegistrationIdentity
    private lateinit var activeStore: DefaultActiveTrackingSessionStore
    private lateinit var turnOff: DefaultTrackingTurnOffController
    private lateinit var scopeJob: kotlinx.coroutines.Job
    private lateinit var actorScope: CoroutineScope
    private lateinit var sensor: Sensor
    private val listeners = mutableListOf<SensorEventListener>()
    private val removedListeners = mutableListOf<SensorEventListener>()
    private val enqueued = mutableListOf<Intent>()
    private val apiEntryPointField = TrackerServiceApi::class.java.getDeclaredField("cachedEntryPoint")
        .apply { isAccessible = true }
    private var previousApiEntryPoint: Any? = null
    private var previousBackgroundDispatchers: Any? = null
    private lateinit var descriptorFile: File

    @Before
    @Suppress("LongMethod") // One composed fixture; production components retain their own boundaries.
    fun setUp() = runBlocking {
        BackgroundTrackingApi.shutdown()
        val application = ApplicationProvider.getApplicationContext<Application>()
        scopeJob = SupervisorJob()
        actorScope = CoroutineScope(scopeJob + Dispatchers.IO)
        val sensors = mockk<SensorManager>()
        val packageManager = mockk<PackageManager>()
        sensor = mockk<Sensor> {
            every { type } returns Sensor.TYPE_STEP_COUNTER
            every { stringType } returns "android.sensor.step_counter"
            every { vendor } returns "fixture"
            every { name } returns "counter"
            every { version } returns 1
            every { id } returns 7
            every { fifoMaxEventCount } returns 16
            every { minDelay } returns 0
        }
        every { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns sensor
        every { sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns null
        every { sensors.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>()) } answers {
            listeners += firstArg<SensorEventListener>()
            true
        }
        every { sensors.unregisterListener(any<SensorEventListener>()) } answers {
            removedListeners += firstArg<SensorEventListener>()
        }
        every { sensors.flush(any()) } answers {
            (firstArg<SensorEventListener>() as SensorEventListener2).onFlushCompleted(sensor)
            true
        }
        every { packageManager.hasSystemFeature(any()) } answers {
            firstArg<String>() == PackageManager.FEATURE_SENSOR_STEP_COUNTER
        }
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): PackageManager = packageManager
            override fun getSystemService(name: String): Any? =
                if (name == Context.SENSOR_SERVICE) sensors else super.getSystemService(name)
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.ACTIVITY_RECOGNITION) PackageManager.PERMISSION_GRANTED
                else PackageManager.PERMISSION_DENIED
            override fun checkSelfPermission(permission: String): Int = checkPermission(permission, 0, 0)
            override fun startForegroundService(service: Intent): ComponentName {
                enqueued += Intent(service)
                return requireNotNull(service.component)
            }
        }
        mockkObject(Assist)
        every { Assist.isPlayServicesAvailable(any()) } returns true
        context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
        commands = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
            BootClockDomainProvider { BOOT })
        gate = object : TrackingStartupGate {
            override val isReady = true
            override val currentGeneration = 1L
            override val permitsNewCollection: Boolean get() = commands.pendingCollectionResumeExitId == null
            override val currentCommandGeneration: Long get() = commands.latestCommandGeneration()
            override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
        }
        database = AppDatabase.testDatabase(application)
        database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
        val binding = ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2
        database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(
            SourceKind.STEPS.stableCode, SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
            SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
            SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION, System.currentTimeMillis()))
        val rollout = installCanonicalProductLanesForTest(database, listOf(binding), 1L)
        rollout.save(TrackingRolloutState.eventCanonical(setOf(SourceKind.STEPS), 2L,
            controlSources = setOf(SourceKind.ACTIVITY),
            captureModes = mapOf(SourceKind.STEPS to binding.captureModes)), System.currentTimeMillis())
        val policies = RoomSourcePolicyRepository(database) {
            SourcePolicyEffectiveTime(BOOT, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
        }
        val selected = TrackingParamsState(legacySettingsMigrationCompleted = true,
            locationEnabled = false, activityEnabled = false, stepsEnabled = true, barometerEnabled = false,
            autoTrackingMode = GroupedActivity.ON_FOOT.ordinal, transitionDetectionEnabled = true,
            sourceCollectionSettings = SourceCollectionSettings(location = SourceCollectionFrequency.OFF,
                activity = SourceCollectionFrequency.OFF, steps = SourceCollectionFrequency.BALANCED,
                pressure = SourceCollectionFrequency.OFF, wifi = SourceCollectionFrequency.OFF,
                cell = SourceCollectionFrequency.OFF))
        val policy = policies.bootstrapFromLegacy(selected)
        val settings = selected.copy(sourcePolicyRevision = policy.revision)
        val params = mockk<TrackingParamsRepository> { every { data } returns MutableStateFlow(settings) }
        val lifecycleSnapshot = CollectedDataLifecycleSnapshot(0L, null)
        val lifecycle = mockk<CollectedDataLifecycleStore> {
            coEvery { snapshot() } returns lifecycleSnapshot
            every { snapshots } returns MutableStateFlow(lifecycleSnapshot)
        }
        val boot = BootClockDomainProvider { BOOT }
        val exactAuthority = CurrentTrackingPurposeAuthorityReader(policies, lifecycle, rollout,
            mockk<RetentionAuthorityProducer>(), Provider { gate })
        val identity = TrackingPurposeLeaseIdentity(source = TrackingSource.ACTIVITY, purpose = TrackingPurpose.CONTROL,
            policyRevision = policy.revision, consentEpoch = requireNotNull(policy[TrackingSource.ACTIVITY].controlConsentEpoch),
            collectedDataEpoch = 0L, rolloutRevision = 2L, executionRevision = 1L, ownerCasToken = "fixture-control")
        val registry = TrackingPurposeExecutionRevisionRegistry().also {
            it.update(identity.sourcePurpose, 1L)
            it.bind(identity)
        }
        val publication = CurrentTrackingPurposeAvailability(
            TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
                automaticControl = AutomaticTrackingOperationalAvailability.Ready(identity)),
            mapOf(identity.sourcePurpose to TrackingPurposeAuthorityVector(identity.sourcePurpose, policy.revision,
                identity.consentEpoch, 0L, 2L, 1L)))
        val reader = mockk<CurrentTrackingPurposeAvailabilityReader> {
            every { availability } returns MutableStateFlow(publication)
            every { authorityRevision } returns MutableStateFlow(TrackingPurposeAuthorityRevision(policy.revision, 0L, 2L))
            coEvery { isCurrent(any()) } coAnswers {
                val current = exactAuthority.read(identity.sourcePurpose, 1L)
                firstArg<TrackingPurposeLeaseIdentity>() == identity && current != null &&
                    identity.matchesAuthority(TrackingPurposeAuthorityVector(current.sourcePurpose,
                        current.policyRevision, current.consentEpoch, current.collectedDataEpoch,
                        current.rolloutRevision, current.executionRevision, current.retainedFromMs))
            }
        }
        val authority = CurrentSourceCallerAuthorityReader(database, rollout, reader, exactAuthority, registry,
            boot, Provider { gate })
        val receipts = RoomSourceCallerAcceptedAuthorityRepository(database)
        val broker = SourceBroker(database, rollout)
        val callers = GuardedSourceCallerDemandDispatcher(authority,
            ExactSourceCallerGuard(authority, receipts, Provider { gate }), broker, receipts)
        val controlDemand = callers.dispatchAutomaticControl(AutomaticControlDemandDispatchRequest(
            identity, "fixture-control", BOOT, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
            60_000L, 0L)).shouldBeInstanceOf<GuardedPurposeDemandResult.Applied<SourceDemandEntity>>().value
        // The Activity arbiter is in sensor:activity; tracker:engine consumes only its API.
        // Simulate its accepted physical registration, using the real guarded demand above.
        val acceptedAt = SystemClock.elapsedRealtimeNanos()
        val ownerScope = SourceProviderPurposeScope.sharedOwnerScope(SourceKind.ACTIVITY.stableCode)
        database.sourceRegistrationStateDao().insertIfAbsent(SourceRegistrationStateEntity(
            SourceKind.ACTIVITY.stableCode, ownerScope, "fixture-activity", BOOT, 1L, 0L, 1L, 0L,
            System.currentTimeMillis()))
        database.sourceBrokerDao().insertRegistration(ProviderRegistrationGenerationEntity(
            sourceKind = SourceKind.ACTIVITY.stableCode, registrationGeneration = 1L,
            sourceInstanceId = "fixture-activity", ownerScope = ownerScope, clockDomainId = BOOT,
            physicalConfigurationFingerprint = "fixture-transitions", collectedDataEpoch = 0L,
            providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
            providerProcessIncarnationId = null, status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
            reservedAtMs = System.currentTimeMillis(), reservedElapsedRealtimeNanos = acceptedAt,
            acceptedAtMs = System.currentTimeMillis(), acceptedElapsedRealtimeNanos = acceptedAt,
            retiredAtMs = null, retiredElapsedRealtimeNanos = null, failureCode = null))
        database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(SourceKind.ACTIVITY.stableCode,
            1L, 1L, listOf(controlDemand), BOOT, acceptedAt, System.currentTimeMillis()))
        controlRegistration = ActivityRegistrationIdentity("fixture-activity", 1L, 0L, BOOT, "fixture-transitions")
        val locks = mockk<LockManager>(relaxed = true) { every { isLocked } returns false }
        val epoch = ActivityAutomationEpochAuthority(database, context, locks,
            com.adsamcik.tracker.shared.base.time.SystemClock, boot)
        epoch.currentForValidation().automaticControlEnabled shouldBe true
        val ingress = RoomDurableSourceIngress(database, lifecycle, DefaultSourcePayloadCodec(),
            ExecutableSourceLaneCatalog(), Provider { gate })
        stepsLane = StepsSessionFactProjectionLane(database, ingress)
        val registrations = SourceRegistrationRepository(database, lifecycle, boot, ProcessIncarnationIdProvider(), rollout, broker)
        val steps = StepSourceRuntime(context, actorScope, registrations, stepsLane)
        val actions = ActivityAutomaticStartActionRepository(database, gate)
        effects = ActivityAutomationOutboxDispatcher(database, ActivityAutomationEffectValidator(database, boot, actions, epoch),
            BackgroundTrackingActivityAutomationEffectConsumer(context, actions), ActivityControlRetentionMaintenance(database, boot, gate))
        val projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection()))
        val activityLane = ActivityAutomationProjectionLane(database, ingress, projections)
        activityLane.ensureRegisteredAtLiveTail()
        val coordinator = TrackingCoordinator(database, ingress, projections)
        val sessionCoordinator = AuthoritativeSessionCoordinator(database, RoomSourcePlanStore(database, SourcePlanCodec()),
            SourceRuntimeRegistry(setOf(steps)), DurableSourceEventSinkFactory(ingress), coordinator,
            actions, effects, epoch, boot, com.adsamcik.tracker.shared.base.time.SystemClock, callers,
            rolloutStore = rollout, sourceBroker = broker,
            sourceProductDrainRouter = RoomSourceProductDrainRouter(database, stepsLane, mockk(), mockk(), mockk(), mockk(), mockk()))
        descriptorFile = File(context.cacheDir, "automatic-chain-${UUID.randomUUID()}.pb")
        activeStore = DefaultActiveTrackingSessionStore(DataStoreFactory.create(serializer = ActiveTrackingSessionSerializer,
            scope = actorScope, produceFile = { descriptorFile }), TestDispatchersProvider(Dispatchers.IO))
        val telemetry = TrackingCoordinatorTelemetry()
        val factory = SemanticAcquisitionPlanFactory()
        val resolver = SourcePlanResolver()
        sourceSession = TrackerServiceSourceSession(database, sessionCoordinator, factory, resolver, telemetry,
            DefaultTrackingSettingsStatusProvider(factory, resolver, QualitativeBatteryImpactEstimator(), telemetry),
            Provider { gate }, rollout, activeStore)
        turnOff = DefaultTrackingTurnOffController(context, commands, database, boot, mockk())
        val motion = CollectionMotionController()
        val battery = mockk<BatteryAwarePolicy> { every { currentPressure() } returns BatteryPressure.NONE }
        starts = DefaultTrackingStartRequestCoordinator(context, database, gate, params, activeStore,
            sourceSession, sessionCoordinator, rollout, boot, motion, epoch, commands, callers,
            activeTrackingSessionCallerAuthorityReconciler = mockk(), sourcePolicyRepository = policies,
            batteryAwarePolicy = battery)
        previousApiEntryPoint = apiEntryPointField.get(TrackerServiceApi)
        val state = DefaultTrackerServiceController()
        apiEntryPointField.set(TrackerServiceApi, Proxy.newProxyInstance(apiEntryPointField.type.classLoader,
            arrayOf(apiEntryPointField.type)) { _, method, _ -> when (method.name) {
                "trackingLifecycleCommandAuthority" -> commands
                "trackingStartRequestCoordinator" -> starts
                "trackerStateReader" -> state
                else -> error("Unexpected Start API dependency: ${method.name}")
            } })
        val backgroundEntryPoint = mockk<BackgroundTrackingApiEntryPoint> {
            every { currentTrackingPurposeAvailabilityReader() } returns reader
            every { lockManager() } returns locks
            every { trackerStateReader() } returns state
            every { trackingRolloutStateStore() } returns rollout
            every { trackingStartupGate() } returns gate
        }
        setBackgroundField("entryPoint", backgroundEntryPoint)
        setBackgroundField("appContext", context)
        previousBackgroundDispatchers = BackgroundTrackingApi::class.java.getDeclaredField("dispatchers")
            .apply { isAccessible = true }.get(BackgroundTrackingApi)
        setBackgroundField("dispatchers", TestDispatchersProvider(Dispatchers.IO))
        setBackgroundField("cachedParams", settings)
        setBackgroundField("paramsInitialized", true)
        setBackgroundField("activeSourcePolicyRevision", policy.revision)
        setBackgroundField("activityControlConsentEpoch", identity.consentEpoch)
        BackgroundTrackingApi::class.java.getDeclaredMethod("publishActivityAutomationAuthority")
            .apply { isAccessible = true }.invoke(BackgroundTrackingApi)
        val recovery = SourcePipelineRecovery(mockk<LegacyV27ProjectionRecovery>(), coordinator, activityLane, effects)
        activityIngress = RoomActivityRecognitionEventIngress(ActivitySourceDeliveryFactory(), Provider { gate },
            database, epoch, ingress, ingress, recovery, motion, context)
    }

    @After
    fun tearDown() = runBlocking {
        apiEntryPointField.set(TrackerServiceApi, previousApiEntryPoint)
        BackgroundTrackingApi.shutdown()
        scopeJob.cancelAndJoin()
        setBackgroundField("dispatchers", previousBackgroundDispatchers)
        descriptorFile.delete()
        database.close()
        unmockkObject(Assist)
    }

    @Test
    fun `first delivered pre End transition cannot restart even with the current callback generation`() = runBlocking {
        advanceClock(1L)
        val observedBeforeEnd = SystemClock.elapsedRealtimeNanos()
        advanceClock(1L)
        val stop = endCommand()
        commands.markStopHandled(stop) shouldBe true
        // Delivery happened after End. Its generation is current, but the evidence is old.
        val receipt = activityIngress.admit(callback(observedBeforeEnd))
        receipt.isDurable shouldBe true
        receipt.admittedCount shouldBe 1
        commands.latestCommandGeneration() shouldBe stop.generation
        enqueued shouldBe emptyList()
        database.sourceSessionDao().activeSession() shouldBe null
        database.activityAutomaticStartActionDao().current()?.status shouldBe ActivityAutomaticStartActionEntity.STATUS_TERMINAL
        database.activityAutomaticStartActionDao().current()?.terminalReason shouldBe "START_COMMAND_SUPERSEDED"
        database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode).single().persistenceEligible shouldBe false
        database.stepFactRevisionDao().countAll() shouldBe 0L
        assertNoActivityCapture()
    }

    @Test
    fun `post End transition reaches real prepared accepted Steps capture and End retires that owner`() = runBlocking {
        val ended = endCommand()
        commands.markStopHandled(ended) shouldBe true
        val capture = startChosenStepsAfterEnd(ended.generation)
        recordFiveSteps(capture)
        val listener = listeners.single()
        val stop = endCommand()
        sourceSession.stop("EXPLICIT_REQUEST", false,
            SourceSessionStopCutoff(stop.requestedAtEpochMs, requireNotNull(stop.requestedElapsedRealtimeNanos), BOOT)) shouldBe
            SourceSessionStopOutcome.Stopped
        commands.markStopHandled(stop) shouldBe true
        database.sourceSessionDao().session(capture.logicalTrackingId)?.state shouldBe SessionLifecycleState.FINALIZED.name
        removedListeners shouldBe listOf(listener)
        database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode).single().purpose shouldBe
            SourceBrokerPurpose.CONTROL_AUTOSTART
        val before = database.sourceEventWalDao().maximumAdmissionOrdinal()
        listener.onSensorChanged(stepEvent(110f))
        database.sourceEventWalDao().maximumAdmissionOrdinal() shouldBe before
        savedSteps(capture) shouldBe 5L
        assertNoActivityCapture()
    }

    @Test
    fun `Off fences a late transition and preserves the independently saved Steps product`() = runBlocking {
        val ended = endCommand()
        commands.markStopHandled(ended) shouldBe true
        val capture = startChosenStepsAfterEnd(ended.generation)
        recordFiveSteps(capture)
        val off = requireNotNull(turnOff.reserveAndFence(null))
        val stop = off.stop
        sourceSession.stop("USER_OFF", false,
            SourceSessionStopCutoff(stop.requestedAtEpochMs, requireNotNull(stop.requestedElapsedRealtimeNanos), BOOT)) shouldBe
            SourceSessionStopOutcome.Stopped
        commands.markStopHandled(stop) shouldBe true
        database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
        database.sourceBrokerDao().activeDemands(SourceKind.STEPS.stableCode) shouldBe emptyList()
        removedListeners.size shouldBe 1
        val before = database.sourceEventWalDao().maximumAdmissionOrdinal()
        val startCount = enqueued.size
        advanceClock(1L)
        val receipt = activityIngress.admit(callback(SystemClock.elapsedRealtimeNanos()))
        receipt.isDurable shouldBe false
        receipt.failureCode shouldBe "COLLECTION_SUPPRESSED"
        enqueued.size shouldBe startCount
        commands.latestCommandGeneration() shouldBe stop.generation
        commands.pendingCollectionResumeExitId shouldBe off.exitId
        database.sourceEventWalDao().maximumAdmissionOrdinal() shouldBe before
        savedSteps(capture) shouldBe 5L
        assertNoActivityCapture()
    }

    private suspend fun startChosenStepsAfterEnd(endGeneration: Long): SessionStartResult.Started {
        advanceClock(1L)
        val receipt = activityIngress.admit(callback(SystemClock.elapsedRealtimeNanos()))
        receipt.isDurable shouldBe true
        receipt.admittedCount shouldBe 1
        commands.latestCommandGeneration() shouldBe endGeneration + 1L
        val delivered = database.sourceEventWalDao().eventsAfter(0L, 10).single()
        delivered.sourceKind shouldBe SourceKind.ACTIVITY.stableCode
        delivered.authorizationPurposeEligibilityMask shouldBe SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
        effects.drain() shouldBe 0
        enqueued.size shouldBe 1 // Replay can acknowledge, but cannot enqueue another Start.
        val intent = enqueued.single()
        val token = PreparedTrackingStartToken(requireNotNull(intent.getStringExtra(TrackerServiceContract.ARG_PREPARED_START_TOKEN)))
        val command = TrackingStartCommand(intent.getLongExtra(TrackerServiceContract.ARG_LIFECYCLE_COMMAND_GENERATION, -1L))
        val run = requireNotNull(database.sourceSessionDao().serviceRunByDeliveryToken(token.value))
        run.androidDeliveryState shouldBe AndroidStartDeliveryState.ENQUEUED.name
        database.activityAutomaticStartActionDao().current()?.status shouldBe
            ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED
        val staged = database.sourceBrokerDao().demandHistory("session:${run.logicalTrackingId}")
        staged.filter { it.persistenceEligible }.map { it.sourceKind } shouldBe listOf(SourceKind.STEPS.stableCode)
        staged.all { it.status == SourceDemandEntity.STATUS_BLOCKED } shouldBe true
        listeners shouldBe emptyList()
        val claim = starts.claimForService(token, command.generation)
            .shouldBeInstanceOf<TrackingServicePreparedStartClaim.Claimed>()
        claim.claim.acceptedSources shouldBe setOf(SourceKind.STEPS)
        var promotions = 0
        starts.acceptForeground(claim, command) { promotions++; true } shouldBe claim
        promotions shouldBe 1
        val started = withTimeout(5_000L) {
            requireNotNull(starts.apply(claim, command.generation))
        }
            .shouldBeInstanceOf<SessionStartResult.Started>()
        started.applied.map { it.source }.toSet() shouldBe setOf(SourceKind.STEPS)
        listeners.size shouldBe 1
        val active = database.sourceBrokerDao().currentDemands("session:${started.logicalTrackingId}")
        active.map { it.sourceKind to it.purpose }.toSet() shouldBe setOf(
            SourceKind.STEPS.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
            SourceKind.ACTIVITY.stableCode to SourceBrokerPurpose.CONTROL_CONTINUATION)
        active.single { it.sourceKind == SourceKind.ACTIVITY.stableCode }.persistenceEligible shouldBe false
        assertNoActivityCapture()
        return started
    }

    private suspend fun recordFiveSteps(capture: SessionStartResult.Started) {
        val listener = listeners.single()
        listener.onSensorChanged(stepEvent(100f))
        // Delivery batching controls wakeups, not whether a received counter delta is saved.
        listener.onSensorChanged(stepEvent(105f))
        withTimeout(5_000L) {
            while ((database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L) < 3L) delay(5L)
        }
        val highWater = requireNotNull(database.sourceEventWalDao().maximumAdmissionOrdinal())
        stepsLane.drainThrough(highWater).shouldBeInstanceOf<StepsSessionFactDrainResult.Complete>()
        savedSteps(capture) shouldBe 5L
        val fact = requireNotNull(database.stepFactRevisionDao().writerAdmission(
            StepsSessionFactProjectionLane.WRITER_ID, StepsSessionFactProjectionLane.WRITER_VERSION, highWater))
        fact.logicalTrackingId shouldBe capture.logicalTrackingId
        fact.serviceRunId shouldBe capture.serviceRunId
        fact.effectiveStepCount shouldBe 5L
    }

    private suspend fun savedSteps(capture: SessionStartResult.Started) = database.stepFactRevisionDao().effectiveStepCount(
        StepsSessionFactProjectionLane.WRITER_ID, StepsSessionFactProjectionLane.WRITER_VERSION, capture.logicalTrackingId)

    private suspend fun assertNoActivityCapture() {
        val policy = requireNotNull(database.sourcePolicyDao().authority())
        database.sourcePolicyDao().policyAtRevision(policy.currentPolicyRevision, SourceKind.ACTIVITY.stableCode)
            ?.capturePersistenceEligible shouldBe false
        database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode)
            .all { !it.persistenceEligible } shouldBe true
        // CONTROL WAL is expected; the recording product must remain empty.
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM activity_captured_window_revision").use {
            it.moveToFirst() shouldBe true
            it.getLong(0) shouldBe 0L
        }
    }

    private suspend fun endCommand(): TrackingStopCommand {
        advanceClock(1L)
        return requireNotNull(commands.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST,
            System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()))
    }

    private fun callback(observedAt: Long) = ActivityRecognitionEvidenceBatch(
        receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(), receivedWallTimeMs = System.currentTimeMillis(),
        registrationIdentity = controlRegistration, startContext = ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK,
        liveStartCommandGeneration = commands.latestCommandGeneration(), automaticRecognitionEligible = false,
        automaticTransitions = setOf(ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)),
        recognitions = emptyList(), transitions = listOf(ActivityTransitionEvidence(
            DetectedActivityType.WALKING, ActivityTransitionType.ENTER, observedAt)))

    private fun stepEvent(count: Float): SensorEvent {
        advanceClock(1L)
        val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
        return constructor.newInstance(1).also {
            it.sensor = sensor
            it.timestamp = SystemClock.elapsedRealtimeNanos()
            it.values[0] = count
        }
    }

    private fun advanceClock(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))

    private fun setBackgroundField(name: String, value: Any?) {
        BackgroundTrackingApi::class.java.getDeclaredField(name).apply { isAccessible = true }
            .set(BackgroundTrackingApi, value)
    }

    private companion object { const val BOOT = "automatic-chain-boot" }
}
