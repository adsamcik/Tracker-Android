package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity
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
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AtomicTrackingPurposeAvailabilityStore
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityRevision
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

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
	fun `replaced owner makes an in flight callback stale`() = runTest {
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
		val firstRegistration = async {
			fixture.runtime.registerAmbientSourceOwner(
				AmbientTrackingSource.STEPS,
				executionRevision = 4L,
			) { lease ->
				firstLeaseReceived.complete(Unit)
				releaseFirst.await()
				AmbientSourceOperationalAvailability.ready(
					AmbientTrackingSource.STEPS,
					AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
					lease.purposeLeaseIdentity,
				)
			}
		}
		firstLeaseReceived.await()

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
		releaseFirst.complete(Unit)
		firstRegistration.await()

		val published = fixture.store.availability.value.ambientSources
			.getValue(AmbientTrackingSource.STEPS)
		published.state shouldBe AmbientSourceOperationalState.UNAVAILABLE
		published.reason shouldBe AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
		published.lastIdentity shouldBe secondIdentity
		secondIdentity?.ownerCasToken shouldBe "owner-2"
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
	fun `ambient sources stay pending when no owner is registered`() = runTest {
		val fixture = fixture(
			authority(
				source = TrackingSource.LOCATION,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				executionRevision = 0L,
			),
		)

		fixture.runtime.reconcileCurrentSettings()

		fixture.store.availability.value.ambientSources.values.forEach { availability ->
			availability.state shouldBe AmbientSourceOperationalState.WAITING
			availability.reason shouldBe AmbientSourceUnavailableReason.RECONCILIATION_PENDING
			availability.isOperational shouldBe false
		}
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

	private fun authority(
		source: TrackingSource,
		purpose: TrackingPurpose,
		executionRevision: Long,
	) = TrackingPurposeAuthoritySnapshot(
		sourcePurpose = source.forPurpose(purpose),
		policyRevision = 12L,
		consentEpoch = 5L,
		collectedDataEpoch = 3L,
		rolloutRevision = 9L,
		executionRevision = executionRevision,
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
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> = emptyList()

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
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> = emptyList()

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
