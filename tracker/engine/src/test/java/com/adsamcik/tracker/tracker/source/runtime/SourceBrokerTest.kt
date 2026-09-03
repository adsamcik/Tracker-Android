package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.ActivityAcquisitionCapability
import com.adsamcik.tracker.tracker.source.model.ActivityAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerTest {
	private lateinit var database: AppDatabase
	private lateinit var subject: SourceBroker
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore
	private var elapsed = 10L

	@Before
	fun setUp() {
		resetBroker(setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `Activity automatic control enable disable and revoke rotate an independent epoch`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		val granted = policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_ACTIVITY_CONTROL_GRANT",
		)

		suspend fun replace(enabled: Boolean, at: Long) = subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = enabled,
			bootId = "boot-1",
			elapsedRealtimeNanos = at,
			wallTimeMs = at,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		)

		requireNotNull(replace(true, 100L))
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(2L to true)
		requireNotNull(replace(true, 150L))
		database.activityAutomationEpochDao().current()?.epoch shouldBe 2L
		replace(false, 200L) shouldBe null
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(3L to false)
		requireNotNull(replace(true, 300L))
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(4L to true)

		// The policy boundary shares the same monotonic clock as provider demand changes.
		elapsed = 400L
		policy.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_ACTIVITY_CONTROL_REVOKE",
		)
		replace(true, 400L) shouldBe null
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(5L to false)
	}

	@Test
	fun `automatic Steps capture rollout admits Activity control without admitting Activity capture`() = runTest {
		val rollout = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS),
			revision = 7L,
			controlSources = setOf(SourceKind.ACTIVITY),
			captureModes = mapOf(
				SourceKind.STEPS to setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE),
			),
		)
		rolloutStore.save(rollout, updatedAtMs = 7L)
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = true,
				barometerEnabled = false,
				sourceCollectionSettings = SourceCollectionSettings(
					location = SourceCollectionFrequency.OFF,
					activity = SourceCollectionFrequency.OFF,
					steps = SourceCollectionFrequency.BALANCED,
					pressure = SourceCollectionFrequency.OFF,
					wifi = SourceCollectionFrequency.OFF,
					cell = SourceCollectionFrequency.OFF,
				),
				legacySettingsMigrationCompleted = true,
			),
		)
		policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_STEPS_ACTIVITY_CONTROL_GRANT",
		)

		val demand = requireNotNull(subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		))

		rollout.isAcquisitionReachable(SourceKind.STEPS) shouldBe true
		rollout.isControlAcquisitionReachable(SourceKind.ACTIVITY) shouldBe true
		rollout.isAcquisitionReachable(SourceKind.ACTIVITY) shouldBe false
		demand.purpose shouldBe SourceBrokerPurpose.CONTROL_AUTOSTART
		demand.persistenceEligible shouldBe false
		database.sourceBrokerDao().currentDemands("app:automation:activity") shouldBe listOf(demand)
	}

	@Test
	fun `manual-only Steps rollout cannot admit Activity automatic control`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_MANUAL_ONLY_ACTIVITY_CONTROL",
		)

		subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		) shouldBe null
		database.sourceBrokerDao().currentDemands("app:automation:activity") shouldBe emptyList()
		database.activityAutomationEpochDao().current()?.automaticControlEnabled shouldBe false
	}

	@Test
	fun `contained rollout retires stale automatic control and authorizes no replacement`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_ACTIVITY_CONTROL_GRANT",
		)
		requireNotNull(subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 50L,
			wallTimeMs = 50L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		))
		rolloutStore.save(
			TrackingRolloutState.contained(revision = 7L),
			updatedAtMs = 7L,
		)

		subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		) shouldBe null

		database.sourceBrokerDao().currentDemands("app:automation:activity") shouldBe emptyList()
	}

	@Test
	fun `manifest bindings become explicit capture and continuation control demands`() {
		val demands = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.LOCATION, SourceBrokerPurpose.SESSION_CAPTURE, 12L, true),
				binding(SourceKind.ACTIVITY, "CONTROL", 15L, false),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		)

		demands.map { it.sourceKind to it.purpose } shouldBe listOf(
			SourceKind.ACTIVITY.stableCode to SourceBrokerPurpose.CONTROL_CONTINUATION,
			SourceKind.LOCATION.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
		)
		demands.single { it.purpose == SourceBrokerPurpose.CONTROL_CONTINUATION }
			.persistenceEligible shouldBe false
		demands.forEach { it.demandId.shouldNotBeBlank() }
	}

	@Test
	fun `Activity capture and continuation share classifications after persistence`() = runTest {
		val demands = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.ACTIVITY, SourceBrokerPurpose.SESSION_CAPTURE, 15L, true)
					.copy(qosCode = 3),
				binding(SourceKind.ACTIVITY, "CONTROL", 15L, false).copy(qosCode = 3),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		)

		database.sourceBrokerDao().insertDemands(demands)
		val restored = database.sourceBrokerDao().demandHistory(subject.sessionConsumerId("session-1"))
		val capture = restored.single { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
			.toSourceDemandContract().floor as ActivityAcquisitionFloor
		val control = restored.single { it.purpose == SourceBrokerPurpose.CONTROL_CONTINUATION }
			.toSourceDemandContract().floor as ActivityAcquisitionFloor
		capture.requiredCapabilities shouldBe setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
		control.requiredCapabilities shouldBe setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
		capture.union(control).requiredCapabilities shouldBe
			setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
	}

	@Test
	fun `authorization fingerprint includes floor and adaptive permission`() {
		val demand = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(binding(SourceKind.LOCATION, SourceBrokerPurpose.SESSION_CAPTURE, 12L, true)),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		).single()
		val original = SourceBrokerAuthorization.fingerprint(listOf(demand))
		val changedSpec = SourceDemandContractFactory.forQos(
			SourceKind.LOCATION,
			3,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		).encodeFloor()
		val changedFloor = SourceBrokerAuthorization.fingerprint(listOf(demand.copy(
			minimumAcquisitionSpec = changedSpec,
		)))
		val changedAdaptive = SourceBrokerAuthorization.fingerprint(listOf(demand.copy(
			adaptiveReductionAllowed = !demand.adaptiveReductionAllowed,
		)))
		val changedRequestedLatency = SourceBrokerAuthorization.fingerprint(listOf(demand.copy(
			requestedDeliveryLatencyMs = 123L,
		)))

		(original == changedFloor) shouldBe false
		(original == changedAdaptive) shouldBe false
		(original == changedRequestedLatency) shouldBe false
	}

	@Test
	fun `persisted direct demands round trip the one live contract for every source qos and purpose`() = runTest {
		val cases = buildList {
			SourceKind.entries.forEach { source ->
				(1..3).forEach { qos ->
					add(Triple(source, qos, SourceBrokerPurpose.SESSION_CAPTURE))
				}
			}
			listOf(SourceKind.ACTIVITY, SourceKind.STEPS).forEach { source ->
				(0..3).forEach { qos ->
					add(Triple(source, qos, "CONTROL"))
				}
			}
		}
		cases.forEachIndexed { index, (source, qos, manifestPurpose) ->
			val logicalTrackingId = "session-$index"
			val demand = subject.buildSessionDemands(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = "run-$index",
				manifestRevision = 4L,
				lifecycleLeaseGeneration = 2L,
				policyRevision = 9L,
				bindings = listOf(binding(source, manifestPurpose, 12L, true).copy(qosCode = qos)),
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L + index,
				wallTimeMs = 200L + index,
			).single()
			database.sourceBrokerDao().insertDemands(listOf(demand))
			val restored = database.sourceBrokerDao()
				.demandHistory(subject.sessionConsumerId(logicalTrackingId)).single()
			val purpose = if (manifestPurpose == SourceBrokerPurpose.SESSION_CAPTURE) {
				DirectSourceDemandPurpose.SESSION_CAPTURE
			} else {
				DirectSourceDemandPurpose.CONTROL_CONTINUATION
			}
			restored.toSourceDemandContract() shouldBe
				SourceDemandContractFactory.forQos(source, qos, purpose)
		}
	}

	@Test
	fun `automatic control reenable creates a fresh auditable demand and revoke fails closed`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val granted = policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.STEPS,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_CONTROL_GRANT",
		)

		val first = requireNotNull(subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:steps",
			source = SourceKind.STEPS,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		))
		subject.authorizationDemands(SourceKind.STEPS) shouldBe listOf(first)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "steps-provider-1",
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "steps-physical-config",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 90L,
				reservedElapsedRealtimeNanos = 90L,
				acceptedAtMs = 100L,
				acceptedElapsedRealtimeNanos = 100L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.STEPS.stableCode,
				1L,
				1L,
				listOf(first),
				"boot-1",
				100L,
				100L,
			),
		)
		subject.replaceAutomaticControlDemand(
			"app:automation:steps",
			SourceKind.STEPS,
			false,
			"boot-1",
			200L,
			200L,
			30_000L,
			5_000L,
		)
		val second = requireNotNull(subject.replaceAutomaticControlDemand(
			"app:automation:steps",
			SourceKind.STEPS,
			true,
			"boot-1",
			300L,
			300L,
			30_000L,
			5_000L,
		))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 2L,
				sourceInstanceId = "steps-provider-1",
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "steps-physical-config-v2",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				reservedAtMs = 350L,
				reservedElapsedRealtimeNanos = 350L,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.STEPS.stableCode,
				2L,
				4L,
				listOf(second),
				"boot-1",
				350L,
				350L,
			),
		)

		(first.demandId == second.demandId) shouldBe false
		database.sourceBrokerDao().demandHistory("app:automation:steps").map { it.status } shouldBe
			listOf(SourceDemandEntity.STATUS_RETIRED, SourceDemandEntity.STATUS_ACTIVE)

		// The policy boundary shares the same monotonic clock as provider demand changes.
		elapsed = 400L
		policy.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = TrackingSourceComponent.STEPS,
			purpose = SourcePurpose.CONTROL,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_CONTROL_REVOKE",
		)
		subject.replaceAutomaticControlDemand(
			"app:automation:steps",
			SourceKind.STEPS,
			true,
			"boot-1",
			400L,
			400L,
			30_000L,
			5_000L,
		) shouldBe null
		database.sourceBrokerDao().currentDemands("app:automation:steps") shouldBe emptyList()
		subject.authorizationDemands(SourceKind.STEPS) shouldBe emptyList()
		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			1L,
			"boot-1",
			399L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			1L,
			"boot-1",
			400L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			2L,
			"boot-1",
			400L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
	}

	@Test
	fun `retiring session demand remains registration eligible until drain completes`() = runTest {
		val demand = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.ACTIVITY, SourceBrokerPurpose.SESSION_CAPTURE, 15L, true)
					.copy(manifestRevision = 1L),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		).single()
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-provider-1",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "physical-config",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 90L,
				reservedElapsedRealtimeNanos = 90L,
				acceptedAtMs = 100L,
				acceptedElapsedRealtimeNanos = 100L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.ACTIVITY.stableCode,
				1L,
				1L,
				listOf(demand),
				"boot-1",
				100L,
				200L,
			),
		)

		subject.markSessionDemandsRetiring("session-1", "boot-1", 300L, 300L)

		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode)
			.map { it.status } shouldBe listOf(SourceDemandEntity.STATUS_RETIRING)
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			299L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			300L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		database.sourceBrokerDao().registration(
			SourceKind.ACTIVITY.stableCode,
			1L,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE

		subject.retireSessionDemands("session-1", "boot-1", 400L, 400L)
		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
	}

	private fun binding(
		source: SourceKind,
		purpose: String,
		consentEpoch: Long,
		persistenceEligible: Boolean,
	): SessionManifestSourceEntity {
		val writer = when {
			source == SourceKind.STEPS && isCaptured(purpose, persistenceEligible) -> STEPS_TEST_WRITER
			source == SourceKind.PRESSURE && isCaptured(purpose, persistenceEligible) -> PRESSURE_TEST_WRITER
			else -> null
		}
		return SessionManifestSourceEntity(
			logicalTrackingId = "session-1",
			manifestRevision = 4L,
			sourceKind = source.stableCode,
			purpose = purpose,
			consentEpoch = consentEpoch,
			persistenceEligible = persistenceEligible,
			qosCode = 2,
			outputDestination = writer?.destination,
			writerOwner = writer?.owner,
			writerOwnerGeneration = writer?.ownerGeneration,
			writerProjectionId = writer?.projectionId,
			writerProjectionVersion = writer?.projectionVersion,
			writerBindingGeneration = writer?.bindingGeneration,
		)
	}

	private fun resetBroker(captureModes: Set<CaptureReachabilityMode>) {
		if (::database.isInitialized) database.close()
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		rolloutStore = runBlocking {
			activateAllBrokerTestProductLanes(database, captureModes)
		}
		subject = SourceBroker(database, rolloutStore)
	}
}

