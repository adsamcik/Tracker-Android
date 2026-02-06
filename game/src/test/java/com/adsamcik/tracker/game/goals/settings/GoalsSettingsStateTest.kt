package com.adsamcik.tracker.game.goals.settings

import com.adsamcik.tracker.shared.base.Time
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [GoalsSettingsState] data class construction, equality, copy semantics,
 * and the boundary constants used by [DefaultGoalsSettingsRepository] for coercion.
 */
@DisplayName("GoalsSettingsState")
class GoalsSettingsStateTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `stores all fields correctly`() {
			val state = GoalsSettingsState(
				notificationsEnabled = true,
				dailyStepGoal = 8000,
				weeklyStepGoal = 50000,
				weeklyProgressDailyLimit = 0.5f
			)

			state.notificationsEnabled shouldBe true
			state.dailyStepGoal shouldBe 8000
			state.weeklyStepGoal shouldBe 50000
			state.weeklyProgressDailyLimit shouldBe 0.5f
		}

		@Test
		fun `typical user configuration`() {
			val state = GoalsSettingsState(
				notificationsEnabled = false,
				dailyStepGoal = 10000,
				weeklyStepGoal = 70000,
				weeklyProgressDailyLimit = 1f / 7f
			)

			state.notificationsEnabled shouldBe false
			state.dailyStepGoal shouldBe 10000
			state.weeklyStepGoal shouldBe 70000
			state.weeklyProgressDailyLimit shouldBe 1f / 7f
		}

		@Test
		fun `boundary values - zero step goal and max daily limit`() {
			val state = GoalsSettingsState(
				notificationsEnabled = false,
				dailyStepGoal = 0,
				weeklyStepGoal = 0,
				weeklyProgressDailyLimit = Float.MAX_VALUE
			)

			state.dailyStepGoal shouldBe 0
			state.weeklyStepGoal shouldBe 0
			state.weeklyProgressDailyLimit shouldBe Float.MAX_VALUE
		}

		@Test
		fun `negative step goals are stored as-is by data class`() {
			// The data class itself does not enforce coercion.
			// Coercion is the repository's responsibility.
			val state = GoalsSettingsState(
				notificationsEnabled = true,
				dailyStepGoal = -5,
				weeklyStepGoal = -100,
				weeklyProgressDailyLimit = -0.5f
			)

			state.dailyStepGoal shouldBe -5
			state.weeklyStepGoal shouldBe -100
			state.weeklyProgressDailyLimit shouldBe -0.5f
		}
	}

	@Nested
	@DisplayName("Equality")
	inner class Equality {

		@Test
		fun `same values are equal`() {
			val a = GoalsSettingsState(
				notificationsEnabled = true,
				dailyStepGoal = 5000,
				weeklyStepGoal = 35000,
				weeklyProgressDailyLimit = 0.25f
			)
			val b = GoalsSettingsState(
				notificationsEnabled = true,
				dailyStepGoal = 5000,
				weeklyStepGoal = 35000,
				weeklyProgressDailyLimit = 0.25f
			)

			a shouldBe b
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `different notificationsEnabled are not equal`() {
			val a = GoalsSettingsState(true, 5000, 35000, 0.25f)
			val b = GoalsSettingsState(false, 5000, 35000, 0.25f)

			a shouldNotBe b
		}

		@Test
		fun `different dailyStepGoal are not equal`() {
			val a = GoalsSettingsState(true, 5000, 35000, 0.25f)
			val b = GoalsSettingsState(true, 6000, 35000, 0.25f)

			a shouldNotBe b
		}

		@Test
		fun `different weeklyStepGoal are not equal`() {
			val a = GoalsSettingsState(true, 5000, 35000, 0.25f)
			val b = GoalsSettingsState(true, 5000, 40000, 0.25f)

			a shouldNotBe b
		}

		@Test
		fun `different weeklyProgressDailyLimit are not equal`() {
			val a = GoalsSettingsState(true, 5000, 35000, 0.25f)
			val b = GoalsSettingsState(true, 5000, 35000, 0.50f)

			a shouldNotBe b
		}
	}

	@Nested
	@DisplayName("Copy semantics")
	inner class CopySemantics {

		private val original = GoalsSettingsState(
			notificationsEnabled = false,
			dailyStepGoal = 7500,
			weeklyStepGoal = 52500,
			weeklyProgressDailyLimit = 0.3f
		)

		@Test
		fun `copy with no changes is equal to original`() {
			original.copy() shouldBe original
		}

		@Test
		fun `copy with changed notificationsEnabled preserves other fields`() {
			val copied = original.copy(notificationsEnabled = true)

			copied.notificationsEnabled shouldBe true
			copied.dailyStepGoal shouldBe original.dailyStepGoal
			copied.weeklyStepGoal shouldBe original.weeklyStepGoal
			copied.weeklyProgressDailyLimit shouldBe original.weeklyProgressDailyLimit
		}

		@Test
		fun `copy with changed dailyStepGoal preserves other fields`() {
			val copied = original.copy(dailyStepGoal = 12000)

			copied.notificationsEnabled shouldBe original.notificationsEnabled
			copied.dailyStepGoal shouldBe 12000
			copied.weeklyStepGoal shouldBe original.weeklyStepGoal
			copied.weeklyProgressDailyLimit shouldBe original.weeklyProgressDailyLimit
		}

		@Test
		fun `copy with changed weeklyProgressDailyLimit preserves other fields`() {
			val copied = original.copy(weeklyProgressDailyLimit = 1.0f)

			copied.notificationsEnabled shouldBe original.notificationsEnabled
			copied.dailyStepGoal shouldBe original.dailyStepGoal
			copied.weeklyStepGoal shouldBe original.weeklyStepGoal
			copied.weeklyProgressDailyLimit shouldBe 1.0f
		}
	}

	@Nested
	@DisplayName("Coercion boundary constants")
	inner class CoercionBoundaryConstants {

		// These mirror the private constants in DefaultGoalsSettingsRepository.
		// MIN_DAILY_PORTION = 1f / Time.WEEK_IN_DAYS.toFloat()
		// MAX_DAILY_PORTION = 1f
		private val expectedMinDailyPortion: Float = 1f / Time.WEEK_IN_DAYS.toFloat()
		private val expectedMaxDailyPortion: Float = 1f

		@Test
		fun `WEEK_IN_DAYS is 7`() {
			Time.WEEK_IN_DAYS shouldBe 7L
		}

		@Test
		fun `MIN_DAILY_PORTION equals one seventh`() {
			expectedMinDailyPortion shouldBe 1f / 7f
		}

		@Test
		fun `MAX_DAILY_PORTION equals 1`() {
			expectedMaxDailyPortion shouldBe 1f
		}

		@Test
		fun `MIN_DAILY_PORTION is positive`() {
			expectedMinDailyPortion shouldBeGreaterThan 0f
		}

		@Test
		fun `MIN_DAILY_PORTION is less than MAX_DAILY_PORTION`() {
			expectedMinDailyPortion shouldBeLessThan expectedMaxDailyPortion
		}
	}

	@Nested
	@DisplayName("Step goal coercion behavior")
	inner class StepGoalCoercion {

		// Documents the coercion behavior used in DefaultGoalsSettingsRepository:
		// steps.coerceAtLeast(1)

		@Test
		fun `zero is coerced to 1`() {
			0.coerceAtLeast(1) shouldBe 1
		}

		@Test
		fun `negative value is coerced to 1`() {
			(-5).coerceAtLeast(1) shouldBe 1
		}

		@Test
		fun `one remains 1`() {
			1.coerceAtLeast(1) shouldBe 1
		}

		@Test
		fun `positive value above minimum is unchanged`() {
			100.coerceAtLeast(1) shouldBe 100
		}

		@Test
		fun `large negative value is coerced to 1`() {
			Int.MIN_VALUE.coerceAtLeast(1) shouldBe 1
		}
	}

	@Nested
	@DisplayName("Weekly daily limit coercion behavior")
	inner class WeeklyDailyLimitCoercion {

		// Documents the coercion behavior used in DefaultGoalsSettingsRepository:
		// fraction.coerceIn(MIN_DAILY_PORTION, MAX_DAILY_PORTION)
		private val minPortion: Float = 1f / Time.WEEK_IN_DAYS.toFloat()
		private val maxPortion: Float = 1f

		@Test
		fun `value below minimum is clamped to minimum`() {
			0f.coerceIn(minPortion, maxPortion) shouldBe minPortion
		}

		@Test
		fun `negative value is clamped to minimum`() {
			(-1f).coerceIn(minPortion, maxPortion) shouldBe minPortion
		}

		@Test
		fun `value above maximum is clamped to maximum`() {
			2f.coerceIn(minPortion, maxPortion) shouldBe maxPortion
		}

		@Test
		fun `value at minimum boundary is unchanged`() {
			minPortion.coerceIn(minPortion, maxPortion) shouldBe minPortion
		}

		@Test
		fun `value at maximum boundary is unchanged`() {
			maxPortion.coerceIn(minPortion, maxPortion) shouldBe maxPortion
		}

		@Test
		fun `value within range is unchanged`() {
			0.5f.coerceIn(minPortion, maxPortion) shouldBe 0.5f
		}
	}
}
