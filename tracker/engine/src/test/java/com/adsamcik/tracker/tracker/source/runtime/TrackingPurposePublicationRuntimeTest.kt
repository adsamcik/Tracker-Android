package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigurationApprovalResult
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationCallback
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationReport
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AtomicTrackingPurposeAvailabilityStore
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityRevision
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReconciliationEvidence
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReportPreparation
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiActivationRequest
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandReconciler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.firstArg
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import javax.inject.Provider

@Suppress("LargeClass", "LongMethod")
class TrackingPurposePublicationRuntimeTest {
	@Test
	fun `current projection invalidates ready on lifecycle or rollout change without params emission`() =
		runTest {
			val store = AtomicTrackingPurposeAvailabilityStore()
			val identity = TrackingPurposeLeaseIdentity(
				source = TrackingSource.ACTIVITY,
				purpose = TrackingPurpose.CONTROL,
				policyRevision = 12L,
				consentEpoch = 5L,
				collectedDataEpoch = 3L,
				rolloutRevision = 9L,
				executionRevision = 1L,
				ownerCasToken = "ready-d3-r9",
			)
			store.beginOrReplaceAutomaticControlLease(identity)
			store.tryAccept(
				com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationReport(
					identity,
					AutomaticTrackingOperationalAvailability.Ready(identity),
				),
			)
			val policy = MutableSourcePolicyRepository(
				policySnapshot(revision = 12L, controlPersistenceEligible = true),
			)
			val lifecycle = MutableLifecycleStore(CollectedDataLifecycleSnapshot(3L, null))
			val rollout = MutableRolloutStateStore(controlRollout(revision = 9L))
			val executions = TrackingPurposeExecutionRevisionRegistry().also { registry ->
				registry.update(identity.sourcePurpose, identity.executionRevision)
			}
			val exactAuthorityReader = CurrentTrackingPurposeAuthorityReader(
				policy,
				lifecycle,
				rollout,
				AlwaysApprovedRetentionAuthorityProducer,
			)
			val projection = CurrentTrackingPurposeAvailabilityProjection(
				publishedReader = store,
				sourcePolicyRepository = policy,
				collectedDataLifecycleStore = lifecycle,
				rolloutStateStore = rollout,
				executionRevisionRegistry = executions,
				exactAuthorityReader = exactAuthorityReader,
				applicationScope = backgroundScope,
			)
			runCurrent()
			projection.availability.value.automaticControl shouldBe
				AutomaticTrackingOperationalAvailability.Ready(identity)
			projection.isCurrent(identity) shouldBe true

			lifecycle.update(CollectedDataLifecycleSnapshot(3L, 100L))
			runCurrent()

			projection.authorityRevision.value shouldBe TrackingPurposeAuthorityRevision(
				policyRevision = 12L,
				collectedDataEpoch = 3L,
				rolloutRevision = 9L,
				retainedFromMs = 100L,
			)
			projection.availability.value.automaticControl shouldBe
				AutomaticTrackingOperationalAvailability.Unavailable(
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
					identity,
				)
			projection.isCurrent(identity) shouldBe false

			lifecycle.update(CollectedDataLifecycleSnapshot(4L, null))
			runCurrent()

			projection.authorityRevision.value shouldBe TrackingPurposeAuthorityRevision(
				policyRevision = 12L,
				collectedDataEpoch = 4L,
				rolloutRevision = 9L,
			)
			projection.availability.value.automaticControl shouldBe
				AutomaticTrackingOperationalAvailability.Unavailable(
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
					identity,
				)
			projection.isCurrent(identity) shouldBe false

			lifecycle.update(CollectedDataLifecycleSnapshot(3L, null))
			rollout.update(controlRollout(revision = 10L))
			runCurrent()

			projection.authorityRevision.value shouldBe TrackingPurposeAuthorityRevision(
				policyRevision = 12L,
				collectedDataEpoch = 3L,
				rolloutRevision = 10L,
			)
			projection.availability.value.automaticControl shouldBe
				AutomaticTrackingOperationalAvailability.Unavailable(
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
					identity,
				)

			policy.update(
				policySnapshot(
					revision = 13L,
					controlPersistenceEligible = true,
					controlConsentEpoch = 6L,
				),
			)
			runCurrent()

			projection.authorityRevision.value shouldBe TrackingPurposeAuthorityRevision(
				policyRevision = 13L,
				collectedDataEpoch = 3L,
				rolloutRevision = 10L,
			)
			projection.availability.value.automaticControl shouldBe
				AutomaticTrackingOperationalAvailability.Unavailable(
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
					identity,
				)
		}

