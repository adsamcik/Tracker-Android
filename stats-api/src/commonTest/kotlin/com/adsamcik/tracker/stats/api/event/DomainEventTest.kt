package com.adsamcik.tracker.stats.api.event

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DomainEventTest {

	private val ts = EpochMs(1_700_000_000_000L)
	private val pid = "test-processor"

	@Nested
	inner class SessionEvents {

		@Test
		fun `SessionStarted preserves fields`() {
			val event = DomainEvent.SessionStarted(ts, pid, isUserInitiated = true, initialTier = PolicyTier.ACTIVE)
			event.timestampMs shouldBe ts
			event.processorId shouldBe pid
			event.isUserInitiated shouldBe true
			event.initialTier shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `SessionEnded uses value types`() {
			val event = DomainEvent.SessionEnded(
				ts, pid,
				sessionId = 42L,
				totalDistance = DistanceM(1500f),
				totalSteps = StepCount(2000),
				duration = DurationMs(3600_000L),
			)
			event.sessionId shouldBe 42L
			event.totalDistance.raw shouldBe 1500f
			event.totalSteps.raw shouldBe 2000
			event.duration.raw shouldBe 3600_000L
		}
	}

	@Nested
	inner class TierChangedEvent {

		@Test
		fun `preserves from and to tiers`() {
			val event = DomainEvent.TierChanged(ts, pid, PolicyTier.AMBIENT, PolicyTier.ACTIVE, "accumulator")
			event.fromTier shouldBe PolicyTier.AMBIENT
			event.toTier shouldBe PolicyTier.ACTIVE
			event.reason shouldBe "accumulator"
		}
	}

	@Nested
	inner class TripEvents {

		@Test
		fun `TripStarted with null trigger`() {
			val event = DomainEvent.TripStarted(ts, pid, triggerActivity = null)
			event.triggerActivity.shouldBeNull()
		}

		@Test
		fun `TripStarted with activity trigger`() {
			val event = DomainEvent.TripStarted(ts, pid, triggerActivity = DetectedActivityType.WALKING)
			event.triggerActivity shouldBe DetectedActivityType.WALKING
		}

		@Test
		fun `TripCompleted preserves all fields`() {
			val event = DomainEvent.TripCompleted(
				ts, pid,
				tripStartMs = EpochMs(1_699_999_000_000L),
				distance = DistanceM(800f),
				steps = StepCount(1200),
				duration = DurationMs(600_000L),
				primaryMode = TransportMode.WALK,
			)
			event.tripStartMs.raw shouldBe 1_699_999_000_000L
			event.distance.raw shouldBe 800f
			event.primaryMode shouldBe TransportMode.WALK
		}
	}

	@Nested
	inner class ExplorationEvent {

		@Test
		fun `CellDiscovered preserves token and level`() {
			val event = DomainEvent.CellDiscovered(
				ts, pid, cellToken = "h3_abc123", level = 7,
				centerLatE7 = 0, centerLonE7 = 0, quality = 0, seasonBit = 0,
			)
			event.cellToken shouldBe "h3_abc123"
			event.level shouldBe 7
		}
	}

	@Nested
	inner class GamificationEvents {

		@Test
		fun `AchievementUnlocked preserves fields`() {
			val event = DomainEvent.AchievementUnlocked(ts, pid, "explorer_cells", "Gold")
			event.achievementId shouldBe "explorer_cells"
			event.tier shouldBe "Gold"
		}

		@Test
		fun `AchievementProgress preserves values`() {
			val event = DomainEvent.AchievementProgress(ts, pid, "distance_walker", 45_000L, 50_000L)
			event.currentValue shouldBe 45_000L
			event.targetValue shouldBe 50_000L
		}

		@Test
		fun `ChallengeProgress preserves values`() {
			val event = DomainEvent.ChallengeProgress(
				ts, pid, challengeId = 42L, type = "Step",
				currentValue = 12_500.0, targetValue = 50_000.0,
			)
			event.challengeId shouldBe 42L
			event.type shouldBe "Step"
			event.currentValue shouldBe 12_500.0
		}

		@Test
		fun `ChallengeCompleted preserves values`() {
			val event = DomainEvent.ChallengeCompleted(
				ts, pid, challengeId = 17L, type = "Speed",
				currentValue = 2_100.0, targetValue = 2_000.0,
			)
			event.challengeId shouldBe 17L
			event.type shouldBe "Speed"
			event.currentValue shouldBe 2_100.0
		}
	}

	@Nested
	inner class AnalyticsEvent {

		@Test
		fun `DailySummaryUpdated preserves fields`() {
			val event = DomainEvent.DailySummaryUpdated(
				ts, pid,
				dayEpoch = 19700L,
				totalDistance = DistanceM(5000f),
				totalSteps = StepCount(6500),
				totalDuration = DurationMs(7200_000L),
				tripCount = 3,
			)
			event.dayEpoch shouldBe 19700L
			event.tripCount shouldBe 3
		}
	}

	@Nested
	inner class Polymorphism {

		@Test
		fun `all subtypes are DomainEvent`() {
			val events: List<DomainEvent> = listOf(
				DomainEvent.SessionStarted(ts, pid, false, PolicyTier.OFF),
				DomainEvent.SessionEnded(ts, pid, 1L, DistanceM.ZERO, StepCount.ZERO, DurationMs.ZERO),
				DomainEvent.TierChanged(ts, pid, PolicyTier.OFF, PolicyTier.AMBIENT, "init"),
				DomainEvent.TripStarted(ts, pid, null),
				DomainEvent.TripCompleted(ts, pid, ts, DistanceM.ZERO, StepCount.ZERO, DurationMs.ZERO, TransportMode.UNKNOWN),
				DomainEvent.CellDiscovered(ts, pid, "token", 5, 0, 0, 0, 0),
				DomainEvent.AchievementUnlocked(ts, pid, "a1", "Bronze"),
				DomainEvent.AchievementProgress(ts, pid, "a1", 0L, 10L),
				DomainEvent.ChallengeProgress(ts, pid, 1L, "Step", 0.0, 10.0),
				DomainEvent.ChallengeCompleted(ts, pid, 1L, "Step", 10.0, 10.0),
				DomainEvent.DailySummaryUpdated(ts, pid, 0L, DistanceM.ZERO, StepCount.ZERO, DurationMs.ZERO, 0),
			)
			events.forEach { it.shouldBeInstanceOf<DomainEvent>() }
			events.size shouldBe 11
		}

		@Test
		fun `when expression covers all subtypes`() {
			val event: DomainEvent = DomainEvent.CellDiscovered(ts, pid, "t", 1, 0, 0, 0, 0)
			val label = when (event) {
				is DomainEvent.SessionStarted -> "ss"
				is DomainEvent.SessionEnded -> "se"
				is DomainEvent.TierChanged -> "tc"
				is DomainEvent.TripStarted -> "ts"
				is DomainEvent.TripCompleted -> "tcm"
				is DomainEvent.CellDiscovered -> "cd"
				is DomainEvent.AchievementUnlocked -> "au"
				is DomainEvent.AchievementProgress -> "ap"
				is DomainEvent.ChallengeProgress -> "cp"
				is DomainEvent.ChallengeCompleted -> "cc"
				is DomainEvent.DailySummaryUpdated -> "dsu"
			}
			label shouldBe "cd"
		}
	}
}
