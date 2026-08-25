package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
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
		val currentEntity = dao.get()
		val current = currentEntity?.decodeCurrentModelOrNull()
		if (current != null) {
			val activeLanes = database.sourceProjectionStateDao().activeProductLanes()
			val authorized = current.withoutUnbackedProductLanes(activeLanes)
			if (authorized == current) return@withTransaction current

			check(current.revision < Long.MAX_VALUE) { "Tracking rollout revision exhausted" }
			return@withTransaction authorized.copy(revision = current.revision + 1L).also { repaired ->
				dao.save(repaired.toEntity(System.currentTimeMillis()))
			}
		}

		// v28 never shipped. Any v27/global-v2 rollout row predates source-local product reachability
		// and therefore cannot authorize a provider in this binary. Preserve its revision history, but
		// contain every acquisition owner until an explicit source-local shadow gate is persisted.
		val currentRevision = currentEntity?.revision ?: 0L
		check(currentRevision < Long.MAX_VALUE) { "Tracking rollout revision exhausted" }
		TrackingRolloutState.contained(revision = currentRevision + 1L).also { migrated ->
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
			val authorized = state.withoutUnbackedProductLanes(
				database.sourceProjectionStateDao().activeProductLanes(),
			)
			require(authorized == state) {
				"Capture rollout requires an active matching source-local product lane and cursor"
			}
			database.trackingRolloutStateDao().save(state.toEntity(updatedAtMs))
		}
	}

	/**
	 * Atomically installs the first concrete shadow product lane for [source] and makes only that
	 * source capture-reachable. The activation starts after the durable WAL high-water and its
	 * initialized cursor is a mandatory retention pin. Canonical cutover intentionally has no API
	 * here; it needs source-specific shadow evidence and legacy-writer fencing first. In this first
	 * source-local contract, [projectionId] is the output contract and [projectionVersion] is the
	 * writer generation; a second abstraction would add no independent identity.
	 */
	suspend fun installAndActivateShadowLane(
		source: SourceKind,
		projectionId: String,
		projectionVersion: Int,
		rolloutRevision: Long,
		updatedAtMs: Long,
	): SourceProductLaneActivation {
		require(projectionId.isNotBlank()) { "Projection ID must not be blank" }
		require(projectionVersion > 0) { "Projection version must be positive" }
		require(rolloutRevision > 0L) { "Rollout revision must be positive" }
		require(updatedAtMs >= 0L)
		return database.withTransaction {
			val rolloutDao = database.trackingRolloutStateDao()
			val projectionDao = database.sourceProjectionStateDao()
			val currentEntity = rolloutDao.get()
			require(currentEntity == null || rolloutRevision > currentEntity.revision) {
				"Tracking rollout revisions must advance monotonically"
			}
			require(projectionDao.activeProductLane(source.stableCode) == null) {
				"Source $source already has an active product lane"
			}
			require(projectionDao.productLaneByProjection(projectionId, projectionVersion) == null) {
				"Projection identity $projectionId:$projectionVersion already owns another source"
			}
			require(projectionDao.registration(projectionId, projectionVersion) == null) {
				"Projection identity $projectionId:$projectionVersion is already registered globally"
			}

			val activeLanes = projectionDao.activeProductLanes()
			val current = currentEntity?.decodeCurrentModelOrNull()
				?.withoutUnbackedProductLanes(activeLanes)
				?: TrackingRolloutState.contained(revision = currentEntity?.revision ?: 0L)
			val activationOrdinal = database.liveSourceProjectionActivationOrdinal()
			val lane = SourceProductProjectionLaneEntity(
				sourceKind = source.stableCode,
				projectionId = projectionId,
				projectionVersion = projectionVersion,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = rolloutRevision,
				activationOrdinal = activationOrdinal,
				contiguousAdmissionOrdinal = activationOrdinal - 1L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = updatedAtMs,
				updatedAtMs = updatedAtMs,
			)
			projectionDao.installProductLane(lane)
			val rollout = current.copy(
				revision = rolloutRevision,
				sourceOwners = current.sourceOwners + (source to SourceOwner.EVENT),
				productProjectionStages = current.productProjectionStages +
					(source to ProductProjectionStage.EVENT_SHADOW),
			)
			check(
				rollout.withoutUnbackedProductLanes(
					projectionDao.activeProductLanes(),
				) == rollout,
			) { "Installed source lane did not authorize its exact rollout stage" }
			rolloutDao.save(rollout.toEntity(updatedAtMs))
			SourceProductLaneActivation(rollout, lane)
		}
	}
}

data class SourceProductLaneActivation(
	val rollout: TrackingRolloutState,
	val lane: SourceProductProjectionLaneEntity,
)

private fun TrackingRolloutStateEntity.decodeCurrentModelOrNull(): TrackingRolloutState? =
	if (schemaVersion != TrackingRolloutState.CURRENT_SCHEMA_VERSION) {
		null
	} else {
		runCatching { toModel() }.getOrNull()
	}

private fun TrackingRolloutState.withoutUnbackedProductLanes(
	activeLanes: List<SourceProductProjectionLaneEntity>,
): TrackingRolloutState {
	val lanesBySource = activeLanes.associateBy(SourceProductProjectionLaneEntity::sourceKind)
	val unbacked = SourceKind.entries.filterTo(mutableSetOf()) { source ->
		if (!isAcquisitionReachable(source)) return@filterTo false
		val stage = productProjectionStages.getValue(source)
		val lane = lanesBySource[source.stableCode]
		lane == null || !lane.matches(source, stage, revision)
	}
	if (unbacked.isEmpty()) return this
	return copy(
		sourceOwners = sourceOwners.mapValues { (source, owner) ->
			if (source in unbacked) SourceOwner.CONTAINED else owner
		},
		productProjectionStages = productProjectionStages.mapValues { (source, stage) ->
			if (source in unbacked) ProductProjectionStage.LEGACY_CANONICAL else stage
		},
	)
}

private fun SourceProductProjectionLaneEntity.matches(
	source: SourceKind,
	stage: ProductProjectionStage,
	rolloutRevision: Long,
): Boolean = sourceKind == source.stableCode &&
	projectionId.isNotBlank() &&
	projectionVersion > 0 &&
	productStage == stage.name &&
	stage in setOf(ProductProjectionStage.EVENT_SHADOW, ProductProjectionStage.EVENT_CANONICAL) &&
	activatedRolloutRevision > 0L &&
	activatedRolloutRevision <= rolloutRevision &&
	activationOrdinal > 0L &&
	contiguousAdmissionOrdinal >= activationOrdinal - 1L &&
	retentionRequired &&
	status == SourceProductProjectionLaneEntity.STATUS_ACTIVE

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
