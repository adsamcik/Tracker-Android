package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
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
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TrackingPurposePublicationRuntimeTest {
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
		return Fixture(
			store = store,
			runtime = DefaultTrackingPurposePublicationRuntime(issuer, store),
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

	private fun policySnapshot(revision: Long): SourcePolicySnapshot {
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
					controlConsentEpoch = 5L.takeIf { source == TrackingSource.ACTIVITY },
					ambientConsentEpoch = 5L.takeIf { source == TrackingSource.STEPS },
					capturePersistenceEligible = false,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = source == TrackingSource.STEPS,
					effectiveTime = effectiveTime,
					policyRevision = revision,
				)
			},
		)
	}
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
