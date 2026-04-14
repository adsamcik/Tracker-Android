package com.adsamcik.tracker.statistics.viewmodel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WifiStatsLoadState")
class WifiStatsLoadStateTest {

	@Nested
	@DisplayName("State variants")
	inner class StateVariants {

		@Test
		fun `Idle is a singleton`() {
			val state: WifiStatsLoadState = WifiStatsLoadState.Idle
			state.shouldBeInstanceOf<WifiStatsLoadState.Idle>()
		}

		@Test
		fun `Loading is a singleton`() {
			val state: WifiStatsLoadState = WifiStatsLoadState.Loading
			state.shouldBeInstanceOf<WifiStatsLoadState.Loading>()
		}

		@Test
		fun `Empty is a singleton`() {
			val state: WifiStatsLoadState = WifiStatsLoadState.Empty
			state.shouldBeInstanceOf<WifiStatsLoadState.Empty>()
		}

		@Test
		fun `Error stores message`() {
			val state = WifiStatsLoadState.Error("Network failed")
			state.message shouldBe "Network failed"
		}

		@Test
		fun `Error equality works`() {
			val e1 = WifiStatsLoadState.Error("fail")
			val e2 = WifiStatsLoadState.Error("fail")
			e1 shouldBe e2
			e1.hashCode() shouldBe e2.hashCode()
		}
	}

	@Nested
	@DisplayName("Sealed hierarchy")
	inner class SealedHierarchy {

		@Test
		fun `when expression covers all subtypes`() {
			val states: List<WifiStatsLoadState> = listOf(
				WifiStatsLoadState.Idle,
				WifiStatsLoadState.Loading,
				WifiStatsLoadState.Empty,
				WifiStatsLoadState.Error("err"),
			)
			val labels = states.map { state ->
				when (state) {
					is WifiStatsLoadState.Idle -> "idle"
					is WifiStatsLoadState.Loading -> "loading"
					is WifiStatsLoadState.Empty -> "empty"
					is WifiStatsLoadState.Success -> "success"
					is WifiStatsLoadState.Error -> "error"
				}
			}
			labels shouldBe listOf("idle", "loading", "empty", "error")
		}
	}
}
