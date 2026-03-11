package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.data.DomainEventCursorEntity
import com.adsamcik.tracker.shared.base.database.data.DomainEventEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject

/**
 * Room-backed implementation of [DomainEventRepository].
 * Serializes polymorphic [DomainEvent] subtypes via JSON payload.
 */
class DefaultDomainEventRepository @Inject constructor(
	private val dao: DomainEventDao,
) : DomainEventRepository {

	override suspend fun persist(events: List<DomainEvent>) {
		if (events.isEmpty()) return
		dao.insertAll(events.map { it.toEntity() })
	}

	override fun observeEvents(since: EpochMs): Flow<List<DomainEvent>> {
		return dao.observeSince(since.raw).map { entities ->
			entities.mapNotNull { it.toDomain() }
		}
	}

	override suspend fun getUnconsumed(consumerId: String): List<DomainEvent> {
		return dao.getUnconsumedFor(consumerId).mapNotNull { it.toDomain() }
	}

	override suspend fun markConsumed(consumerId: String, upToTimestamp: EpochMs) {
		dao.upsertCursor(DomainEventCursorEntity(consumerId, upToTimestamp.raw))
	}

	// region serialization

	private fun DomainEvent.toEntity(): DomainEventEntity {
		val (type, payload) = when (this) {
			is DomainEvent.SessionStarted -> "SessionStarted" to JSONObject().apply {
				put("isUserInitiated", isUserInitiated)
				put("initialTier", initialTier.name)
			}
			is DomainEvent.SessionEnded -> "SessionEnded" to JSONObject().apply {
				put("sessionId", sessionId)
				put("totalDistance", totalDistance.raw)
				put("totalSteps", totalSteps.raw)
				put("duration", duration.raw)
			}
			is DomainEvent.TierChanged -> "TierChanged" to JSONObject().apply {
				put("fromTier", fromTier.name)
				put("toTier", toTier.name)
				put("reason", reason)
			}
			is DomainEvent.TripStarted -> "TripStarted" to JSONObject().apply {
				put("triggerActivity", triggerActivity?.name)
			}
			is DomainEvent.TripCompleted -> "TripCompleted" to JSONObject().apply {
				put("tripStartMs", tripStartMs.raw)
				put("distance", distance.raw)
				put("steps", steps.raw)
				put("duration", duration.raw)
				put("primaryMode", primaryMode.name)
			}
			is DomainEvent.CellDiscovered -> "CellDiscovered" to JSONObject().apply {
				put("cellToken", cellToken)
				put("level", level)
				put("centerLatE7", centerLatE7)
				put("centerLonE7", centerLonE7)
				put("quality", quality)
				put("seasonBit", seasonBit)
			}
			is DomainEvent.AchievementUnlocked -> "AchievementUnlocked" to JSONObject().apply {
				put("achievementId", achievementId)
				put("tier", tier)
			}
			is DomainEvent.AchievementProgress -> "AchievementProgress" to JSONObject().apply {
				put("achievementId", achievementId)
				put("currentValue", currentValue)
				put("targetValue", targetValue)
			}
			is DomainEvent.DailySummaryUpdated -> "DailySummaryUpdated" to JSONObject().apply {
				put("dayEpoch", dayEpoch)
				put("totalDistance", totalDistance.raw)
				put("totalSteps", totalSteps.raw)
				put("totalDuration", totalDuration.raw)
				put("tripCount", tripCount)
			}
		}

		return DomainEventEntity(
			eventType = type,
			processorId = processorId,
			timestampMs = timestampMs.raw,
			payload = payload.toString(),
		)
	}

	private fun DomainEventEntity.toDomain(): DomainEvent? {
		val ts = EpochMs(timestampMs)
		val pid = processorId
		val json = JSONObject(payload)

		return when (eventType) {
			"SessionStarted" -> DomainEvent.SessionStarted(
				timestampMs = ts,
				processorId = pid,
				isUserInitiated = json.getBoolean("isUserInitiated"),
				initialTier = PolicyTier.valueOf(json.getString("initialTier")),
			)
			"SessionEnded" -> DomainEvent.SessionEnded(
				timestampMs = ts,
				processorId = pid,
				sessionId = json.optLong("sessionId", 0L),
				totalDistance = DistanceM(json.getDouble("totalDistance").toFloat()),
				totalSteps = StepCount(json.getInt("totalSteps")),
				duration = DurationMs(json.getLong("duration")),
			)
			"TierChanged" -> DomainEvent.TierChanged(
				timestampMs = ts,
				processorId = pid,
				fromTier = PolicyTier.valueOf(json.getString("fromTier")),
				toTier = PolicyTier.valueOf(json.getString("toTier")),
				reason = json.getString("reason"),
			)
			"TripStarted" -> DomainEvent.TripStarted(
				timestampMs = ts,
				processorId = pid,
				triggerActivity = json.optString("triggerActivity")
					.takeIf { it.isNotEmpty() && it != "null" }
					?.let { DetectedActivityType.valueOf(it) },
			)
			"TripCompleted" -> DomainEvent.TripCompleted(
				timestampMs = ts,
				processorId = pid,
				tripStartMs = EpochMs(json.getLong("tripStartMs")),
				distance = DistanceM(json.getDouble("distance").toFloat()),
				steps = StepCount(json.getInt("steps")),
				duration = DurationMs(json.getLong("duration")),
				primaryMode = TransportMode.valueOf(json.getString("primaryMode")),
			)
			"CellDiscovered" -> DomainEvent.CellDiscovered(
				timestampMs = ts,
				processorId = pid,
				cellToken = json.getString("cellToken"),
				level = json.getInt("level"),
				centerLatE7 = json.optInt("centerLatE7", 0),
				centerLonE7 = json.optInt("centerLonE7", 0),
				quality = json.optInt("quality", 0),
				seasonBit = json.optInt("seasonBit", 0),
			)
			"AchievementUnlocked" -> DomainEvent.AchievementUnlocked(
				timestampMs = ts,
				processorId = pid,
				achievementId = json.getString("achievementId"),
				tier = json.getString("tier"),
			)
			"AchievementProgress" -> DomainEvent.AchievementProgress(
				timestampMs = ts,
				processorId = pid,
				achievementId = json.getString("achievementId"),
				currentValue = json.getLong("currentValue"),
				targetValue = json.getLong("targetValue"),
			)
			"DailySummaryUpdated" -> DomainEvent.DailySummaryUpdated(
				timestampMs = ts,
				processorId = pid,
				dayEpoch = json.getLong("dayEpoch"),
				totalDistance = DistanceM(json.getDouble("totalDistance").toFloat()),
				totalSteps = StepCount(json.getInt("totalSteps")),
				totalDuration = DurationMs(json.getLong("totalDuration")),
				tripCount = json.getInt("tripCount"),
			)
			else -> null
		}
	}

	// endregion
}
