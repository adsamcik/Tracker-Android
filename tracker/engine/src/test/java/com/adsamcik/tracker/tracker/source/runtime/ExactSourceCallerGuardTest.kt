package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuardRejection
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRequest
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ExactSourceCallerGuardTest {
	@Test
	fun `manual only-X is exact and unaffected by automatic CONTROL unavailability`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(location),
			availability = unavailableAutomatic(),
		)

		val receipt = fixture.permit(
			SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources = setOf(TrackingSource.LOCATION),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(location),
			),
		)

		receipt.permittedDemandIdentities shouldBe setOf(location)
	}

	@Test
	fun `manual rejects hidden captured CONTROL and ambient demand`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		listOf(
			capture(TrackingSource.STEPS),
			control(),
			ambient(TrackingSource.WIFI),
		).forEach { hidden ->
			val fixture = TrustedSourceCallerGuardFixtureFactory.create(
				current = setOf(location, hidden),
			)

			fixture.guard.accept(
				SourceCallerRequest.ManualSessionStart(
					requestedCapturedSources = setOf(TrackingSource.LOCATION),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(location, hidden),
				),
			) shouldBe rejected(
				SourceCallerRejectionReason.UNDECLARED_DEMAND,
				hidden,
			)
		}
	}

	@Test
	fun `automatic path requires exactly Activity CONTROL and checks readiness unconditionally`() =
		runTest {
			val steps = capture(TrackingSource.STEPS)
			val activityControl = control()
			val ready = automaticReady(activityControl)
			val fixture = TrustedSourceCallerGuardFixtureFactory.create(
				current = setOf(steps, activityControl),
				availability = ready,
			)

			fixture.permit(
				SourceCallerRequest.AutomaticSessionStart(
					requestedCapturedSources = setOf(TrackingSource.STEPS),
					declaredControlDependencies = setOf(TrackingSource.ACTIVITY),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(steps, activityControl),
				),
			).permittedDemandIdentities shouldBe setOf(steps, activityControl)

			fixture.guard.accept(
				SourceCallerRequest.AutomaticSessionStart(
					requestedCapturedSources = setOf(TrackingSource.STEPS),
					declaredControlDependencies = emptySet(),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(steps),
				),
			) shouldBe rejected(
				SourceCallerRejectionReason.AUTOMATIC_CONTROL_SET_MISMATCH,
				control(),
			)

			fixture.guard.accept(
				SourceCallerRequest.AutomaticSessionStart(
					requestedCapturedSources = setOf(TrackingSource.STEPS),
					declaredControlDependencies = setOf(TrackingSource.ACTIVITY),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(steps),
				),
			) shouldBe rejected(
				SourceCallerRejectionReason.MISSING_DEMAND_IDENTITY,
				control(),
			)

			fixture.guard.accept(
				SourceCallerRequest.AutomaticSessionStart(
					requestedCapturedSources = setOf(TrackingSource.STEPS),
					declaredControlDependencies =
						setOf(TrackingSource.ACTIVITY, TrackingSource.LOCATION),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(steps, activityControl),
				),
			) shouldBe rejected(
				SourceCallerRejectionReason.AUTOMATIC_CONTROL_SET_MISMATCH,
				control(),
			)

			fixture.availability = unavailableAutomatic()
			fixture.guard.accept(
				SourceCallerRequest.AutomaticSessionStart(
					requestedCapturedSources = setOf(TrackingSource.STEPS),
					declaredControlDependencies = emptySet(),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(steps),
				),
			) shouldBe SourceCallerGuardResult.Rejected(
				SourceCallerGuardRejection(
					reason = SourceCallerRejectionReason.AUTOMATIC_CONTROL_UNAVAILABLE,
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
					automaticUnavailableReason =
						AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				),
			)
		}

	@Test
	fun `automatic readiness must carry the exact requested Activity CONTROL identity`() = runTest {
		val steps = capture(TrackingSource.STEPS)
		val activityControl = control()
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(steps, activityControl),
			availability = automaticReady(
				activityControl.copy(
					purposeLeaseIdentity = activityControl.purposeLeaseIdentity.copy(
						executionRevision = 18L,
					),
				),
			),
		)

		fixture.guard.accept(
			SourceCallerRequest.AutomaticSessionStart(
				requestedCapturedSources = setOf(TrackingSource.STEPS),
				declaredControlDependencies = setOf(TrackingSource.ACTIVITY),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(steps, activityControl),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			activityControl,
		)
	}

	@Test
	fun `ambient is default-off source-local sessionless and readiness-bound`() = runTest {
		val steps = ambient(TrackingSource.STEPS)
		val wifi = ambient(TrackingSource.WIFI)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(steps, wifi),
			availability = ambientReady(steps),
		)

		fixture.guard.accept(
			SourceCallerRequest.Ambient(
				source = TrackingSource.STEPS,
				requestedDemandIdentities = setOf(steps),
			),
		) shouldBe rejected(SourceCallerRejectionReason.AMBIENT_DISABLED)

		fixture.permit(
			SourceCallerRequest.Ambient(
				source = TrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(steps),
			),
		).permittedDemandIdentities shouldBe setOf(steps)

		fixture.guard.accept(
			SourceCallerRequest.Ambient(
				source = TrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(steps, wifi),
			),
		) shouldBe rejected(SourceCallerRejectionReason.UNDECLARED_DEMAND, wifi)

		fixture.availability = ambientReady(
			steps.copy(
				purposeLeaseIdentity = steps.purposeLeaseIdentity.copy(ownerCasToken = "replaced"),
			),
		)
		fixture.guard.accept(
			SourceCallerRequest.Ambient(
				source = TrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(steps),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			steps,
		)
	}

	@Test
	fun `fresh acceptance rejects every stale authority coordinate`() = runTest {
		val requested = capture(TrackingSource.CELL)
		val stale = listOf(
			requested.copy(
				manifestIdentity = MANIFEST.copy(manifestRevision = MANIFEST.manifestRevision + 1L),
			) to SourceCallerRejectionReason.STALE_MANIFEST_IDENTITY,
			withLease(requested) { it.copy(policyRevision = it.policyRevision + 1L) } to
				SourceCallerRejectionReason.STALE_POLICY_REVISION,
			withLease(requested) { it.copy(consentEpoch = it.consentEpoch + 1L) } to
				SourceCallerRejectionReason.STALE_CONSENT_EPOCH,
			withLease(requested) { it.copy(collectedDataEpoch = it.collectedDataEpoch + 1L) } to
				SourceCallerRejectionReason.STALE_COLLECTED_DATA_EPOCH,
			withLease(requested) { it.copy(rolloutRevision = it.rolloutRevision + 1L) } to
				SourceCallerRejectionReason.STALE_ROLLOUT_REVISION,
			withLease(requested) { it.copy(executionRevision = it.executionRevision + 1L) } to
				SourceCallerRejectionReason.STALE_EXECUTION_REVISION,
			withLease(requested) { it.copy(ownerCasToken = "replaced") } to
				SourceCallerRejectionReason.STALE_OWNER_CAS_TOKEN,
		)

		stale.forEach { (current, reason) ->
			val fixture = TrustedSourceCallerGuardFixtureFactory.create(current = setOf(current))
			fixture.guard.accept(
				SourceCallerRequest.ManualSessionStart(
					requestedCapturedSources = setOf(TrackingSource.CELL),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(requested),
				),
			) shouldBe rejected(reason, requested)
		}
	}

	@Test
	fun `FGS restart and recovery replay only the exact persisted authority`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val steps = capture(TrackingSource.STEPS)
		val original = setOf(location, steps)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(current = original)
		val receipt = fixture.permit(
			SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources = setOf(TrackingSource.LOCATION, TrackingSource.STEPS),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = original,
			),
		)

		SourceCallerReplayKind.entries.forEach { kind ->
			fixture.guard.accept(replay(receipt, kind, original)) shouldBe
				SourceCallerGuardResult.Permitted(receipt)
		}

		val changes = listOf(
			ReplayChange(
				TrackingPurpose.AMBIENT_PRODUCT,
				original,
				SourceCallerRejectionReason.REPLAY_PURPOSE_MISMATCH,
			),
			ReplayChange(
				TrackingPurpose.SESSION_CAPTURE,
				original + capture(TrackingSource.ACTIVITY),
				SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION,
			),
			ReplayChange(
				TrackingPurpose.SESSION_CAPTURE,
				setOf(location),
				SourceCallerRejectionReason.REPLAY_AUTHORITY_DOWNGRADE,
			),
			replayMutation(
				original,
				location,
				location.copy(
					manifestIdentity = MANIFEST.copy(manifestRevision = 4L),
				),
				SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION,
			),
			replayMutation(
				original,
				location,
				location.copy(
					manifestIdentity = MANIFEST.copy(manifestRevision = 2L),
				),
				SourceCallerRejectionReason.REPLAY_AUTHORITY_DOWNGRADE,
			),
			replayMutation(
				original,
				location,
				location.copy(
					manifestIdentity = MANIFEST.copy(logicalTrackingId = "other-logical"),
				),
				SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
			),
			replayLeaseMutation(original, location, { it.copy(policyRevision = 12L) }, true),
			replayLeaseMutation(original, location, { it.copy(policyRevision = 10L) }, false),
			replayLeaseMutation(original, location, { it.copy(consentEpoch = 8L) }, true),
			replayLeaseMutation(original, location, { it.copy(consentEpoch = 6L) }, false),
			replayLeaseMutation(original, location, { it.copy(collectedDataEpoch = 6L) }, true),
			replayLeaseMutation(original, location, { it.copy(collectedDataEpoch = 4L) }, false),
			replayLeaseMutation(original, location, { it.copy(rolloutRevision = 14L) }, true),
			replayLeaseMutation(original, location, { it.copy(rolloutRevision = 12L) }, false),
			replayLeaseMutation(original, location, { it.copy(executionRevision = 18L) }, true),
			replayLeaseMutation(original, location, { it.copy(executionRevision = 16L) }, false),
			replayMutation(
				original,
				location,
				withLease(location) { it.copy(ownerCasToken = "other-owner") },
				SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
			),
		)

		changes.forEach { change ->
			fixture.current = change.demands
			fixture.guard.accept(
				SourceCallerRequest.Replay(
					replayKind = SourceCallerReplayKind.RECOVERY,
					reference = receipt.reference,
					purpose = change.purpose,
					requestedDemandIdentities = change.demands,
				),
			) shouldBe rejected(change.reason)
		}
	}

	@Test
	fun `caller-constructed receipt data cannot substitute for persisted accepted authority`() =
		runTest {
			val location = capture(TrackingSource.LOCATION)
			val fixture = TrustedSourceCallerGuardFixtureFactory.create(current = setOf(location))

			fixture.guard.accept(
				SourceCallerRequest.Replay(
					replayKind = SourceCallerReplayKind.RECOVERY,
					reference = SourceCallerReplayReference("caller-forged"),
					purpose = TrackingPurpose.SESSION_CAPTURE,
					requestedDemandIdentities = setOf(location),
				),
			) shouldBe rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_UNAVAILABLE)
		}

	@Test
	fun `replay cannot survive automatic or ambient readiness loss or replacement`() = runTest {
		val capture = capture(TrackingSource.STEPS)
		val control = control()
		val automaticFixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(capture, control),
			availability = automaticReady(control),
		)
		val automaticReceipt = automaticFixture.permit(
			SourceCallerRequest.AutomaticSessionStart(
				requestedCapturedSources = setOf(TrackingSource.STEPS),
				declaredControlDependencies = setOf(TrackingSource.ACTIVITY),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(capture, control),
			),
		)
		automaticFixture.availability = unavailableAutomatic()
		automaticFixture.guard.accept(
			replay(
				automaticReceipt,
				SourceCallerReplayKind.RESTART,
				setOf(capture, control),
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()
			.rejection.reason shouldBe SourceCallerRejectionReason.AUTOMATIC_CONTROL_UNAVAILABLE
		automaticFixture.availability = automaticReady(
			withLease(control) { it.copy(executionRevision = 18L) },
		)
		automaticFixture.guard.accept(
			replay(
				automaticReceipt,
				SourceCallerReplayKind.RECOVERY,
				setOf(capture, control),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			control,
		)

		val ambient = ambient(TrackingSource.WIFI)
		val ambientFixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(ambient),
			availability = ambientReady(ambient),
		)
		val ambientReceipt = ambientFixture.permit(
			SourceCallerRequest.Ambient(
				source = TrackingSource.WIFI,
				enabled = true,
				requestedDemandIdentities = setOf(ambient),
			),
		)
		ambientFixture.availability = unavailableAmbient(AmbientTrackingSource.WIFI, ambient)
		ambientFixture.guard.accept(
			replay(
				ambientReceipt,
				SourceCallerReplayKind.RECOVERY,
				setOf(ambient),
				TrackingPurpose.AMBIENT_PRODUCT,
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()
			.rejection.reason shouldBe SourceCallerRejectionReason.AMBIENT_SOURCE_UNAVAILABLE
		ambientFixture.availability = ambientReady(
			withLease(ambient) { it.copy(ownerCasToken = "replaced") },
		)
		ambientFixture.guard.accept(
			replay(
				ambientReceipt,
				SourceCallerReplayKind.FOREGROUND_SERVICE,
				setOf(ambient),
				TrackingPurpose.AMBIENT_PRODUCT,
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			ambient,
		)
	}

	private fun replay(
		receipt: SourceCallerAcceptanceReceipt,
		kind: SourceCallerReplayKind,
		demands: Set<SourceCallerDemandIdentity>,
		purpose: TrackingPurpose = TrackingPurpose.SESSION_CAPTURE,
	) = SourceCallerRequest.Replay(
		replayKind = kind,
		reference = receipt.reference,
		purpose = purpose,
		requestedDemandIdentities = demands,
	)

	private fun capture(source: TrackingSource) =
		demand(source, TrackingPurpose.SESSION_CAPTURE, MANIFEST)

	private fun control() = demand(TrackingSource.ACTIVITY, TrackingPurpose.CONTROL)

	private fun ambient(source: TrackingSource) =
		demand(source, TrackingPurpose.AMBIENT_PRODUCT)

	private fun demand(
		source: TrackingSource,
		purpose: TrackingPurpose,
		manifest: SourceCallerManifestIdentity? = null,
	) = SourceCallerDemandIdentity(
		purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
			sourcePurpose = source.forPurpose(purpose),
			policyRevision = 11L,
			consentEpoch = 7L,
			collectedDataEpoch = 5L,
			rolloutRevision = 13L,
			executionRevision = 17L,
			ownerCasToken = "${source.name}:${purpose.stableName}:owner",
		),
		manifestIdentity = manifest,
	)

	private fun automaticReady(
		control: SourceCallerDemandIdentity,
	): TrackingPurposeAvailabilitySnapshot =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			automaticControl = AutomaticTrackingOperationalAvailability.Ready(
				control.purposeLeaseIdentity,
			),
		)

	private fun unavailableAutomatic(): TrackingPurposeAvailabilitySnapshot =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			automaticControl = AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
			),
		)

	private fun ambientReady(
		demand: SourceCallerDemandIdentity,
	): TrackingPurposeAvailabilitySnapshot {
		val ambientSource = demand.sourcePurpose.source.toAmbientSource()
		return TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			ambientSources = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources +
				(
					ambientSource to AmbientSourceOperationalAvailability(
						source = ambientSource,
						state = AmbientSourceOperationalState.READY,
						mechanism = ambientSource.mechanism(),
						operationalIdentity = demand.purposeLeaseIdentity,
					)
				),
		)
	}

	private fun unavailableAmbient(
		source: AmbientTrackingSource,
		last: SourceCallerDemandIdentity,
	): TrackingPurposeAvailabilitySnapshot =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			ambientSources = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources +
				(
					source to AmbientSourceOperationalAvailability(
						source = source,
						state = AmbientSourceOperationalState.UNAVAILABLE,
						reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
						lastIdentity = last.purposeLeaseIdentity,
					)
				),
		)

	private fun withLease(
		demand: SourceCallerDemandIdentity,
		transform: (TrackingPurposeLeaseIdentity) -> TrackingPurposeLeaseIdentity,
	): SourceCallerDemandIdentity = demand.copy(
		purposeLeaseIdentity = transform(demand.purposeLeaseIdentity),
	)

	private fun replayLeaseMutation(
		original: Set<SourceCallerDemandIdentity>,
		old: SourceCallerDemandIdentity,
		transform: (TrackingPurposeLeaseIdentity) -> TrackingPurposeLeaseIdentity,
		escalation: Boolean,
	): ReplayChange = replayMutation(
		original,
		old,
		withLease(old, transform),
		if (escalation) {
			SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION
		} else {
			SourceCallerRejectionReason.REPLAY_AUTHORITY_DOWNGRADE
		},
	)

	private fun replayMutation(
		original: Set<SourceCallerDemandIdentity>,
		old: SourceCallerDemandIdentity,
		replacement: SourceCallerDemandIdentity,
		reason: SourceCallerRejectionReason,
	) = ReplayChange(
		purpose = TrackingPurpose.SESSION_CAPTURE,
		demands = original - old + replacement,
		reason = reason,
	)

	private fun rejected(
		reason: SourceCallerRejectionReason,
		demand: SourceCallerDemandIdentity? = null,
	) = SourceCallerGuardResult.Rejected(
		SourceCallerGuardRejection(
			reason = reason,
			source = demand?.sourcePurpose?.source,
			purpose = demand?.sourcePurpose?.purpose,
		),
	)

	private data class ReplayChange(
		val purpose: TrackingPurpose,
		val demands: Set<SourceCallerDemandIdentity>,
		val reason: SourceCallerRejectionReason,
	)

	private companion object {
		val MANIFEST = SourceCallerManifestIdentity("logical-session", 3L)
	}
}