	@Test
	fun `authority reader publishes exact stable revisions and masks unapproved control execution`() =
		runTest {
			val policy = policySnapshot(revision = 12L)
			val lifecycle = MutableLifecycleStore(CollectedDataLifecycleSnapshot(3L, null))
			val rollout = TrackingRolloutState.eventCanonical(
				sources = setOf(com.adsamcik.tracker.tracker.source.model.SourceKind.STEPS),
				controlSources =
					setOf(com.adsamcik.tracker.tracker.source.model.SourceKind.ACTIVITY),
				captureModes = mapOf(
					com.adsamcik.tracker.tracker.source.model.SourceKind.STEPS to
						setOf(CaptureReachabilityMode.AMBIENT),
				),
				revision = 9L,
			)
			val reader = CurrentTrackingPurposeAuthorityReader(
				FixedSourcePolicyRepository(policy),
				lifecycle,
				FixedRolloutStateStore(rollout),
				AlwaysApprovedRetentionAuthorityProducer,
			)

			reader.read(
				TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
				registeredExecutionRevision = 7L,
			) shouldBe authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 7L,
			)
			reader.read(
				TrackingSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL),
				registeredExecutionRevision = 8L,
			) shouldBe authority(
				source = TrackingSource.ACTIVITY,
				purpose = TrackingPurpose.CONTROL,
				executionRevision = 0L,
			)
		}

	@Test
	fun `authority reader rejects an equal double read across different retained grants`() = runTest {
		val reader = CurrentTrackingPurposeAuthorityReader(
			FixedSourcePolicyRepository(policySnapshot(revision = 12L)),
			SequencedLifecycleStore(
				listOf(
					CollectedDataLifecycleSnapshot(3L, 100L),
					CollectedDataLifecycleSnapshot(3L, 200L),
					CollectedDataLifecycleSnapshot(3L, 200L),
					CollectedDataLifecycleSnapshot(3L, 200L),
				),
			),
			FixedRolloutStateStore(controlRollout(revision = 9L)),
			AlwaysApprovedRetentionAuthorityProducer,
		)

		reader.read(
			TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
			registeredExecutionRevision = 7L,
		)?.retainedFromMs shouldBe 200L
	}

	@Test
	fun `automatic containment callback publishes exact identity without readiness`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.ACTIVITY,
				purpose = TrackingPurpose.CONTROL,
				executionRevision = 0L,
			),
		)
		var callbackIdentity: TrackingPurposeLeaseIdentity? = null

		fixture.runtime.registerAutomaticControlOwner(executionRevision = 0L) { lease ->
			callbackIdentity = lease.identity
			AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				lease.identity,
			)
		}

		val unavailable = fixture.store.availability.value.automaticControl as
			AutomaticTrackingOperationalAvailability.Unavailable
		unavailable.reason shouldBe
			AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE
		val identity = requireNotNull(unavailable.lastIdentity)
		identity shouldBe callbackIdentity
		identity.policyRevision shouldBe 12L
		identity.consentEpoch shouldBe 5L
		identity.collectedDataEpoch shouldBe 3L
		identity.rolloutRevision shouldBe 9L
		identity.executionRevision shouldBe 0L
		identity.ownerCasToken shouldBe "owner-1"
	}

	@Test
	fun `unapproved retention never issues an ambient owner lease`() = runTest {
		val policy = policySnapshot(revision = 12L)
		val lifecycle = MutableLifecycleStore(CollectedDataLifecycleSnapshot(3L, null))
		val reader = CurrentTrackingPurposeAuthorityReader(
			FixedSourcePolicyRepository(policy),
			lifecycle,
			FixedRolloutStateStore(controlRollout(revision = 9L)),
			UnavailableRetentionAuthorityProducerForTest,
		)
		val store = AtomicTrackingPurposeAvailabilityStore()
		val issuer = SerializedTrackingPurposeLeaseIssuer(
			authorityReader = reader,
			reporter = store,
			tokenFactory = TrackingPurposeOwnerCasTokenFactory { "owner-denied" },
		)
		val runtime = DefaultTrackingPurposePublicationRuntime(
			issuer,
			store,
			TrackingPurposeExecutionRevisionRegistry(),
			UnavailableRetentionAuthorityProducerForTest,
		)
		var callbackCount = 0

		runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
		) { lease ->
			callbackCount++
			AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				lease.purposeLeaseIdentity,
			)
		}

		callbackCount shouldBe 0
		store.availability.value.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe
			AmbientSourceOperationalAvailability.unavailable(
				AmbientTrackingSource.STEPS,
				AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
			)
	}

	@Test
	fun `lifecycle rotation replaces lease before stale callback and reissues ready`() = runTest {
		val store = AtomicTrackingPurposeAvailabilityStore()
		var authority = authority(
			source = TrackingSource.ACTIVITY,
			purpose = TrackingPurpose.CONTROL,
			executionRevision = 1L,
		)
		val issuer = SerializedTrackingPurposeLeaseIssuer(
			authorityReader = TrackingPurposeAuthorityReader { _, registeredExecution ->
				authority.copy(executionRevision = registeredExecution)
			},
			reporter = store,
			tokenFactory = object : TrackingPurposeOwnerCasTokenFactory {
				private var token = 0
				override fun next(
					_sourcePurpose: TrackingSourcePurposeIdentity,
				): String = "rotation-${++token}"
			},
		)
		val runtime = DefaultTrackingPurposePublicationRuntime(
			issuer,
			store,
			TrackingPurposeExecutionRevisionRegistry(),
			UnavailableRetentionAuthorityProducerForTest,
		)
		val secondLease = CompletableDeferred<TrackingPurposeLeaseIdentity>()
		val releaseSecond = CompletableDeferred<Unit>()
		var oldIdentity: TrackingPurposeLeaseIdentity? = null
		runtime.registerAutomaticControlOwner(executionRevision = 1L) { lease ->
			if (lease.identity.collectedDataEpoch == 3L) {
				oldIdentity = lease.identity
			} else {
				secondLease.complete(lease.identity)
				releaseSecond.await()
			}
			AutomaticTrackingOperationalAvailability.Ready(lease.identity)
		}
		val publishedIdentity = requireNotNull(oldIdentity)
		store.availability.value.automaticControl shouldBe
			AutomaticTrackingOperationalAvailability.Ready(publishedIdentity)

		authority = authority.copy(
			collectedDataEpoch = 4L,
			rolloutRevision = 10L,
		)
		val rotation = async { runtime.reconcileCurrentSettings() }
		val reissuedIdentity = secondLease.await()

		store.availability.value.automaticControl shouldBe
			AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				publishedIdentity,
			)
		store.tryAccept(
			com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationReport(
				publishedIdentity,
				AutomaticTrackingOperationalAvailability.Ready(publishedIdentity),
			),
		) shouldBe
			com.adsamcik.tracker.tracker.api.AutomaticControlPublicationAcceptance.Rejected(
				com.adsamcik.tracker.tracker.api.TrackingPurposePublicationRejection
					.STALE_IDENTITY,
			)

		releaseSecond.complete(Unit)
		rotation.await()
		store.availability.value.automaticControl shouldBe
			AutomaticTrackingOperationalAvailability.Ready(reissuedIdentity)
		reissuedIdentity.collectedDataEpoch shouldBe 4L
		reissuedIdentity.rolloutRevision shouldBe 10L
	}

	@Test
	fun `floor only rotation replaces the published lease identity`() = runTest {
		val store = AtomicTrackingPurposeAvailabilityStore()
		var authority = authority(
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			executionRevision = 7L,
			retainedFromMs = 100L,
		)
		val runtime = DefaultTrackingPurposePublicationRuntime(
			SerializedTrackingPurposeLeaseIssuer(
				authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, execution ->
					authority.takeIf { it.sourcePurpose == sourcePurpose }
						?.copy(executionRevision = execution)
				},
				reporter = store,
				tokenFactory = TrackingPurposeOwnerCasTokenFactory { "floor-${++token}" },
			),
			store,
			TrackingPurposeExecutionRevisionRegistry(),
			AlwaysApprovedRetentionAuthorityProducer,
		)
		val identities = mutableListOf<TrackingPurposeLeaseIdentity>()
		runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
		) { lease ->
			identities += lease.purposeLeaseIdentity
			AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				lease.purposeLeaseIdentity,
			)
		}

		authority = authority.copy(retainedFromMs = 200L)
		runtime.reconcileCurrentSettings()

		identities.map(TrackingPurposeLeaseIdentity::retainedFromMs) shouldBe
			listOf(100L, 200L)
		identities[0].ownerCasToken shouldBe "floor-1"
		identities[1].ownerCasToken shouldBe "floor-2"
		store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS)
			.operationalIdentity shouldBe identities[1]
	}

	@Test
	fun `retention floor reconciliation retires a previously owned Steps source`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 7L,
				retainedFromMs = 100L,
			),
		)
		var callbackCount = 0
		var retirementCount = 0
		fixture.runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
			callback = object : AmbientSourceReconciliationCallback {
				override suspend fun reconcile(
					lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
				): AmbientSourceOperationalAvailability {
					callbackCount += 1
					return AmbientSourceOperationalAvailability.ready(
						AmbientTrackingSource.STEPS,
						AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
						lease.purposeLeaseIdentity,
					)
				}

				override suspend fun retireAfterRetentionAuthorityFailure(
					previousLease:
						com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
				): Boolean {
					previousLease?.identity?.source shouldBe AmbientTrackingSource.STEPS
					retirementCount += 1
					return true
				}
			},
		)
		callbackCount = 0

		fixture.runtime.reconcile(
			expectedStartupGeneration = 0L,
			retainedFromMs = 200L,
			approvedSources = emptySet(),
		) shouldBe com.adsamcik.tracker.tracker.api
			.TrackingRetentionFloorReconciliationResult.Complete(200L, emptySet())
		callbackCount shouldBe 0
		retirementCount shouldBe 1
		fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).reason shouldBe
			AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE
	}

	@Test
	fun `completed provider reconciliation retries with a fresh one shot token`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 7L,
				retainedFromMs = 200L,
			),
		)
		val tokens = mutableListOf<String>()
		fixture.runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
		) { lease ->
			tokens += lease.purposeLeaseIdentity.ownerCasToken
			AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
				lease.purposeLeaseIdentity,
			)
		}

		fixture.runtime.reconcile(
			expectedStartupGeneration = 0L,
			retainedFromMs = 200L,
			approvedSources = setOf(AmbientTrackingSource.STEPS),
		) shouldBe com.adsamcik.tracker.tracker.api
			.TrackingRetentionFloorReconciliationResult.Complete(
				200L,
				setOf(AmbientTrackingSource.STEPS),
			)
		tokens shouldBe listOf("owner-1", "owner-2")
	}

	@Test
	fun `built in Wi-Fi owner starts only for an approved floor source`() = runTest {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val authority = authority(
			source = TrackingSource.WIFI,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			executionRevision = 1L,
			retainedFromMs = 200L,
		)
		val wifi = mockk<AmbientWifiDemandReconciler>()
		coEvery { wifi.retireAfterRetentionAuthorityFailure(null) } returns true
		coEvery {
			wifi.reconcilePurposeAvailability(
				any(),
				AmbientWifiActivationRequest(enabled = true),
			)
		} coAnswers {
			val lease =
				firstArg<com.adsamcik.tracker.tracker.api.AmbientReconciliationLease>()
			val availability = AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.WIFI,
				AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
				lease.purposeLeaseIdentity,
			)
			AmbientRadioReportPreparation.Prepared(
				AmbientSourceReconciliationResult.Reconciled(
					AmbientSourceReconciliationReport(lease.identity, availability),
				),
				AmbientRadioReconciliationEvidence(
					source = AmbientTrackingSource.WIFI,
					policyRevision = lease.identity.policyRevision,
					ambientConsentEpoch = lease.identity.consentEpoch,
					collectedDataEpoch = lease.identity.collectedDataEpoch,
					retainedFromMs = lease.identity.retainedFromMs,
					rolloutRevision = lease.identity.rolloutRevision,
					executionGeneration = lease.identity.executionRevision,
					authorityRevision = 1L,
					ownerCasToken = lease.identity.ownerCasToken,
					reconciliationAttempt = 1L,
					authorityReconciliationAttempt = 1L,
					demandId = "wifi-demand",
					providerKey = null,
				),
			)
		}
		val runtime = DefaultTrackingPurposePublicationRuntime(
			SerializedTrackingPurposeLeaseIssuer(
				authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, execution ->
					authority.takeIf { it.sourcePurpose == sourcePurpose }
						?.copy(executionRevision = execution)
				},
				reporter = store,
				tokenFactory = TrackingPurposeOwnerCasTokenFactory { "wifi-owner" },
			),
			store,
			TrackingPurposeExecutionRevisionRegistry(),
			AlwaysApprovedRetentionAuthorityProducer,
			trackingStartupGateProvider = null,
			ambientWifiDemandReconcilerProvider = Provider { wifi },
			ambientCellDemandReconcilerProvider = null,
		)

		runtime.reconcile(0L, 200L, emptySet())
		coVerify(exactly = 1) { wifi.retireAfterRetentionAuthorityFailure(null) }

		runtime.reconcile(
			expectedStartupGeneration = 0L,
			retainedFromMs = 200L,
			approvedSources = setOf(AmbientTrackingSource.WIFI),
		) shouldBe com.adsamcik.tracker.tracker.api
			.TrackingRetentionFloorReconciliationResult.Complete(
				200L,
				setOf(AmbientTrackingSource.WIFI),
			)
		coVerify(exactly = 1) {
			wifi.reconcilePurposeAvailability(
				any(),
				AmbientWifiActivationRequest(enabled = true),
			)
		}
	}

	@Test
	fun `retention floor fails closed when the required Steps purpose owner is missing`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 1L,
				retainedFromMs = 200L,
			),
		)

		val result = fixtureResult(
			fixture.runtime.reconcile(
				expectedStartupGeneration = 0L,
				retainedFromMs = 200L,
				approvedSources = setOf(AmbientTrackingSource.STEPS),
			),
		)

		result.debt.failures.single().reason shouldBe
			com.adsamcik.tracker.tracker.api
				.TrackingRetentionFloorReconciliationFailureReason.OWNER_MISSING
		fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).isOperational shouldBe false
	}

	@Test
	fun `built in Steps owner publishes the exact retained floor lease`() = runTest {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val authority = authority(
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			executionRevision = 1L,
			retainedFromMs = 200L,
		)
		var received: TrackingPurposeLeaseIdentity? = null
		val owner = object : AmbientStepsPurposeOwner {
			override suspend fun reconcile(
				lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
			): AmbientSourceOperationalAvailability {
				received = lease.purposeLeaseIdentity
				return AmbientSourceOperationalAvailability.ready(
					AmbientTrackingSource.STEPS,
					AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
					lease.purposeLeaseIdentity,
				)
			}

			override suspend fun retireAfterRetentionAuthorityFailure(
				previousLease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
			): Boolean = true
		}
		val runtime = DefaultTrackingPurposePublicationRuntime(
			leaseIssuer = SerializedTrackingPurposeLeaseIssuer(
				authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, execution ->
					authority.takeIf { it.sourcePurpose == sourcePurpose }
						?.copy(executionRevision = execution)
				},
				reporter = store,
				tokenFactory = TrackingPurposeOwnerCasTokenFactory { "steps-owner" },
			),
			reporter = store,
			executionRevisionRegistry = TrackingPurposeExecutionRevisionRegistry(),
			retentionAuthorityProducer = AlwaysApprovedRetentionAuthorityProducer,
			ambientStepsPurposeOwnerProvider = Provider { owner },
		)

		runtime.reconcile(
			expectedStartupGeneration = 0L,
			retainedFromMs = 200L,
			approvedSources = setOf(AmbientTrackingSource.STEPS),
		) shouldBe com.adsamcik.tracker.tracker.api
			.TrackingRetentionFloorReconciliationResult.Complete(
				200L,
				setOf(AmbientTrackingSource.STEPS),
			)

		received?.retainedFromMs shouldBe 200L
		received?.ownerCasToken shouldBe "steps-owner"
		store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).operationalIdentity shouldBe received
	}

	@Test
	fun `ambient owner receives exact lease and publishes status only`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 7L,
			),
		)
		var callbackIdentity: TrackingPurposeLeaseIdentity? = null

		fixture.runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
		) { lease ->
			callbackIdentity = lease.purposeLeaseIdentity
			AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				lease.purposeLeaseIdentity,
			)
		}

		callbackIdentity?.policyRevision shouldBe 12L
		callbackIdentity?.consentEpoch shouldBe 5L
		callbackIdentity?.collectedDataEpoch shouldBe 3L
		callbackIdentity?.rolloutRevision shouldBe 9L
		callbackIdentity?.executionRevision shouldBe 7L
		callbackIdentity?.ownerCasToken shouldBe "owner-1"
		fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS) shouldBe
			AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				requireNotNull(callbackIdentity),
			)
	}

	@Test
	fun `owner replacement waits then retires the exact prior lease`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 4L,
			),
		)
		val firstLeaseReceived = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		var secondIdentity: TrackingPurposeLeaseIdentity? = null
		var compensatedIdentity: TrackingPurposeLeaseIdentity? = null
		val firstRegistration = async {
			fixture.runtime.registerAmbientSourceOwner(
				AmbientTrackingSource.STEPS,
				executionRevision = 4L,
				callback = object : AmbientSourceReconciliationCallback {
					override suspend fun reconcile(
						lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
					): AmbientSourceOperationalAvailability {
						firstLeaseReceived.complete(Unit)
						releaseFirst.await()
						return AmbientSourceOperationalAvailability.ready(
							AmbientTrackingSource.STEPS,
							AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
							lease.purposeLeaseIdentity,
						)
					}

					override suspend fun compensate(
						lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
					): Boolean {
						compensatedIdentity = lease.purposeLeaseIdentity
						return true
					}
				},
			)
		}
		firstLeaseReceived.await()

		val secondRegistration = async {
			fixture.runtime.registerAmbientSourceOwner(
				AmbientTrackingSource.STEPS,
				executionRevision = 4L,
			) {
				secondIdentity = it.purposeLeaseIdentity
				AmbientSourceOperationalAvailability.unavailable(
					AmbientTrackingSource.STEPS,
					AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
					it.purposeLeaseIdentity,
				)
			}
		}
		runCurrent()
		secondIdentity shouldBe null
		releaseFirst.complete(Unit)
		firstRegistration.await()
		secondRegistration.await()

		val published = fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS)
		published.state shouldBe AmbientSourceOperationalState.UNAVAILABLE
		published.reason shouldBe AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
		published.lastIdentity shouldBe secondIdentity
		secondIdentity?.ownerCasToken shouldBe "owner-2"
		compensatedIdentity?.ownerCasToken shouldBe "owner-1"
	}

	@Test
	fun `overlapping reconciliation coalesces one exact lease and one owner callback`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 4L,
				retainedFromMs = 200L,
			),
		)
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var calls = 0
		fixture.runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 4L,
		) { lease ->
			calls += 1
			if (calls == 2) {
				entered.complete(Unit)
				release.await()
			}
			AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
				lease.purposeLeaseIdentity,
			)
		}
		val first = async {
			fixture.runtime.reconcile(
				expectedStartupGeneration = 7L,
				retainedFromMs = 200L,
				approvedSources = setOf(AmbientTrackingSource.STEPS),
			)
		}
		entered.await()
		val second = async {
			fixture.runtime.reconcile(
				expectedStartupGeneration = 7L,
				retainedFromMs = 200L,
				approvedSources = setOf(AmbientTrackingSource.STEPS),
			)
		}
		runCurrent()

		calls shouldBe 2
		release.complete(Unit)
		second.await() shouldBe first.await()
		calls shouldBe 2
	}

	@Test
	fun `close before tryAccept compensates and rejects stale Ready publication`() = runTest {
		val gate = RejectNextPublicationGate(currentGeneration = 7L, ready = false)
		val store = AtomicTrackingPurposeAvailabilityStore()
		val authority = authority(
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			executionRevision = 7L,
			retainedFromMs = 200L,
		)
		val runtime = DefaultTrackingPurposePublicationRuntime(
			SerializedTrackingPurposeLeaseIssuer(
				authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, execution ->
					authority.takeIf { it.sourcePurpose == sourcePurpose }
						?.copy(executionRevision = execution)
				},
				reporter = store,
				tokenFactory = TrackingPurposeOwnerCasTokenFactory { "stale-ready" },
			),
			store,
			TrackingPurposeExecutionRevisionRegistry(),
			AlwaysApprovedRetentionAuthorityProducer,
			trackingStartupGateProvider = Provider { gate },
		)
		var compensated = false
		runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
			callback = object : AmbientSourceReconciliationCallback {
				override suspend fun reconcile(
					lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
				): AmbientSourceOperationalAvailability {
					return AmbientSourceOperationalAvailability.ready(
						AmbientTrackingSource.STEPS,
						AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
						lease.purposeLeaseIdentity,
					)
				}

				override suspend fun compensate(
					lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
				): Boolean {
					compensated = true
					return true
				}
			},
		)
		gate.ready = true
		gate.rejectNextPublication = true

		val result = fixtureResult(
			runtime.reconcile(
				expectedStartupGeneration = 7L,
				retainedFromMs = 200L,
				approvedSources = setOf(AmbientTrackingSource.STEPS),
			),
		)

		result.debt.failures.single().reason shouldBe
			com.adsamcik.tracker.tracker.api
				.TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED
		compensated shouldBe true
		store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).isOperational shouldBe false
	}

	@Test
	fun `close after tryAccept retires the accepted lease before Complete publication`() = runTest {
		val gate = CloseAfterPublicationGate(currentGeneration = 7L)
		val store = AtomicTrackingPurposeAvailabilityStore()
		val authority = authority(
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			executionRevision = 7L,
			retainedFromMs = 200L,
		)
		val runtime = DefaultTrackingPurposePublicationRuntime(
			leaseIssuer = SerializedTrackingPurposeLeaseIssuer(
				authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, execution ->
					authority.takeIf { it.sourcePurpose == sourcePurpose }
						?.copy(executionRevision = execution)
				},
				reporter = store,
				tokenFactory = TrackingPurposeOwnerCasTokenFactory { "accepted-before-close" },
			),
			reporter = store,
			executionRevisionRegistry = TrackingPurposeExecutionRevisionRegistry(),
			retentionAuthorityProducer = AlwaysApprovedRetentionAuthorityProducer,
			trackingStartupGateProvider = Provider { gate },
		)
		var retired = false
		runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
			callback = object : AmbientSourceReconciliationCallback {
				override suspend fun reconcile(
					lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
				): AmbientSourceOperationalAvailability =
					AmbientSourceOperationalAvailability.ready(
						AmbientTrackingSource.STEPS,
						AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
						lease.purposeLeaseIdentity,
					)

				override suspend fun compensate(
					lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
				): Boolean {
					retired = true
					return true
				}
			},
		)
		gate.closeAfterNextPublication = true

		val result = fixtureResult(
			runtime.reconcile(
				expectedStartupGeneration = 7L,
				retainedFromMs = 200L,
				approvedSources = setOf(AmbientTrackingSource.STEPS),
			),
		)

		result.debt.failures.single().reason shouldBe
			com.adsamcik.tracker.tracker.api
				.TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED
		retired shouldBe true
		store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).isOperational shouldBe false
	}

	@Test
	fun `hung compensation is bounded and surfaced as retry debt`() = runTest {
		val gate = MutableReadyGate(currentGeneration = 7L, ready = false)
		val store = AtomicTrackingPurposeAvailabilityStore()
		val authority = authority(
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			executionRevision = 7L,
			retainedFromMs = 200L,
		)
		val runtime = DefaultTrackingPurposePublicationRuntime(
			leaseIssuer = SerializedTrackingPurposeLeaseIssuer(
				authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, execution ->
					authority.takeIf { it.sourcePurpose == sourcePurpose }
						?.copy(executionRevision = execution)
				},
				reporter = store,
				tokenFactory = TrackingPurposeOwnerCasTokenFactory { "bounded-compensation" },
			),
			reporter = store,
			executionRevisionRegistry = TrackingPurposeExecutionRevisionRegistry(),
			retentionAuthorityProducer = AlwaysApprovedRetentionAuthorityProducer,
			trackingStartupGateProvider = Provider { gate },
			ownerCallbackScope = backgroundScope,
			ownerCallbackTimeoutMillis = 10L,
		)
		runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
			callback = object : AmbientSourceReconciliationCallback {
				override suspend fun reconcile(
					lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
				): AmbientSourceOperationalAvailability =
					AmbientSourceOperationalAvailability.unavailable(
						AmbientTrackingSource.WIFI,
						AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
					)

				override suspend fun compensate(
					lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
				): Boolean = awaitCancellation()
			},
		)
		gate.ready = true

		val result = fixtureResult(
			runtime.reconcile(
				expectedStartupGeneration = 7L,
				retainedFromMs = 200L,
				approvedSources = setOf(AmbientTrackingSource.STEPS),
			),
		)

		result.debt.failures.single().reason shouldBe
			com.adsamcik.tracker.tracker.api
				.TrackingRetentionFloorReconciliationFailureReason.COMPENSATION_TIMED_OUT
		store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).isOperational shouldBe false
	}

	@Test
	fun `timed out owner callback remains owned and retry accepts its exact late result`() = runTest {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val authority = authority(
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			executionRevision = 7L,
			retainedFromMs = 200L,
		)
		var token = 0
		val runtime = DefaultTrackingPurposePublicationRuntime(
			leaseIssuer = SerializedTrackingPurposeLeaseIssuer(
				authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, execution ->
					authority.takeIf { it.sourcePurpose == sourcePurpose }
						?.copy(executionRevision = execution)
				},
				reporter = store,
				tokenFactory = TrackingPurposeOwnerCasTokenFactory { "late-owner-result-${++token}" },
			),
			reporter = store,
			executionRevisionRegistry = TrackingPurposeExecutionRevisionRegistry(),
			retentionAuthorityProducer = AlwaysApprovedRetentionAuthorityProducer,
			ownerCallbackScope = backgroundScope,
			ownerCallbackTimeoutMillis = 10L,
		)
		val release = CompletableDeferred<Unit>()
		var calls = 0
		runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 7L,
		) { lease ->
			calls += 1
			if (calls == 2) release.await()
			AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
				lease.purposeLeaseIdentity,
			)
		}

		val timedOut = fixtureResult(
			runtime.reconcile(
				expectedStartupGeneration = 7L,
				retainedFromMs = 200L,
				approvedSources = setOf(AmbientTrackingSource.STEPS),
			),
		)
		timedOut.debt.failures.single().reason shouldBe
			com.adsamcik.tracker.tracker.api
				.TrackingRetentionFloorReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS

		release.complete(Unit)
		runCurrent()
		runtime.reconcile(
			expectedStartupGeneration = 7L,
			retainedFromMs = 200L,
			approvedSources = setOf(AmbientTrackingSource.STEPS),
		).shouldBeInstanceOf<
			com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult.Complete
		>()
		calls shouldBe 2
	}

	@Test
	fun `source mismatch cannot publish another ambient owners status`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 4L,
			),
		)

		fixture.runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 4L,
		) {
			AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.WIFI,
				state = AmbientSourceOperationalState.UNAVAILABLE,
				reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			)
		}

		fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS) shouldBe
			AmbientSourceOperationalAvailability.reconciliationPending(
				AmbientTrackingSource.STEPS,
			)
	}

	@Test
	fun `callback failure stays pending and a later settings signal can reconcile`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 4L,
			),
		)
		var attempts = 0
		fixture.runtime.registerAmbientSourceOwner(
			AmbientTrackingSource.STEPS,
			executionRevision = 4L,
		) {
			attempts++
			if (attempts == 1) error("status unavailable")
			AmbientSourceOperationalAvailability.unavailable(
				AmbientTrackingSource.STEPS,
				AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
				it.purposeLeaseIdentity,
			)
		}
		fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).state shouldBe
			AmbientSourceOperationalState.WAITING

		fixture.runtime.reconcileCurrentSettings()

		attempts shouldBe 2
		fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS).reason shouldBe
			AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
	}

	@Test
	fun `ambient sources publish provider unavailable when no owner is registered`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.LOCATION,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 0L,
			),
		)

		fixture.runtime.reconcileCurrentSettings()

		fixture.store.availability.value.ambientSources.values.forEach { availability ->
			availability.state shouldBe AmbientSourceOperationalState.UNAVAILABLE
			availability.reason shouldBe AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
			availability.isOperational shouldBe false
		}
	}

	@Test
	fun `settings reconciliation aggregates all owner debt before persisting retry ownership`() =
		runTest {
			val scheduled = mutableListOf<TrackingPurposeSettingsReconciliationDebt>()
			val fixture = fixtureWithScheduler(
				TrackingPurposeReconciliationRetryScheduler { debt ->
					scheduled += debt
					true
				},
				authority(
					source = TrackingSource.LOCATION,
					purpose = TrackingPurpose.AMBIENT_PRODUCT,
					executionRevision = 0L,
				),
			)

			val result = fixture.runtime.reconcileCurrentSettings()
				.shouldBeInstanceOf<TrackingPurposeSettingsReconciliationResult.Debt>()

			result.debt.failures.mapNotNull { it.source }.toSet() shouldBe
				AmbientTrackingSource.entries.toSet()
			scheduled shouldBe listOf(result.debt)
		}

	@Test
	fun `collected data deletion fences every previously owned ambient source`() = runTest {
		val fixture = fixture(
			*AmbientTrackingSource.entries
				.filterNot { it == AmbientTrackingSource.LOCATION }
				.map { source ->
					authority(
						source = source.canonicalSource,
						purpose = TrackingPurpose.AMBIENT_PRODUCT,
						executionRevision = 1L,
					)
				}
				.toTypedArray(),
		)
		val closed = mutableSetOf<AmbientTrackingSource>()
		AmbientTrackingSource.entries
			.filterNot { it == AmbientTrackingSource.LOCATION }
			.forEach { source ->
				fixture.runtime.registerAmbientSourceOwner(
					source,
					executionRevision = 1L,
					callback = object : AmbientSourceReconciliationCallback {
						override suspend fun reconcile(
							lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
						) = AmbientSourceOperationalAvailability.unavailable(
							source,
							AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
							lease.purposeLeaseIdentity,
						)

						override suspend fun closeForCollectedDataDeletion(
							previousLease:
								com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
						): Boolean {
							closed += source
							return true
						}
					},
				)
			}

		fixture.runtime.fenceForCollectedDataDeletion() shouldBe
			TrackingPurposeSettingsReconciliationResult.Complete(closed)
		closed shouldBe setOf(
			AmbientTrackingSource.STEPS,
			AmbientTrackingSource.WIFI,
			AmbientTrackingSource.CELL,
		)
	}

	private fun fixture(
		vararg authorities: TrackingPurposeAuthoritySnapshot,
	): Fixture {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val byPurpose = authorities.associateBy(TrackingPurposeAuthoritySnapshot::sourcePurpose)
		var token = 0
		val issuer = SerializedTrackingPurposeLeaseIssuer(
			authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, registeredExecution ->
				byPurpose[sourcePurpose]?.copy(executionRevision = registeredExecution)
			},
			reporter = store,
			tokenFactory = TrackingPurposeOwnerCasTokenFactory {
				"owner-${++token}"
			},
		)
		val executions = TrackingPurposeExecutionRevisionRegistry()
		return Fixture(
			store = store,
			runtime = DefaultTrackingPurposePublicationRuntime(
				issuer,
				store,
				executions,
				AlwaysApprovedRetentionAuthorityProducer,
			),
		)
	}

	private fun fixtureWithScheduler(
		scheduler: TrackingPurposeReconciliationRetryScheduler,
		vararg authorities: TrackingPurposeAuthoritySnapshot,
	): Fixture {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val byPurpose = authorities.associateBy(TrackingPurposeAuthoritySnapshot::sourcePurpose)
		var token = 0
		val issuer = SerializedTrackingPurposeLeaseIssuer(
			authorityReader = TrackingPurposeAuthorityReader { sourcePurpose, registeredExecution ->
				byPurpose[sourcePurpose]?.copy(executionRevision = registeredExecution)
			},
			reporter = store,
			tokenFactory = TrackingPurposeOwnerCasTokenFactory { "owner-${++token}" },
		)
		return Fixture(
			store,
			DefaultTrackingPurposePublicationRuntime(
				leaseIssuer = issuer,
				reporter = store,
				executionRevisionRegistry = TrackingPurposeExecutionRevisionRegistry(),
				retentionAuthorityProducer = AlwaysApprovedRetentionAuthorityProducer,
				retryScheduler = scheduler,
			),
		)
	}

	private fun authority(
		source: TrackingSource,
		purpose: TrackingPurpose,
		executionRevision: Long,
		retainedFromMs: Long? = null,
	) = TrackingPurposeAuthoritySnapshot(
		sourcePurpose = source.forPurpose(purpose),
		policyRevision = 12L,
		consentEpoch = 5L,
		collectedDataEpoch = 3L,
		rolloutRevision = 9L,
		executionRevision = executionRevision,
		retainedFromMs = retainedFromMs,
	)

	private fun policySnapshot(
		revision: Long,
		controlPersistenceEligible: Boolean = false,
		controlConsentEpoch: Long = 5L,
	): SourcePolicySnapshot {
		val effectiveTime = SourcePolicyEffectiveTime("boot", 1L, 1L)
		return SourcePolicySnapshot(
			revision = revision,
			policies = TrackingSource.entries.associateWith { source ->
				SourcePolicy(
					source = source,
					enabled = false,
					qos = SourceQos.OFF,
					locationMinTimeSeconds = 1.takeIf { source == TrackingSource.LOCATION },
					locationMinDistanceMeters = 1.takeIf { source == TrackingSource.LOCATION },
					locationRequiredAccuracyMeters = 1.takeIf {
						source == TrackingSource.LOCATION
					},
					captureConsentEpoch = null,
					controlConsentEpoch =
						controlConsentEpoch.takeIf { source == TrackingSource.ACTIVITY },
					ambientConsentEpoch = 5L.takeIf { source == TrackingSource.STEPS },
					capturePersistenceEligible = false,
					controlPersistenceEligible =
						source == TrackingSource.ACTIVITY && controlPersistenceEligible,
					ambientPersistenceEligible = source == TrackingSource.STEPS,
					effectiveTime = effectiveTime,
					policyRevision = revision,
				)
			},
		)
	}

	private fun controlRollout(revision: Long) = TrackingRolloutState.eventCanonical(
		sources = setOf(com.adsamcik.tracker.tracker.source.model.SourceKind.STEPS),
		controlSources = setOf(com.adsamcik.tracker.tracker.source.model.SourceKind.ACTIVITY),
		captureModes = mapOf(
			com.adsamcik.tracker.tracker.source.model.SourceKind.STEPS to
				setOf(CaptureReachabilityMode.AMBIENT),
		),
		revision = revision,
	)
}

