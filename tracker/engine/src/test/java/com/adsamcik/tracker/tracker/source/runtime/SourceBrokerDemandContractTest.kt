package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.ActivityAcquisitionCapability
import com.adsamcik.tracker.tracker.source.model.ActivityAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerDemandContractTest : SourceBrokerRoomTestFixture() {
    @Test
    fun `six source manifest demands retain exact profile floors and only selected bindings gain live preference`() = runTest {
        val repository = RoomSourcePolicyRepository(database) { SourcePolicyEffectiveTime("boot-1", 10, 10) }
        val settings = TrackingParamsState(legacySettingsMigrationCompleted = true, balanceProfileVersion = 1,
            overallBalanceLevel = 2, locationProfileVersion = 1, locationBalanceLevel = 2,
            wifiEnabled = true, cellEnabled = true, barometerEnabled = true)
        val snapshot = repository.bootstrapFromLegacy(settings)
        val policies = database.sourcePolicyDao().policiesAtRevision(snapshot.revision).associateBy { it.sourceKind }
        for (source in SourceKind.entries) {
            val policy = policies.getValue(source.stableCode)
            val sourceBinding = binding(source, SourceBrokerPurpose.SESSION_CAPTURE,
                requireNotNull(policy.captureConsentEpoch), true).copy(qosCode = policy.qosCode)
            val demand = subject.buildSessionDemands("session-1", "run-1", 4, 2, snapshot.revision,
                listOf(sourceBinding), "boot-1", 100, 200, policies = policies).single()
            demand.sourceKind shouldBe source.stableCode
            demand.toSourceDemandContract() shouldBe SourceDemandContractFactory.forProfile(source, 1, 4,
                DirectSourceDemandPurpose.SESSION_CAPTURE)
            demand.sourcePolicyRevision shouldBe snapshot.revision
            demand.consentEpoch shouldBe policy.captureConsentEpoch
        }
        subject.buildSessionDemands("session-1", "run-1", 4, 2, snapshot.revision,
            emptyList(), "boot-1", 100, 200, policies = policies).shouldBeEmpty()
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
	fun `adaptive Location manifest uses exact policy profile while legacy manifest keeps its floor`() = runTest {
		val repository = RoomSourcePolicyRepository(database) { SourcePolicyEffectiveTime("boot-1", 10L, 10L) }
		val snapshot = repository.bootstrapFromLegacy(TrackingParamsState(
			legacySettingsMigrationCompleted = true, locationProfileVersion = 1, locationBalanceLevel = 5,
		))
		val policy = requireNotNull(database.sourcePolicyDao().policyAtRevision(snapshot.revision, SourceKind.LOCATION.stableCode))
		val sourceBinding = binding(SourceKind.LOCATION, SourceBrokerPurpose.SESSION_CAPTURE,
			requireNotNull(policy.captureConsentEpoch), true).copy(qosCode = policy.qosCode)
		fun demand(rows: Map<Int, com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity>?) =
			subject.buildSessionDemands("session-1", "run-1", 4, 2, snapshot.revision,
				listOf(sourceBinding), "boot-1", 100, 200, policies = rows).single()
		val newDemand = demand(mapOf(policy.sourceKind to policy))
		newDemand.maximumAgeMs shouldBe 120_000L
		newDemand.desiredLatencyMs shouldBe 1_000L
		newDemand.toSourceDemandContract() shouldBe SourceDemandContractFactory.forProfile(
			SourceKind.LOCATION, 1, 5, DirectSourceDemandPurpose.SESSION_CAPTURE)
		val historical = demand(mapOf(policy.sourceKind to policy.copy(balanceProfileVersion = null, balanceLevel = null)))
		historical.toSourceDemandContract() shouldBe SourceDemandContractFactory.forQos(
			SourceKind.LOCATION, policy.qosCode, DirectSourceDemandPurpose.SESSION_CAPTURE)
		shouldThrow<IllegalArgumentException> {
			demand(mapOf(policy.sourceKind to policy.copy(policyRevision = policy.policyRevision + 1)))
		}
	}

	@Test
	fun `Pressure manifest creates one exact session capture demand`() = runTest {
		val demand = subject.buildSessionDemands(
			logicalTrackingId = "pressure-session",
			serviceRunId = "pressure-run",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.PRESSURE, SourceBrokerPurpose.SESSION_CAPTURE, 12L, true)
					.copy(logicalTrackingId = "pressure-session"),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		).single()

		database.sourceBrokerDao().insertDemands(listOf(demand))
		val stored = database.sourceBrokerDao().demandHistory(
			subject.sessionConsumerId("pressure-session"),
		).single()
		stored.sourceKind shouldBe SourceKind.PRESSURE.stableCode
		stored.purpose shouldBe SourceBrokerPurpose.SESSION_CAPTURE
		stored.persistenceEligible shouldBe true
		stored.logicalTrackingId shouldBe "pressure-session"
		stored.serviceRunId shouldBe "pressure-run"
		stored.toSourceDemandContract() shouldBe SourceDemandContractFactory.forQos(
			SourceKind.PRESSURE,
			stored.qosCode,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
		database.sourceBrokerDao().currentDemands(
			subject.sessionConsumerId("pressure-session"),
		).map { it.sourceKind to it.purpose } shouldBe listOf(
			SourceKind.PRESSURE.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
		)
	}

	@Test
	fun `Pressure control manifest is rejected before a demand can exist`() = runTest {
		shouldThrow<IllegalArgumentException> {
			subject.buildSessionDemands(
				logicalTrackingId = "pressure-session",
				serviceRunId = "pressure-run",
				manifestRevision = 4L,
				lifecycleLeaseGeneration = 2L,
				policyRevision = 9L,
				bindings = listOf(
					binding(SourceKind.PRESSURE, "CONTROL", 12L, false)
						.copy(logicalTrackingId = "pressure-session"),
				),
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L,
				wallTimeMs = 200L,
			)
		}.message shouldBe "Pressure supports recording purposes only"
		database.sourceBrokerDao().demandHistory(
			subject.sessionConsumerId("pressure-session"),
		).shouldBeEmpty()
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
}
