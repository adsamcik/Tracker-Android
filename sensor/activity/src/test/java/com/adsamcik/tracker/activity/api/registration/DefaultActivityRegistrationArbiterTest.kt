package com.adsamcik.tracker.activity.api.registration

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
	private val appliedIdentities = CopyOnWriteArrayList<ActivityRegistrationIdentity>()
	private val removedIdentities = CopyOnWriteArrayList<ActivityRegistrationIdentity>()
	private val statusesObservedAtProviderCall = CopyOnWriteArrayList<String>()
	private var clockDomainId = "android-boot-count:7"

	@Before
	fun setUp() {
		val application: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
		backend = mockk()
		coEvery { backend.applyRegistration(any(), any()) } coAnswers {
			val identity = arg<ActivityRegistrationIdentity>(1)
			appliedIdentities += identity
			statusesObservedAtProviderCall += requireNotNull(
				database.sourceBrokerDao().registration(ACTIVITY_SOURCE_KIND, identity.registrationGeneration),
			).status
			true
		}
		coEvery { backend.removeRegistration(any()) } coAnswers {
			removedIdentities += arg<ActivityRegistrationIdentity>(0)
		}
		io.mockk.every { backend.isAvailable } returns true
		appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		subject = DefaultActivityRegistrationArbiter(
			application,
			BootClockDomainProvider { clockDomainId },
			database,
			FakeLifecycleStore(CollectedDataLifecycleSnapshot(7L, null)),
			backend,
			appScope,
		)
	}

	@After
	fun tearDown() {
		appScope.cancel()
		database.close()
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