private data class Fixture(
	val store: AtomicTrackingPurposeAvailabilityStore,
	val runtime: DefaultTrackingPurposePublicationRuntime,
)

private class FixedSourcePolicyRepository(
	snapshot: SourcePolicySnapshot,
) : SourcePolicyRepository {
	private val state = SourcePolicyAuthorityState.Active(snapshot)
	override val states: Flow<SourcePolicyAuthorityState> = MutableStateFlow(state)

	override suspend fun currentState(): SourcePolicyAuthorityState = state

	override suspend fun bootstrapFromLegacy(settings: TrackingParamsState): SourcePolicySnapshot =
		error("Not used")

	override suspend fun replaceCaptureSettings(
		expectedPolicyRevision: Long,
		settings: TrackingParamsState,
		reason: String,
	): SourcePolicySnapshot = error("Not used")

	override suspend fun setNonCaptureConsent(
		expectedPolicyRevision: Long,
		source: TrackingSource,
		purpose: SourcePurpose,
		eligible: Boolean,
		persistenceEligible: Boolean,
		reason: String,
	): SourcePolicySnapshot = error("Not used")
}

private class MutableSourcePolicyRepository(
	initial: SourcePolicySnapshot,
) : SourcePolicyRepository {
	private val state = MutableStateFlow<SourcePolicyAuthorityState>(
		SourcePolicyAuthorityState.Active(initial),
	)
	override val states: Flow<SourcePolicyAuthorityState> = state

	override suspend fun currentState(): SourcePolicyAuthorityState = state.value

	override suspend fun bootstrapFromLegacy(settings: TrackingParamsState): SourcePolicySnapshot =
		error("Not used")

	override suspend fun replaceCaptureSettings(
		expectedPolicyRevision: Long,
		settings: TrackingParamsState,
		reason: String,
	): SourcePolicySnapshot = error("Not used")

	override suspend fun setNonCaptureConsent(
		expectedPolicyRevision: Long,
		source: TrackingSource,
		purpose: SourcePurpose,
		eligible: Boolean,
		persistenceEligible: Boolean,
		reason: String,
	): SourcePolicySnapshot = error("Not used")

	fun update(snapshot: SourcePolicySnapshot) {
		state.value = SourcePolicyAuthorityState.Active(snapshot)
	}
}

