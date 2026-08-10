package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackingRolloutStateTest {
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
		)
		}
	}
}

