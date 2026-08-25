package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackingRolloutStateTest {
	@Test
	fun `contained bootstrap authorizes no source acquisition`() {
		val state = TrackingRolloutState.contained()

		state.coordinatorMode shouldBe CoordinatorMode.EVENT
		state.sourceOwners.values.toSet() shouldBe setOf(SourceOwner.CONTAINED)
		state.productProjectionStages.values.toSet() shouldBe
			setOf(ProductProjectionStage.LEGACY_CANONICAL)
		SourceKind.entries.forEach { source ->
			state.isAcquisitionReachable(source) shouldBe false
			state.isControlAcquisitionReachable(source) shouldBe false
		}
	}

	@Test
	fun `event shadow enables only its explicitly reachable source`() {
		val state = TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS))

		state.sourceOwners.getValue(SourceKind.STEPS) shouldBe SourceOwner.EVENT
		state.productProjectionStages.getValue(SourceKind.STEPS) shouldBe
			ProductProjectionStage.EVENT_SHADOW
		state.isAcquisitionReachable(SourceKind.STEPS) shouldBe true
		state.isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		) shouldBe true
		state.isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
		) shouldBe false
		state.isCaptureReachable(SourceKind.STEPS, CaptureReachabilityMode.AMBIENT) shouldBe false
		state.isControlAcquisitionReachable(SourceKind.STEPS) shouldBe true
		SourceKind.entries.filterNot { it == SourceKind.STEPS }.forEach { source ->
			state.sourceOwners.getValue(source) shouldBe SourceOwner.CONTAINED
			state.productProjectionStages.getValue(source) shouldBe
				ProductProjectionStage.LEGACY_CANONICAL
			state.isAcquisitionReachable(source) shouldBe false
			state.isControlAcquisitionReachable(source) shouldBe false
		}
	}

	@Test
	fun `automatic capture requires its own explicit mode bit`() {
		val state = TrackingRolloutState.eventShadow(
			sources = setOf(SourceKind.STEPS),
			captureModes = mapOf(
				SourceKind.STEPS to setOf(
					CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
					CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
				),
			),
		)

		state.isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
		) shouldBe true
	}

	@Test
	fun `control-only Activity is operational without becoming a captured product source`() {
		val state = TrackingRolloutState.eventShadow(
			sources = setOf(SourceKind.STEPS),
			controlSources = setOf(SourceKind.ACTIVITY),
		)

		state.sourceOwners.getValue(SourceKind.ACTIVITY) shouldBe SourceOwner.CONTROL
		state.productProjectionStages.getValue(SourceKind.ACTIVITY) shouldBe
			ProductProjectionStage.LEGACY_CANONICAL
		state.isControlAcquisitionReachable(SourceKind.ACTIVITY) shouldBe true
		state.isAcquisitionReachable(SourceKind.ACTIVITY) shouldBe false
		state.isAcquisitionReachable(SourceKind.STEPS) shouldBe true
	}

	@Test
	fun `a source cannot be capture-owned and control-only in one rollout`() {
		shouldThrow<IllegalArgumentException> {
			TrackingRolloutState.eventShadow(
				sources = setOf(SourceKind.STEPS),
				controlSources = setOf(SourceKind.STEPS),
			)
		}
	}

	@Test
	fun `legacy state assigns exactly one legacy owner to every source`() {
		val state = TrackingRolloutState.legacy()

		state.sourceOwners.keys shouldBe SourceKind.entries.toSet()
		state.sourceOwners.values.toSet() shouldBe setOf(SourceOwner.LEGACY)
	}

	@Test
	fun `event source ownership cannot be enabled under legacy coordinator`() {
		shouldThrow<IllegalArgumentException> {
			TrackingRolloutState.legacy().copy(
				sourceOwners = SourceKind.entries.associateWith { source ->
					if (source == SourceKind.STEPS) SourceOwner.EVENT else SourceOwner.LEGACY
				},
				productProjectionStages = SourceKind.entries.associateWith { source ->
					if (source == SourceKind.STEPS) {
						ProductProjectionStage.EVENT_SHADOW
					} else {
						ProductProjectionStage.LEGACY_CANONICAL
					}
				},
			)
		}
	}

	@Test
	fun `event acquisition without a reachable source lane is rejected`() {
		shouldThrow<IllegalArgumentException> {
			TrackingRolloutState.contained().copy(
				sourceOwners = SourceKind.entries.associateWith { source ->
					if (source == SourceKind.STEPS) SourceOwner.EVENT else SourceOwner.CONTAINED
				},
			)
		}
	}

	@Test
	fun `one canonical event projection requires ownership of only that source`() {
		val stages = SourceKind.entries.associateWith { source ->
			if (source == SourceKind.STEPS) {
				ProductProjectionStage.EVENT_CANONICAL
			} else {
				ProductProjectionStage.LEGACY_CANONICAL
			}
		}

		TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS)).copy(
			productProjectionStages = stages,
		)

		shouldThrow<IllegalArgumentException> {
			TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS)).copy(
				sourceOwners = SourceKind.entries.associateWith { SourceOwner.CONTAINED },
				productProjectionStages = stages,
			)
		}
	}
}