private class MutableLifecycleStore(
	initial: CollectedDataLifecycleSnapshot,
) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state

	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value

	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		error("Not used")

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot = error("Not used")

	fun update(snapshot: CollectedDataLifecycleSnapshot) {
		state.value = snapshot
	}
}

private class SequencedLifecycleStore(
	private val snapshotsInOrder: List<CollectedDataLifecycleSnapshot>,
) : CollectedDataLifecycleStore {
	private var index = 0
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> =
		MutableStateFlow(snapshotsInOrder.last())

	override suspend fun snapshot(): CollectedDataLifecycleSnapshot =
		snapshotsInOrder[index.coerceAtMost(snapshotsInOrder.lastIndex)].also {
			index += 1
		}

	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		error("Not used")

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot = error("Not used")
}

private class MutableReadyGate(
	override val currentGeneration: Long,
	var ready: Boolean,
) : TrackingStartupGate {
	override val isReady: Boolean
		get() = ready

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		if (ready) {
			TrackingStartupResult.Ready(false, 0L)
		} else {
			TrackingStartupResult.RetryableFailure(
				com.adsamcik.tracker.shared.base.startup.TrackingStartupStage.STORAGE,
				"NOT_READY",
			)
		}
}

private class CloseAfterPublicationGate(
		override val currentGeneration: Long,
) : TrackingStartupGate {
		private var ready = true
		var closeAfterNextPublication = false

		override val isReady: Boolean
			get() = ready

		override fun <T> withReadyGeneration(
			expectedGeneration: Long,
			operation: () -> T,
		): T? {
			if (!isReadyGeneration(expectedGeneration)) return null
			return operation().also {
				if (closeAfterNextPublication) {
					closeAfterNextPublication = false
					ready = false
				}
			}
		}

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			if (ready) {
				TrackingStartupResult.Ready(false, 0L)
			} else {
				TrackingStartupResult.RetryableFailure(
					com.adsamcik.tracker.shared.base.startup.TrackingStartupStage.STORAGE,
					"NOT_READY",
				)
			}
}

