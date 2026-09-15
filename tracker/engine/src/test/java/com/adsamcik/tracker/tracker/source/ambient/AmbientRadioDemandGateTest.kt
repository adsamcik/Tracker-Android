package com.adsamcik.tracker.tracker.source.ambient

import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellActivationRequest
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandBlockReason
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandReconciler
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandReconciliation
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiActivationRequest
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandBlockReason
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandReconciler
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandReconciliation
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class AmbientRadioDemandGateTest {
	@Test
	fun `missing Wi-Fi retention approval retires demand before runtime capability`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedWifiSourceController>()
		val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
		val events = mutableListOf<String>()
		coEvery {
			broker.replaceAmbientWifiDemand(any(), false, null, any(), any(), any())
		} returns AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
		)
		coEvery { controller.reconcileAmbientJoin() } answers {
			events += "runtime"
			AmbientWifiRuntimeJoinResult.Inactive
		}
		every { reporter.reportAmbientSource(any()) } answers {
			events += "report"
			Unit
		}
		val subject = AmbientWifiDemandReconciler(
			activePolicyRepository(TrackingSourceComponent.WIFI),
			mockk<TrackingRolloutStateStore>(),
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
			reporter,
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
		verify(exactly = 1) {
			reporter.reportAmbientSource(
				AmbientSourceOperationalAvailability.retentionPolicyUnavailable(
					AmbientTrackingSource.WIFI,
				),
			)
		}
		assertEquals(listOf("runtime", "report"), events)
	}

	@Test
	fun `default-off Cell request retires demand without provider activation`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedCellSourceController>()
		val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
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
			reporter,
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
		verify(exactly = 1) {
			reporter.reportAmbientSource(
				AmbientSourceOperationalAvailability.retentionPolicyUnavailable(
					AmbientTrackingSource.CELL,
				),
			)
		}
	}

	@Test
	fun `Wi-Fi is ready only after a real provider generation settles`() {
		val waiting = AmbientWifiDemandReconciliation.Active(
			demandId = "wifi-demand",
			authorityRevision = 3L,
			sourceInstanceId = null,
			registrationGeneration = null,
		).toPurposeAvailabilityResult()
		val ready = AmbientWifiDemandReconciliation.Active(
			demandId = "wifi-demand",
			authorityRevision = 3L,
			sourceInstanceId = SourceInstanceId("wifi-1"),
			registrationGeneration = 7L,
		).toPurposeAvailabilityResult()

		assertEquals(
			AmbientSourceReconciliationResult.Unavailable(
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.WIFI,
					state = AmbientSourceOperationalState.WAITING,
					mechanism = AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
					reason = AmbientSourceUnavailableReason.RECONCILIATION_PENDING,
				),
			),
			waiting,
		)
		assertEquals(
			AmbientSourceReconciliationResult.Reconciled(
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.WIFI,
					state = AmbientSourceOperationalState.READY,
					mechanism = AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
				),
			),
			ready,
		)
	}

	@Test
	fun `Cell permission and degraded provider outcomes map without guessing grants`() {
		val permission = AmbientCellDemandReconciliation.Unavailable(
			reasons = setOf(SourceDegradedReason.PERMISSION_MISSING),
			retryable = false,
		).toPurposeAvailabilityResult()
		val degraded = AmbientCellDemandReconciliation.Degraded(
			demandId = "cell-demand",
			authorityRevision = 4L,
			sourceInstanceId = SourceInstanceId("cell-1"),
			registrationGeneration = 8L,
			reasons = setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
		).toPurposeAvailabilityResult()

		assertEquals(
			AmbientSourceReconciliationResult.Unavailable(
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.CELL,
					state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
					mechanism = AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS,
					reason = AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED,
				),
			),
			permission,
		)
		assertEquals(
			AmbientSourceReconciliationResult.Reconciled(
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.CELL,
					state = AmbientSourceOperationalState.DEGRADED,
					mechanism = AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS,
					reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
				),
			),
			degraded,
		)
	}

	@Test
	fun `rollout containment is unavailable and failed retirement remains pending`() {
		assertEquals(
			AmbientSourceUnavailableReason.ROLLOUT_CONTAINED,
			AmbientWifiDemandReconciliation.Inactive(
				AmbientWifiDemandBlockReason.ROLLOUT_CONTAINED,
			).toOperationalAvailability().reason,
		)
		assertEquals(
			AmbientSourceOperationalState.WAITING,
			AmbientCellDemandReconciliation.Unavailable(
				reasons = emptySet(),
				retryable = true,
			).toOperationalAvailability().state,
		)
	}

	@Test
	fun `failed policy-blocked provider retirement reports pending instead of safe-looking ready`() =
		runTest {
			val broker = mockk<SourceBroker>()
			val controller = mockk<SharedWifiSourceController>()
			val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
			coEvery {
				broker.replaceAmbientWifiDemand(any(), false, null, any(), any(), any())
			} returns AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
			)
			coEvery { controller.reconcileAmbientJoin() } returns
				AmbientWifiRuntimeJoinResult.Unavailable(emptySet(), retryable = true)
			val subject = AmbientWifiDemandReconciler(
				activePolicyRepository(TrackingSourceComponent.WIFI),
				mockk<TrackingRolloutStateStore>(),
				broker,
				controller,
				BootClockDomainProvider { "boot-1" },
				reporter,
			)

			assertEquals(
				AmbientWifiDemandReconciliation.Unavailable(emptySet(), retryable = true),
				subject.reconcile(AmbientWifiActivationRequest(true, null)),
			)
			verify(exactly = 1) {
				reporter.reportAmbientSource(
					AmbientSourceOperationalAvailability(
						source = AmbientTrackingSource.WIFI,
						state = AmbientSourceOperationalState.WAITING,
						mechanism = AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
						reason = AmbientSourceUnavailableReason.RECONCILIATION_PENDING,
					),
				)
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
