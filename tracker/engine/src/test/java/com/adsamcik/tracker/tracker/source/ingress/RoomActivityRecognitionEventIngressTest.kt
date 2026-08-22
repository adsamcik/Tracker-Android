package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
class RoomActivityRecognitionEventIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var durableIngress: DurableSourceIngress
	private lateinit var recovery: SourcePipelineRecovery
	private lateinit var subject: RoomActivityRecognitionEventIngress
	private val capturedCandidate = slot<SourceEvidenceCandidate<*>>()

	@Before
	fun setUp() {
		val application: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
		durableIngress = mockk()
		coEvery { durableIngress.admit(capture(capturedCandidate)) } returns
			AdmissionResult.Admitted(SourceEventId("event-1"), 1L)
		recovery = mockk(relaxed = true)
		subject = RoomActivityRecognitionEventIngress(
			database,
			FakeActivityLifecycleStore(CollectedDataLifecycleSnapshot(EPOCH, null)),
			durableIngress,
			recovery,
			CollectionMotionController(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `control-only callback remains sessionless while a logical session is active`() = runTest {
		insertActiveSession()
		insertRegistration(CONTROL_FINGERPRINT)

		val result = subject.admit(batch(identity()))

		result.isDurable shouldBe true
		capturedCandidate.captured.logicalTrackingId shouldBe null
		capturedCandidate.captured.serviceRunId shouldBe null
		capturedCandidate.captured.sourcePolicyRevision shouldBe null
		capturedCandidate.captured.captureConsentEpoch shouldBe null
		capturedCandidate.captured.planAttribution shouldBe PlanAttribution.RECEIVE_TIME_ONLY
		capturedCandidate.captured.registrationPurposeEligibilityMask shouldBe
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
	}

	@Test
	fun `callback with stale physical configuration is rejected before durable ingress`() = runTest {
		insertRegistration(CONTROL_FINGERPRINT)

		val result = subject.admit(batch(identity("stale-physical-config")))

		result.isDurable shouldBe false
		result.failureCode shouldBe "STALE_REGISTRATION_GENERATION"
		coVerify(exactly = 0) { durableIngress.admit(any()) }
	}

	@Test
	fun `callback observed before control demand activation is rejected before sequence allocation`() = runTest {
		insertRegistration(CONTROL_FINGERPRINT, effectiveElapsedRealtimeNanos = 1_500_000_000L)

		val result = subject.admit(batch(identity()))

		result.isDurable shouldBe false
		result.failureCode shouldBe "STALE_REGISTRATION_GENERATION"
		database.sourceRegistrationStateDao().get(SourceKind.ACTIVITY.stableCode, OWNER_SCOPE)
			?.nextSequence shouldBe 0L
		coVerify(exactly = 0) { durableIngress.admit(any()) }
	}

	@Test
	fun `late pre-handoff callback keeps its validated old identity while borrowing only global sequence`() = runTest {
		insertHandoffRegistrations()

		val result = subject.admit(batch(identity(), observedElapsedRealtimeNanos = 1_000_000_000L))

		result.isDurable shouldBe true
		capturedCandidate.captured.sourceInstanceId.value shouldBe INSTANCE_ID
		capturedCandidate.captured.registrationGeneration shouldBe GENERATION
		capturedCandidate.captured.clockDomainId shouldBe BOOT_ID
		capturedCandidate.captured.sourceSequence shouldBe 7L
		database.sourceRegistrationStateDao().get(SourceKind.ACTIVITY.stableCode, OWNER_SCOPE)
			?.sourceInstanceId shouldBe INSTANCE_ID
		database.sourceRegistrationStateDao().get(SourceKind.ACTIVITY.stableCode, OWNER_SCOPE)
			?.nextSequence shouldBe 8L
	}

	@Test
	fun `old callback at the exact handoff boundary is rejected without sequence allocation`() = runTest {
		insertHandoffRegistrations()

		val result = subject.admit(batch(identity(), observedElapsedRealtimeNanos = HANDOFF_NANOS))

		result.isDurable shouldBe false
		result.failureCode shouldBe "STALE_REGISTRATION_GENERATION"
		database.sourceRegistrationStateDao().get(SourceKind.ACTIVITY.stableCode, OWNER_SCOPE)
			?.nextSequence shouldBe 7L
		coVerify(exactly = 0) { durableIngress.admit(any()) }
	}

	@Test
	fun `callback cannot borrow sequence from a different source instance`() = runTest {
		insertHandoffRegistrations(pointerInstanceId = NEW_INSTANCE_ID)

		val result = subject.admit(batch(identity(), observedElapsedRealtimeNanos = 1_000_000_000L))

		result.isDurable shouldBe false
		result.failureCode shouldBe "STALE_REGISTRATION_GENERATION"
		database.sourceRegistrationStateDao().get(SourceKind.ACTIVITY.stableCode, OWNER_SCOPE)
			?.nextSequence shouldBe 7L
		coVerify(exactly = 0) { durableIngress.admit(any()) }
	}

	private suspend fun insertRegistration(
		fingerprint: String,
		effectiveElapsedRealtimeNanos: Long = 90L,
	) {
		database.sourceRegistrationStateDao().replace(
			SourceRegistrationStateEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				ownerScope = OWNER_SCOPE,
				sourceInstanceId = INSTANCE_ID,
				clockDomainId = BOOT_ID,
				registrationGeneration = GENERATION,
				nextSequence = 0L,
				appliedRevision = null,
				collectedDataEpoch = EPOCH,
				updatedAtMs = 100L,
			),
		)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = GENERATION,
				sourceInstanceId = INSTANCE_ID,
				ownerScope = OWNER_SCOPE,
				clockDomainId = BOOT_ID,
				collectedDataEpoch = EPOCH,
				physicalConfigurationFingerprint = PHYSICAL_CONFIG,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 90L,
				reservedElapsedRealtimeNanos = 80L,
				acceptedAtMs = 100L,
				acceptedElapsedRealtimeNanos = 90L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = GENERATION,
					authorizationRevision = 1L,
					memberId = "demand:control-demand",
					authorizationFingerprint = fingerprint,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
					demandId = "control-demand",
					consumerId = "app:auto",
					purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					sourcePolicyRevision = 4L,
					consentEpoch = 9L,
					persistenceEligible = true,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = 90L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
	}

	private suspend fun insertActiveSession() {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = "session-1",
				state = "ACTIVE",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "AUTOMATIC",
				clockDomainId = BOOT_ID,
				startedAtMs = 100L,
				startedElapsedNanos = 100L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "AUTOMATIC",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = BOOT_ID,
				automationEpoch = 1L,
			),
		)
	}

	private suspend fun insertHandoffRegistrations(pointerInstanceId: String = INSTANCE_ID) {
		database.sourceRegistrationStateDao().replace(
			SourceRegistrationStateEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				ownerScope = OWNER_SCOPE,
				sourceInstanceId = pointerInstanceId,
				clockDomainId = BOOT_ID,
				registrationGeneration = 2L,
				nextSequence = 7L,
				appliedRevision = null,
				collectedDataEpoch = EPOCH,
				updatedAtMs = 1_500L,
			),
		)
		listOf(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = GENERATION,
				sourceInstanceId = INSTANCE_ID,
				ownerScope = OWNER_SCOPE,
				clockDomainId = BOOT_ID,
				collectedDataEpoch = EPOCH,
				physicalConfigurationFingerprint = PHYSICAL_CONFIG,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				reservedAtMs = 90L,
				reservedElapsedRealtimeNanos = 80L,
				acceptedAtMs = 100L,
				acceptedElapsedRealtimeNanos = 90L,
				retiredAtMs = 1_500L,
				retiredElapsedRealtimeNanos = HANDOFF_NANOS,
				failureCode = "SUPERSEDED_BY_NEW_GENERATION",
			),
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 2L,
				sourceInstanceId = INSTANCE_ID,
				ownerScope = OWNER_SCOPE,
				clockDomainId = BOOT_ID,
				collectedDataEpoch = EPOCH,
				physicalConfigurationFingerprint = "physical-config-v2",
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 1_400L,
				reservedElapsedRealtimeNanos = HANDOFF_NANOS - 1L,
				acceptedAtMs = 1_500L,
				acceptedElapsedRealtimeNanos = HANDOFF_NANOS,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		).forEach { database.sourceBrokerDao().insertRegistration(it) }
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				controlAuthorization(GENERATION, 1L, 90L),
				controlAuthorization(2L, 2L, HANDOFF_NANOS),
			),
		)
	}

	private fun controlAuthorization(
		generation: Long,
		revision: Long,
		effectiveElapsedRealtimeNanos: Long,
	) = SourceAuthorizationEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = generation,
		authorizationRevision = revision,
		memberId = "demand:control-demand-$generation",
		authorizationFingerprint = "$CONTROL_FINGERPRINT-$generation",
		purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
		demandId = "control-demand-$generation",
		consumerId = "app:auto",
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		sourcePolicyRevision = 4L,
		consentEpoch = 9L,
		persistenceEligible = true,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = 90L,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
	)

	private fun identity(physicalConfigurationFingerprint: String = PHYSICAL_CONFIG) = ActivityRegistrationIdentity(
		sourceInstanceId = INSTANCE_ID,
		registrationGeneration = GENERATION,
		collectedDataEpoch = EPOCH,
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
	)

	private fun batch(
		identity: ActivityRegistrationIdentity,
		observedElapsedRealtimeNanos: Long = 1_000_000_000L,
	) = ActivityRecognitionEvidenceBatch(
		receivedElapsedRealtimeNanos = 2_000_000_000L,
		receivedWallTimeMs = 2_000L,
		registrationIdentity = identity,
		recognitions = listOf(
			ActivityRecognitionEvidence(
				activityType = DetectedActivityType.WALKING,
				confidencePercent = 90,
				providerElapsedRealtimeNanos = observedElapsedRealtimeNanos,
			),
		),
	)

	private companion object {
		const val OWNER_SCOPE = "source-broker:2"
		const val INSTANCE_ID = "activity-instance"
		const val NEW_INSTANCE_ID = "activity-instance-new"
		const val BOOT_ID = "boot-1"
		const val GENERATION = 1L
		const val EPOCH = 7L
		const val PHYSICAL_CONFIG = "physical-config"
		const val CONTROL_FINGERPRINT = "control-fingerprint"
		const val HANDOFF_NANOS = 1_500_000_000L
	}
}

private class FakeActivityLifecycleStore(initial: CollectedDataLifecycleSnapshot) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs).also { state.emit(it) }

	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(retainedFromMs = retainedFromMs).also { state.emit(it) }
}
