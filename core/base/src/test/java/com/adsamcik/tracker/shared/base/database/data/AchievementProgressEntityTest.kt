package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class AchievementProgressEntityTest {
	@Test
	fun `qualified authority must be complete and digest-valid`() {
		shouldThrow<IllegalArgumentException> {
			qualified().copy(authorityDigest = null)
		}
		shouldThrow<IllegalArgumentException> {
			qualified().copy(authorityRevision = null)
		}
		shouldThrow<IllegalArgumentException> {
			qualified().copy(authorityState = null)
		}
		shouldThrow<IllegalArgumentException> {
			qualified().copy(authorityRevision = -1L)
		}
		shouldThrow<IllegalArgumentException> {
			qualified().copy(authorityDigest = "A".repeat(64))
		}
		shouldThrow<IllegalArgumentException> {
			qualified().copy(lastUnlockedAt = -1L)
		}
		shouldThrow<IllegalArgumentException> {
			qualified().copy(qualifiedNotificationClaimedTierIndex = 0)
		}
		shouldThrow<IllegalArgumentException> {
			AchievementProgressEntity(
				metricKey = "distance_total_m",
				qualifiedNotificationClaimedTierIndex = 0,
			)
		}
	}

	private fun qualified() = AchievementProgressEntity(
		metricKey = "goal_streak_days",
		lastTierIndex = 1,
		lastValue = 7.0,
		updatedAt = 100L,
		authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
		authorityRevision = 4L,
		authorityDigest = "a".repeat(64),
		authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
	)
}
