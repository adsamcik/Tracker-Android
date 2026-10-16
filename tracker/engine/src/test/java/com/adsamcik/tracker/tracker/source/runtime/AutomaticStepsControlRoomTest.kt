package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.registration.*
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.*
import com.adsamcik.tracker.shared.preferences.tracking.*
import com.adsamcik.tracker.tracker.api.*
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.source.catalog.ActivityRecognitionSourceState
import com.adsamcik.tracker.tracker.source.catalog.AndroidActivityRecognitionSourceStateProvider
import com.adsamcik.tracker.tracker.source.coordinator.*
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import com.adsamcik.tracker.tracker.source.projection.ActivityControlRetentionMaintenance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real policy, lease, caller receipt, broker and projection registration; OS provider is a fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticStepsControlRoomTest {
	private lateinit var application: Application
	private lateinit var database: AppDatabase
	private var permitsCollection = true
	private var permissionGranted = true
	private var providerAvailable = true
	private var locked = false

	@Before fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
	}
	@After fun tearDown() = database.close()

	@Test fun `built in owner arms Steps only control during flight then refuses post publication activation`() = roomTest {
		val f = fixture(this)
		f.arbiter.beforeSet = {
			f.store.availability.value.automaticControl.isOperational shouldBe false
			val demands = database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode)
			demands.size shouldBe 1
			demands.single().purpose shouldBe SourceBrokerPurpose.CONTROL_AUTOSTART
			demands.single().persistenceEligible shouldBe false
		}
		f.runtime.reconcileCurrentSettings()
		val observedAvailability = f.store.availability.value.automaticControl
		val ready = observedAvailability.shouldBeInstanceOf<AutomaticTrackingOperationalAvailability.Ready>()
		f.arbiter.setCount shouldBe 1
		val policy = (f.policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		policy[TrackingSource.ACTIVITY].capturePersistenceEligible shouldBe false
		policy[TrackingSource.LOCATION].enabled shouldBe false
		val stale = f.monitor.reconcile(true, true, 5, automaticStartTransitions(2), ready)
		stale.status shouldBe ActivityRegistrationStatus.BLOCKED
		f.arbiter.setCount shouldBe 1
		f.arbiter.clearCount shouldBe 0
	}

	@Test fun `persisted lock restore is awaited and suppression stamped before registration`() = roomTest {
		val f = fixture(this)
		val restore = CompletableDeferred<Unit>()
		coEvery { f.locks.initializeFromPersistence(application) } coAnswers { restore.await(); locked = true }
		f.arbiter.beforeSet = {
			requireNotNull(database.activityAutomationEpochDao().current()).lockSuppressed shouldBe true
		}
		val request = async { f.runtime.reconcileCurrentSettings() }
		yield()
		f.arbiter.setCount shouldBe 0
		restore.complete(Unit)
		request.await()
		f.arbiter.setCount shouldBe 1
	}

	@Test fun `disabled transitions preserve chosen automatic mode and never arm continuous fallback`() = roomTest {
		val f = fixture(this, transitions = false)
		f.runtime.reconcileCurrentSettings()
		f.store.availability.value.automaticControl.shouldBeInstanceOf<AutomaticTrackingOperationalAvailability.Unavailable>()
			.reason shouldBe AutomaticTrackingUnavailableReason.TRANSITION_DETECTION_DISABLED
		f.params.value.autoTrackingMode shouldBe 2
		f.arbiter.setCount shouldBe 0
		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
	}

	@Test fun `missing permission exposes repair and does not activate any provider`() = roomTest {
		val f = fixture(this)
		permissionGranted = false
		f.runtime.reconcileCurrentSettings()
		f.store.availability.value.automaticControl.shouldBeInstanceOf<AutomaticTrackingOperationalAvailability.Unavailable>()
			.reason shouldBe AutomaticTrackingUnavailableReason.PERMISSION_REQUIRED
		f.arbiter.setCount shouldBe 0
		permissionGranted = true
		f.runtime.reconcileCurrentSettings()
		f.store.availability.value.automaticControl.isOperational shouldBe true
		f.arbiter.setCount shouldBe 1
	}

	@Test fun `unavailable provider remains truthful without recording Activity`() = roomTest {
		val f = fixture(this)
		providerAvailable = false
		f.runtime.reconcileCurrentSettings()
		f.store.availability.value.automaticControl.shouldBeInstanceOf<AutomaticTrackingOperationalAvailability.Unavailable>()
			.reason shouldBe AutomaticTrackingUnavailableReason.PROVIDER_UNAVAILABLE
		f.arbiter.setCount shouldBe 0
	}

	@Test fun `failed provider registration removes durable control instead of publishing Ready`() = roomTest {
		val f = fixture(this)
		f.arbiter.rejectRegistration = true
		f.runtime.reconcileCurrentSettings()
		f.store.availability.value.automaticControl.shouldBeInstanceOf<AutomaticTrackingOperationalAvailability.Unavailable>()
			.reason shouldBe AutomaticTrackingUnavailableReason.PROVIDER_UNAVAILABLE
		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
		f.store.availability.value.automaticControl.isOperational shouldBe false
	}

	@Test fun `revocation retires only control and preserves the existing captured Activity sibling`() = roomTest {
		val f = fixture(this)
		f.arbiter.owners += ActivityRegistrationOwner.ACTIVE_SESSION
		f.runtime.reconcileCurrentSettings()
		val old = requireNotNull(f.issuer.currentAutomaticControlLease())
		val disabled = f.params.value.copy(autoTrackingMode = 0)
		val updated = f.policies.replaceCaptureSettings(requireNotNull(f.params.value.sourcePolicyRevision), disabled, "DISABLE_AUTO")
		f.params.value = disabled.copy(sourcePolicyRevision = updated.revision)
		f.runtime.reconcileCurrentSettings()
		f.issuer.currentAutomaticControlLease() shouldBe null
		f.arbiter.owners shouldBe setOf(ActivityRegistrationOwner.ACTIVE_SESSION)
		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
		f.owner.compensate(old) shouldBe true
		f.arbiter.owners shouldBe setOf(ActivityRegistrationOwner.ACTIVE_SESSION)
	}

	@Test fun `Off fences registration and acknowledged cleanup closes the exact previous purpose`() = roomTest {
		val f = fixture(this)
		f.runtime.reconcileCurrentSettings()
		permitsCollection = false
		f.runtime.closeForUserStop().shouldBeInstanceOf<TrackingPurposeSettingsReconciliationResult.Complete>()
		f.runtime.reconcileCurrentSettings()
		f.arbiter.setCount shouldBe 1
		f.store.availability.value.automaticControl.isOperational shouldBe false
		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
	}

	@Test fun `unacknowledged provider removal retains cleanup lease for retry`() = roomTest {
		val f = fixture(this)
		f.runtime.reconcileCurrentSettings()
		val previous = requireNotNull(f.issuer.currentAutomaticControlLease())
		f.arbiter.rejectRemoval = true
		f.runtime.closeForUserStop().shouldBeInstanceOf<TrackingPurposeSettingsReconciliationResult.Debt>()
		f.issuer.currentAutomaticControlLease() shouldBe previous
		f.store.availability.value.automaticControl.isOperational shouldBe false
		f.arbiter.rejectRemoval = false
		f.runtime.closeForUserStop().shouldBeInstanceOf<TrackingPurposeSettingsReconciliationResult.Complete>()
		f.issuer.currentAutomaticControlLease() shouldBe null
	}

	@Test fun `stale compensation cannot remove a newer accepted activation`() = roomTest {
		val f = fixture(this)
		f.runtime.reconcileCurrentSettings()
		val old = requireNotNull(f.issuer.currentAutomaticControlLease())
		f.runtime.reconcileCurrentSettings()
		val successor = requireNotNull(f.issuer.currentAutomaticControlLease())
		(old.identity == successor.identity) shouldBe false
		val before = f.arbiter.clearCount
		f.owner.compensate(old) shouldBe true
		f.arbiter.clearCount shouldBe before
		f.store.availability.value.automaticControl.isOperational shouldBe true
	}

	@Test fun `cold disabled automatic authority cleans up without a fabricated lease and preserves capture`() = roomTest {
		val f = fixture(this, automaticMode = 0)
		f.arbiter.owners += setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR, ActivityRegistrationOwner.ACTIVE_SESSION)
		f.issuer.currentAutomaticControlLease() shouldBe null

		f.runtime.reconcileCurrentSettings()

		f.arbiter.clearCount shouldBe 1
		f.arbiter.setCount shouldBe 0
		f.arbiter.owners shouldBe setOf(ActivityRegistrationOwner.ACTIVE_SESSION)
		f.issuer.currentAutomaticControlLease() shouldBe null
		f.store.availability.value.automaticControl.isOperational shouldBe false
	}

	@Test fun `cold Off waits for acknowledged cleanup even with no process lease`() = roomTest {
		val f = fixture(this)
		f.arbiter.owners += ActivityRegistrationOwner.AUTOMATIC_START_MONITOR
		f.arbiter.rejectRemoval = true
		permitsCollection = false

		f.runtime.closeForUserStop().shouldBeInstanceOf<TrackingPurposeSettingsReconciliationResult.Debt>()
		f.issuer.currentAutomaticControlLease() shouldBe null
		f.arbiter.setCount shouldBe 0
		f.arbiter.rejectRemoval = false
		f.runtime.closeForUserStop().shouldBeInstanceOf<TrackingPurposeSettingsReconciliationResult.Complete>()
		f.arbiter.clearCount shouldBe 2
	}

	@Test fun `timed out cold cleanup must finish before a successor can become ready`() = roomTest {
		val f = fixture(this)
		val remove = CompletableDeferred<Unit>()
		f.arbiter.beforeClear = { remove.await() }
		val closing = async { f.runtime.closeForUserStop() }
		yield()
		delay(2_001L)
		yield()
		closing.await().shouldBeInstanceOf<TrackingPurposeSettingsReconciliationResult.Debt>()
		val successor = async { f.runtime.reconcileCurrentSettings() }
		yield()
		f.arbiter.setCount shouldBe 0
		f.store.availability.value.automaticControl.isOperational shouldBe false

		remove.complete(Unit)
		successor.await()
		f.arbiter.setCount shouldBe 1
		f.store.availability.value.automaticControl.isOperational shouldBe true
	}

	@Test fun `late unowned cleanup cannot retire a newly accepted activation`() = roomTest {
		val f = fixture(this)
		f.runtime.reconcileCurrentSettings()
		val before = f.arbiter.clearCount

		f.owner.retireWhenUnowned() shouldBe false

		f.arbiter.clearCount shouldBe before
		f.arbiter.owners shouldBe setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		f.store.availability.value.automaticControl.isOperational shouldBe true
	}

	// Keep one real Steps-only automatic-control fixture in its policy, rollout, lease, caller, broker and
	// owner wiring order.
	@Suppress("LongMethod")
	private suspend fun fixture(scope: CoroutineScope, transitions: Boolean = true, automaticMode: Int = 2): Fixture {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 3L, updatedAtMs = 1L))
		database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(
			SourceDestinationOwnerEntity.SOURCE_STEPS, SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS, SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION, 1L))
		val binding = ExecutableSourceLaneCatalog.PREFERRED_STEPS_SESSION_FACTS
		val rollout = installCanonicalProductLanesForTest(database, listOf(binding), 7L)
		rollout.save(TrackingRolloutState.eventCanonical(setOf(SourceKind.STEPS), 8L,
			controlSources = setOf(SourceKind.ACTIVITY), captureModes = mapOf(SourceKind.STEPS to binding.captureModes)), 8L)
		val policies = RoomSourcePolicyRepository(database) { SourcePolicyEffectiveTime("boot-1", 1L, 1L) }
		val selected = TrackingParamsState(legacySettingsMigrationCompleted = true,
			locationEnabled = false, activityEnabled = false, stepsEnabled = true, barometerEnabled = false,
			autoTrackingMode = automaticMode, transitionDetectionEnabled = transitions,
			sourceCollectionSettings = SourceCollectionSettings(location = SourceCollectionFrequency.OFF,
				activity = SourceCollectionFrequency.OFF, steps = SourceCollectionFrequency.BALANCED,
				pressure = SourceCollectionFrequency.OFF, wifi = SourceCollectionFrequency.OFF, cell = SourceCollectionFrequency.OFF))
		val policy = policies.bootstrapFromLegacy(selected)
		val params = MutableStateFlow(selected.copy(sourcePolicyRevision = policy.revision))
		val repository = mockk<TrackingParamsRepository> { every { data } returns params }
		val gate = object : TrackingStartupGate {
			override val isReady = true
			override val currentGeneration = 1L
			override val permitsNewCollection: Boolean get() = permitsCollection
			override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
		}
		val lifecycle = mockk<CollectedDataLifecycleStore> {
			coEvery { snapshot() } returns CollectedDataLifecycleSnapshot(3L, null)
		}
		val retention = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns listOf(TrackingSource.STEPS, TrackingSource.WIFI, TrackingSource.CELL).map {
				RetentionAuthorityResult.Unchanged(it, RetentionAuthorityScope.LIVE_AMBIENT, RetentionAuthorityState.ACTIVE, 1L)
			}
			coEvery { currentLiveAmbient(any(), any(), any(), any(), any()) } returns
				CurrentRetentionAuthority.Unavailable(RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE)
			coEvery { reconcileLiveAmbient(any()) } answers {
				RetentionAuthorityResult.Unavailable(firstArg(), RetentionAuthorityScope.LIVE_AMBIENT,
					RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE)
			}
		}
		val exact = CurrentTrackingPurposeAuthorityReader(policies, lifecycle, rollout, retention, Provider { gate })
		val store = AtomicTrackingPurposeAvailabilityStore()
		val issuer = SerializedTrackingPurposeLeaseIssuer(exact, store, TrackingPurposeOwnerCasTokenFactory { java.util.UUID.randomUUID().toString() })
		val registry = TrackingPurposeExecutionRevisionRegistry()
		val publicReader = mockk<CurrentTrackingPurposeAvailabilityReader> {
			every { availability } returns MutableStateFlow(CurrentTrackingPurposeAvailability.SAFE_DEFAULT)
		}
		val clockDomain = BootClockDomainProvider { "boot-1" }
		val authority = CurrentSourceCallerAuthorityReader(database, rollout, publicReader, exact, registry, clockDomain, Provider { gate })
		val receipts = RoomSourceCallerAcceptedAuthorityRepository(database)
		val broker = SourceBroker(database, rollout)
		val dispatcher = GuardedSourceCallerDemandDispatcher(authority, ExactSourceCallerGuard(authority, receipts, Provider { gate }), broker, receipts)
		val arbiter = RecordingControlArbiter()
		val monitor = AutomaticStartTransitionMonitor(arbiter, dispatcher, clockDomain,
			ActivityAutomationProjectionLane(database, mockk(),
				ProjectionDispatcher(database, setOf(ActivityAutomationProjection()))), issuer)
		val locks = mockk<LockManager>(relaxed = true) { every { isLocked } answers { locked } }
		val device = mockk<AndroidActivityRecognitionSourceStateProvider> {
			every { snapshot() } answers { ActivityRecognitionSourceState(permissionGranted, providerAvailable) }
		}
		val epoch = ActivityAutomationEpochAuthority(database, application, locks, FixedClock(1_000L, 100L),
			com.adsamcik.tracker.shared.base.time.BootClockDomainProvider { "boot-1" })
		val owner = DefaultAutomaticControlPurposeOwner(application, repository, device, locks, monitor, epoch,
			ActivityControlRetentionMaintenance(database, clockDomain, gate))
		val runtime = DefaultTrackingPurposePublicationRuntime(issuer, store, registry, retention,
			ownerCallbackScope = scope, automaticControlPurposeOwnerProvider = Provider { owner })
		return Fixture(params, policies, store, issuer, runtime, owner, monitor, arbiter, locks)
	}

	// Native Room work must finish against the same real deadlines as provider callbacks.
	private fun roomTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
		try {
			block()
		} finally {
			coroutineContext.cancelChildren()
		}
	}

	private data class Fixture(val params: MutableStateFlow<TrackingParamsState>, val policies: RoomSourcePolicyRepository,
		val store: AtomicTrackingPurposeAvailabilityStore, val issuer: SerializedTrackingPurposeLeaseIssuer,
		val runtime: DefaultTrackingPurposePublicationRuntime, val owner: DefaultAutomaticControlPurposeOwner,
		val monitor: AutomaticStartTransitionMonitor, val arbiter: RecordingControlArbiter, val locks: LockManager)
}