internal object TrustedSourceCallerGuardFixtureFactory {
	fun create(
		current: Set<SourceCallerDemandIdentity>,
		availability: TrackingPurposeAvailabilitySnapshot =
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
	): TrustedSourceCallerGuardFixture = TrustedSourceCallerGuardFixture(
		current,
		availability,
	)
}

internal class TrustedSourceCallerGuardFixture(
	current: Set<SourceCallerDemandIdentity>,
	availability: TrackingPurposeAvailabilitySnapshot,
) {
	var current = current
	var availability = availability
	private val persisted = mutableMapOf<SourceCallerReplayReference, String>()
	val guard = ExactSourceCallerGuard(
		authorityReader = SourceCallerAuthoritySnapshotReader {
			SourceCallerAuthoritySnapshot(current, availability)
		},
		authorityRepository = object : SourceCallerAcceptedAuthorityRepository {
			override suspend fun storeIfAbsent(
				reference: SourceCallerReplayReference,
				encodedAuthority: String,
			): Boolean = persisted.putIfAbsent(reference, encodedAuthority) == null

			override suspend fun load(reference: SourceCallerReplayReference): String? =
				persisted[reference]
		},
	)

	suspend fun permit(request: SourceCallerRequest): SourceCallerAcceptanceReceipt =
		guard.accept(request)
			.shouldBeInstanceOf<SourceCallerGuardResult.Permitted>()
			.receipt
}

private fun TrackingSource.toAmbientSource(): AmbientTrackingSource = when (this) {
	TrackingSource.STEPS -> AmbientTrackingSource.STEPS
	TrackingSource.LOCATION -> AmbientTrackingSource.LOCATION
	TrackingSource.WIFI -> AmbientTrackingSource.WIFI
	TrackingSource.CELL -> AmbientTrackingSource.CELL
	TrackingSource.ACTIVITY,
	TrackingSource.PRESSURE,
	-> error("$this is not ambient")
}

private fun AmbientTrackingSource.mechanism(): AmbientAcquisitionMechanism = when (this) {
	AmbientTrackingSource.STEPS -> AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS
	AmbientTrackingSource.LOCATION -> AmbientAcquisitionMechanism.PASSIVE_LOCATION
	AmbientTrackingSource.WIFI -> AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS
	AmbientTrackingSource.CELL -> AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS
}
