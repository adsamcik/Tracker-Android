package com.adsamcik.tracker.tracker.source.ambient

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
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
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioLeaseMutation
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class AmbientRadioDemandGateTest {
	@Test
	fun `default-off Cell retires the durable join before touching provider state`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedCellSourceController>()
		val lease = lease(AmbientTrackingSource.CELL)
		coEvery {
			broker.withAmbientRadioMutationLease(
				lease.identity,
				any<suspend () -> AmbientCellDemandReconciliation>(),
			)
		} coAnswers {
			AmbientRadioLeaseMutation.Applied(
				secondArg<suspend () -> AmbientCellDemandReconciliation>().invoke(),
			)
		}
		coEvery {
			broker.replaceAmbientCellDemandUnderHeldLease(
				any(), false, lease.identity, 1L, any(), any(), any(),
			)
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
	fun `stale Wi-Fi lease cannot enter broker or provider reconciliation`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedWifiSourceController>()
		val lease = lease(AmbientTrackingSource.WIFI)
		coEvery {
			broker.withAmbientRadioMutationLease(
				lease.identity,
				any<suspend () -> AmbientWifiDemandReconciliation>(),
			)
		} returns AmbientRadioLeaseMutation.Stale
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
		coVerify(exactly = 0) {
			broker.replaceAmbientWifiDemandUnderHeldLease(
				any(), any(), any(), any(), any(), any(), any(),
			)
		}
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
			broker.withAmbientRadioMutationLease(
				lease.identity,
				any<suspend () -> AmbientWifiDemandReconciliation>(),
			)
		} coAnswers {
			AmbientRadioLeaseMutation.Applied(
				secondArg<suspend () -> AmbientWifiDemandReconciliation>().invoke(),
			)
		}
		coEvery {
			broker.replaceAmbientWifiDemandUnderHeldLease(
				any(), true, lease.identity, 1L, any(), any(), any(),
			)
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
							lastIdentity = lease.purposeLeaseIdentity,
						),
					),
				),
				owner.evidence,
			),
			owner.prepareReport(lease),
		)
	}

	@Test
	fun `cancellation after broker commit compensates exact demand while guard stays held`() =
		runTest {
			val broker = mockk<SourceBroker>()
			val controller = mockk<SharedWifiSourceController>()
			val lease = lease(AmbientTrackingSource.WIFI)
			val demand = ambientDemand(SourceKind.WIFI, "wifi-exact-demand")
			val activeAuthority = authority(SourceKind.WIFI)
			var guardHeld = false
			var providerCalls = 0
			coEvery {
				broker.withAmbientRadioMutationLease(
					lease.identity,
					any<suspend () -> AmbientWifiDemandReconciliation>(),
				)
			} coAnswers {
				guardHeld = true
				try {
					AmbientRadioLeaseMutation.Applied(
						secondArg<suspend () -> AmbientWifiDemandReconciliation>().invoke(),
					)
				} finally {
					guardHeld = false
				}
			}
			coEvery {
				broker.replaceAmbientWifiDemandUnderHeldLease(
					any(), true, lease.identity, 1L, any(), any(), any(),
				)
			} returns AmbientRadioDemandResult.Active(demand, 7L, activeAuthority)
			coEvery { controller.reconcileAmbientJoin() } answers {
				assertTrue(guardHeld)
				providerCalls += 1
				if (providerCalls == 1) {
					throw CancellationException("lease replaced during provider reconcile")
				}
				AmbientWifiRuntimeJoinResult.Inactive(providerKey = null)
			}
			coEvery {
				broker.compensateAmbientWifiDemandUnderHeldLease(
					any(), lease.identity, 1L, demand.demandId, any(), any(), any(),
				)
			} answers {
				assertTrue(guardHeld)
				activeAuthority.copy(authorityRevision = 8L)
			}
			val subject = AmbientWifiDemandReconciler(
				broker,
				controller,
				BootClockDomainProvider { "boot-1" },
			)

			assertFailsWith<CancellationException> {
				subject.reconcile(lease, AmbientWifiActivationRequest(enabled = true))
			}

			assertEquals(2, providerCalls)
			coVerify(exactly = 1) {
				broker.compensateAmbientWifiDemandUnderHeldLease(
					any(), lease.identity, 1L, demand.demandId, any(), any(), any(),
				)
			}
		}

	@Test
	fun `compensation DAO failure is suppressed onto the primary cancellation`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedWifiSourceController>()
		val lease = lease(AmbientTrackingSource.WIFI)
		val demand = ambientDemand(SourceKind.WIFI, "wifi-dao-failure-demand")
		val activeAuthority = authority(SourceKind.WIFI)
		val primary = CancellationException("primary cancellation")
		val compensationFailure = IllegalStateException("compensation DAO failure")
		coEvery {
			broker.withAmbientRadioMutationLease(
				lease.identity,
				any<suspend () -> AmbientWifiDemandReconciliation>(),
			)
		} coAnswers {
			AmbientRadioLeaseMutation.Applied(
				secondArg<suspend () -> AmbientWifiDemandReconciliation>().invoke(),
			)
		}
		coEvery {
			broker.replaceAmbientWifiDemandUnderHeldLease(
				any(), true, lease.identity, 1L, any(), any(), any(),
			)
		} returns AmbientRadioDemandResult.Active(demand, 7L, activeAuthority)
		coEvery { controller.reconcileAmbientJoin() } throws primary
		coEvery {
			broker.compensateAmbientWifiDemandUnderHeldLease(
				any(), lease.identity, 1L, demand.demandId, any(), any(), any(),
			)
		} throws compensationFailure
		val subject = AmbientWifiDemandReconciler(
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		val thrown = assertFailsWith<CancellationException> {
			subject.reconcile(lease, AmbientWifiActivationRequest(enabled = true))
		}

		assertSame(primary, thrown)
		assertEquals(listOf(compensationFailure), thrown.suppressedExceptions)
	}

	@Test
	fun `cleanup provider failure is suppressed onto the primary reconciliation failure`() =
		runTest {
			val broker = mockk<SourceBroker>()
			val controller = mockk<SharedCellSourceController>()
			val lease = lease(AmbientTrackingSource.CELL)
			val demand = ambientDemand(SourceKind.CELL, "cell-provider-failure-demand")
			val activeAuthority = authority(SourceKind.CELL)
			val primary = IllegalArgumentException("primary provider failure")
			val cleanupFailure = IllegalStateException("cleanup provider failure")
			var providerCalls = 0
			coEvery {
				broker.withAmbientRadioMutationLease(
					lease.identity,
					any<suspend () -> AmbientCellDemandReconciliation>(),
				)
			} coAnswers {
				AmbientRadioLeaseMutation.Applied(
					secondArg<suspend () -> AmbientCellDemandReconciliation>().invoke(),
				)
			}
			coEvery {
				broker.replaceAmbientCellDemandUnderHeldLease(
					any(), true, lease.identity, 1L, any(), any(), any(),
				)
			} returns AmbientRadioDemandResult.Active(demand, 7L, activeAuthority)
			coEvery { controller.reconcileAmbientJoin() } answers {
				providerCalls += 1
				if (providerCalls == 1) throw primary
				throw cleanupFailure
			}
			coEvery {
				broker.compensateAmbientCellDemandUnderHeldLease(
					any(), lease.identity, 1L, demand.demandId, any(), any(), any(),
				)
			} returns activeAuthority.copy(authorityRevision = 8L)
			val subject = AmbientCellDemandReconciler(
				broker,
				controller,
				BootClockDomainProvider { "boot-1" },
			)

			val thrown = assertFailsWith<IllegalArgumentException> {
				subject.reconcile(lease, AmbientCellActivationRequest(enabled = true))
			}

			assertSame(primary, thrown)
			assertEquals(listOf(cleanupFailure), thrown.suppressedExceptions)
		}

	@Test
	fun `typed provider failure compensates the exact active attempt`() = runTest {
		val broker = mockk<SourceBroker>()
		val controller = mockk<SharedCellSourceController>()
		val lease = lease(AmbientTrackingSource.CELL)
		val demand = ambientDemand(SourceKind.CELL, "cell-exact-demand")
		val activeAuthority = authority(SourceKind.CELL)
		val revokedAuthority = activeAuthority.copy(authorityRevision = 8L)
		coEvery {
			broker.withAmbientRadioMutationLease(
				lease.identity,
				any<suspend () -> AmbientCellDemandReconciliation>(),
			)
		} coAnswers {
			AmbientRadioLeaseMutation.Applied(
				secondArg<suspend () -> AmbientCellDemandReconciliation>().invoke(),
			)
		}
		coEvery {
			broker.replaceAmbientCellDemandUnderHeldLease(
				any(), true, lease.identity, 1L, any(), any(), any(),
			)
		} returns AmbientRadioDemandResult.Active(demand, 7L, activeAuthority)
		coEvery { controller.reconcileAmbientJoin() } returnsMany listOf(
			AmbientCellRuntimeJoinResult.Unavailable(null, emptySet(), retryable = true),
			AmbientCellRuntimeJoinResult.Inactive(providerKey = null),
		)
		coEvery {
			broker.compensateAmbientCellDemandUnderHeldLease(
				any(), lease.identity, 1L, demand.demandId, any(), any(), any(),
			)
		} returns revokedAuthority
		val subject = AmbientCellDemandReconciler(
			broker,
			controller,
			BootClockDomainProvider { "boot-1" },
		)

		val result = subject.reconcile(lease, AmbientCellActivationRequest(enabled = true))

		assertEquals(
			revokedAuthority,
			assertIs<AmbientCellDemandReconciliation.Unavailable>(result.outcome)
				.reconciliationAuthority,
		)
		coVerify(exactly = 1) {
			broker.compensateAmbientCellDemandUnderHeldLease(
				any(), lease.identity, 1L, demand.demandId, any(), any(), any(),
			)
		}
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
							operationalIdentity = lease.purposeLeaseIdentity,
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
					operationalIdentity = currentLease.purposeLeaseIdentity,
				),
			),
		)
	}

	@Test
	fun `late ready from an older retained floor is rejected before publication`() {
		val currentLease = lease(AmbientTrackingSource.WIFI, retainedFromMs = 200L)
		val staleAuthority = authority(SourceKind.WIFI, retainedFromMs = 100L)
		val evidence = AmbientRadioReconciliationEvidence.from(
			authority = staleAuthority,
			reconciliationAttempt = 1L,
			demandId = "wifi-demand",
			sourceInstanceId = SourceInstanceId("wifi-1"),
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
					source = AmbientTrackingSource.WIFI,
					state = AmbientSourceOperationalState.READY,
					mechanism = AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
					operationalIdentity = currentLease.purposeLeaseIdentity,
				),
			),
		)
	}

	private fun authority(
		source: SourceKind,
		authorityRevision: Long = 7L,
		ownerCasToken: String = "owner-cas-1",
		reconciliationAttempt: Long = 1L,
		retainedFromMs: Long = 100L,
	) = AmbientRadioReconciliationAuthority(
		source = source,
		policyRevision = 10L,
		ambientConsentEpoch = 3L,
		collectedDataEpoch = 2L,
		retainedFromMs = retainedFromMs,
		rolloutRevision = 4L,
		executionGeneration = 1L,
		authorityRevision = authorityRevision,
		ownerCasToken = ownerCasToken,
		reconciliationAttempt = reconciliationAttempt,
	)

	private fun lease(
		source: AmbientTrackingSource,
		retainedFromMs: Long = 100L,
	) = AmbientReconciliationLease(
		AmbientReconciliationIdentity(
			source = source,
			policyRevision = 10L,
			consentEpoch = 3L,
			collectedDataEpoch = 2L,
			rolloutRevision = 4L,
			ownerCasToken = "owner-cas-1",
			executionRevision = 1L,
			retainedFromMs = retainedFromMs,
		),
	)

	private fun ambientDemand(source: SourceKind, demandId: String) = SourceDemandEntity(
		demandId = demandId,
		consumerId = "app:ambient:${source.name.lowercase()}",
		sourceKind = source.stableCode,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 10L,
		consentEpoch = 3L,
		persistenceEligible = true,
		qosCode = 0,
		minimumAcquisitionSpec = if (source == SourceKind.WIFI) {
			"wifi:v1:broadcast"
		} else {
			"cell:v1:callback"
		},
		adaptiveReductionAllowed = true,
		maximumAgeMs = 60_000L,
		desiredLatencyMs = Long.MAX_VALUE,
		requestedDeliveryLatencyMs = null,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1L,
		requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)
}
