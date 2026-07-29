package com.adsamcik.tracker.game.goals.data.implementation

import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.BaseGoal
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for [WeeklyStepGoal] covering properties, step accumulation,
 * weekly aggregation, and week-boundary getGoalTime encoding using ISO 8601 weeks.
 */
@DisplayName("WeeklyStepGoal")
class WeeklyStepGoalTest {

	private lateinit var goal: WeeklyStepGoal
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
		goal = WeeklyStepGoal(
			persistence = persistence,
			initialTarget = 70_000,
			initialDailyLimit = 1f,
		)
	}

	private fun createSession(id: Long = 1L, steps: Int = 100) = TrackerSessionSnapshot(
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
		val method = WeeklyStepGoal::class.java.getDeclaredMethod(
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
		fun `period is Week`() {
			goal.period shouldBe BaseGoal.GoalPeriod.Week
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
		fun `initial target is explicitly supplied`() {
			goal.target shouldBe 70_000
		}

		@Test
		fun `is not enabled by default`() {
			goal.isEnabled shouldBe false
		}
	}

	@Nested
	@DisplayName("Settings-backed cap updates")
	inner class SettingsBackedCapUpdates {
		@Test
		fun `changing cap recomputes live weekly progress immediately`() {
			goal.updateConfiguration(target = 20_000, dailyLimit = 0.3f)
			goal.onCumulativeStepsUpdated(9_000)
			goal.value shouldBe 6_000

			goal.updateConfiguration(target = 20_000, dailyLimit = 0.5f)

			goal.value shouldBe 9_000
		}

		@Test
		fun `initial settings synchronization does not consume completion`() {
			goal.onCumulativeStepsUpdated(30_000)

			goal.updateConfiguration(
				target = 20_000,
				dailyLimit = 1f,
				evaluateCompletion = false,
			) shouldBe false

			goal.onCumulativeStepsUpdated(30_000) shouldBe true
		}
	}

	@Nested
	@DisplayName("Step accumulation")
	inner class StepAccumulation {

		@BeforeEach
		fun setHighTarget() {
			setTarget(Int.MAX_VALUE)
		}

		@Test
		fun `new session adds all steps to value`() {
			goal.onSessionUpdated(createSession(steps = 1000), isNewSession = true)

			goal.value shouldBe 1000
		}

		@Test
		fun `continuing session adds step difference`() {
			goal.onSessionUpdated(createSession(id = 1L, steps = 200), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 1L, steps = 500), isNewSession = false)

			goal.value shouldBe 500
		}

		@Test
		fun `multiple sessions across the week accumulate`() {
			goal.onSessionUpdated(createSession(id = 1L, steps = 3000), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 2L, steps = 4000), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 3L, steps = 2000), isNewSession = true)

			goal.value shouldBe 9000
		}

		@Test
		fun `mixed new and continuing sessions accumulate correctly`() {
			// Day 1: walk session
			goal.onSessionUpdated(createSession(id = 1L, steps = 2000), isNewSession = true)
			goal.onSessionUpdated(createSession(id = 1L, steps = 5000), isNewSession = false)
			// Day 2: new session
			goal.onSessionUpdated(createSession(id = 2L, steps = 3000), isNewSession = true)

			// 2000 (new) + 3000 (diff) + 3000 (new) = 8000
			goal.value shouldBe 8000
		}
	}

	@Nested
	@DisplayName("Weekly aggregation and goal completion")
	inner class WeeklyAggregation {

		@Test
		fun `returns false when partial week progress is below target`() {
			setTarget(50000)

			val result = goal.onSessionUpdated(createSession(steps = 10000), isNewSession = true)

			result shouldBe false
		}

		@Test
		fun `returns true when weekly total meets target`() {
			setTarget(10000)

			goal.onSessionUpdated(createSession(id = 1L, steps = 5000), isNewSession = true)
			val result = goal.onSessionUpdated(createSession(id = 2L, steps = 5000), isNewSession = true)

			result shouldBe true
		}

		@Test
		fun `returns true when weekly total exceeds target`() {
			setTarget(10000)

			val result = goal.onSessionUpdated(createSession(steps = 15000), isNewSession = true)

			result shouldBe true
		}

		@Test
		fun `does not re-report after weekly goal reached`() {
			setTarget(10000)

			goal.onSessionUpdated(createSession(id = 1L, steps = 12000), isNewSession = true)
			val second = goal.onSessionUpdated(createSession(id = 2L, steps = 3000), isNewSession = true)

			second shouldBe false
		}

		@Test
		fun `partial week progress tracked correctly`() {
			setTarget(70000)

			goal.onSessionUpdated(createSession(id = 1L, steps = 10000), isNewSession = true)
			goal.value shouldBe 10000

			goal.onSessionUpdated(createSession(id = 2L, steps = 10000), isNewSession = true)
			goal.value shouldBe 20000

			goal.onSessionUpdated(createSession(id = 3L, steps = 10000), isNewSession = true)
			goal.value shouldBe 30000
		}
	}

	@Nested
	@DisplayName("Week boundary - getGoalTime")
	inner class WeekBoundary {

		// 2024-06-10 is a Monday (ISO week 24, 2024)
		private val zone = ZoneId.systemDefault()

		@Test
		fun `same week returns same goal time`() {
			val monday = ZonedDateTime.of(2024, 6, 10, 8, 0, 0, 0, zone)
			val friday = ZonedDateTime.of(2024, 6, 14, 20, 0, 0, 0, zone)
			val sunday = ZonedDateTime.of(2024, 6, 16, 23, 59, 0, 0, zone)

			val timeMonday = callGetGoalTime(monday)
			val timeFriday = callGetGoalTime(friday)
			val timeSunday = callGetGoalTime(sunday)

			timeMonday shouldBe timeFriday
			timeFriday shouldBe timeSunday
		}

		@Test
		fun `different weeks return different goal times`() {
			val thisWeek = ZonedDateTime.of(2024, 6, 10, 12, 0, 0, 0, zone)
			val nextWeek = ZonedDateTime.of(2024, 6, 17, 12, 0, 0, 0, zone)

			callGetGoalTime(thisWeek) shouldNotBe callGetGoalTime(nextWeek)
		}

		@Test
		fun `encodes ISO week and week-based year`() {
			val day = ZonedDateTime.of(2024, 6, 12, 12, 0, 0, 0, zone)
			// ISO week 24, year 2024 → 24 + 2024*100 = 202424
			val expected = 24 + 2024 * 100

			callGetGoalTime(day) shouldBe expected
		}

		@Test
		fun `year boundary - late December may belong to next ISO year`() {
			// 2024-12-30 (Monday) is ISO week 1 of 2025
			val dec30 = ZonedDateTime.of(2024, 12, 30, 12, 0, 0, 0, zone)
			// Expected: week 1 of ISO year 2025 → 1 + 2025*100 = 202501
			callGetGoalTime(dec30) shouldBe 202501
		}

		@Test
		fun `year boundary - early January may belong to previous ISO year`() {
			// 2025-01-01 (Wednesday) is still ISO week 1 of 2025
			val jan1 = ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, zone)
			callGetGoalTime(jan1) shouldBe 202501
		}

		@Test
		fun `consecutive weeks have sequential values`() {
			val week1 = ZonedDateTime.of(2024, 6, 10, 12, 0, 0, 0, zone) // Week 24
			val week2 = ZonedDateTime.of(2024, 6, 17, 12, 0, 0, 0, zone) // Week 25

			val time1 = callGetGoalTime(week1)
			val time2 = callGetGoalTime(week2)

			(time2 - time1) shouldBe 1
		}

		@Test
		fun `midweek boundary - Sunday and Monday in different weeks`() {
			// ISO weeks start on Monday
			val sunday = ZonedDateTime.of(2024, 6, 16, 23, 59, 0, 0, zone) // Week 24
			val monday = ZonedDateTime.of(2024, 6, 17, 0, 1, 0, 0, zone)   // Week 25

			callGetGoalTime(sunday) shouldNotBe callGetGoalTime(monday)
		}
	}
}
