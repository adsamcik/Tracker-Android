package com.adsamcik.tracker.game.goals.data.implementation

import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.BaseGoal
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for [DailyStepGoal] covering properties, step accumulation,
 * goal completion logic, and day-boundary getGoalTime encoding.
 */
@DisplayName("DailyStepGoal")
class DailyStepGoalTest {

	private lateinit var goal: DailyStepGoal
	private lateinit var persistence: FakeGoalPersistence

	private class FakeGoalPersistence : GoalPersistence {
		private val store = mutableMapOf<String, Int>()
		override suspend fun persist(key: String, value: Int) {
			require(value >= 0)
			store[key] = value
		}

		override suspend fun load(key: String): Int? = store[key]
	}

	@BeforeEach
	fun setUp() {
		persistence = FakeGoalPersistence()
		goal = DailyStepGoal(persistence)
	}

	private fun createSession(id: Long = 1L, steps: Int = 100) = TrackerSession(
		id = id,
		start = 1000L,
		end = 2000L,
		isUserInitiated = true,
		collections = 1,
		distanceInM = 100f,
		distanceOnFootInM = 80f,
		distanceInVehicleInM = 0f,
		steps = steps,
	)

	/** Sets the target field directly, bypassing the protected setter. */
	private fun setTarget(target: Int) {
		val field = BaseGoal::class.java.getDeclaredField("target")
		field.isAccessible = true
		field.setInt(goal, target)
	}

	/** Calls the protected getGoalTime method via reflection. */
	private fun callGetGoalTime(day: ZonedDateTime): Int {
		val method = DailyStepGoal::class.java.getDeclaredMethod(
			"getGoalTime",
			ZonedDateTime::class.java,
		)
		method.isAccessible = true
		return method.invoke(goal, day) as Int
	}

	@Nested
	@DisplayName("Properties")
	inner class Properties {

		@Test
		fun `period is Day`() {
			goal.period shouldBe BaseGoal.GoalPeriod.Day
		}

		@Test
		fun `pointMultiplier is 0 dot 01`() {
			goal.pointMultiplier shouldBe 0.01
		}

		@Test
		fun `initial value is zero`() {
			goal.value shouldBe 0
		}

		@Test
		fun `initial target is zero`() {
			goal.target shouldBe 0
		}

		@Test
		fun `is not enabled by default`() {
			goal.isEnabled shouldBe false
		}
	}

	@Nested
	@DisplayName("Step accumulation")
	inner class StepAccumulation {

		@BeforeEach
		fun setHighTarget() {
			// Prevent goal completion from interfering with accumulation tests
			setTarget(Int.MAX_VALUE)
		}

		@Test
		fun `new session adds all steps to value`() {
			goal.onSessionUpdated(createSession(steps = 500), isNewSession = true)

			goal.value shouldBe 500
		}

		@Test
		fun `continuing session adds step difference`() {
			goal.onSessionUpdated(createSession(id = 1L, steps = 100), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 1L, steps = 250), isNewSession = false)

			goal.value shouldBe 250
		}

		@Test
		fun `multiple new sessions accumulate steps`() {
			goal.onSessionUpdated(createSession(id = 1L, steps = 300), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 2L, steps = 200), isNewSession = true)

