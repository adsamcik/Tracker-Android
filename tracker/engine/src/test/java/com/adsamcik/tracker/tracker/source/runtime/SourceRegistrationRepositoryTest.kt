package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
@Config(sdk = [34])
class SourceRegistrationRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var subject: SourceRegistrationRepository

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		subject = SourceRegistrationRepository(
			database,
			FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null)),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-7"
			},
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `registration fails closed without a durable active demand`() = runTest {
		shouldThrow<IllegalArgumentException> {
			subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		}
	}

	@Test
	fun `registration snapshots the exact merged purpose vector before provider acceptance`() = runTest {
		val demands = listOf(
			demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 4L, true),
			demand("control", "app:automation", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
		)
		database.sourceBrokerDao().insertDemands(demands)

		val registration = subject.begin(SourceKind.STEPS, 9L, PHYSICAL_CONFIG, 100L, 100L)
		val reserved = requireNotNull(
			database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L),
		)

		reserved.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, registration.ownerScope) shouldBe null
		reserved.ownerScope shouldBe "source-broker:${SourceKind.STEPS.stableCode}"
		reserved.physicalConfigurationFingerprint shouldBe PHYSICAL_CONFIG
		registration.authorization.purposeEligibilityMask shouldBe
			(SourceBrokerPurpose.MASK_SESSION_CAPTURE or SourceBrokerPurpose.MASK_CONTROL_AUTOSTART)
		registration.authorization.authorizedMembers
			.map { it.demandId } shouldBe listOf("control", "capture")

		subject.markAccepted(registration, acceptedAtMs = 120L, acceptedElapsedRealtimeNanos = 120L)
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
	}

	@Test
	fun `manifest and global plan revisions rotate authorization without physical generation churn`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture-v1", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)
		database.sourceBrokerDao().retireConsumer("session:s1", "boot-7", 120L, 120L)
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture-v2", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 2L, true)),
		)

		val second = subject.begin(SourceKind.STEPS, 2L, PHYSICAL_CONFIG, 130L, 130L)
		val unrelatedGlobalRevision = subject.begin(SourceKind.STEPS, 99L, PHYSICAL_CONFIG, 140L, 140L)

		second.state.registrationGeneration shouldBe 1L
		second.requiresProviderAcceptance shouldBe false
		second.authorization.authorizationRevision shouldBe first.authorization.authorizationRevision + 1L
		unrelatedGlobalRevision.state.registrationGeneration shouldBe 1L
		unrelatedGlobalRevision.authorization.authorizationRevision shouldBe second.authorization.authorizationRevision
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		second.authorization.authorizedMembers.single().manifestRevision shouldBe 2L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L) shouldBe null
	}

	@Test
	fun `active authorization refresh never reserves an incompatible replacement`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)

		val refreshed = subject.refreshActiveAuthorization(
			SourceKind.STEPS,
			first,
			2L,
			PHYSICAL_CONFIG,
			120L,
			120L,
		)
		val incompatible = subject.refreshActiveAuthorization(
			SourceKind.STEPS,
			first,
			3L,
			"physical-config-v2",
			130L,
			130L,
		)

		refreshed?.state?.registrationGeneration shouldBe 1L
		refreshed?.requiresProviderAcceptance shouldBe false
		incompatible shouldBe null
		database.sourceBrokerDao().maximumRegistrationGeneration(SourceKind.STEPS.stableCode) shouldBe 1L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L) shouldBe null
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, first.ownerScope)
			?.appliedRevision shouldBe 2L
	}

	@Test
	fun `physical configuration change alone rotates generation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)

		val second = subject.begin(SourceKind.STEPS, 2L, "physical-config-v2", 200L, 200L)

		second.state.registrationGeneration shouldBe 2L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RESERVED
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, second.ownerScope)
			?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			200L,
		)?.registrationGeneration shouldBe 1L

		val retiring = subject.markAccepted(second, 220L, 220L)

		retiring?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRING
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, second.ownerScope)
			?.registrationGeneration shouldBe 2L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			219L,
		)?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			220L,
		) shouldBe null
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			2L,
			second.state.sourceInstanceId,
			"boot-7",
			"physical-config-v2",
			219L,
		) shouldBe null
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			2L,
			second.state.sourceInstanceId,
			"boot-7",
			"physical-config-v2",
			220L,
		)?.registrationGeneration shouldBe 2L
	}

	@Test
	fun `failed replacement preserves accepted generation and pointer`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)
		val replacement = subject.begin(SourceKind.STEPS, 2L, "physical-config-v2", 200L, 200L)

		subject.markFailed(replacement, "PROVIDER_REGISTRATION_FAILED", 210L, 210L)

		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_FAILED
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, replacement.ownerScope)
			?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			250L,
		)?.registrationGeneration shouldBe 1L
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
		sourceKind = SourceKind.STEPS.stableCode,
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
		requestedBootId = "boot-7",
		requestedElapsedRealtimeNanos = 100L + (manifestRevision ?: 0L),
		requestedAtMs = 100L + (manifestRevision ?: 0L),
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private companion object {
		const val PHYSICAL_CONFIG = "physical-config-v1"
	}
}

private class FakeCollectedDataLifecycleStore(initial: CollectedDataLifecycleSnapshot) :
	CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs).also { state.emit(it) }

	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(retainedFromMs = retainedFromMs).also { state.emit(it) }
}