private class RejectNextPublicationGate(
			override val currentGeneration: Long,
			var ready: Boolean,
) : TrackingStartupGate {
			var rejectNextPublication = false

			override val isReady: Boolean
				get() = ready

			override fun <T> withReadyGeneration(
				expectedGeneration: Long,
				operation: () -> T,
			): T? {
				if (!isReadyGeneration(expectedGeneration)) return null
				if (rejectNextPublication) {
					rejectNextPublication = false
					ready = false
					return null
				}
				return operation()
			}

			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
				if (ready) {
					TrackingStartupResult.Ready(false, 0L)
				} else {
					TrackingStartupResult.RetryableFailure(
						com.adsamcik.tracker.shared.base.startup.TrackingStartupStage.STORAGE,
						"NOT_READY",
					)
				}
}

private fun fixtureResult(
	result: com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult,
): com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult.Retryable =
	result as com.adsamcik.tracker.tracker.api
		.TrackingRetentionFloorReconciliationResult.Retryable

private class FixedRolloutStateStore(
	private val state: TrackingRolloutState,
) : TrackingRolloutStateStore {
	override suspend fun load(): TrackingRolloutState = state

	override suspend fun save(
		state: TrackingRolloutState,
		updatedAtMs: Long,
	): Unit = error("Not used")
}

