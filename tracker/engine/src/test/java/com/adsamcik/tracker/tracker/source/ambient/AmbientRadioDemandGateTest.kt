package com.adsamcik.tracker.tracker.source.ambient

import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationReport
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
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
import kotlinx.coroutines.test.runTest

class AmbientRadioDemandGateTest {
	@Test
	fun `default-off Cell retires the durable join before touching provider state`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedCellSourceController>()
		val lease = lease(AmbientTrackingSource.CELL)
		coEvery {
			broker.replaceAmbientCellDemand(any(), false, lease.identity, 1L, any(), any(), any())
		} returns AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
		)
		coEvery { controller.reconcileAmbientJoin() } returns
			AmbientCellRuntimeJoinResult.Inactive(providerKey = null)
		coEvery { broker.ambientRadioReconciliationAuthority(SourceKind.CELL) } returns
			authority(SourceKind.CELL)
		val subject = AmbientCellDemandReconciler(
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		val result = subject.reconcile(lease, AmbientCellActivationRequest(enabled = false))

		assertEquals(
			AmbientCellDemandReconciliation.Inactive(
				AmbientCellDemandBlockReason.REQUEST_DISABLED,
			),
			result.outcome,
		)
		coVerify(exactly = 1) { controller.reconcileAmbientJoin() }
	}

	@Test
	fun `stale Wi-Fi mutation result cannot retire or activate a provider join`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedWifiSourceController>()
		val lease = lease(AmbientTrackingSource.WIFI)
		coEvery {
			broker.replaceAmbientWifiDemand(any(), true, lease.identity, 1L, any(), any(), any())
		} returns AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
		)
		coEvery { broker.ambientRadioReconciliationAuthority(SourceKind.WIFI) } returns
			authority(SourceKind.WIFI)
		val subject = AmbientWifiDemandReconciler(
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		val result = subject.reconcile(lease, AmbientWifiActivationRequest(enabled = true))

		assertEquals(
			AmbientWifiDemandReconciliation.Inactive(
				AmbientWifiDemandBlockReason.STALE_RECONCILIATION_LEASE,
			),
			result.outcome,
		)
		coVerify(exactly = 0) { controller.reconcileAmbientJoin() }
	}

	@Test
	fun `durably verified missing retention publishes typed Wi-Fi unavailability`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedWifiSourceController>()
		val lease = lease(AmbientTrackingSource.WIFI)
		val exactAuthority = authority(SourceKind.WIFI).copy(
			executionGeneration = null,
			authorityRevision = null,
		)
		coEvery {
			broker.replaceAmbientWifiDemand(any(), true, lease.identity, 1L, any(), any(), any())
		} returns AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING,
			exactAuthority,
		)
		coEvery { controller.reconcileAmbientJoin() } returns
			AmbientWifiRuntimeJoinResult.Inactive(providerKey = null)
		val subject = AmbientWifiDemandReconciler(
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		val owner = subject.reconcile(lease, AmbientWifiActivationRequest(enabled = true))

		assertEquals(
			AmbientRadioReportPreparation.Prepared(
				AmbientSourceReconciliationResult.Unavailable(
					AmbientSourceReconciliationReport(
						lease.identity,
						AmbientSourceOperationalAvailability(
							source = AmbientTrackingSource.WIFI,
							state = AmbientSourceOperationalState.UNAVAILABLE,
							reason = AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
						),
					),
				),
				owner.evidence,
			),
			owner.prepareReport(lease),
		)
	}

	@Test
	fun `ready report carries the exact CAS identity accepted by the source owner`() {
		val lease = lease(AmbientTrackingSource.WIFI)
		val sourceAuthority = authority(
			SourceKind.WIFI,
			authorityRevision = 3L,
			reconciliationAttempt = 9L,
		)
		val evidence = AmbientRadioReconciliationEvidence.from(
			authority = sourceAuthority,
			reconciliationAttempt = 9L,
			demandId = "wifi-demand",
			sourceInstanceId = SourceInstanceId("wifi-1"),
			registrationGeneration = 7L,
		)
		val owner = com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiOwnerReconciliation(
			outcome = AmbientWifiDemandReconciliation.Active(
				demandId = "wifi-demand",
				authorityRevision = 3L,
				reconciliationAuthority = sourceAuthority,
				sourceInstanceId = SourceInstanceId("wifi-1"),
				registrationGeneration = 7L,
			),
			evidence = evidence,
		)

		assertEquals(
			AmbientRadioReportPreparation.Prepared(
				AmbientSourceReconciliationResult.Reconciled(
					AmbientSourceReconciliationReport(
						identity = lease.identity,
						availability = AmbientSourceOperationalAvailability(
							source = AmbientTrackingSource.WIFI,
							state = AmbientSourceOperationalState.READY,
							mechanism = AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
						),
					),
				),
				evidence,
			),
			owner.prepareReport(lease),
		)
	}

	@Test
	fun `late ready with an older owner token is rejected before publication`() {
		val currentLease = lease(AmbientTrackingSource.CELL)
		val staleAuthority = authority(
			SourceKind.CELL,
			ownerCasToken = "owner-cas-old",
		)
		val evidence = AmbientRadioReconciliationEvidence.from(
			authority = staleAuthority,
			reconciliationAttempt = 4L,
			demandId = "cell-demand",
			sourceInstanceId = SourceInstanceId("cell-1"),
			registrationGeneration = 8L,
		)

		assertEquals(
			AmbientRadioReportPreparation.Rejected(
				evidence,
				AmbientRadioReportPreparationRejection.STALE_AUTHORITY,
			),
			prepareAmbientRadioReport(
				currentLease,
				evidence,
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.CELL,
					state = AmbientSourceOperationalState.READY,
					mechanism = AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS,
				),
			),
		)
	}

	private fun authority(
		source: SourceKind,
		authorityRevision: Long = 7L,
		ownerCasToken: String = "owner-cas-1",
		reconciliationAttempt: Long = 1L,
	) = AmbientRadioReconciliationAuthority(
		source = source,
		policyRevision = 10L,
		ambientConsentEpoch = 3L,
		collectedDataEpoch = 2L,
		rolloutRevision = 4L,
		executionGeneration = 1L,
		authorityRevision = authorityRevision,
		ownerCasToken = ownerCasToken,
		reconciliationAttempt = reconciliationAttempt,
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
}
