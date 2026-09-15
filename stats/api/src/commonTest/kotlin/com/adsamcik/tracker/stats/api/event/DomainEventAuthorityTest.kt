package com.adsamcik.tracker.stats.api.event

import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import kotlin.test.Test

class DomainEventAuthorityTest {
	@Test
	fun `achievement authority token is complete nonnegative and digest valid`() {
		shouldThrow<IllegalArgumentException> { unlock().copy(authorityRevision = 1L) }
		shouldThrow<IllegalArgumentException> { unlock().copy(authorityDigest = "a".repeat(64)) }
		shouldThrow<IllegalArgumentException> {
			unlock().copy(authorityRevision = -1L, authorityDigest = "a".repeat(64))
		}
		shouldThrow<IllegalArgumentException> {
			unlock().copy(authorityRevision = 1L, authorityDigest = "A".repeat(64))
		}
	}

	private fun unlock() = DomainEvent.AchievementUnlocked(
		timestampMs = EpochMs(1L),
		processorId = "test",
		achievementId = "goal_streak_3",
		tier = "BRONZE",
	)
}
