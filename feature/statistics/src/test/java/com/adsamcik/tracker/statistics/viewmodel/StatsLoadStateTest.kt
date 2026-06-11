package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StatsLoadState and DayBar")
class StatsLoadStateTest {

	@Nested
	@DisplayName("StatsLoadState variants")
	inner class StatsLoadStateVariants {

		@Test
		fun `Idle is a singleton`() {
			val state: StatsLoadState = StatsLoadState.Idle
			state.shouldBeInstanceOf<StatsLoadState.Idle>()
		}

		@Test
		fun `Loading is a singleton`() {
			val state: StatsLoadState = StatsLoadState.Loading
			state.shouldBeInstanceOf<StatsLoadState.Loading>()
		}

		@Test
		fun `Success stores stats list`() {
			val stats = listOf(
				Stat(nameRes = 1, iconRes = 2, displayType = StatisticDisplayType.INFORMATION, data = "value")
			)
			val state = StatsLoadState.Success(stats)
			state.stats shouldHaveSize 1
			state.stats[0].nameRes shouldBe 1
		}

		@Test
		fun `Success with empty list`() {
			val state = StatsLoadState.Success(emptyList())
			state.stats.shouldBeEmpty()
		}

		@Test
		fun `Error stores message`() {
			val state = StatsLoadState.Error("Something broke")
			state.message shouldBe "Something broke"
		}

		@Test
		fun `Error equality`() {
			val e1 = StatsLoadState.Error("x")
			val e2 = StatsLoadState.Error("x")
			e1 shouldBe e2
		}
	}

	@Nested
	@DisplayName("Sealed hierarchy")
	inner class SealedHierarchy {

		@Test
		fun `when expression covers all subtypes`() {
			val states: List<StatsLoadState> = listOf(
				StatsLoadState.Idle,
				StatsLoadState.Loading,
				StatsLoadState.Success(emptyList()),
				StatsLoadState.Error("err"),
			)
			val labels = states.map { state ->
				when (state) {
					is StatsLoadState.Idle -> "idle"
					is StatsLoadState.Loading -> "loading"
					is StatsLoadState.Success -> "success"
					is StatsLoadState.Error -> "error"
				}
			}
			labels shouldBe listOf("idle", "loading", "success", "error")
		}
	}

	@Nested
	@DisplayName("DayBar")
	inner class DayBarTests {

		@Test
		fun `stores all fields`() {
			val bar = DayBar(
				dayLabel = "Mon",
				distanceM = 1500f,
				steps = 3000,
				epochDay = 19800L,
				sessionCount = 2
			)
			bar.dayLabel shouldBe "Mon"
			bar.distanceM shouldBe 1500f
			bar.steps shouldBe 3000
			bar.epochDay shouldBe 19800L
			bar.sessionCount shouldBe 2
		}

		@Test
		fun `default sessionCount is 0`() {
			val bar = DayBar(dayLabel = "Tue", distanceM = 0f, steps = 0, epochDay = 0L)
			bar.sessionCount shouldBe 0
		}

		@Test
		fun `equality works`() {
			val b1 = DayBar(dayLabel = "Wed", distanceM = 100f, steps = 200, epochDay = 1L)
			val b2 = DayBar(dayLabel = "Wed", distanceM = 100f, steps = 200, epochDay = 1L)
			b1 shouldBe b2
			b1.hashCode() shouldBe b2.hashCode()
		}

		@Test
		fun `inequality for different values`() {
			val b1 = DayBar(dayLabel = "Wed", distanceM = 100f, steps = 200, epochDay = 1L)
			val b2 = DayBar(dayLabel = "Thu", distanceM = 100f, steps = 200, epochDay = 2L)
			b1 shouldNotBe b2
		}

		@Test
		fun `copy works`() {
			val original = DayBar(dayLabel = "Fri", distanceM = 500f, steps = 1000, epochDay = 5L)
			val copy = original.copy(distanceM = 1000f)
			copy.distanceM shouldBe 1000f
			copy.dayLabel shouldBe "Fri"
		}

		@Test
		fun `destructuring works`() {
			val bar = DayBar(dayLabel = "Sat", distanceM = 750f, steps = 1500, epochDay = 6L, sessionCount = 3)
			val (dayLabel, distanceM, steps, epochDay, sessionCount) = bar
			dayLabel shouldBe "Sat"
			distanceM shouldBe 750f
			steps shouldBe 1500
			epochDay shouldBe 6L
			sessionCount shouldBe 3
		}
	}
}