private fun isCaptured(purpose: String, persistenceEligible: Boolean): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible

private data class BrokerTestWriter(
	val destination: String,
	val owner: String,
	val ownerGeneration: Long,
	val projectionId: String? = null,
	val projectionVersion: Int? = null,
	val bindingGeneration: Long? = null,
)

private val STEPS_TEST_WRITER = BrokerTestWriter(
	destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
	owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
	ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
)

private val PRESSURE_TEST_WRITER = ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS.let { binding ->
	BrokerTestWriter(
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		owner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		projectionId = binding.projectionId,
		projectionVersion = binding.projectionVersion,
		bindingGeneration = binding.bindingGeneration,
	)
}

private suspend fun activateAllBrokerTestProductLanes(
	database: AppDatabase,
	captureModes: Set<CaptureReachabilityMode>,
): RoomTrackingRolloutStateStore {
	database.sourceDestinationOwnerDao().insertIfAbsent(
		SourceDestinationOwnerEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			owner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			updatedAtMs = 1L,
		),
	)
	val bindings = SourceKind.entries.map { source ->
		if (source == SourceKind.PRESSURE) {
			ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS
		} else {
			ExecutableSourceLaneBinding(
				source = source,
				bindingGeneration = 1L,
				projectionId = "broker-test-${source.name.lowercase()}-product",
				projectionVersion = 1,
				captureModes = captureModes,
			)
		}
	}
	return installCanonicalProductLanesForTest(
		database = database,
		bindings = bindings,
		rolloutRevision = bindings.size.toLong(),
	)
}