private class RecordingControlArbiter : ActivityRegistrationArbiter {
	val owners = linkedSetOf<ActivityRegistrationOwner>()
	var setCount = 0
	var clearCount = 0
	var rejectRegistration = false
	var rejectRemoval = false
	var beforeSet: suspend () -> Unit = {}
	var beforeClear: suspend () -> Unit = {}
	override suspend fun setDemand(owner: ActivityRegistrationOwner, demand: ActivityRegistrationDemand): ActivityRegistrationResult {
		setCount++
		beforeSet()
		demand.continuousRecognitionIntervalSeconds shouldBe null
		if (rejectRegistration) {
			return ActivityRegistrationResult(ActivityRegistrationStatus.FAILED, snapshot(), ActivityRegistrationFailureCode.PROVIDER_UNAVAILABLE)
		}
		owners += owner
		return result()
	}
	override suspend fun clearDemand(owner: ActivityRegistrationOwner): ActivityRegistrationResult {
		clearCount++
		beforeClear()
		owners -= owner
		return if (rejectRemoval) {
			ActivityRegistrationResult(ActivityRegistrationStatus.DEGRADED, snapshot(),
			ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED, retryable = true)
		} else {
			result()
		}
	}
	override suspend fun reconcileDurableDemands() = result()
	override suspend fun closeForCollectedDataDeletion() = result()
	override suspend fun resumeAfterCollectedDataDeletion() = result()
	override suspend fun retryPendingProviderCleanup() = ActivityProviderCleanupResult.COMPLETE
	override fun snapshot() = ActivityRegistrationSnapshot(owners.isNotEmpty(),
		ActivityRegistrationIdentity("fixture-activity", 1L, 3L, "boot-1", "fixture-transitions")
			.takeIf { owners.isNotEmpty() }, owners.toSet(), null, emptySet<ActivityTransitionData>())
	private fun result() = ActivityRegistrationResult(ActivityRegistrationStatus.APPLIED, snapshot())
}
