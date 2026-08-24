package com.adsamcik.tracker.activity.api.registration

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
	private var clockDomainId = "android-boot-count:7"
	private val appliedIdentities = CopyOnWriteArrayList<ActivityRegistrationIdentity>()
	private val appliedConfigs = CopyOnWriteArrayList<RecognitionConfig>()
	private val removedIdentities = CopyOnWriteArrayList<ActivityRegistrationIdentity>()
	private val statusesObservedAtProviderCall = CopyOnWriteArrayList<String>()

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		clockDomainId = "android-boot-count:7"
		database = AppDatabase.testDatabase(application)
		runBlocking { seedAcquisitionAuthority() }
		backend = mockk()
		stubSuccessfulProviderApply()
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
		lifecycleStore,
		backend,
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

		startupGate.ready = true
		val reconciled = subject.reconcileDurableDemands()

		reconciled.status shouldBe ActivityRegistrationStatus.APPLIED
		reconciled.snapshot.active shouldBe true
		coVerify(exactly = 2) { backend.applyRegistration(any(), any()) }
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
	fun `automatic owner attribution rotates callback metadata when session keeps provider request`() = runTest {
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

		requireNotNull(sessionOnly.snapshot.identity).registrationGeneration shouldBe 1L
		requireNotNull(withAutomaticOwner.snapshot.identity).registrationGeneration shouldBe 2L
		requireNotNull(withoutAutomaticOwner.snapshot.identity).registrationGeneration shouldBe 3L
		appliedConfigs.map(RecognitionConfig::automaticRecognitionEligible) shouldBe
			listOf(false, true, false)
		appliedConfigs.map(RecognitionConfig::intervalSeconds) shouldBe listOf(5, 5, 5)
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
		coEvery { backend.applyRegistration(any(), any()) } returns false

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

	private suspend fun seedAcquisitionAuthority() {
		database.trackingRolloutStateDao().save(eventRollout())
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

	private fun eventRollout(
		owners: Map<Int, String> = (1..6).associateWith { "EVENT" },
		stages: Map<Int, String> = (1..6).associateWith { "EVENT_SHADOW" },
		revision: Long = 1L,
	) = TrackingRolloutStateEntity(
		revision = revision,
		schemaVersion = 3,
		coordinatorMode = "EVENT",
		projectionMode = stages.entries.sortedBy { it.key }
			.joinToString(",") { (source, stage) -> "$source:$stage" },
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
		const val OWNER_SCOPE = "source-broker:2"
	}
}

private class FakeLifecycleStore(initial: CollectedDataLifecycleSnapshot) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
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
