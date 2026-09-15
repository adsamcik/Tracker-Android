package com.adsamcik.tracker.game.goals

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GoalTrackerTest {
	@Test
	fun `new day emits a payload-free calendar invalidation`() = runTest {
		val invalidation = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
			GoalTracker.calendarInvalidations.first()
		}

		GoalTracker.onNewDay()

		invalidation.await() shouldBe Unit
	}
}
