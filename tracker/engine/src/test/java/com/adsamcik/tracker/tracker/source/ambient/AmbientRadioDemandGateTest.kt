package com.adsamcik.tracker.tracker.source.ambient

import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
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
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioReconciliationAuthority
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
		val events = mutableListOf<String>()
		coEvery {
			broker.replaceAmbientWifiDemand(any(), false, null, any(), any(), any())
		} returns AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
		)
		coEvery { controller.reconcileAmbientJoin() } answers {
			events += "runtime"
			AmbientWifiRuntimeJoinResult.Inactive(providerKey = null)
		}
		coEvery { broker.ambientRadioReconciliationAuthority(SourceKind.WIFI) } answers {
			events += "evidence"
			authority(SourceKind.WIFI)
		}
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
			result.outcome,
		)
		coVerify(exactly = 1) {
			broker.replaceAmbientWifiDemand(any(), false, null, any(), any(), any())
		}
		coVerify(exactly = 0) {
			broker.replaceAmbientWifiDemand(any(), true, any(), any(), any(), any())
		}
		assertEquals(1L, result.evidence.reconciliationAttempt)
		assertEquals(listOf("runtime", "evidence"), events)
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
		coEvery { controller.reconcileAmbientJoin() } returns
			AmbientCellRuntimeJoinResult.Inactive(providerKey = null)
		coEvery { broker.ambientRadioReconciliationAuthority(SourceKind.CELL) } returns
			authority(SourceKind.CELL)
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
			result.outcome,
		)
		coVerify(exactly = 0) {
			broker.replaceAmbientCellDemand(any(), true, any(), any(), any(), any())
		}
		assertEquals(1L, result.evidence.reconciliationAttempt)
		assertEquals(
			AmbientSourceOperationalAvailability.reconciliationPending(
				AmbientTrackingSource.CELL,
			),
			result.outcome.toOperationalAvailability(),
		)
		assertEquals(
			AmbientRadioReportPreparation.Rejected(
				result.evidence,
				AmbientRadioReportPreparationRejection.NOT_RECONCILED,
			),
			result.prepareReport(lease(AmbientTrackingSource.CELL)),
		)
	}

	@Test
	fun `late Wi-Fi ready carries exact owner evidence and stale lease is rejected`() {
		val unsettled = AmbientWifiDemandReconciliation.Active(
			demandId = "wifi-demand",
			authorityRevision = 3L,
			reconciliationAuthority = authority(SourceKind.WIFI, authorityRevision = 3L),
			sourceInstanceId = null,
			registrationGeneration = null,
		).toOperationalAvailability()
		val evidence = AmbientRadioReconciliationEvidence.from(
			authority = authority(SourceKind.WIFI, authorityRevision = 3L),
			reconciliationAttempt = 9L,
			demandId = "wifi-demand",
			sourceInstanceId = SourceInstanceId("wifi-1"),
			registrationGeneration = 7L,
		)
		val ready = com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiOwnerReconciliation(
			outcome = AmbientWifiDemandReconciliation.Active(
				demandId = "wifi-demand",
				authorityRevision = 3L,
				reconciliationAuthority = authority(
					SourceKind.WIFI,
					authorityRevision = 3L,
				),
				sourceInstanceId = SourceInstanceId("wifi-1"),
				registrationGeneration = 7L,
			),
			evidence = evidence,
		)

		assertEquals(
			AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.WIFI,
				state = AmbientSourceOperationalState.UNAVAILABLE,
				reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			),
			unsettled,
		)
		val prepared = ready.prepareReport(lease(AmbientTrackingSource.WIFI))
		assertEquals(10L, evidence.policyRevision)
		assertEquals(3L, evidence.ambientConsentEpoch)
		assertEquals(2L, evidence.collectedDataEpoch)
		assertEquals(4L, evidence.rolloutRevision)
		assertEquals(1L, evidence.executionGeneration)
		assertEquals(9L, evidence.reconciliationAttempt)
		assertEquals(
			com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey(
				SourceInstanceId("wifi-1"),
				7L,
			),
			evidence.providerKey,
		)
		assertEquals(
			AmbientRadioReportPreparation.Prepared(
				AmbientSourceReconciliationResult.Reconciled(
					com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationReport(
						identity = lease(AmbientTrackingSource.WIFI).identity,
						availability = AmbientSourceOperationalAvailability(
							source = AmbientTrackingSource.WIFI,
							state = AmbientSourceOperationalState.READY,
							mechanism = AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
						),
					),
				),
				evidence,
			),
			prepared,
		)
		assertEquals(
			AmbientRadioReportPreparation.Rejected(
				evidence,
				AmbientRadioReportPreparationRejection.STALE_AUTHORITY,
			),
			ready.prepareReport(
				lease(AmbientTrackingSource.WIFI).copy(
					identity = lease(AmbientTrackingSource.WIFI).identity.copy(
						policyRevision = 11L,
						consentEpoch = 12L,
					),
				),
			),
		)
		listOf(
			lease(AmbientTrackingSource.WIFI).copy(
				identity = lease(AmbientTrackingSource.WIFI).identity.copy(
					collectedDataEpoch = 3L,
				),
			),
			lease(AmbientTrackingSource.WIFI).copy(
				identity = lease(AmbientTrackingSource.WIFI).identity.copy(
					rolloutRevision = 5L,
				),
			),
		).forEach { staleLease ->
			assertEquals(
				AmbientRadioReportPreparation.Rejected(
					evidence,
					AmbientRadioReportPreparationRejection.STALE_AUTHORITY,
				),
				ready.prepareReport(staleLease),
			)
		}
	}

	@Test
	fun `Cell permission and provider failure map through the strict source matrix`() {
		val permission = AmbientCellDemandReconciliation.Unavailable(
			reasons = setOf(SourceDegradedReason.PERMISSION_MISSING),
			retryable = false,
		).toOperationalAvailability()
		val degraded = AmbientCellDemandReconciliation.Degraded(
			demandId = "cell-demand",
			authorityRevision = 4L,
			reconciliationAuthority = authority(SourceKind.CELL, authorityRevision = 4L),
			sourceInstanceId = SourceInstanceId("cell-1"),
			registrationGeneration = 8L,
			reasons = setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
		).toOperationalAvailability()

		assertEquals(
			AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.CELL,
				state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
				mechanism = AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS,
				reason = AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED,
			),
			permission,
		)
		assertEquals(
			AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.CELL,
				state = AmbientSourceOperationalState.UNAVAILABLE,
				reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			),
			degraded,
		)
	}

	@Test
	fun `rollout containment is unavailable and failed retirement is provider unavailable`() {
		assertEquals(
			AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.WIFI,
				state = AmbientSourceOperationalState.UNAVAILABLE,
				reason = AmbientSourceUnavailableReason.ROLLOUT_CONTAINED,
			),
			AmbientWifiDemandReconciliation.Inactive(
				AmbientWifiDemandBlockReason.ROLLOUT_CONTAINED,
			).toOperationalAvailability(),
		)
		assertEquals(
			AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.CELL,
				state = AmbientSourceOperationalState.UNAVAILABLE,
				reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			),
			AmbientCellDemandReconciliation.Unavailable(
				reasons = emptySet(),
				retryable = true,
			).toOperationalAvailability(),
		)
	}

	@Test
	fun `cancelled lease never prepares a completed owner report`() {
		val evidence = AmbientRadioReconciliationEvidence.from(
			authority = authority(SourceKind.CELL, authorityRevision = 4L),
			reconciliationAttempt = 4L,
			demandId = "cell-demand",
			sourceInstanceId = SourceInstanceId("cell-1"),
			registrationGeneration = 8L,
		)
		val owner = com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellOwnerReconciliation(
			outcome = AmbientCellDemandReconciliation.Active(
				"cell-demand",
				4L,
				authority(SourceKind.CELL, authorityRevision = 4L),
				SourceInstanceId("cell-1"),
				8L,
			),
			evidence = evidence,
		)

		assertEquals(
			AmbientRadioReportPreparation.Rejected(
				evidence,
				AmbientRadioReportPreparationRejection.CANCELLED,
			),
			owner.prepareReport(
				lease(AmbientTrackingSource.CELL).copy(cancelled = true),
			),
		)
	}

	@Test
	fun `cancelled callback lease performs no policy broker or provider work`() = runTest {
		val policy = mockk<SourcePolicyRepository>()
		val rollout = mockk<TrackingRolloutStateStore>()
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedWifiSourceController>()
		val subject = AmbientWifiDemandReconciler(
			policy,
			rollout,
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		assertEquals(
			AmbientRadioReportPreparation.Rejected(
				evidence = null,
				reason = AmbientRadioReportPreparationRejection.CANCELLED,
			),
			subject.reconcilePurposeAvailability(
				lease(AmbientTrackingSource.WIFI).copy(cancelled = true),
				AmbientWifiActivationRequest(enabled = false, retentionApproval = null),
			),
		)
		coVerify(exactly = 0) { policy.currentState() }
		coVerify(exactly = 0) { rollout.load() }
		coVerify(exactly = 0) {
			broker.replaceAmbientWifiDemand(any(), any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { controller.reconcileAmbientJoin() }
	}

	@Test
	fun `failed policy-blocked provider retirement returns evidence without publishing globally`() =
		runTest {
			val broker = mockk<SourceBroker>()
			val controller = mockk<SharedWifiSourceController>()
			coEvery {
				broker.replaceAmbientWifiDemand(any(), false, null, any(), any(), any())
			} returns AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
			)
			coEvery { controller.reconcileAmbientJoin() } returns
				AmbientWifiRuntimeJoinResult.Unavailable(
					providerKey = null,
					reasons = emptySet(),
					retryable = true,
				)
			coEvery { broker.ambientRadioReconciliationAuthority(SourceKind.WIFI) } returns
				authority(SourceKind.WIFI)
			val subject = AmbientWifiDemandReconciler(
				activePolicyRepository(TrackingSourceComponent.WIFI),
				mockk<TrackingRolloutStateStore>(),
				broker,
				controller,
				BootClockDomainProvider { "boot-1" },
			)

			val owner = subject.reconcile(AmbientWifiActivationRequest(true, null))

			assertEquals(
				AmbientWifiDemandReconciliation.Unavailable(emptySet(), retryable = true),
				owner.outcome,
			)
			assertEquals(1L, owner.evidence.reconciliationAttempt)
			assertEquals(null, owner.evidence.providerKey)
		}

	private fun authority(
		source: SourceKind,
		authorityRevision: Long = 7L,
	) = AmbientRadioReconciliationAuthority(
		source = source,
		policyRevision = 10L,
		ambientConsentEpoch = 3L,
		collectedDataEpoch = 2L,
		rolloutRevision = 4L,
		executionGeneration = 1L,
		authorityRevision = authorityRevision,
	)

	private fun lease(source: AmbientTrackingSource) = AmbientReconciliationLease(
		AmbientReconciliationIdentity(
			source = source,
			policyRevision = 10L,
			consentEpoch = 3L,
			collectedDataEpoch = 2L,
			rolloutRevision = 4L,
			ownerCasToken = "owner-cas-1",
		),
	)

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
