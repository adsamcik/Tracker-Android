package com.adsamcik.tracker.tracker.source.ambient

import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellActivationRequest
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandBlockReason
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandReconciler
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandReconciliation
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiActivationRequest
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandBlockReason
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandReconciler
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandReconciliation
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.runtime.AmbientCellRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientWifiRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedWifiSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class AmbientRadioDemandGateTest {
	@Test
	fun `missing Wi-Fi retention approval retires demand before runtime capability`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedWifiSourceController>()
		coEvery {
			broker.replaceAmbientWifiDemand(any(), false, null, any(), any(), any())
		} returns AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
		)
		coEvery { controller.reconcileAmbientJoin() } returns AmbientWifiRuntimeJoinResult.Inactive
		val subject = AmbientWifiDemandReconciler(
			activePolicyRepository(TrackingSourceComponent.WIFI),
			mockk<TrackingRolloutStateStore>(),
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		val result = subject.reconcile(AmbientWifiActivationRequest(true, null))

		assertEquals(
			AmbientWifiDemandReconciliation.Inactive(
				AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISSING,
			),
			result,
		)
		coVerify(exactly = 1) {
			broker.replaceAmbientWifiDemand(any(), false, null, any(), any(), any())
		}
		coVerify(exactly = 0) {
			broker.replaceAmbientWifiDemand(any(), true, any(), any(), any(), any())
		}
	}

	@Test
	fun `default-off Cell request retires demand without provider activation`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedCellSourceController>()
		coEvery {
			broker.replaceAmbientCellDemand(any(), false, null, any(), any(), any())
		} returns AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
		)
		coEvery { controller.reconcileAmbientJoin() } returns AmbientCellRuntimeJoinResult.Inactive
		val subject = AmbientCellDemandReconciler(
			activePolicyRepository(TrackingSourceComponent.CELL),
			mockk<TrackingRolloutStateStore>(),
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		val result = subject.reconcile(AmbientCellActivationRequest(false, null))

		assertEquals(
			AmbientCellDemandReconciliation.Inactive(
				AmbientCellDemandBlockReason.REQUEST_DISABLED,
			),
			result,
		)
		coVerify(exactly = 0) {
			broker.replaceAmbientCellDemand(any(), true, any(), any(), any(), any())
		}
	}

	private fun activePolicyRepository(ambientSource: TrackingSourceComponent) =
		object : SourcePolicyRepository {
			override val states = emptyFlow<SourcePolicyAuthorityState>()

			override suspend fun currentState(): SourcePolicyAuthorityState =
				SourcePolicyAuthorityState.Active(
					SourcePolicySnapshot(
						1L,
						TrackingSourceComponent.entries.associateWith { source ->
							SourcePolicy(
								source = source,
								enabled = false,
								qos = SourceQos.OFF,
								locationMinTimeSeconds = 1.takeIf {
									source == TrackingSourceComponent.LOCATION
								},
								locationMinDistanceMeters = 1.takeIf {
									source == TrackingSourceComponent.LOCATION
								},
								locationRequiredAccuracyMeters = 1.takeIf {
									source == TrackingSourceComponent.LOCATION
								},
								captureConsentEpoch = null,
								controlConsentEpoch = null,
								ambientConsentEpoch = 1L.takeIf { source == ambientSource },
								capturePersistenceEligible = false,
								controlPersistenceEligible = false,
								ambientPersistenceEligible = source == ambientSource,
								effectiveTime = SourcePolicyEffectiveTime("boot-1", 1L, 1L),
								policyRevision = 1L,
							)
						},
					),
				)

			override suspend fun bootstrapFromLegacy(
				settings: com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState,
			): SourcePolicySnapshot = error("not used")

			override suspend fun replaceCaptureSettings(
				expectedPolicyRevision: Long,
				settings: com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState,
				reason: String,
			): SourcePolicySnapshot = error("not used")

			override suspend fun setNonCaptureConsent(
				expectedPolicyRevision: Long,
				source: TrackingSourceComponent,
				purpose: com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose,
				eligible: Boolean,
				persistenceEligible: Boolean,
				reason: String,
			): SourcePolicySnapshot = error("not used")
		}
}
