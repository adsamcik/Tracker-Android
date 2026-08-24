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
	}

	@Test
	fun `event shadow enables only its explicitly reachable source`() {
		val state = TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS))

		state.sourceOwners.getValue(SourceKind.STEPS) shouldBe SourceOwner.EVENT
		state.productProjectionStages.getValue(SourceKind.STEPS) shouldBe
			ProductProjectionStage.EVENT_SHADOW
		SourceKind.entries.filterNot { it == SourceKind.STEPS }.forEach { source ->
			state.sourceOwners.getValue(source) shouldBe SourceOwner.CONTAINED
			state.productProjectionStages.getValue(source) shouldBe
				ProductProjectionStage.LEGACY_CANONICAL
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
