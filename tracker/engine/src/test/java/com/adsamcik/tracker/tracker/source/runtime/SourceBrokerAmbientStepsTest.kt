package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerAmbientStepsTest : SourceBrokerRoomTestFixture() {
	@Test
	fun `all-day Steps choice creates one sessionless provider-specific demand`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val snapshot = policy.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		val retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			requireNotNull(snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)

		val result = subject.replaceAmbientStepsDemand(
			consumerId = "app:ambient:steps",
			mechanism = AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			leaseIdentity = ambientStepsIdentity(retention),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			retentionSnapshot = retention,
		)

		val demand = (result as AmbientStepsDemandResult.Active).demand
		snapshot[TrackingSourceComponent.STEPS].enabled shouldBe true
		demand.purpose shouldBe SourceBrokerPurpose.AMBIENT_PRODUCT
		demand.logicalTrackingId shouldBe null
		demand.serviceRunId shouldBe null
		demand.manifestRevision shouldBe null
		demand.lifecycleLeaseGeneration shouldBe null
		demand.persistenceEligible shouldBe true
		// System history uses provider-native windows and does not acquire a sampled QoS cadence.
		demand.qosCode shouldBe 0
		demand.liveAmbientRetentionPolicyId shouldBe "privacy:steps:ambient:v1"
		demand.liveAmbientRetentionApprovalRevision shouldBe 1L
		(demand.toSourceDemandContract().floor as AmbientStepsAcquisitionFloor).mechanism shouldBe
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe listOf(demand)
		subject.authorizationDemands(SourceKind.STEPS) shouldBe listOf(demand)
	}

	// Keep Steps provider replacement, unchanged retry and retention-approval rotation together.
	@Suppress("LongMethod")
	@Test
	fun `ambient provider replacement at one boundary never overlaps or aliases demand identity`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val snapshot = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		var retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			requireNotNull(snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)
		val healthConnect = (subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			ambientStepsIdentity(retention),
			"boot-1",
			100L,
			100L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active).demand
		val localRecording = (subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			ambientStepsIdentity(retention),
			"boot-1",
			100L,
			100L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active).demand

		(healthConnect.demandId == localRecording.demandId) shouldBe false
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe listOf(localRecording)
		val history = database.sourceBrokerDao().demandHistory("app:ambient:steps")
		history.size shouldBe 2
		history.single { it.demandId == healthConnect.demandId }.status shouldBe
			SourceDemandEntity.STATUS_RETIRED
		history.single { it.demandId == localRecording.demandId }.status shouldBe
			SourceDemandEntity.STATUS_ACTIVE

		val unchanged = subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			ambientStepsIdentity(retention),
			"boot-1",
			150L,
			150L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active
		unchanged.demand shouldBe localRecording
		database.sourceBrokerDao().demandHistory("app:ambient:steps").size shouldBe 2

		retentionReader.approvalRevision = 2L
		retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			requireNotNull(snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)
		val rotated = subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			ambientStepsIdentity(retention),
			"boot-1",
			200L,
			200L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active
		rotated.demand.liveAmbientRetentionApprovalRevision shouldBe 2L
		(rotated.demand.demandId == localRecording.demandId) shouldBe false
		(SourceBrokerAuthorization.fingerprint(listOf(rotated.demand)) ==
			SourceBrokerAuthorization.fingerprint(listOf(localRecording))) shouldBe false
		database.sourceBrokerDao().demandHistory("app:ambient:steps").size shouldBe 3
	}

	@Test
	fun `ambient consent and rollout independently deny demand creation`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		rolloutStore.save(TrackingRolloutState.contained(revision = 9L), updatedAtMs = 150L)

		subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			ambientStepsIdentityWithoutRetention(),
			"boot-1",
			200L,
			200L,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.ROLLOUT_CONTAINED,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe emptyList()

		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(TrackingParamsState(stepsEnabled = false, legacySettingsMigrationCompleted = true))
		subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			ambientStepsIdentityWithoutRetention(),
			"boot-1",
			300L,
			300L,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.CONSENT_REVOKED,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe emptyList()
	}

	@Test
	fun `Ambient Steps mutation fails closed when retention hook is stale`() = runTest {
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		retentionReader.current = false

		subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			ambientStepsIdentityWithoutRetention(),
			"boot-1",
			100L,
			100L,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe emptyList()
	}

	// Keep the accepted Steps snapshot, corrupted persisted grant and refused atomic mutation together.
	@Suppress("LongMethod")
	@Test
	fun `Ambient Steps mutation rejects a corrupt persisted retention row after snapshot`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		val retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			policy.revision,
			requireNotNull(policy[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)
		val grant = retention.grants.getValue(SourceKind.STEPS)
		val identity = AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = policy.revision,
			consentEpoch = requireNotNull(
				policy[TrackingSourceComponent.STEPS].ambientConsentEpoch,
			),
			collectedDataEpoch = grant.collectedDataEpoch,
			rolloutRevision = rolloutStore.load().revision,
			ownerCasToken = "steps-stale-snapshot-owner",
			executionRevision = 1L,
			retainedFromMs = grant.retainedFromMs,
			retentionPolicyId = grant.opaquePolicyId,
			retentionApprovalRevision = grant.approvalRevision,
		)
		val callerIdentity = SourceCallerDemandIdentity(identity.purposeLeaseIdentity, null)
		val callerReference = SourceCallerReplayReference("steps-stale-snapshot-caller")
		val prepared = PreparedSourceCallerAcceptance(
			receipt = SourceCallerAcceptanceReceipt(callerReference, setOf(callerIdentity)),
			authority = StoredSourceCallerAuthority(
				StoredSourceCallerOrigin.PURPOSE_OWNER,
				TrackingPurpose.AMBIENT_PRODUCT,
				setOf(callerIdentity),
			),
			createdAtMs = 95L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_retention_authority SET effect_checksum = 'corrupt' " +
				"WHERE scope = 'LIVE_AMBIENT'",
		)

		subject.replaceAmbientStepsDemand(
			consumerId = "app:ambient:steps",
			mechanism = AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			leaseIdentity = identity,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			sourceCallerAuthorityReference = callerReference.value,
			retentionSnapshot = retention,
			preparedCallerAcceptance = prepared,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps").shouldBeEmpty()
		RoomSourceCallerAcceptedAuthorityRepository(database).load(callerReference) shouldBe
			StoredSourceCallerAuthorityLoadResult.Missing
	}

	private suspend fun ambientStepsIdentity(
		retention: LiveAmbientRetentionSnapshot,
	): AmbientReconciliationIdentity {
		val grant = retention.grants.getValue(SourceKind.STEPS)
		return AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = grant.sourcePolicyRevision,
			consentEpoch = grant.ambientConsentEpoch,
			collectedDataEpoch = grant.collectedDataEpoch,
			rolloutRevision = rolloutStore.load().revision,
			ownerCasToken = "source-broker-test-steps-owner",
			executionRevision = 1L,
			retainedFromMs = grant.retainedFromMs,
			retentionPolicyId = grant.opaquePolicyId,
			retentionApprovalRevision = grant.approvalRevision,
		)
	}

	private suspend fun ambientStepsIdentityWithoutRetention(): AmbientReconciliationIdentity {
		val policyRevision = requireNotNull(database.sourcePolicyDao().authority()).currentPolicyRevision
		val policy = requireNotNull(
			database.sourcePolicyDao().policyAtRevision(policyRevision, SourceKind.STEPS.stableCode),
		)
		return AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = policyRevision,
			consentEpoch = policy.ambientConsentEpoch ?: 1L,
			collectedDataEpoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch,
			rolloutRevision = rolloutStore.load().revision,
			ownerCasToken = "source-broker-test-steps-owner",
			executionRevision = 1L,
		)
	}
}