			goal.value shouldBe 500
		}

		@Test
		fun `zero steps on new session does not change value`() {
			goal.onSessionUpdated(createSession(steps = 0), isNewSession = true)

			goal.value shouldBe 0
		}

		@Test
		fun `incremental updates within same session accumulate correctly`() {
			goal.onSessionUpdated(createSession(id = 1L, steps = 100), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 1L, steps = 200), isNewSession = false)
			goal.onSessionUpdated(createSession(id = 1L, steps = 350), isNewSession = false)

			// Total: 100 (new) + 100 (diff 200-100) + 150 (diff 350-200) = 350
			goal.value shouldBe 350
		}

		@Test
		fun `new session after continuing session resets diff tracking`() {
			goal.onSessionUpdated(createSession(id = 1L, steps = 100), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 1L, steps = 300), isNewSession = false)
			// value = 300 (100 + 200 diff), lastStepValue = 300
			goal.onSessionUpdated(createSession(id = 2L, steps = 50), isNewSession = true)
			// New session: diff = 50 (all steps), value = 300 + 50 = 350

			goal.value shouldBe 350
		}
	}

	@Nested
	@DisplayName("Goal completion")
	inner class GoalCompletion {

		@Test
		fun `returns true when value meets target`() {
			setTarget(500)

			val result = goal.onSessionUpdated(createSession(steps = 500), isNewSession = true)

			result shouldBe true
		}

		@Test
		fun `returns true when value exceeds target`() {
			setTarget(500)

			val result = goal.onSessionUpdated(createSession(steps = 700), isNewSession = true)

			result shouldBe true
		}

		@Test
		fun `returns false when value is below target`() {
			setTarget(1000)

			val result = goal.onSessionUpdated(createSession(steps = 500), isNewSession = true)

			result shouldBe false
		}

		@Test
		fun `does not re-report after goal completion`() {
			setTarget(500)

			val first = goal.onSessionUpdated(createSession(id = 1L, steps = 600), isNewSession = true)
			val second = goal.onSessionUpdated(createSession(id = 2L, steps = 100), isNewSession = true)

			first shouldBe true
			second shouldBe false
		}

		@Test
		fun `reports completion when accumulation crosses target`() {
			setTarget(500)

			val below = goal.onSessionUpdated(createSession(id = 1L, steps = 300), isNewSession = true)
			val crosses = goal.onSessionUpdated(createSession(id = 2L, steps = 200), isNewSession = true)

			below shouldBe false
			crosses shouldBe true
		}

		@Test
		fun `with default target zero any positive steps triggers completion`() {
			// target defaults to 0; value >= 0 is always true after update
			val result = goal.onSessionUpdated(createSession(steps = 1), isNewSession = true)

			result shouldBe true
		}

		@Test
		fun `with default target zero subsequent updates do not re-report`() {
			goal.onSessionUpdated(createSession(id = 1L, steps = 1), isNewSession = true)
			val second = goal.onSessionUpdated(createSession(id = 2L, steps = 100), isNewSession = true)

			second shouldBe false
		}
	}

	@Nested
	@DisplayName("Progress percentage")
	inner class ProgressPercentage {

		@Test
		fun `progress can be calculated from value and target`() {
			setTarget(1000)
			goal.onSessionUpdated(createSession(steps = 250), isNewSession = true)

			val progress = if (goal.target > 0) {
				goal.value.toDouble() / goal.target
			} else {
				1.0
			}

			progress shouldBe 0.25
		}

		@Test
		fun `progress at zero target is 100 percent by convention`() {
			// When target is 0, any progress is considered complete
			val progress = if (goal.target > 0) {
				goal.value.toDouble() / goal.target
			} else {
				1.0
			}

			progress shouldBe 1.0
		}

		@Test
		fun `progress exceeding target can be over 100 percent`() {
			setTarget(500)
			goal.onSessionUpdated(createSession(steps = 750), isNewSession = true)

			val progress = goal.value.toDouble() / goal.target

			progress shouldBe 1.5
		}
	}

	@Nested
	@DisplayName("Day boundary - getGoalTime")
	inner class DayBoundary {

		@Test
		fun `same day returns same goal time`() {
			val zone = ZoneId.systemDefault()
			val morning = ZonedDateTime.of(2024, 6, 10, 8, 0, 0, 0, zone)
			val evening = ZonedDateTime.of(2024, 6, 10, 20, 30, 0, 0, zone)

			callGetGoalTime(morning) shouldBe callGetGoalTime(evening)
		}

		@Test
		fun `different days return different goal times`() {
			val zone = ZoneId.systemDefault()
			val day1 = ZonedDateTime.of(2024, 6, 10, 12, 0, 0, 0, zone)
			val day2 = ZonedDateTime.of(2024, 6, 11, 12, 0, 0, 0, zone)

			callGetGoalTime(day1) shouldNotBe callGetGoalTime(day2)
		}

		@Test
		fun `encodes year and day of year`() {
			val zone = ZoneId.systemDefault()
			val day = ZonedDateTime.of(2024, 6, 10, 12, 0, 0, 0, zone)
			val expected = day.dayOfYear + day.year * 1000

			callGetGoalTime(day) shouldBe expected
		}

		@Test
		fun `year boundary produces different values`() {
			val zone = ZoneId.systemDefault()
			val dec31 = ZonedDateTime.of(2024, 12, 31, 23, 59, 0, 0, zone)
			val jan1 = ZonedDateTime.of(2025, 1, 1, 0, 1, 0, 0, zone)

			callGetGoalTime(dec31) shouldNotBe callGetGoalTime(jan1)
		}

		@Test
		fun `first day of year encodes correctly`() {
			val zone = ZoneId.systemDefault()
			val jan1 = ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, zone)

			// dayOfYear=1, year=2025 → 1 + 2025*1000 = 2025001
			callGetGoalTime(jan1) shouldBe 2025001
		}

		@Test
		fun `last day of leap year encodes correctly`() {
			val zone = ZoneId.systemDefault()
			val dec31 = ZonedDateTime.of(2024, 12, 31, 12, 0, 0, 0, zone)

			// 2024 is a leap year: dayOfYear=366, year=2024 → 366 + 2024*1000 = 2024366
			callGetGoalTime(dec31) shouldBe 2024366
		}
	}
}
