package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

interface TrackingRolloutStateStore {
	suspend fun load(): TrackingRolloutState
	suspend fun save(state: TrackingRolloutState, updatedAtMs: Long)
}

@Singleton
class RoomTrackingRolloutStateStore @Inject constructor(
	private val database: AppDatabase,
) : TrackingRolloutStateStore {
	override suspend fun load(): TrackingRolloutState = database.withTransaction {
		val dao = database.trackingRolloutStateDao()
		val current = dao.get()?.toModel()
		if (current?.isEventCanonical() == true) return@withTransaction current

		// Phase 10 retires in-binary legacy acquisition. Advancing the persisted revision keeps old
		// release data decodable while ensuring a service run can never re-acquire physical sources
		// through both the trigger/poller and source-native runtimes.
		TrackingRolloutState.eventCanonical(revision = (current?.revision ?: 0L) + 1L).also { retired ->
			dao.save(retired.toEntity(System.currentTimeMillis()))
		}
	}

	override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) {
		require(updatedAtMs >= 0L)
		database.withTransaction {
			val current = database.trackingRolloutStateDao().get()
			require(current == null || state.revision > current.revision) {
				"Tracking rollout revisions must advance monotonically"
			}
			database.trackingRolloutStateDao().save(state.toEntity(updatedAtMs))
		}
	}
}

private fun TrackingRolloutState.isEventCanonical(): Boolean =
	coordinatorMode == CoordinatorMode.EVENT &&
		projectionMode == ProjectionMode.EVENT_CANONICAL &&
		sourceOwners.values.all { it == SourceOwner.EVENT }

private fun TrackingRolloutState.toEntity(updatedAtMs: Long) = TrackingRolloutStateEntity(
	revision = revision,
	schemaVersion = schemaVersion,
	coordinatorMode = coordinatorMode.name,
	projectionMode = projectionMode.name,
	sourceOwners = sourceOwners.entries.sortedBy { it.key.stableCode }
		.joinToString(",") { (source, owner) -> "${source.stableCode}:${owner.name}" },
	semanticSettingsEnabled = semanticSettingsEnabled,
	batteryEstimateMode = batteryEstimateMode.name,
	updatedAtMs = updatedAtMs,
)

private fun TrackingRolloutStateEntity.toModel(): TrackingRolloutState {
	val owners = sourceOwners.split(',')
		.filter(String::isNotBlank)
		.associate { encoded ->
			val (sourceCode, ownerName) = encoded.split(':', limit = 2)
			val source = SourceKind.entries.single { it.stableCode == sourceCode.toInt() }
			source to SourceOwner.valueOf(ownerName)
		}
	return TrackingRolloutState(
		revision = revision,
		schemaVersion = schemaVersion,
		coordinatorMode = CoordinatorMode.valueOf(coordinatorMode),
		projectionMode = ProjectionMode.valueOf(projectionMode),
		sourceOwners = owners,
		semanticSettingsEnabled = semanticSettingsEnabled,
		batteryEstimateMode = BatteryEstimateMode.valueOf(batteryEstimateMode),
	)
}
