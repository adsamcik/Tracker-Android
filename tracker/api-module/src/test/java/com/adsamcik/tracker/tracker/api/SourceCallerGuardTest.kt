package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose as CanonicalTrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource as CanonicalTrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity as CanonicalSourcePurpose
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SourceCallerGuardTest {
	@Test
	fun `tracker API source compatibility enters the canonical caller contract once`() {
		val canonicalSource = TrackingCaptureSource.WIFI.toCanonicalTrackingSource()
		val demand = capture(canonicalSource)

		demand.sourcePurpose shouldBe canonicalSource.forPurpose(
			CanonicalTrackingPurpose.SESSION_CAPTURE,
		)
		TrackingSourcePurposeIdentity.from(demand.sourcePurpose).canonicalIdentity shouldBe
			demand.sourcePurpose
	}

	@Test
	fun `manual only-X accepts exactly its captured source without automatic control`() {
		val location = capture(CanonicalTrackingSource.LOCATION)

		val result = evaluate(
			session(
				kind = SourceCallerStartKind.MANUAL,
				captured = setOf(CanonicalTrackingSource.LOCATION),
				demands = setOf(location),
			),
			current = setOf(location),
		)

		result shouldBe SourceCallerGuardResult.Accepted(
			AcceptedSourceCallerAuthority(
				origin = AcceptedSourceCallerOrigin.MANUAL,
				purpose = CanonicalTrackingPurpose.SESSION_CAPTURE,
				permittedDemandIdentities = setOf(location),
			),
		)
	}

	@Test
	fun `manual only-X rejects undeclared captured control and ambient demand`() {
		val location = capture(CanonicalTrackingSource.LOCATION)
		val hiddenDemands = listOf(
			capture(CanonicalTrackingSource.STEPS),
			control(CanonicalTrackingSource.ACTIVITY),
			ambient(CanonicalTrackingSource.WIFI),
		)

		hiddenDemands.forEach { hidden ->
			evaluate(
				session(
					kind = SourceCallerStartKind.MANUAL,
					captured = setOf(CanonicalTrackingSource.LOCATION),
					demands = setOf(location, hidden),
				),
				current = setOf(location, hidden),
			) shouldBe rejected(
				SourceCallerRejectionReason.UNDECLARED_DEMAND,
				hidden.sourcePurpose,
			)
		}

		val activityControl = control(CanonicalTrackingSource.ACTIVITY)
		evaluate(
			session(
				kind = SourceCallerStartKind.MANUAL,
				captured = setOf(CanonicalTrackingSource.LOCATION),
				controls = setOf(CanonicalTrackingSource.ACTIVITY),
				demands = setOf(location, activityControl),
			),
			current = setOf(location, activityControl),
		) shouldBe rejected(SourceCallerRejectionReason.MANUAL_CONTROL_NOT_ALLOWED)
	}

	@Test
	fun `session request with no captured source fails closed`() {
		evaluate(
			session(
				kind = SourceCallerStartKind.MANUAL,
				captured = emptySet(),
				demands = emptySet(),
			),
			current = emptySet(),
		) shouldBe rejected(SourceCallerRejectionReason.ZERO_CAPTURE_SOURCE_REQUEST)
	}

	@Test
	fun `multi-source request returns the exact declared capture set`() {
		val demands = setOf(
			capture(CanonicalTrackingSource.LOCATION),
			capture(CanonicalTrackingSource.STEPS),
			capture(CanonicalTrackingSource.PRESSURE),
		)

		val accepted = evaluate(
			session(
				kind = SourceCallerStartKind.MANUAL,
				captured = setOf(
					CanonicalTrackingSource.LOCATION,
					CanonicalTrackingSource.STEPS,
					CanonicalTrackingSource.PRESSURE,
				),
				demands = demands,
			),
			current = demands,
		).shouldBeInstanceOf<SourceCallerGuardResult.Accepted>()

		accepted.authority.permittedDemandIdentities shouldBe demands
	}

	@Test
	fun `automatic only-X accepts separately declared Activity CONTROL when ready`() {
		val steps = capture(CanonicalTrackingSource.STEPS)
		val activityControl = control(CanonicalTrackingSource.ACTIVITY)

		val accepted = evaluate(
			session(
				kind = SourceCallerStartKind.AUTOMATIC,
				captured = setOf(CanonicalTrackingSource.STEPS),
				controls = setOf(CanonicalTrackingSource.ACTIVITY),
				demands = setOf(steps, activityControl),
			),
			current = setOf(steps, activityControl),
			availability = availability(automaticReady = true),
		).shouldBeInstanceOf<SourceCallerGuardResult.Accepted>()

		accepted.authority.permittedDemandIdentities shouldBe setOf(steps, activityControl)
		accepted.authority.permittedDemandIdentities
			.single { identity ->
				identity.sourcePurpose.source == CanonicalTrackingSource.ACTIVITY
			}
			.sourcePurpose.purpose shouldBe CanonicalTrackingPurpose.CONTROL
	}

	@Test
	fun `automatic control unavailability is typed and does not affect manual only-X`() {
		val steps = capture(CanonicalTrackingSource.STEPS)
		val activityControl = control(CanonicalTrackingSource.ACTIVITY)
		val unavailable = availability(automaticReady = false)

		evaluate(
			session(
				kind = SourceCallerStartKind.AUTOMATIC,
				captured = setOf(CanonicalTrackingSource.STEPS),
				controls = setOf(CanonicalTrackingSource.ACTIVITY),
				demands = setOf(steps, activityControl),
			),
			current = setOf(steps, activityControl),
			availability = unavailable,
		) shouldBe SourceCallerGuardResult.Rejected(
			SourceCallerGuardRejection(
				reason = SourceCallerRejectionReason.AUTOMATIC_CONTROL_UNAVAILABLE,
				source = CanonicalTrackingSource.ACTIVITY,
				purpose = CanonicalTrackingPurpose.CONTROL,
				automaticUnavailableReason =
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
			),
		)

		evaluate(
			session(
				kind = SourceCallerStartKind.MANUAL,
				captured = setOf(CanonicalTrackingSource.STEPS),
				demands = setOf(steps),
			),
			current = setOf(steps),
			availability = unavailable,
		).shouldBeInstanceOf<SourceCallerGuardResult.Accepted>()
	}

	@Test
	fun `CONTROL cannot leak into session manifest or another control source`() {
		val steps = capture(CanonicalTrackingSource.STEPS)
		val sessionControl = control(
			CanonicalTrackingSource.ACTIVITY,
			manifestIdentity = MANIFEST,
		)
		evaluate(
			session(
				kind = SourceCallerStartKind.AUTOMATIC,
				captured = setOf(CanonicalTrackingSource.STEPS),
				controls = setOf(CanonicalTrackingSource.ACTIVITY),
				demands = setOf(steps, sessionControl),
			),
			current = setOf(steps, sessionControl),
			availability = availability(automaticReady = true),
		) shouldBe rejected(
			SourceCallerRejectionReason.CONTROL_SESSION_CONFUSION,
			sessionControl.sourcePurpose,
		)

		evaluate(
			session(
				kind = SourceCallerStartKind.AUTOMATIC,
				captured = setOf(CanonicalTrackingSource.STEPS),
				controls = setOf(CanonicalTrackingSource.LOCATION),
				demands = setOf(steps),
			),
			current = setOf(steps),
			availability = availability(automaticReady = true),
		) shouldBe rejected(
			SourceCallerRejectionReason.CONTROL_SOURCE_NOT_ALLOWED,
			source = CanonicalTrackingSource.LOCATION,
			purpose = CanonicalTrackingPurpose.CONTROL,
		)
	}

	@Test
	fun `ambient request is default-off source-local and sessionless`() {
		val stepsAmbient = ambient(CanonicalTrackingSource.STEPS)
		evaluate(
			SourceCallerRequest.Ambient(
				source = AmbientTrackingSource.STEPS,
				requestedDemandIdentities = setOf(stepsAmbient),
			),
			current = setOf(stepsAmbient),
			availability = ambientReady(AmbientTrackingSource.STEPS),
		) shouldBe rejected(SourceCallerRejectionReason.AMBIENT_DISABLED)

		evaluate(
			SourceCallerRequest.Ambient(
				source = AmbientTrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(stepsAmbient),
			),
			current = setOf(stepsAmbient),
			availability = ambientReady(AmbientTrackingSource.STEPS),
		).shouldBeInstanceOf<SourceCallerGuardResult.Accepted>()

		evaluate(
			SourceCallerRequest.Ambient(
				source = AmbientTrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(stepsAmbient),
			),
			current = setOf(stepsAmbient),
		) shouldBe SourceCallerGuardResult.Rejected(
			SourceCallerGuardRejection(
				reason = SourceCallerRejectionReason.AMBIENT_SOURCE_UNAVAILABLE,
				source = CanonicalTrackingSource.STEPS,
				purpose = CanonicalTrackingPurpose.AMBIENT_PRODUCT,
				ambientAvailability = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT
					.ambientSources
					.getValue(AmbientTrackingSource.STEPS),
			),
		)

		val sessionAmbient = ambient(
			CanonicalTrackingSource.STEPS,
			manifestIdentity = MANIFEST,
		)
		evaluate(
			SourceCallerRequest.Ambient(
				source = AmbientTrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(sessionAmbient),
			),
			current = setOf(sessionAmbient),
			availability = ambientReady(AmbientTrackingSource.STEPS),
		) shouldBe rejected(
			SourceCallerRejectionReason.AMBIENT_SESSION_CONFUSION,
			sessionAmbient.sourcePurpose,
		)

		val hiddenWifi = ambient(CanonicalTrackingSource.WIFI)
		evaluate(
			SourceCallerRequest.Ambient(
				source = AmbientTrackingSource.STEPS,
				enabled = true,
				requestedDemandIdentities = setOf(stepsAmbient, hiddenWifi),
			),
			current = setOf(stepsAmbient, hiddenWifi),
			availability = ambientReady(AmbientTrackingSource.STEPS),
		) shouldBe rejected(
			SourceCallerRejectionReason.UNDECLARED_DEMAND,
			hiddenWifi.sourcePurpose,
		)
	}

	@Test
	fun `stale manifest policy consent collected-data and rollout identities are rejected`() {
		val requested = capture(CanonicalTrackingSource.CELL)
		val staleCases = listOf(
			requested.copy(
				manifestIdentity = MANIFEST.copy(manifestRevision = MANIFEST.manifestRevision + 1L),
			) to SourceCallerRejectionReason.STALE_MANIFEST_IDENTITY,
			requested.copy(sourcePolicyRevision = requested.sourcePolicyRevision + 1L) to
				SourceCallerRejectionReason.STALE_POLICY_REVISION,
			requested.copy(consentEpoch = requested.consentEpoch + 1L) to
				SourceCallerRejectionReason.STALE_CONSENT_EPOCH,
			requested.copy(collectedDataEpoch = requested.collectedDataEpoch + 1L) to
				SourceCallerRejectionReason.STALE_COLLECTED_DATA_EPOCH,
			requested.copy(rolloutRevision = requested.rolloutRevision + 1L) to
				SourceCallerRejectionReason.STALE_ROLLOUT_REVISION,
		)

		staleCases.forEach { (current, expectedReason) ->
			evaluate(
				session(
					kind = SourceCallerStartKind.MANUAL,
					captured = setOf(CanonicalTrackingSource.CELL),
					demands = setOf(requested),
				),
				current = setOf(current),
			) shouldBe rejected(expectedReason, requested.sourcePurpose)
		}
	}

	@Test
	fun `FGS restart and recovery replay exact accepted authority without upgrading it`() {
		val location = capture(CanonicalTrackingSource.LOCATION)
		val accepted = evaluate(
			session(
				kind = SourceCallerStartKind.MANUAL,
				captured = setOf(CanonicalTrackingSource.LOCATION),
				demands = setOf(location),
			),
			current = setOf(location),
		).shouldBeInstanceOf<SourceCallerGuardResult.Accepted>().authority

		SourceCallerReplayKind.entries.forEach { replayKind ->
			evaluate(
				request = SourceCallerRequest.Replay(
					replayKind = replayKind,
					purpose = accepted.purpose,
					requestedDemandIdentities = accepted.permittedDemandIdentities,
				),
				current = accepted.permittedDemandIdentities,
				previouslyAccepted = accepted,
			) shouldBe SourceCallerGuardResult.Accepted(accepted)
		}

		val escalated = accepted.permittedDemandIdentities +
			capture(CanonicalTrackingSource.ACTIVITY)
		evaluate(
			request = SourceCallerRequest.Replay(
				replayKind = SourceCallerReplayKind.RECOVERY,
				purpose = accepted.purpose,
				requestedDemandIdentities = escalated,
			),
			current = escalated,
			previouslyAccepted = accepted,
		) shouldBe rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION)
	}

	private fun evaluate(
		request: SourceCallerRequest,
		current: Set<SourceCallerDemandIdentity>,
		availability: TrackingPurposeAvailabilitySnapshot =
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		previouslyAccepted: AcceptedSourceCallerAuthority? = null,
	): SourceCallerGuardResult = evaluateSourceCallerGuard(
		SourceCallerGuardInput(
			request = request,
			currentAuthority = SourceCallerAuthoritySnapshot(
				currentDemandIdentities = current,
				purposeAvailability = availability,
			),
			previouslyAcceptedAuthority = previouslyAccepted,
		),
	)

	private fun session(
		kind: SourceCallerStartKind,
		captured: Set<CanonicalTrackingSource>,
		controls: Set<CanonicalTrackingSource> = emptySet(),
		demands: Set<SourceCallerDemandIdentity>,
	) = SourceCallerRequest.SessionStart(
		startKind = kind,
		requestedCapturedSources = captured,
		declaredControlDependencies = controls,
		manifestIdentity = MANIFEST,
		requestedDemandIdentities = demands,
	)

	private fun capture(
		source: CanonicalTrackingSource,
		manifestIdentity: SourceCallerManifestIdentity? = MANIFEST,
	) = identity(source, CanonicalTrackingPurpose.SESSION_CAPTURE, manifestIdentity)

	private fun control(
		source: CanonicalTrackingSource,
		manifestIdentity: SourceCallerManifestIdentity? = null,
	) = identity(source, CanonicalTrackingPurpose.CONTROL, manifestIdentity)

	private fun ambient(
		source: CanonicalTrackingSource,
		manifestIdentity: SourceCallerManifestIdentity? = null,
	) = identity(source, CanonicalTrackingPurpose.AMBIENT_PRODUCT, manifestIdentity)

	private fun identity(
		source: CanonicalTrackingSource,
		purpose: CanonicalTrackingPurpose,
		manifestIdentity: SourceCallerManifestIdentity?,
	) = SourceCallerDemandIdentity(
		sourcePurpose = source.forPurpose(purpose),
		manifestIdentity = manifestIdentity,
		sourcePolicyRevision = 11L,
		consentEpoch = 7L,
		collectedDataEpoch = 5L,
		rolloutRevision = 13L,
	)

	private fun availability(automaticReady: Boolean) =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			automaticControl = if (automaticReady) {
				AutomaticTrackingOperationalAvailability.Ready
			} else {
				AutomaticTrackingOperationalAvailability.Unavailable(
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				)
			},
		)

	private fun ambientReady(source: AmbientTrackingSource) =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			ambientSources = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources +
				(
					source to AmbientSourceOperationalAvailability(
						source = source,
						state = AmbientSourceOperationalState.READY,
						mechanism = when (source) {
							AmbientTrackingSource.STEPS ->
								AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS
							AmbientTrackingSource.LOCATION ->
								AmbientAcquisitionMechanism.PASSIVE_LOCATION
							AmbientTrackingSource.WIFI ->
								AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS
							AmbientTrackingSource.CELL ->
								AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS
						},
					)
				),
		)

	private fun rejected(
		reason: SourceCallerRejectionReason,
		demand: CanonicalSourcePurpose? = null,
		source: CanonicalTrackingSource? = demand?.source,
		purpose: CanonicalTrackingPurpose? = demand?.purpose,
	) = SourceCallerGuardResult.Rejected(
		SourceCallerGuardRejection(
			reason = reason,
			source = source,
			purpose = purpose,
		),
	)

	private companion object {
		val MANIFEST = SourceCallerManifestIdentity(
			logicalTrackingId = "logical-session",
			manifestRevision = 3L,
		)
	}
}