private class MutableRolloutStateStore(
	initial: TrackingRolloutState,
) : TrackingRolloutStateStore {
	private val state = MutableStateFlow(initial)
	override val states: Flow<TrackingRolloutState> = state

	override suspend fun load(): TrackingRolloutState = state.value

	override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) {
		this.state.value = state
	}

	fun update(state: TrackingRolloutState) {
		this.state.value = state
	}
}

private object AlwaysApprovedRetentionAuthorityProducer : RetentionAuthorityProducer {
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> =
		listOf(TrackingSource.STEPS, TrackingSource.WIFI, TrackingSource.CELL).map { source ->
			activeResult(source, RetentionAuthorityScope.LIVE_AMBIENT)
		}

	override suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult =
		RetentionConfigurationApprovalResult.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)

	override suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult =
		RetentionConfigurationApprovalResult.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)

	override suspend fun reconcileLiveAmbient(source: TrackingSource): RetentionAuthorityResult =
		activeResult(source, RetentionAuthorityScope.LIVE_AMBIENT)

	override suspend fun approvePortableImport(source: TrackingSource): RetentionAuthorityResult =
		activeResult(source, RetentionAuthorityScope.PORTABLE_IMPORT)

	override suspend fun revokePortableImport(source: TrackingSource): RetentionAuthorityResult =
		unavailableResult(source)

	override suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult =
		activeResult(TrackingSource.LOCATION, RetentionAuthorityScope.LIVE_AMBIENT)

	override suspend fun currentLiveAmbient(
		source: TrackingSource,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
	): CurrentRetentionAuthority = CurrentRetentionAuthority.Approved(
		"test-policy",
		1L,
		"test-boot",
		0L,
		0L,
		expectedCollectedDataEpoch,
		expectedRetainedFromMs,
	)
}

