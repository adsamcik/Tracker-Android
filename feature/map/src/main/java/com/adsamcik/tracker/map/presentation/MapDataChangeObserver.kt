package com.adsamcik.tracker.map.presentation

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

internal enum class MapDataSource {
    Location,
    Cell,
    Wifi,
}

internal sealed interface MapDataChange {
    data object Ready : MapDataChange

    data class Committed(
        val sources: Set<MapDataSource>,
    ) : MapDataChange
}

/**
 * Publishes committed database changes that can affect a live map layer.
 *
 * Room invalidations are emitted after a successful transaction, so consumers never re-query data
 * that is still buffered or later rolled back. Room's initial emission is exposed only as
 * [MapDataChange.Ready], allowing the map to await observer registration before querying without
 * performing a redundant refresh.
 */
class MapDataChangeObserver private constructor(databaseProvider: () -> AppDatabase) {
    @Inject
    constructor(@ApplicationContext context: Context) : this({ AppDatabase.database(context.applicationContext) })

    internal constructor(database: AppDatabase) : this({ database })

    private val database by lazy(LazyThreadSafetyMode.SYNCHRONIZED, databaseProvider)

    internal fun changes(): Flow<MapDataChange> = flow {
        var isInitialEmission = true
        database.invalidationTracker
            .createFlow(*observedTables.toTypedArray(), emitInitialState = true)
            .collect { invalidatedTables ->
                if (isInitialEmission) {
                    isInitialEmission = false
                    emit(MapDataChange.Ready)
                } else {
                    val sources = changedSources(invalidatedTables)
                    if (sources.isNotEmpty()) {
                        emit(MapDataChange.Committed(sources))
                    }
                }
            }
    }

    internal companion object {
        private val locationTables = setOf(
            "location_sample", "location_point_correction", "location_observation", "location_observation_decision",
            "location_accepted_receipt", "location_sample_purpose_authority",
            "location_registration_plan", "ambient_location_retention_authority",
        )
        private val wifiTables = setOf(
            "wifi_observation", "wifi_captured_fact_revision", "wifi_captured_fact_cursor",
            "wifi_capture_deletion_generation", "ambient_wifi_fact_revision", "ambient_wifi_fact_cursor",
            "wifi_captured_child", "ambient_wifi_child",
            "ambient_wifi_gap", "ambient_wifi_deletion_marker", "ambient_wifi_replay_footprint",
            "ambient_wifi_authority", "ambient_wifi_retention_authority",
        )
        private val cellTables = setOf(
            "cell_sample", "cell_captured_fact_revision", "cell_captured_fact_cursor",
            "cell_capture_deletion_generation", "ambient_cell_fact_revision", "ambient_cell_fact_cursor",
            "cell_captured_child", "ambient_cell_child",
            "ambient_cell_gap", "ambient_cell_deletion_marker", "ambient_cell_replay_footprint",
            "ambient_cell_authority", "ambient_cell_retention_authority",
        )
        // These rows qualify retained facts, not just active acquisition. Any change must requalify
        // each potentially affected source; source-specific fact commits retain their narrow scope.
        private val sharedAuthorityTables = setOf(
            "source_policy_authority", "source_policy", "source_consent_epoch", "source_evidence_state",
            "collected_data_deletion_operation", "source_deletion_fence", "source_event_wal",
            "source_event_retention_proof", "captured_radio_fact_retention_proof",
            "source_destination_owner", "source_projection_registration", "source_projection_checkpoint",
            "source_product_projection_lane", "source_projection_failure", "source_projection_join_state",
            "source_demand", "source_desired_plan", "source_authorization", "provider_registration_generation",
            "acquisition_plan_revision", "logical_tracking_session", "source_service_run", "session_segment",
            "session_manifest_version", "session_manifest_source", "source_session_completeness",
            "lifecycle_desired_action", "source_run_retirement",
        )
        internal val observedTables: Set<String> = locationTables + wifiTables + cellTables + sharedAuthorityTables

        internal fun changedSources(invalidatedTables: Set<String>): Set<MapDataSource> = buildSet {
            if (invalidatedTables.any(sharedAuthorityTables::contains)) {
                addAll(setOf(MapDataSource.Location, MapDataSource.Wifi, MapDataSource.Cell))
            }
            if (invalidatedTables.any(locationTables::contains)) {
                add(MapDataSource.Location)
            }
            if (invalidatedTables.any(wifiTables::contains)) {
                add(MapDataSource.Wifi)
            }
            if (invalidatedTables.any(cellTables::contains)) {
                add(MapDataSource.Cell)
            }
        }
    }
}
