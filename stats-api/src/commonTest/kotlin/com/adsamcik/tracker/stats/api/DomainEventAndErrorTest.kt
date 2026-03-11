package com.adsamcik.tracker.stats.api

import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DomainEventAndErrorTest {

	@Test
	fun `domain event variants are constructible and discriminated exhaustively`() {
		val events = listOf(
			DomainEvent.SessionStarted(EpochMs(1L), "p1", true, PolicyTier.AMBIENT),
			DomainEvent.SessionEnded(EpochMs(2L), "p1", 11L, DistanceM(12f), StepCount(13), DurationMs(14L)),
			DomainEvent.TierChanged(EpochMs(3L), "p1", PolicyTier.AMBIENT, PolicyTier.ACTIVE, "movement"),
			DomainEvent.TripStarted(EpochMs(4L), "p1", DetectedActivityType.WALKING),
			DomainEvent.TripCompleted(EpochMs(5L), "p1", EpochMs(4L), DistanceM(100f), StepCount(150), DurationMs(50_000L), TransportMode.WALK),
			DomainEvent.CellDiscovered(EpochMs(6L), "p1", "cell-token", 15, 492000000, 166000000, 0, 1),
			DomainEvent.AchievementUnlocked(EpochMs(7L), "p1", "achv", "gold"),
			DomainEvent.AchievementProgress(EpochMs(8L), "p1", "achv", 4L, 10L),
			DomainEvent.DailySummaryUpdated(EpochMs(9L), "p1", 20_000L, DistanceM(500f), StepCount(700), DurationMs(80_000L), 3),
		)

		events.map(::eventTypeName).shouldContainExactlyInAnyOrder(
			"SessionStarted",
			"SessionEnded",
			"TierChanged",
			"TripStarted",
			"TripCompleted",
			"CellDiscovered",
			"AchievementUnlocked",
			"AchievementProgress",
			"DailySummaryUpdated",
		)
	}

	@Test
	fun `domain event data classes preserve value semantics`() {
		val original = DomainEvent.TripCompleted(
			timestampMs = EpochMs(123L),
			processorId = "trip-processor",
			tripStartMs = EpochMs(50L),
			distance = DistanceM(321.5f),
			steps = StepCount(456),
			duration = DurationMs(73_000L),
			primaryMode = TransportMode.CYCLE,
		)
		val copied = original.copy(distance = DistanceM(500f))

		copied.processorId shouldBe original.processorId
		copied.primaryMode shouldBe original.primaryMode
		copied.distance shouldBe DistanceM(500f)
	}

	@Test
	fun `stats error subtypes retain payload and support exhaustive handling`() {
		val cause = IllegalStateException("db down")
		val errors = listOf(
			StatsError.DatabaseError("db", cause),
			StatsError.ProcessorError("processor", "processor-id"),
			StatsError.CheckpointError("checkpoint"),
			StatsError.ValidationError("validation"),
			StatsError.NotFound("missing", "Trip", "42"),
		)

		errors.map(::errorTypeName).shouldContainExactlyInAnyOrder(
			"DatabaseError",
			"ProcessorError",
			"CheckpointError",
			"ValidationError",
			"NotFound",
		)
		(errors.first() as StatsError.DatabaseError).cause shouldBe cause
		(errors.last() as StatsError.NotFound).entityType shouldBe "Trip"
	}

	private fun eventTypeName(event: DomainEvent): String = when (event) {
		is DomainEvent.SessionStarted -> "SessionStarted"
		is DomainEvent.SessionEnded -> "SessionEnded"
		is DomainEvent.TierChanged -> "TierChanged"
		is DomainEvent.TripStarted -> "TripStarted"
		is DomainEvent.TripCompleted -> "TripCompleted"
		is DomainEvent.CellDiscovered -> "CellDiscovered"
		is DomainEvent.AchievementUnlocked -> "AchievementUnlocked"
		is DomainEvent.AchievementProgress -> "AchievementProgress"
		is DomainEvent.DailySummaryUpdated -> "DailySummaryUpdated"
	}

	private fun errorTypeName(error: StatsError): String = when (error) {
		is StatsError.DatabaseError -> "DatabaseError"
		is StatsError.ProcessorError -> "ProcessorError"
		is StatsError.CheckpointError -> "CheckpointError"
		is StatsError.ValidationError -> "ValidationError"
		is StatsError.NotFound -> "NotFound"
	}
}
