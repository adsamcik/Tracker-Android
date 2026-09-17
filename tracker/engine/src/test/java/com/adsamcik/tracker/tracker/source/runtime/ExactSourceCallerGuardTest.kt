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
import com.adsamcik.tracker.tracker.api.isRetryable
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
	fun `new recovery manifest receives a fresh exact capture acceptance`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(current = setOf(location))

		fixture.permit(
			SourceCallerRequest.RecoverySessionStart(
				requestedCapturedSources = setOf(TrackingSource.LOCATION),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(location),
			),
		).permittedDemandIdentities shouldBe setOf(location)
	}

	@Test
	fun `manual only-X rejects requested unbound execution even when current is also unbound`() =
		runTest {
			val location = capture(TrackingSource.LOCATION)
			val unboundLocation = unbound(location)
			val boundFixture = TrustedSourceCallerGuardFixtureFactory.create(
				current = setOf(location),
			)

			boundFixture.guard.accept(
				SourceCallerRequest.ManualSessionStart(
					requestedCapturedSources = setOf(TrackingSource.LOCATION),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(unboundLocation),
				),
			) shouldBe rejected(
				SourceCallerRejectionReason.UNBOUND_EXECUTION_AUTHORITY,
				unboundLocation,
			)

			val bothUnbound = TrustedSourceCallerGuardFixtureFactory.create(
				current = setOf(unboundLocation),
			)
			bothUnbound.guard.accept(
				SourceCallerRequest.ManualSessionStart(
					requestedCapturedSources = setOf(TrackingSource.LOCATION),
					manifestIdentity = MANIFEST,
					requestedDemandIdentities = setOf(unboundLocation),
				),
			) shouldBe rejected(
				SourceCallerRejectionReason.UNBOUND_EXECUTION_AUTHORITY,
				unboundLocation,
			)
		}

	@Test
	fun `trusted snapshot rejects unbound current authority for a bound manual request`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(unbound(location)),
		)

		fixture.guard.accept(
			SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources = setOf(TrackingSource.LOCATION),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(location),
			),
		) shouldBe rejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
	}

	@Test
	fun `multisource manual request rejects one unbound captured identity`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val steps = capture(TrackingSource.STEPS)
		val unboundSteps = unbound(steps)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(location, steps),
		)

		fixture.guard.accept(
			SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources =
					setOf(TrackingSource.LOCATION, TrackingSource.STEPS),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(location, unboundSteps),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.UNBOUND_EXECUTION_AUTHORITY,
			unboundSteps,
		)
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
	fun `purpose owner mutation accepts exact current control lease before readiness publication`() =
		runTest {
			val activityControl = control()
			val fixture = TrustedSourceCallerGuardFixtureFactory.create(
				current = setOf(activityControl),
				availability = unavailableAutomatic(),
			)

			fixture.permit(
				SourceCallerRequest.PurposeOwnerMutation(
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
					enabled = true,
					requestedDemandIdentities = setOf(activityControl),
				),
			).permittedDemandIdentities shouldBe setOf(activityControl)
		}

	@Test
	fun `purpose owner disable is current-bound and stored authority can later authorize retirement`() =
		runTest {
			val activityControl = control()
			val fixture = TrustedSourceCallerGuardFixtureFactory.create(
				current = setOf(activityControl),
			)
			val receipt = fixture.permit(
				SourceCallerRequest.PurposeOwnerMutation(
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
					enabled = false,
					requestedDemandIdentities = setOf(activityControl),
				),
			)

			fixture.current = emptySet()
			fixture.permit(
				SourceCallerRequest.PurposeOwnerRetirement(
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
					reference = receipt.reference,
				),
			).permittedDemandIdentities shouldBe setOf(activityControl)
		}

	@Test
	fun `purpose owner mutation accepts only one exact sessionless ambient lease`() = runTest {
		val wifi = ambient(TrackingSource.WIFI)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(wifi),
		)

		fixture.permit(
			SourceCallerRequest.PurposeOwnerMutation(
				source = TrackingSource.WIFI,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				enabled = true,
				requestedDemandIdentities = setOf(wifi),
			),
		).permittedDemandIdentities shouldBe setOf(wifi)

		fixture.guard.accept(
			SourceCallerRequest.PurposeOwnerMutation(
				source = TrackingSource.WIFI,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				enabled = true,
				requestedDemandIdentities = setOf(
					wifi,
					ambient(TrackingSource.CELL),
				),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.UNDECLARED_DEMAND,
			ambient(TrackingSource.CELL),
		)
	}

	@Test
	fun `automatic request rejects unbound capture despite valid bound Activity CONTROL`() = runTest {
		val steps = capture(TrackingSource.STEPS)
		val unboundSteps = unbound(steps)
		val activityControl = control()
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(steps, activityControl),
			availability = automaticReady(activityControl),
		)

		fixture.guard.accept(
			SourceCallerRequest.AutomaticSessionStart(
				requestedCapturedSources = setOf(TrackingSource.STEPS),
				declaredControlDependencies = setOf(TrackingSource.ACTIVITY),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(unboundSteps, activityControl),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.UNBOUND_EXECUTION_AUTHORITY,
			unboundSteps,
		)

		val unboundControl = unbound(activityControl)
		fixture.guard.accept(
			SourceCallerRequest.AutomaticSessionStart(
				requestedCapturedSources = setOf(TrackingSource.STEPS),
				declaredControlDependencies = setOf(TrackingSource.ACTIVITY),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(steps, unboundControl),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.UNBOUND_EXECUTION_AUTHORITY,
			unboundControl,
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
	fun `ambient request rejects unbound source identity before readiness acceptance`() = runTest {
		val steps = ambient(TrackingSource.STEPS)
		val unboundSteps = unbound(steps)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(
			current = setOf(steps),
			availability = ambientReady(steps),
		)

		fixture.guard.accept(
			SourceCallerRequest.Ambient(
				source = TrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(unboundSteps),
			),
		) shouldBe rejected(
			SourceCallerRejectionReason.UNBOUND_EXECUTION_AUTHORITY,
			unboundSteps,
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
			withLease(requested) { it.copy(retainedFromMs = 1_000L) } to
				SourceCallerRejectionReason.STALE_RETENTION_BOUNDARY,
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
			replayMutation(
				original,
				location,
				withLease(location) { it.copy(retainedFromMs = 1_000L) },
				SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
			),
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
	fun `exact persisted replay rejects current authority-only mutations`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(current = setOf(location))
		val receipt = fixture.permit(
			SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources = setOf(TrackingSource.LOCATION),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(location),
			),
		)
		val exactReplay = replay(
			receipt,
			SourceCallerReplayKind.RECOVERY,
			setOf(location),
		)
		val currentMutations = listOf(
			setOf(
				location.copy(
					manifestIdentity = MANIFEST.copy(manifestRevision = 4L),
				),
			) to SourceCallerRejectionReason.STALE_MANIFEST_IDENTITY,
			setOf(
				location.copy(
					manifestIdentity = MANIFEST.copy(logicalTrackingId = "other-logical"),
				),
			) to SourceCallerRejectionReason.STALE_MANIFEST_IDENTITY,
			emptySet<SourceCallerDemandIdentity>() to
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
			setOf(withLease(location) { it.copy(policyRevision = 12L) }) to
				SourceCallerRejectionReason.STALE_POLICY_REVISION,
			setOf(withLease(location) { it.copy(consentEpoch = 8L) }) to
				SourceCallerRejectionReason.STALE_CONSENT_EPOCH,
			setOf(withLease(location) { it.copy(collectedDataEpoch = 6L) }) to
				SourceCallerRejectionReason.STALE_COLLECTED_DATA_EPOCH,
			setOf(withLease(location) { it.copy(retainedFromMs = 1_000L) }) to
				SourceCallerRejectionReason.STALE_RETENTION_BOUNDARY,
			setOf(withLease(location) { it.copy(rolloutRevision = 14L) }) to
				SourceCallerRejectionReason.STALE_ROLLOUT_REVISION,
			setOf(withLease(location) { it.copy(executionRevision = 18L) }) to
				SourceCallerRejectionReason.STALE_EXECUTION_REVISION,
			setOf(withLease(location) { it.copy(ownerCasToken = "current-owner-replaced") }) to
				SourceCallerRejectionReason.STALE_OWNER_CAS_TOKEN,
		)

		currentMutations.forEach { (current, reason) ->
			fixture.current = current
			fixture.guard.accept(exactReplay) shouldBe rejected(reason, location)
		}
	}

	@Test
	fun `persisted replay preserves the exact retained boundary`() = runTest {
		val location = withLease(capture(TrackingSource.LOCATION)) {
			it.copy(retainedFromMs = 1_000L)
		}
		val fixture = TrustedSourceCallerGuardFixtureFactory.create(current = setOf(location))
		val receipt = fixture.permit(
			SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources = setOf(TrackingSource.LOCATION),
				manifestIdentity = MANIFEST,
				requestedDemandIdentities = setOf(location),
			),
		)

		fixture.guard.accept(
			replay(receipt, SourceCallerReplayKind.RECOVERY, setOf(location)),
		) shouldBe SourceCallerGuardResult.Permitted(receipt)
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
	fun `authority storage failures remain explicitly retryable`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val failingStore = object : SourceCallerAcceptedAuthorityRepository {
			override suspend fun storeIfAbsent(
				reference: SourceCallerReplayReference,
				authority: StoredSourceCallerAuthority,
				createdAtMs: Long,
			): Boolean = throw IllegalStateException("storage unavailable")

			override suspend fun load(
				reference: SourceCallerReplayReference,
			): StoredSourceCallerAuthorityLoadResult =
				throw IllegalStateException("storage unavailable")

			override suspend fun tombstone(
				reference: SourceCallerReplayReference,
				reason: String,
				tombstonedAtMs: Long,
			): Boolean = false

			override suspend fun delete(reference: SourceCallerReplayReference): Boolean = false
		}
		val guard = ExactSourceCallerGuard(
			SourceCallerAuthoritySnapshotReader {
				SourceCallerAuthoritySnapshot(
					setOf(location),
					TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
				)
			},
			failingStore,
		)

		val fresh = guard.accept(
			SourceCallerRequest.ManualSessionStart(
				setOf(TrackingSource.LOCATION),
				MANIFEST,
				setOf(location),
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()
		fresh.rejection.reason shouldBe SourceCallerRejectionReason.AUTHORITY_PERSISTENCE_UNAVAILABLE
		fresh.rejection.reason.isRetryable shouldBe true

		val replay = guard.accept(
			SourceCallerRequest.Replay(
				SourceCallerReplayKind.FOREGROUND_SERVICE,
				SourceCallerReplayReference("persisted"),
				TrackingPurpose.SESSION_CAPTURE,
				setOf(location),
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()
		replay.rejection.reason shouldBe SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE
		replay.rejection.reason.isRetryable shouldBe true
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

	private fun unbound(
		demand: SourceCallerDemandIdentity,
	): SourceCallerDemandIdentity = withLease(demand) { identity ->
		identity.copy(executionRevision = 0L)
	}

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
	private val persisted =
		mutableMapOf<SourceCallerReplayReference, StoredSourceCallerAuthority>()
	val guard = ExactSourceCallerGuard(
		authorityReader = SourceCallerAuthoritySnapshotReader {
			SourceCallerAuthoritySnapshot(current, availability)
		},
		authorityRepository = object : SourceCallerAcceptedAuthorityRepository {
			override suspend fun storeIfAbsent(
				reference: SourceCallerReplayReference,
				authority: StoredSourceCallerAuthority,
				createdAtMs: Long,
			): Boolean = persisted.putIfAbsent(reference, authority) == null

			override suspend fun load(
				reference: SourceCallerReplayReference,
			): StoredSourceCallerAuthorityLoadResult = persisted[reference]?.let {
				StoredSourceCallerAuthorityLoadResult.Available(it)
			} ?: StoredSourceCallerAuthorityLoadResult.Missing

			override suspend fun tombstone(
				reference: SourceCallerReplayReference,
				reason: String,
				tombstonedAtMs: Long,
			): Boolean = persisted.remove(reference) != null

			override suspend fun delete(reference: SourceCallerReplayReference): Boolean =
				persisted.remove(reference) != null
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