private object UnavailableRetentionAuthorityProducerForTest : RetentionAuthorityProducer {
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> =
		listOf(TrackingSource.STEPS, TrackingSource.WIFI, TrackingSource.CELL).map(::unavailableResult)

	override suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult =
		RetentionConfigurationApprovalResult.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)

	override suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult =
		RetentionConfigurationApprovalResult.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)

	override suspend fun reconcileLiveAmbient(source: TrackingSource): RetentionAuthorityResult =
		unavailableResult(source)

	override suspend fun approvePortableImport(source: TrackingSource): RetentionAuthorityResult =
		unavailableResult(source)

	override suspend fun revokePortableImport(source: TrackingSource): RetentionAuthorityResult =
		unavailableResult(source)

	override suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult =
		unavailableResult(TrackingSource.LOCATION)

	override suspend fun currentLiveAmbient(
		source: TrackingSource,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
	): CurrentRetentionAuthority = CurrentRetentionAuthority.Unavailable(
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)
}

private fun unavailableResult(source: TrackingSource) = RetentionAuthorityResult.Unavailable(
	source = source,
	scope = RetentionAuthorityScope.LIVE_AMBIENT,
	reason = RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
)

private fun activeResult(
	source: TrackingSource,
	scope: RetentionAuthorityScope,
) = RetentionAuthorityResult.Unchanged(
	source = source,
	scope = scope,
	state = com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState.ACTIVE,
	approvalRevision = 1L,
)
