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
		if (current?.schemaVersion == TrackingRolloutState.CURRENT_SCHEMA_VERSION) {
			return@withTransaction current
		}

		// v28 never shipped. Any v27/global-v2 rollout row predates source-local product reachability
		// and therefore cannot authorize a provider in this binary. Preserve its revision history, but
		// contain every acquisition owner until an explicit source-local shadow gate is persisted.
		TrackingRolloutState.contained(revision = (current?.revision ?: 0L) + 1L).also { migrated ->
			dao.save(migrated.toEntity(System.currentTimeMillis()))
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

private fun TrackingRolloutState.toEntity(updatedAtMs: Long) = TrackingRolloutStateEntity(
	revision = revision,
	schemaVersion = schemaVersion,
	coordinatorMode = coordinatorMode.name,
	projectionMode = productProjectionStages.entries.sortedBy { it.key.stableCode }
		.joinToString(",") { (source, stage) -> "${source.stableCode}:${stage.name}" },
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
		sourceOwners = owners,
		productProjectionStages = decodeProductProjectionStages(projectionMode),
		semanticSettingsEnabled = semanticSettingsEnabled,
		batteryEstimateMode = BatteryEstimateMode.valueOf(batteryEstimateMode),
	)
}

/** Decode unreleased v28 global fixtures without allowing them to drive future all-source cutover. */
private fun decodeProductProjectionStages(encoded: String): Map<SourceKind, ProductProjectionStage> {
	if (':' !in encoded) {
		val stage = when (encoded) {
			"LEGACY_ONLY" -> ProductProjectionStage.LEGACY_CANONICAL
			"SHADOW_READ_ONLY", "EVENT_CANONICAL" -> ProductProjectionStage.EVENT_SHADOW
			else -> error("Unknown product projection rollout encoding")
		}
		return SourceKind.entries.associateWith { stage }
	}
	return encoded.split(',')
		.filter(String::isNotBlank)
		.associate { value ->
			val (sourceCode, stageName) = value.split(':', limit = 2)
			SourceKind.entries.single { it.stableCode == sourceCode.toInt() } to
				ProductProjectionStage.valueOf(stageName)
		}
}
