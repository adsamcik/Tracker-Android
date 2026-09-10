package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsGoalEffectEntityTest {
	@Test
	fun `identity period and exact weekly policy are structural authority`() {
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(effectIdentity = "steps-goal-v1:DAY:other")
		}
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(periodEndEpochDay = START_DAY + 1L)
		}
		assertThrows(ArithmeticException::class.java) {
			complete().copy(
				effectIdentity = StepsGoalEffectEntity.identity(
					StepsGoalEffectEntity.PERIOD_WEEK,
					Long.MAX_VALUE - 5L,
				),
				periodKind = StepsGoalEffectEntity.PERIOD_WEEK,
				periodStartEpochDay = Long.MAX_VALUE - 5L,
				periodEndEpochDay = Long.MAX_VALUE,
				qualifiedThroughEpochDay = Long.MAX_VALUE,
				weeklyDailyLimitBits = 0.5f.toBits(),
			)
		}
		assertThrows(IllegalArgumentException::class.java) {
			weekly().copy(weeklyDailyLimitBits = Float.NaN.toBits())
		}
		assertThrows(IllegalArgumentException::class.java) {
			weekly().copy(weeklyDailyLimitBits = 1.01f.toBits())
		}
	}

	@Test
	fun `decision states cannot fabricate zero or retain effects while unresolved`() {
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(qualifiedSteps = null)
		}
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(desiredPointsMicros = 0L)
		}
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(firstCompletedAtMs = null)
		}
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(firstCompletedAtMs = 101L)
		}
		assertThrows(IllegalArgumentException::class.java) {
			incomplete().copy(desiredXp = 1)
		}
		assertThrows(IllegalArgumentException::class.java) {
			materializing().copy(qualifiedSteps = 0L)
		}
		assertThrows(IllegalArgumentException::class.java) {
			unverifiable().copy(unavailableReason = null)
		}
	}

	@Test
	fun `source digest settlement and notification claims are bounded`() {
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(sourceAuthorityDigest = "A".repeat(64))
		}
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(pointsAppliedRevision = 2L)
		}
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(notificationClaimedRevision = 1L)
		}
		assertThrows(IllegalArgumentException::class.java) {
			complete().copy(notificationClaimedAtMs = 1L)
		}
	}

	@Test
	fun `semantic equality excludes observation counters but includes source authority`() {
		val original = complete()
		assertTrue(
			original.hasSameDecision(
				original.copy(sourceEvidenceRevision = 8L, updatedAtMs = 500L),
			),
		)
		assertFalse(
			original.hasSameDecision(
				original.copy(sourceAuthorityDigest = "b".repeat(64)),
			),
		)
	}

	private fun complete() = StepsGoalEffectEntity(
		effectIdentity = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_DAY, START_DAY),
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = START_DAY,
		periodEndEpochDay = START_DAY,
		qualifiedThroughEpochDay = START_DAY,
		calendarAuthority = "$START_DAY=Europe/Prague",
		targetSteps = 10_000L,
		weeklyDailyLimitBits = null,
		decisionState = StepsGoalEffectEntity.STATE_READY_COMPLETE,
		unavailableReason = null,
		qualifiedSteps = 12_000L,
		sourceAuthorityDigest = "a".repeat(64),
		sourceEvidenceRevision = 4L,
		effectRevision = 1L,
		desiredPointsMicros = 100_000_000L,
		desiredXp = 25,
		firstCompletedAtMs = 100L,
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = 100L,
	)

	private fun incomplete() = complete().copy(
		decisionState = StepsGoalEffectEntity.STATE_READY_INCOMPLETE,
		qualifiedSteps = 9_999L,
		desiredPointsMicros = 0L,
		desiredXp = 0,
	)

	private fun materializing() = incomplete().copy(
		decisionState = StepsGoalEffectEntity.STATE_MATERIALIZING,
		qualifiedSteps = null,
	)

	private fun unverifiable() = materializing().copy(
		decisionState = StepsGoalEffectEntity.STATE_UNVERIFIABLE,
		unavailableReason = "LEGACY_BINDING_UNVERIFIABLE",
	)

	private fun weekly() = complete().copy(
		effectIdentity = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_WEEK, START_DAY),
		periodKind = StepsGoalEffectEntity.PERIOD_WEEK,
		periodEndEpochDay = START_DAY + 6L,
		qualifiedThroughEpochDay = START_DAY + 2L,
		calendarAuthority = (START_DAY..START_DAY + 2L).joinToString("\n") { day ->
			"$day=Europe/Prague"
		},
		weeklyDailyLimitBits = 0.5f.toBits(),
	)

	private companion object {
		const val START_DAY = 20_000L
	}
}
