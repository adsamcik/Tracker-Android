package com.adsamcik.tracker.activity.api.registration

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.activity.receiver.ActivityCallbackFinalizationDisposition
import com.adsamcik.tracker.activity.receiver.ActivityCallbackGapCode
import com.adsamcik.tracker.activity.receiver.ActivityCallbackRetryOwner
import com.adsamcik.tracker.activity.receiver.finalizeActivityCallbackAuthority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DefaultActivityRegistrationArbiterTest {
	private lateinit var database: AppDatabase
	private lateinit var backend: GmsActivityRecognitionBackend
	private lateinit var appScope: CoroutineScope
	private lateinit var subject: DefaultActivityRegistrationArbiter
	private lateinit var startupGate: FakeTrackingStartupGate
	private lateinit var lifecycleStore: FakeLifecycleStore
	private lateinit var application: Application
	private lateinit var cleanupFile: File
	private lateinit var cleanupStore: ActivityRegistrationCleanupStore
	private lateinit var cleanupScheduler: ActivityRegistrationCleanupScheduler
	private lateinit var callbackAdmissionBarrier: ActivityCallbackAdmissionBarrier
	private lateinit var callbackRetryOwner: ActivityCallbackRetryOwner
	private var clockDomainId = "android-boot-count:7"
	private val appliedIdentities = CopyOnWriteArrayList<ActivityRegistrationIdentity>()
	private val appliedConfigs = CopyOnWriteArrayList<RecognitionConfig>()
	private val refreshedIdentities = CopyOnWriteArrayList<ActivityRegistrationIdentity>()
	private val refreshedConfigs = CopyOnWriteArrayList<RecognitionConfig>()
	private val removedIdentities = CopyOnWriteArrayList<ActivityRegistrationIdentity>()
	private val statusesObservedAtProviderCall = CopyOnWriteArrayList<String>()
	private var executableLanePredicate: (SourceProductProjectionLaneEntity) -> Boolean = { false }

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		clockDomainId = "android-boot-count:7"
		executableLanePredicate = { lane ->
			lane.bindingGeneration == 1L &&
				lane.projectionId == "activity-arbiter-test-product-${lane.sourceKind}" &&
				lane.projectionVersion == 1
		}
		database = AppDatabase.testDatabase(application)
		runBlocking { seedAcquisitionAuthority() }
		backend = mockk()
		stubSuccessfulProviderApply()
		coEvery { backend.refreshRegistrationMetadata(any(), any()) } coAnswers {
			refreshedConfigs += arg<RecognitionConfig>(0)
			refreshedIdentities += arg<ActivityRegistrationIdentity>(1)
			true
		}
		coEvery { backend.removeRegistration(any()) } coAnswers {
			removedIdentities += arg<ActivityRegistrationIdentity>(0)
		}
		coEvery { backend.removePendingRegistration(any()) } returns Unit
		io.mockk.every { backend.isAvailable } returns true
		cleanupFile = File(application.cacheDir, "activity-cleanup-arbiter-test")
		cleanupFile.delete()
		File("${cleanupFile.path}.bak").delete()
		File("${cleanupFile.path}.new").delete()
		cleanupStore = ActivityRegistrationCleanupStore(cleanupFile)
		cleanupStore.write(
			ActivityRegistrationCleanupState(
				releasedV27Checked = true,
				pending = emptySet(),
			),
		)
		cleanupScheduler = mockk(relaxed = true)
		callbackAdmissionBarrier = ActivityCallbackAdmissionBarrier()
		callbackRetryOwner = mockk(relaxed = true)
		appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		startupGate = FakeTrackingStartupGate()
		lifecycleStore = FakeLifecycleStore(CollectedDataLifecycleSnapshot(7L, null))
		subject = createSubject(appScope)
	}

	private fun stubSuccessfulProviderApply() {
		coEvery { backend.applyRegistration(any(), any()) } coAnswers {
			appliedConfigs += arg<RecognitionConfig>(0)
			val identity = arg<ActivityRegistrationIdentity>(1)
			appliedIdentities += identity
			statusesObservedAtProviderCall += requireNotNull(
				database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, identity.registrationGeneration),
			).status
			true
		}
	}

	private fun createSubject(scope: CoroutineScope) = DefaultActivityRegistrationArbiter(
		application,
		BootClockDomainProvider { clockDomainId },
		database,
		SourceProductLaneExecutionAuthority { lane -> executableLanePredicate(lane) },
		lifecycleStore,
		backend,
		callbackAdmissionBarrier,
		callbackRetryOwner,
		Provider { startupGate },
		cleanupStore,
		cleanupScheduler,
		scope,
	)

	@After
	fun tearDown() {
		appScope.cancel()
		database.close()
		cleanupFile.delete()
		File("${cleanupFile.path}.bak").delete()
		File("${cleanupFile.path}.new").delete()
	}

	@Test
	fun `provider registration fails closed without durable demand`() = runTest {
		val result = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5, planRevision = 11L),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.failureCode shouldBe ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND
		result.snapshot.active shouldBe false
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `contained rollout rejects stale automatic demand without provider acquisition`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		saveContainedRollout()

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 0L
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `Steps capture rollout admits control-only Activity provider registration`() = runTest {
		saveStepsCaptureActivityControlRollout()
		val control = demand(
			"control",
			"app:auto",
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			null,
			null,
			false,
		)
		database.sourceBrokerDao().insertDemands(listOf(control))

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.APPLIED
		result.snapshot.active shouldBe true
		val identity = requireNotNull(result.snapshot.identity)
		val authorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)
		authorization.purposeEligibilityMask shouldBe SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
		authorization.authorizedMembers.map { it.demandId } shouldBe listOf(control.demandId)
		coVerify(exactly = 1) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `manual-only Steps rollout cannot wake Activity automatic control`() = runTest {
		saveStepsCaptureActivityControlRollout(stepsCaptureModeMask = MANUAL_CAPTURE_MASK)
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `metadata-only product lanes cannot authorize Activity provider acquisition`() = runTest {
		executableLanePredicate = { false }
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `control-only Activity rollout rejects Activity capture demand`() = runTest {
		saveStepsCaptureActivityControlRollout()
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5, planRevision = 11L),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 0L
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `manual session demand cannot reauthorize an automatic-only Activity lane`() = runTest {
		saveActivityCaptureRollout(AUTOMATIC_CAPTURE_MASK)
		database.sourceBrokerDao().insertDemands(
			listOf(demand("manual", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `automatic session demand cannot reauthorize a manual-only Activity lane`() = runTest {
		saveActivityCaptureRollout(MANUAL_CAPTURE_MASK)
		database.sourceBrokerDao().insertDemands(
			listOf(demand("automatic", "session:auto", SourceBrokerPurpose.SESSION_CAPTURE, "auto", 3L, true)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `malformed control owner with an event product stage fails closed`() = runTest {
		database.trackingRolloutStateDao().save(
			eventRollout(
				owners = (1..6).associateWith { sourceKind ->
					when (sourceKind) {
						ACTIVITY_SOURCE_KIND -> "CONTROL"
						STEPS_SOURCE_KIND -> "EVENT"
						else -> "CONTAINED"
					}
				},
				stages = (1..6).associateWith { sourceKind ->
					if (sourceKind == ACTIVITY_SOURCE_KIND || sourceKind == STEPS_SOURCE_KIND) {
						"EVENT_SHADOW"
					} else {
						"LEGACY_CANONICAL"
					}
				},
				revision = 2L,
			),
		)
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 0L
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `unbacked event rollout cannot authorize Activity control or capture`() = runTest {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 0L
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `removing the product lane retires an already active Activity provider`() = runTest {
		val control = demand(
			"control",
			"app:auto",
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			null,
			null,
			false,
		)
		database.sourceBrokerDao().insertDemands(listOf(control))
		val applied = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(applied.snapshot.identity)

		database.sourceProjectionStateDao().deleteAllProductLanes()
		val reconciled = subject.reconcileDurableDemands()

		reconciled.status shouldBe ActivityRegistrationStatus.BLOCKED
		reconciled.snapshot.active shouldBe false
		removedIdentities shouldBe listOf(identity)
	}

	@Test
	fun `contained cold start removes stale rearmable generation without reapplying provider`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val initial = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(initial.snapshot.identity)
		appScope.cancel()
		saveContainedRollout()
		appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		subject = createSubject(appScope)

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		coVerify(exactly = 1) { backend.applyRegistration(any(), any()) }
		removedIdentities shouldBe listOf(identity)
		database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRED
	}

	@Test
	fun `containment between provider reserve and durable accept removes and fails reservation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		coEvery { backend.applyRegistration(any(), any()) } coAnswers {
			val identity = arg<ActivityRegistrationIdentity>(1)
			appliedIdentities += identity
			saveContainedRollout()
			true
		}

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.snapshot.active shouldBe false
		val identity = appliedIdentities.single()
		removedIdentities shouldBe listOf(identity)
		database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_FAILED
		database.sourceRegistrationStateDao().get(ACTIVITY_SOURCE_KIND, "source-broker:2") shouldBe null
	}

	@Test
	fun `enabled demand fails closed before startup recovery without touching storage or provider`() = runTest {
		startupGate.ready = false

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.failureCode shouldBe ActivityRegistrationFailureCode.STARTUP_RECOVERY_NOT_READY
		result.retryable shouldBe true
		result.snapshot.active shouldBe false
		result.snapshot.owners shouldBe setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 0L
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `durable reconciliation stays closed until startup recovery is ready`() = runTest {
		startupGate.ready = false
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		val blocked = subject.reconcileDurableDemands()

		blocked.status shouldBe ActivityRegistrationStatus.BLOCKED
		blocked.failureCode shouldBe ActivityRegistrationFailureCode.STARTUP_RECOVERY_NOT_READY
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }

		startupGate.ready = true
		val applied = subject.reconcileDurableDemands()

		applied.status shouldBe ActivityRegistrationStatus.APPLIED
		applied.snapshot.active shouldBe true
		coVerify(exactly = 1) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `resume after deletion unpauses without registering while startup recovery is closed`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val startedIdentity = requireNotNull(started.snapshot.identity)
		startupGate.ready = false
		subject.closeForCollectedDataDeletion()

		val resumed = subject.resumeAfterCollectedDataDeletion()

		resumed.status shouldBe ActivityRegistrationStatus.BLOCKED
		resumed.failureCode shouldBe ActivityRegistrationFailureCode.STARTUP_RECOVERY_NOT_READY
		resumed.snapshot.active shouldBe false
		removedIdentities shouldBe listOf(startedIdentity)
		coVerify(exactly = 1) { backend.applyRegistration(any(), any()) }
		coVerify(exactly = 1) {
			callbackRetryOwner.fenceAndPurgeForCollectedDataDeletion()
		}
		verify(exactly = 1) { callbackRetryOwner.resumeAfterCollectedDataDeletion() }

		startupGate.ready = true
		val reconciled = subject.reconcileDurableDemands()

		reconciled.status shouldBe ActivityRegistrationStatus.APPLIED
		reconciled.snapshot.active shouldBe true
		coVerify(exactly = 2) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `collected data deletion fails closed when callback retry purge is not durable`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		coEvery { callbackRetryOwner.fenceAndPurgeForCollectedDataDeletion() } throws
			IllegalStateException("retry spool unavailable")

		val closed = subject.closeForCollectedDataDeletion()

		closed.status shouldBe ActivityRegistrationStatus.FAILED
		closed.failureCode shouldBe ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE
		closed.retryable shouldBe true
	}

	@Test
	fun `removing the last demand remains available while startup recovery is closed`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val startedIdentity = requireNotNull(started.snapshot.identity)
		startupGate.ready = false

		val cleared = subject.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)

		cleared.status shouldBe ActivityRegistrationStatus.APPLIED
		cleared.snapshot.active shouldBe false
		removedIdentities shouldBe listOf(startedIdentity)
		coVerify(exactly = 1) { backend.applyRegistration(any(), any()) }
		coVerify(exactly = 1) { backend.removeRegistration(startedIdentity) }
	}

	@Test
	fun `terminal low-storage drop unblocks concurrent disable and provider retirement`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(started.snapshot.identity)
		val callback = checkNotNull(callbackAdmissionBarrier.tryEnter(identity))
		val clearing = async(Dispatchers.Default) {
			subject.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		}
		awaitBarrierFence(identity)

		clearing.isCompleted shouldBe false
		finalizeActivityCallbackAuthority(
			terminallyOwnedOrRejected = false,
			durablyRetryOwned = false,
			gapCode = ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE,
			recordGap = { false },
			completePermit = callback::complete,
		) shouldBe ActivityCallbackFinalizationDisposition.TERMINAL_TELEMETRY_ONLY

		val cleared = clearing.await()
		cleared.status shouldBe ActivityRegistrationStatus.APPLIED
		cleared.snapshot.active shouldBe false
		removedIdentities shouldBe listOf(identity)
	}

	@Test
	fun `terminal low-storage drop unblocks concurrent collected-data deletion`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(started.snapshot.identity)
		val callback = checkNotNull(callbackAdmissionBarrier.tryEnter(identity))
		val deleting = async(Dispatchers.Default) {
			subject.closeForCollectedDataDeletion()
		}
		awaitBarrierFence(identity)

		deleting.isCompleted shouldBe false
		finalizeActivityCallbackAuthority(
			terminallyOwnedOrRejected = false,
			durablyRetryOwned = false,
			gapCode = ActivityCallbackGapCode.RETRY_BYTE_BUDGET_EXHAUSTED,
			recordGap = { false },
			completePermit = callback::complete,
		)

		val deleted = deleting.await()
		deleted.status shouldBe ActivityRegistrationStatus.APPLIED
		deleted.snapshot.active shouldBe false
		coVerify(exactly = 1) { callbackRetryOwner.fenceAndPurgeForCollectedDataDeletion() }
		removedIdentities shouldBe listOf(identity)
	}

	@Test
	fun `reservation snapshots exact authorization before provider is active`() = runTest {
		val demands = listOf(
			demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true),
			demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
		)
		database.sourceBrokerDao().insertDemands(demands)

		val result = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5, planRevision = 11L),
		)

		result.status shouldBe ActivityRegistrationStatus.APPLIED
		val identity = requireNotNull(result.snapshot.identity)
		val authorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)
		authorization.purposeEligibilityMask shouldBe
			(SourceBrokerPurpose.MASK_SESSION_CAPTURE or SourceBrokerPurpose.MASK_CONTROL_AUTOSTART)
		authorization.authorizationFingerprint shouldBe SourceBrokerAuthorization.fingerprint(demands)
		val generation = requireNotNull(
			database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, identity.registrationGeneration),
		)
		generation.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		generation.acceptedAtMs shouldBe generation.reservedAtMs
		generation.acceptedElapsedRealtimeNanos shouldBe generation.reservedElapsedRealtimeNanos
		generation.providerResidency shouldBe
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
		generation.providerProcessIncarnationId shouldBe null
		authorization.authorizedMembers.map { it.demandId } shouldBe listOf("capture", "control")
		appliedIdentities.single() shouldBe identity
		statusesObservedAtProviderCall.single() shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
	}

	@Test
	fun `purpose policy and manifest changes rotate authorization without provider churn`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control-v1", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val firstIdentity = requireNotNull(first.snapshot.identity)
		val firstAuthorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				firstIdentity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)

		database.withTransaction {
			database.sourceBrokerDao().retireConsumer("app:auto", "boot-1", 200L, 200L)
			database.sourceBrokerDao().insertDemands(
				listOf(demand("control-v2", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, true)),
			)
		}
		val second = subject.reconcileDurableDemands()
		val secondIdentity = requireNotNull(second.snapshot.identity)
		val secondAuthorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				secondIdentity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)

		second.status shouldBe ActivityRegistrationStatus.APPLIED
		secondIdentity shouldBe firstIdentity
		secondAuthorization.authorizationRevision shouldBe firstAuthorization.authorizationRevision + 1L
		secondAuthorization.authorizationFingerprint shouldBe SourceBrokerAuthorization.fingerprint(
			listOf(demand("control-v2", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, true)),
		)
		database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			firstIdentity.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		coVerify(exactly = 1) { backend.applyRegistration(any(), firstIdentity) }
		coVerify(exactly = 0) { backend.removeRegistration(any()) }

		val unrelatedRevision = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5, planRevision = 99L),
		)
		requireNotNull(unrelatedRevision.snapshot.identity) shouldBe firstIdentity
		coVerify(exactly = 1) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `fresh arbiter rearms hydrated active identity once then keeps the warm fast path`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val requestedDemand = ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			requestedDemand,
		)
		val identity = requireNotNull(first.snapshot.identity)
		appScope.cancel()
		appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		subject = createSubject(appScope)

		val coldReconcile = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			requestedDemand,
		)
		val warmReconcile = subject.reconcileDurableDemands()

		coldReconcile.status shouldBe ActivityRegistrationStatus.APPLIED
		coldReconcile.snapshot.active shouldBe true
		coldReconcile.snapshot.identity shouldBe identity
		warmReconcile.status shouldBe ActivityRegistrationStatus.APPLIED
		warmReconcile.snapshot.active shouldBe true
		warmReconcile.snapshot.identity shouldBe identity
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 1L
		statusesObservedAtProviderCall shouldBe listOf(
			ProviderRegistrationGenerationEntity.STATUS_RESERVED,
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		)
		coVerify(exactly = 2) { backend.applyRegistration(any(), identity) }
		coVerify(exactly = 0) { backend.removeRegistration(any()) }
	}

	@Test
	fun `eligible control consent rotation closes old authorization then refreshes without provider churn`() = runTest {
		// Drive the two reconciliation boundaries explicitly. The production invalidation collector
		// may otherwise win the first boundary between these assertions, making this test verify a
		// duplicate reconciliation rather than the intended consent-rotation sequence.
		appScope.cancel()
		appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		subject = createSubject(appScope)
		val oldDemand = demand(
			"control-v1",
			"app:auto",
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			null,
			null,
			false,
		)
		database.sourceBrokerDao().insertDemands(listOf(oldDemand))
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(first.snapshot.identity)

		activatePolicyRevision(revision = 6L, activityControlConsentEpoch = 9L)
		val policyCollectorWon = subject.reconcileDurableDemands()
		val denied = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)

		policyCollectorWon.status shouldBe ActivityRegistrationStatus.DEGRADED
		policyCollectorWon.failureCode shouldBe
			ActivityRegistrationFailureCode.AUTHORIZATION_REFRESH_PENDING
		policyCollectorWon.retryable shouldBe true
		policyCollectorWon.snapshot.active shouldBe true
		policyCollectorWon.snapshot.identity shouldBe identity
		denied.isDenied shouldBe true

		database.withTransaction {
			database.sourceBrokerDao().retireConsumer("app:auto", "boot-1", 300L, 300L)
			database.sourceBrokerDao().insertDemands(
				listOf(oldDemand.copy(
					demandId = "control-v2",
					sourcePolicyRevision = 6L,
					consentEpoch = 9L,
					requestedElapsedRealtimeNanos = 300L,
					requestedAtMs = 300L,
				)),
			)
		}
		val refreshed = subject.reconcileDurableDemands()
		val authorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)

		refreshed.status shouldBe ActivityRegistrationStatus.APPLIED
		refreshed.snapshot.identity shouldBe identity
		authorization.authorizedMembers.single().let { member ->
			member.demandId shouldBe "control-v2"
			member.sourcePolicyRevision shouldBe 6L
			member.consentEpoch shouldBe 9L
		}
		coVerify(exactly = 1) { backend.applyRegistration(any(), identity) }
		coVerify(exactly = 0) { backend.removeRegistration(any()) }
	}

	@Test
	fun `failed cold rearm stays inactive and retries the accepted identity`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val requestedDemand = ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			requestedDemand,
		)
		val identity = requireNotNull(first.snapshot.identity)
		appScope.cancel()
		appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		subject = createSubject(appScope)
		coEvery { backend.applyRegistration(any(), identity) } returns false

		val failed = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			requestedDemand,
		)

		failed.status shouldBe ActivityRegistrationStatus.FAILED
		failed.failureCode shouldBe ActivityRegistrationFailureCode.PROVIDER_REGISTRATION_FAILED
		failed.retryable shouldBe true
		failed.snapshot.active shouldBe false
		failed.snapshot.identity shouldBe identity
		database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 1L

		stubSuccessfulProviderApply()
		val retried = subject.reconcileDurableDemands()
		val warmReconcile = subject.reconcileDurableDemands()

		retried.status shouldBe ActivityRegistrationStatus.APPLIED
		retried.snapshot.active shouldBe true
		retried.snapshot.identity shouldBe identity
		warmReconcile.status shouldBe ActivityRegistrationStatus.APPLIED
		warmReconcile.snapshot.active shouldBe true
		coVerify(exactly = 3) { backend.applyRegistration(any(), identity) }
		coVerify(exactly = 0) { backend.removeRegistration(any()) }
	}

	@Test
	fun `automatic owner attribution does not replace an identical provider request`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true),
				demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
			),
		)
		val sessionOnly = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val withAutomaticOwner = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val withoutAutomaticOwner = subject.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)

		val identity = requireNotNull(sessionOnly.snapshot.identity)
		requireNotNull(withAutomaticOwner.snapshot.identity) shouldBe identity
		requireNotNull(withoutAutomaticOwner.snapshot.identity) shouldBe identity
		database.sourceBrokerDao().maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) shouldBe 1L
		appliedConfigs.map(RecognitionConfig::automaticRecognitionEligible) shouldBe
			listOf(false)
		appliedConfigs.map(RecognitionConfig::intervalSeconds) shouldBe listOf(5)
		refreshedConfigs.map(RecognitionConfig::automaticRecognitionEligible) shouldBe
			listOf(true, false)
		refreshedIdentities shouldBe listOf(identity, identity)
		coVerify(exactly = 1) { backend.applyRegistration(any(), identity) }
		coVerify(exactly = 2) { backend.refreshRegistrationMetadata(any(), identity) }
		coVerify(exactly = 0) { backend.removeRegistration(any()) }
	}

	@Test
	fun `capture narrowing waits for entered callback then reopens same control generation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true),
				demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
			),
		)
		val session = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(session.snapshot.identity)
		val captureAuthorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)
		val enteredCallback = checkNotNull(callbackAdmissionBarrier.tryEnter(identity))
		database.sourceBrokerDao().retireConsumer("session:s1", "boot-1", 300L, 300L)

		val narrowing = async(start = CoroutineStart.UNDISPATCHED) {
			subject.clearDemand(ActivityRegistrationOwner.ACTIVE_SESSION)
		}
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				while (true) {
					val probe = callbackAdmissionBarrier.tryEnter(identity) ?: break
					probe.complete()
					delay(1L)
				}
			}
		}

		narrowing.isCompleted shouldBe false
		callbackAdmissionBarrier.tryEnter(identity) shouldBe null
		database.sourceBrokerDao().latestAuthorization(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		).toAuthorizationSnapshotOrNull() shouldBe captureAuthorization

		enteredCallback.complete()
		val controlOnly = narrowing.await()

		controlOnly.status shouldBe ActivityRegistrationStatus.APPLIED
		controlOnly.snapshot.active shouldBe true
		controlOnly.snapshot.identity shouldBe identity
		val narrowedAuthorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)
		narrowedAuthorization.purposeEligibilityMask shouldBe
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
		database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		)?.captureCallbackBarrierAuthorizationRevision shouldBe
			captureAuthorization.authorizationRevision
		checkNotNull(callbackAdmissionBarrier.tryEnter(identity)).complete()
		coVerify(exactly = 0) { backend.removeRegistration(identity) }
	}

	@Test
	fun `capture narrowing revalidates revocation while callback drain is latched`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true),
				demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
			),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(started.snapshot.identity)
		val enteredCallback = checkNotNull(callbackAdmissionBarrier.tryEnter(identity))
		database.sourceBrokerDao().retireConsumer("session:s1", "boot-1", 300L, 300L)

		val narrowing = async(start = CoroutineStart.UNDISPATCHED) {
			subject.clearDemand(ActivityRegistrationOwner.ACTIVE_SESSION)
		}
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				while (true) {
					val probe = callbackAdmissionBarrier.tryEnter(identity) ?: break
					probe.complete()
					delay(1L)
				}
			}
		}
		// This revocation lands after the non-terminal fence but before authorization append.
		database.sourceBrokerDao().retireConsumer("app:auto", "boot-1", 400L, 400L)
		enteredCallback.complete()

		val revoked = narrowing.await()

		revoked.status shouldBe ActivityRegistrationStatus.BLOCKED
		revoked.failureCode shouldBe ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND
		revoked.snapshot.active shouldBe false
		callbackAdmissionBarrier.tryEnter(identity) shouldBe null
		requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		).isDenied shouldBe true
		removedIdentities shouldBe listOf(identity)
	}

	@Test
	fun `non-closing capture expansion revalidates revocation in append transaction`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val controlOnly = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(controlOnly.snapshot.identity)
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true)),
		)
		val snapshotStarted = CompletableDeferred<Unit>()
		val releaseSnapshot = CompletableDeferred<Unit>()
		lifecycleStore.snapshotStarted = snapshotStarted
		lifecycleStore.snapshotRelease = releaseSnapshot

		val expansion = async(start = CoroutineStart.UNDISPATCHED) {
			subject.setDemand(
				ActivityRegistrationOwner.ACTIVE_SESSION,
				ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
			)
		}
		snapshotStarted.await()
		// Initial eligibility already observed the capture demand. Containment wins before append.
		saveContainedRollout()
		releaseSnapshot.complete(Unit)

		val contained = expansion.await()

		contained.status shouldBe ActivityRegistrationStatus.BLOCKED
		contained.failureCode shouldBe ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND
		contained.snapshot.active shouldBe false
		val authorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)
		authorization.isDenied shouldBe true
		authorization.purposeEligibilityMask shouldBe 0L
		removedIdentities shouldBe listOf(identity)
		coVerify(exactly = 1) { backend.applyRegistration(any(), identity) }
	}

	@Test
	fun `cancellation during latched capture drain reauthorizes valid generation then propagates`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true),
				demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
			),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(started.snapshot.identity)
		val enteredCallback = checkNotNull(callbackAdmissionBarrier.tryEnter(identity))
		database.sourceBrokerDao().retireConsumer("session:s1", "boot-1", 300L, 300L)

		val narrowing = async(start = CoroutineStart.UNDISPATCHED) {
			subject.clearDemand(ActivityRegistrationOwner.ACTIVE_SESSION)
		}
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				while (true) {
					val probe = callbackAdmissionBarrier.tryEnter(identity) ?: break
					probe.complete()
					delay(1L)
				}
			}
		}
		narrowing.cancel(CancellationException("cancel capture narrowing"))
		enteredCallback.complete()
		narrowing.join()

		narrowing.isCancelled shouldBe true
		subject.snapshot().active shouldBe true
		subject.snapshot().identity shouldBe identity
		val controlOnly = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
			).toAuthorizationSnapshotOrNull(),
		)
		controlOnly.purposeEligibilityMask shouldBe SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
		checkNotNull(callbackAdmissionBarrier.tryEnter(identity)).complete()
		coVerify(exactly = 0) { backend.removeRegistration(identity) }
	}

	@Test
	fun `callback metadata failure keeps authorization and provider identity unchanged`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 3L, true),
				demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
			),
		)
		val sessionOnly = subject.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(sessionOnly.snapshot.identity)
		val authorizationBefore = database.sourceBrokerDao().latestAuthorization(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		).toAuthorizationSnapshotOrNull()
		coEvery { backend.refreshRegistrationMetadata(any(), identity) } returns false

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.DEGRADED
		result.failureCode shouldBe ActivityRegistrationFailureCode.CALLBACK_METADATA_UPDATE_FAILED
		result.retryable shouldBe true
		result.snapshot.identity shouldBe identity
		database.sourceBrokerDao().latestAuthorization(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		).toAuthorizationSnapshotOrNull() shouldBe authorizationBefore
		coVerify(exactly = 1) { backend.applyRegistration(any(), identity) }
		coVerify(exactly = 1) { backend.refreshRegistrationMetadata(any(), identity) }
		coVerify(exactly = 0) { backend.removeRegistration(any()) }
	}

	@Test
	fun `clock domain change retires persisted generation and applies a fresh identity`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val firstIdentity = requireNotNull(first.snapshot.identity)
		clockDomainId = "android-boot-count:8"

		val second = subject.reconcileDurableDemands()

		val secondIdentity = requireNotNull(second.snapshot.identity)
		second.status shouldBe ActivityRegistrationStatus.APPLIED
		secondIdentity.registrationGeneration shouldBe firstIdentity.registrationGeneration + 1L
		secondIdentity.clockDomainId shouldBe "android-boot-count:8"
		removedIdentities shouldBe listOf(firstIdentity)
		appliedIdentities shouldBe listOf(firstIdentity, secondIdentity)
	}

	@Test
	fun `registration copies opaque canonical clock domain without reformatting`() = runTest {
		clockDomainId = "process:canonical-test"
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.APPLIED
		requireNotNull(result.snapshot.identity).clockDomainId shouldBe "process:canonical-test"
	}

	@Test
	fun `collected data epoch change cannot reuse an active registration`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val firstIdentity = requireNotNull(first.snapshot.identity)
		lifecycleStore.beginFullDeletion(100L)

		val second = subject.reconcileDurableDemands()

		val secondIdentity = requireNotNull(second.snapshot.identity)
		secondIdentity.registrationGeneration shouldBe firstIdentity.registrationGeneration + 1L
		secondIdentity.collectedDataEpoch shouldBe 8L
		removedIdentities shouldBe listOf(firstIdentity)
	}

	@Test
	fun `failed replacement preserves the accepted provider and pointer`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val firstIdentity = requireNotNull(first.snapshot.identity)
		val statusDuringFailedApply = mutableListOf<String>()
		coEvery { backend.applyRegistration(any(), any()) } coAnswers {
			val pending = arg<ActivityRegistrationIdentity>(1)
			statusDuringFailedApply += requireNotNull(
				database.sourceBrokerDao().registration(
					ACTIVITY_SOURCE_KIND,
					pending.registrationGeneration,
				),
			).status
			false
		}

		val replacement = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
		)

		replacement.status shouldBe ActivityRegistrationStatus.DEGRADED
		replacement.snapshot.active shouldBe true
		replacement.snapshot.identity shouldBe firstIdentity
		removedIdentities shouldBe emptyList()
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_FAILED
		statusDuringFailedApply shouldBe listOf(ProviderRegistrationGenerationEntity.STATUS_RESERVED)
		database.sourceRegistrationStateDao().get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE)
			?.registrationGeneration shouldBe 1L
	}

	@Test
	fun `successful replacement accepts new provider before retiring and removing old`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val firstIdentity = requireNotNull(first.snapshot.identity)

		val replacement = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
		)

		replacement.status shouldBe ActivityRegistrationStatus.APPLIED
		val secondIdentity = requireNotNull(replacement.snapshot.identity)
		secondIdentity.registrationGeneration shouldBe 2L
		statusesObservedAtProviderCall shouldBe listOf(
			ProviderRegistrationGenerationEntity.STATUS_RESERVED,
			ProviderRegistrationGenerationEntity.STATUS_RESERVED,
		)
		removedIdentities shouldBe listOf(firstIdentity)
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRED
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceRegistrationStateDao().get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE)
			?.registrationGeneration shouldBe 2L
	}

	@Test
	fun `failed old-provider cleanup remains durable and retries without replacing new provider`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val firstIdentity = requireNotNull(first.snapshot.identity)
		var removalAttempts = 0
		coEvery { backend.removeRegistration(any()) } coAnswers {
			removedIdentities += arg<ActivityRegistrationIdentity>(0)
			if (++removalAttempts == 1) error("provider removal failed")
		}

		val replacement = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
		)

		replacement.status shouldBe ActivityRegistrationStatus.DEGRADED
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRING
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE

		val reconciled = subject.reconcileDurableDemands()

		reconciled.status shouldBe ActivityRegistrationStatus.APPLIED
		removedIdentities shouldBe listOf(firstIdentity, firstIdentity)
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRED
		coVerify(exactly = 2) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `repeated clear after successful removal is idempotent`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(started.snapshot.identity)
		database.sourceBrokerDao().retireConsumer("app:auto", "boot-1", 200L, 200L)

		val firstClear = subject.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		val secondClear = subject.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)

		firstClear.status shouldBe ActivityRegistrationStatus.APPLIED
		secondClear.status shouldBe ActivityRegistrationStatus.APPLIED
		secondClear.snapshot.active shouldBe false
		removedIdentities shouldBe listOf(identity)
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRED
	}

	@Test
	fun `failed stale cleanup does not block last demand from fencing current provider`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val first = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val firstIdentity = requireNotNull(first.snapshot.identity)
		coEvery { backend.removeRegistration(any()) } coAnswers {
			val removed = arg<ActivityRegistrationIdentity>(0)
			removedIdentities += removed
			if (removed == firstIdentity) error("old provider removal failed")
		}
		val replacement = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
		)
		val secondIdentity = requireNotNull(replacement.snapshot.identity)
		database.sourceBrokerDao().retireConsumer("app:auto", "boot-1", 300L, 300L)

		val cleared = subject.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)

		cleared.status shouldBe ActivityRegistrationStatus.DEGRADED
		cleared.failureCode shouldBe ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED
		cleared.snapshot.active shouldBe false
		removedIdentities.contains(secondIdentity) shouldBe true
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRING
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRED
	}

	@Test
	fun `failed stale cleanup blocks another replacement while current provider remains active`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		coEvery { backend.removeRegistration(any()) } throws IllegalStateException("provider removal failed")
		val firstReplacement = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
		)
		val acceptedIdentity = requireNotNull(firstReplacement.snapshot.identity)

		val blockedReplacement = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 15),
		)

		blockedReplacement.status shouldBe ActivityRegistrationStatus.DEGRADED
		blockedReplacement.failureCode shouldBe ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED
		blockedReplacement.snapshot.identity shouldBe acceptedIdentity
		database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, 3L) shouldBe null
		coVerify(exactly = 2) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `collected data deletion journals failed provider removal before allowing local erase`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val started = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		val identity = requireNotNull(started.snapshot.identity)
		coEvery { backend.removeRegistration(any()) } throws IllegalStateException("provider unavailable")
		coEvery { backend.removePendingRegistration(any()) } throws
			IllegalStateException("provider unavailable")

		val closed = subject.closeForCollectedDataDeletion()

		closed.status shouldBe ActivityRegistrationStatus.DEGRADED
		closed.failureCode shouldBe ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED
		closed.retryable shouldBe true
		closed.snapshot.active shouldBe false
		cleanupStore.read().pending shouldBe setOf(
			ActivityRegistrationCleanupKey(
				kind = ActivityRegistrationCleanupKind.BROKERED,
				sourceInstanceId = identity.sourceInstanceId,
				registrationGeneration = identity.registrationGeneration,
			),
		)
		verify(atLeast = 1) { cleanupScheduler.ensureScheduled() }
	}

	@Test
	fun `pending cleanup blocks only a new Activity registration`() = runTest {
		cleanupStore.write(
			ActivityRegistrationCleanupState(
				releasedV27Checked = true,
				pending = setOf(
					ActivityRegistrationCleanupKey(
						kind = ActivityRegistrationCleanupKind.BROKERED,
						sourceInstanceId = "retired-instance",
						registrationGeneration = 4L,
					),
				),
			),
		)
		coEvery { backend.removePendingRegistration(any()) } throws
			IllegalStateException("provider unavailable")
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.failureCode shouldBe ActivityRegistrationFailureCode.PROVIDER_CLEANUP_PENDING
		result.retryable shouldBe true
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `corrupt cleanup journal fails Activity registration closed`() = runTest {
		cleanupFile.writeBytes(byteArrayOf(1, 2, 3))
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)

		val result = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)

		result.status shouldBe ActivityRegistrationStatus.BLOCKED
		result.failureCode shouldBe ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID
		result.retryable shouldBe false
		coVerify(exactly = 0) { backend.applyRegistration(any(), any()) }
	}

	@Test
	fun `released v27 static pending intent is checked before broker registration`() = runTest {
		cleanupStore.write(ActivityRegistrationCleanupState.INITIAL)
		val cleanupKeys = mutableListOf<ActivityRegistrationCleanupKey>()
		coEvery { backend.removePendingRegistration(any()) } coAnswers {
			cleanupKeys += arg<ActivityRegistrationCleanupKey>(0)
		}

		val result = subject.retryPendingProviderCleanup()

		result shouldBe ActivityProviderCleanupResult.COMPLETE
		cleanupKeys shouldBe listOf(ActivityRegistrationCleanupKey.RELEASED_V27)
		cleanupStore.read().releasedV27Checked shouldBe true
	}

	@Test
	fun `fresh automatic demand after deletion registers under the new collected-data epoch`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("auto-before-delete", "app:automatic-start:activity", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		val initial = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
		)
		val initialIdentity = requireNotNull(initial.snapshot.identity)
		initialIdentity.collectedDataEpoch shouldBe 7L

		startupGate.ready = false
		lifecycleStore.beginFullDeletion(deletedAtMs = 500L)
		subject.closeForCollectedDataDeletion().status shouldBe ActivityRegistrationStatus.APPLIED
		val registrationsBeforeRestoration = appliedIdentities.size
		database.withTransaction {
			database.sourceBrokerDao().deleteAllAuthorizations()
			database.sourceBrokerDao().deleteAllRegistrations()
			database.sourceBrokerDao().deleteAllDemands()
			database.sourceRegistrationStateDao().deleteAll()
		}
		subject.resumeAfterCollectedDataDeletion().status shouldBe ActivityRegistrationStatus.BLOCKED
		database.sourceBrokerDao().activeDemands(ACTIVITY_SOURCE_KIND) shouldBe emptyList()

		// This is the durable row written by the authoritative automatic-control reconciler after
		// the reopened startup generation reaches Ready. No preference mutation or process restart
		// occurs between the two provider registrations.
		database.sourceBrokerDao().insertDemands(
			listOf(demand("auto-after-delete", "app:automatic-start:activity", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		startupGate.ready = true
		val restored = subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
		)

		restored.status shouldBe ActivityRegistrationStatus.APPLIED
		val restoredIdentity = requireNotNull(restored.snapshot.identity)
		restoredIdentity.collectedDataEpoch shouldBe 8L
		restoredIdentity.sourceInstanceId shouldNotBe initialIdentity.sourceInstanceId
		appliedIdentities.drop(registrationsBeforeRestoration) shouldBe listOf(restoredIdentity)
		database.sourceBrokerDao().activeDemands(ACTIVITY_SOURCE_KIND).single().demandId shouldBe
			"auto-after-delete"
	}

	@Test
	fun `post acceptance cleanup does not swallow cancellation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		subject.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
		)
		coEvery { backend.removeRegistration(any()) } throws CancellationException("cleanup cancelled")

		var cancellationObserved = false
		try {
			subject.setDemand(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
				ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 10),
			)
		} catch (_: CancellationException) {
			cancellationObserved = true
		}

		cancellationObserved shouldBe true
	}

	@Test
	fun `cancellation during first acceptance propagates after durable and current state converge`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		coEvery { backend.applyRegistration(any(), any()) } coAnswers {
			val identity = arg<ActivityRegistrationIdentity>(1)
			appliedIdentities += identity
			currentCoroutineContext().cancel(CancellationException("cancel during acceptance"))
			true
		}

		val acceptance = async {
			subject.setDemand(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
				ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 5),
			)
		}
		acceptance.join()

		acceptance.isCancelled shouldBe true
		val currentIdentity = requireNotNull(subject.snapshot().identity)
		subject.snapshot().active shouldBe true
		val pointer = requireNotNull(database.sourceRegistrationStateDao().get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE))
		pointer.registrationGeneration shouldBe currentIdentity.registrationGeneration
		pointer.sourceInstanceId shouldBe currentIdentity.sourceInstanceId
		database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			currentIdentity.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
	}

	private suspend fun awaitBarrierFence(identity: ActivityRegistrationIdentity) {
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				while (true) {
					val probe = callbackAdmissionBarrier.tryEnter(identity) ?: break
					probe.complete()
					delay(1L)
				}
			}
		}
	}

	private suspend fun seedAcquisitionAuthority() {
		database.trackingRolloutStateDao().save(eventRollout())
		(1..6).forEach { sourceKind ->
			database.sourceProjectionStateDao().installProductLane(
				SourceProductProjectionLaneEntity(
					sourceKind = sourceKind,
					bindingGeneration = 1L,
					projectionId = "activity-arbiter-test-product-$sourceKind",
					projectionVersion = 1,
					captureModeMask = 7L,
					productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
					activatedRolloutRevision = 1L,
					activationOrdinal = 1L,
					contiguousAdmissionOrdinal = 0L,
					retentionRequired = true,
					status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
					installedAtMs = 1L,
					updatedAtMs = 1L,
				),
			)
		}
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = 5L,
				legacySettingsFingerprint = "test",
				updatedAtMs = 1L,
			),
		)
		database.sourcePolicyDao().insertPolicies(
			(1..6).map { sourceKind ->
				SourcePolicyEntity(
					policyRevision = 5L,
					sourceKind = sourceKind,
					enabled = true,
					qosCode = 2,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = true,
					ambientPersistenceEligible = false,
					captureConsentEpoch = 8L,
					controlConsentEpoch = 8L,
					ambientConsentEpoch = null,
					effectiveBootId = "boot-1",
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 1L,
					changeReason = "TEST",
				)
			},
		)
		insertServiceRun("run-s1", "s1", isUserInitiated = true)
		insertServiceRun("run-auto", "auto", isUserInitiated = false)
	}

	private suspend fun activatePolicyRevision(
		revision: Long,
		activityControlConsentEpoch: Long,
	) {
		val previous = database.sourcePolicyDao().currentPolicies()
		database.sourcePolicyDao().insertPolicies(previous.map { policy ->
			policy.copy(
				policyRevision = revision,
				controlConsentEpoch = if (policy.sourceKind == ACTIVITY_SOURCE_KIND) {
					activityControlConsentEpoch
				} else {
					policy.controlConsentEpoch
				},
				effectiveElapsedRealtimeNanos = 200L,
				effectiveWallTimeMs = 200L,
				changeReason = "TEST_CONTROL_EPOCH_ROTATION",
			)
		})
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 5L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = revision,
			legacySettingsFingerprint = "test",
			updatedAtMs = 200L,
		) shouldBe 1
	}

	private suspend fun insertServiceRun(
		serviceRunId: String,
		logicalTrackingId: String,
		isUserInitiated: Boolean,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = "ACTIVE",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				completedAtMs = null,
				completionReason = null,
				bootId = "boot-1",
				leaseGeneration = 1L,
				startOrigin = if (isUserInitiated) {
					"MANUAL_FOREGROUND_START"
				} else {
					"AUTOMATIC_BACKGROUND_START"
				},
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "START_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 1L,
				startIsUserInitiated = isUserInitiated,
				startIsAmbient = false,
			),
		)
	}

	private suspend fun saveContainedRollout() {
		database.trackingRolloutStateDao().save(
			eventRollout(
				owners = (1..6).associateWith { "CONTAINED" },
				stages = (1..6).associateWith { "LEGACY_CANONICAL" },
				revision = 2L,
			),
		)
	}

	private suspend fun saveStepsCaptureActivityControlRollout(
		stepsCaptureModeMask: Long = AUTOMATIC_CAPTURE_MASK,
	) {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = STEPS_SOURCE_KIND,
				bindingGeneration = 1L,
				projectionId = "activity-arbiter-test-product-$STEPS_SOURCE_KIND",
				projectionVersion = 1,
				captureModeMask = stepsCaptureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 2L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
		database.trackingRolloutStateDao().save(
			eventRollout(
				owners = (1..6).associateWith { sourceKind ->
					when (sourceKind) {
						ACTIVITY_SOURCE_KIND -> "CONTROL"
						STEPS_SOURCE_KIND -> "EVENT"
						else -> "CONTAINED"
					}
				},
				stages = (1..6).associateWith { sourceKind ->
					if (sourceKind == STEPS_SOURCE_KIND) {
						"EVENT_SHADOW"
					} else {
						"LEGACY_CANONICAL"
					}
				},
				captureModeMasks = (1..6).associateWith { sourceKind ->
					if (sourceKind == STEPS_SOURCE_KIND) stepsCaptureModeMask else 0L
				},
				revision = 2L,
			),
		)
	}

	private suspend fun saveActivityCaptureRollout(captureModeMask: Long) {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = ACTIVITY_SOURCE_KIND,
				bindingGeneration = 1L,
				projectionId = "activity-arbiter-test-product-$ACTIVITY_SOURCE_KIND",
				projectionVersion = 1,
				captureModeMask = captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 2L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
		database.trackingRolloutStateDao().save(
			eventRollout(
				owners = (1..6).associateWith { sourceKind ->
					if (sourceKind == ACTIVITY_SOURCE_KIND) "EVENT" else "CONTAINED"
				},
				stages = (1..6).associateWith { sourceKind ->
					if (sourceKind == ACTIVITY_SOURCE_KIND) "EVENT_SHADOW" else "LEGACY_CANONICAL"
				},
				captureModeMasks = (1..6).associateWith { sourceKind ->
					if (sourceKind == ACTIVITY_SOURCE_KIND) captureModeMask else 0L
				},
				revision = 2L,
			),
		)
	}

	private fun eventRollout(
		owners: Map<Int, String> = (1..6).associateWith { "EVENT" },
		stages: Map<Int, String> = (1..6).associateWith { "EVENT_SHADOW" },
		captureModeMasks: Map<Int, Long> = owners.mapValues { (_, owner) ->
			if (owner == "EVENT") ALL_CAPTURE_MASK else 0L
		},
		revision: Long = 1L,
	) = TrackingRolloutStateEntity(
		revision = revision,
		schemaVersion = 4,
		coordinatorMode = "EVENT",
		projectionMode = stages.entries.sortedBy { it.key }
			.joinToString(",") { (source, stage) ->
				"$source:$stage:${captureModeMasks.getValue(source)}"
			},
		sourceOwners = owners.entries.sortedBy { it.key }
			.joinToString(",") { (source, owner) -> "$source:$owner" },
		semanticSettingsEnabled = true,
		batteryEstimateMode = "SOURCE_PLAN_QUALITATIVE",
		updatedAtMs = revision,
	)

	private fun demand(
		id: String,
		consumerId: String,
		purpose: String,
		logicalTrackingId: String?,
		manifestRevision: Long?,
		persistenceEligible: Boolean,
	) = SourceDemandEntity(
		demandId = id,
		consumerId = consumerId,
		sourceKind = ACTIVITY_SOURCE_KIND,
		purpose = purpose,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = logicalTrackingId?.let { "run-$it" },
		manifestRevision = manifestRevision,
		lifecycleLeaseGeneration = logicalTrackingId?.let { 1L },
		sourcePolicyRevision = 5L,
		consentEpoch = 8L,
		persistenceEligible = persistenceEligible,
		qosCode = 2,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 15_000L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 100L + (manifestRevision ?: 0L),
		requestedAtMs = 100L + (manifestRevision ?: 0L),
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private companion object {
		const val ACTIVITY_SOURCE_KIND = 2
		const val STEPS_SOURCE_KIND = 3
		const val OWNER_SCOPE = "source-broker:2"
		const val MANUAL_CAPTURE_MASK = 1L shl 0
		const val AUTOMATIC_CAPTURE_MASK = 1L shl 1
		const val ALL_CAPTURE_MASK = (1L shl 3) - 1L
	}
}

private class FakeLifecycleStore(initial: CollectedDataLifecycleSnapshot) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	var snapshotStarted: CompletableDeferred<Unit>? = null
	var snapshotRelease: CompletableDeferred<Unit>? = null
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot {
		snapshotStarted?.complete(Unit)
		snapshotRelease?.await()
		return state.value
	}
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs).also { state.emit(it) }

	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(retainedFromMs = retainedFromMs).also { state.emit(it) }
}

private class FakeTrackingStartupGate(
	var ready: Boolean = true,
) : TrackingStartupGate {
	override val isReady: Boolean get() = ready

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		if (ready) {
			TrackingStartupResult.Ready(
				legacyRecoveryPartial = false,
				liveCompletedThroughOrdinal = 0L,
			)
		} else {
			TrackingStartupResult.RetryableFailure(
				stage = com.adsamcik.tracker.shared.base.startup.TrackingStartupStage.STORAGE,
				failureCode = "TEST_NOT_READY",
			)
		}
}
