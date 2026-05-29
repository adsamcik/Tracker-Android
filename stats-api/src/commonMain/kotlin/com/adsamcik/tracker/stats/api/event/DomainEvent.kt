package com.adsamcik.tracker.stats.api.event

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount

/**
 * Domain events emitted by processors and consumed by downstream contexts.
 * Persisted in a Room event table for crash-safe, ordered, replayable delivery.
 */
sealed interface DomainEvent {
	val timestampMs: EpochMs
	val processorId: String

	// --- Tracking context ---
	data class SessionStarted(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val isUserInitiated: Boolean,
		val initialTier: PolicyTier,
	) : DomainEvent

	data class SessionEnded(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val sessionId: Long,
		val totalDistance: DistanceM,
		val totalSteps: StepCount,
		val duration: DurationMs,
	) : DomainEvent

	data class TierChanged(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val fromTier: PolicyTier,
		val toTier: PolicyTier,
		val reason: String,
	) : DomainEvent

	// --- Trips context ---
	data class TripStarted(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val triggerActivity: DetectedActivityType?,
	) : DomainEvent

	data class TripCompleted(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val tripStartMs: EpochMs,
		val distance: DistanceM,
		val steps: StepCount,
		val duration: DurationMs,
		val primaryMode: TransportMode,
	) : DomainEvent

	// --- Exploration context ---
	data class CellDiscovered(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val cellToken: String,
		val level: Int,
		val centerLatE7: Int,
		val centerLonE7: Int,
		val quality: Int,
		val seasonBit: Int,
	) : DomainEvent

	// --- Gamification context ---
	data class AchievementUnlocked(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val achievementId: String,
		val tier: String,
	) : DomainEvent

	data class AchievementProgress(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val achievementId: String,
		val currentValue: Long,
		val targetValue: Long,
	) : DomainEvent



	// --- Analytics context ---
	data class DailySummaryUpdated(
		override val timestampMs: EpochMs,
		override val processorId: String,
		val dayEpoch: Long,
		val totalDistance: DistanceM,
		val totalSteps: StepCount,
		val totalDuration: DurationMs,
		val tripCount: Int,
	) : DomainEvent
}
