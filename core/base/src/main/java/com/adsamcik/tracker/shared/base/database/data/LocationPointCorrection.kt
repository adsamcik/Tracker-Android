package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource

/** One current user replacement. The original provider row and its replay identity stay immutable. */
@Entity(tableName = "location_point_correction", indices = [
    androidx.room.Index(value = ["time_ms", "sample_id"], name = "idx_location_correction_time"),
    androidx.room.Index(value = ["lat_e7", "lon_e7"], name = "idx_location_correction_coords"),
], foreignKeys = [ForeignKey(
    entity = LocationSample::class, parentColumns = ["id"], childColumns = ["sample_id"],
    onDelete = ForeignKey.CASCADE,
)])
data class LocationPointCorrection(
    @PrimaryKey @ColumnInfo(name = "sample_id") val sampleId: Long,
    @ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
    val revision: Long,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    @ColumnInfo(name = "lat_e7") val latE7: Int?,
    @ColumnInfo(name = "lon_e7") val lonE7: Int?,
    @ColumnInfo(name = "alt_m") val altitudeM: Float?,
    @ColumnInfo(name = "raw_gps_alt_m") val rawGpsAltitudeM: Float?,
    @ColumnInfo(name = "h_acc_m") val hAccM: Float?,
    @ColumnInfo(name = "v_acc_m") val vAccM: Float?,
    @ColumnInfo(name = "speed_mps") val speedMps: Float?,
    @ColumnInfo(name = "speed_accuracy_mps") val speedAccuracyMps: Float?,
    @ColumnInfo(name = "raw_platform_speed_mps") val rawPlatformSpeedMps: Float?,
    @ColumnInfo(name = "raw_platform_speed_accuracy_mps") val rawPlatformSpeedAccuracyMps: Float?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Float?,
    @ColumnInfo(name = "bearing_accuracy_deg") val bearingAccuracyDeg: Float?,
    val provider: String,
    val quality: SampleQuality,
    @ColumnInfo(name = "motion_state") val motionState: MotionState?,
    @ColumnInfo(name = "permission_precision") val permissionPrecision: String,
    @ColumnInfo(name = "is_mock") val isMock: Boolean,
    @ColumnInfo(name = "alt_datum") val altitudeDatum: AltitudeDatum,
    @ColumnInfo(name = "alt_source") val altitudeSource: AltitudeSource,
    @ColumnInfo(name = "alt_conversion_status") val altitudeConversionStatus: AltitudeConversionStatus,
    @ColumnInfo(name = "raw_gps_alt_datum") val rawGpsAltitudeDatum: AltitudeDatum,
) {
    companion object {
        fun from(sampleId: Long, epoch: Long, revision: Long, value: LocationSample) =
            LocationPointCorrection(sampleId, epoch, revision, value.timeMs, value.latE7, value.lonE7,
                value.altitudeM, value.rawGpsAltitudeM, value.hAccM, value.vAccM, value.speedMps,
                value.speedAccuracyMps, value.rawPlatformSpeedMps, value.rawPlatformSpeedAccuracyMps,
                value.bearingDeg, value.bearingAccuracyDeg, value.provider, value.quality,
                value.motionState, value.permissionPrecision, value.isMock, value.altitudeDatum,
                value.altitudeSource, value.altitudeConversionStatus, value.rawGpsAltitudeDatum)
    }
}

/** Every saved-data consumer sees the same current measurements, including date and spatial filters. */
@DatabaseView(viewName = "effective_location_sample", value = EFFECTIVE_LOCATION_SAMPLE_QUERY)
data class EffectiveLocationSample(@androidx.room.Embedded val sample: LocationSample)

const val EFFECTIVE_LOCATION_SAMPLE_QUERY = """SELECT s.id, s.time_ms, s.elapsed_realtime_nanos, s.lat_e7, s.lon_e7, s.alt_m, s.raw_gps_alt_m, s.h_acc_m, s.v_acc_m, s.speed_mps, s.speed_accuracy_mps, s.provider, s.quality, s.motion_state, s.policy, s.bucket_id, s.created_at, s.received_elapsed_realtime_nanos, s.delivery_age_ms, s.acquisition_mode, s.request_priority, s.permission_precision, s.batch_index, s.batch_size, s.is_mock, s.estimator_version, s.calibration_version, s.source_signal_id, s.source_event_id, s.clock_domain_id, s.source_revision, s.alt_datum, s.alt_source, s.alt_conversion_status, s.raw_gps_alt_datum, s.alt_model_version, s.raw_platform_speed_mps, s.raw_platform_speed_accuracy_mps, s.bearing_deg, s.bearing_accuracy_deg, s.boot_clock_domain_id, s.legacy_lat, s.legacy_lon, s.legacy_alt_m FROM location_sample s
    WHERE NOT EXISTS (SELECT 1 FROM location_point_correction c WHERE c.sample_id = s.id
        AND EXISTS (SELECT 1 FROM source_evidence_state e WHERE e.id = 1 AND (c.collected_data_epoch = e.collected_data_epoch OR instr(',' || e.restored_data_epochs || ',', ',' || c.collected_data_epoch || ',') > 0)))
    UNION ALL SELECT s.id,
    c.time_ms AS time_ms,
    CASE WHEN c.time_ms = s.time_ms THEN s.elapsed_realtime_nanos ELSE 0 END AS elapsed_realtime_nanos,
    c.lat_e7 AS lat_e7,
    c.lon_e7 AS lon_e7,
    c.alt_m AS alt_m,
    c.raw_gps_alt_m AS raw_gps_alt_m,
    c.h_acc_m AS h_acc_m,
    c.v_acc_m AS v_acc_m,
    c.speed_mps AS speed_mps,
    c.speed_accuracy_mps AS speed_accuracy_mps,
    c.provider AS provider,
    c.quality AS quality,
    c.motion_state AS motion_state,
    s.policy, s.bucket_id, s.created_at, s.received_elapsed_realtime_nanos, s.delivery_age_ms,
    s.acquisition_mode, s.request_priority,
    c.permission_precision AS permission_precision,
    s.batch_index, s.batch_size,
    c.is_mock AS is_mock,
    s.estimator_version, s.calibration_version, s.source_signal_id, s.source_event_id,
    CASE WHEN c.time_ms = s.time_ms THEN s.clock_domain_id ELSE NULL END AS clock_domain_id,
    s.source_revision,
    c.alt_datum AS alt_datum,
    c.alt_source AS alt_source,
    c.alt_conversion_status AS alt_conversion_status,
    c.raw_gps_alt_datum AS raw_gps_alt_datum,
    s.alt_model_version,
    c.raw_platform_speed_mps AS raw_platform_speed_mps,
    c.raw_platform_speed_accuracy_mps AS raw_platform_speed_accuracy_mps,
    c.bearing_deg AS bearing_deg,
    c.bearing_accuracy_deg AS bearing_accuracy_deg,
    CASE WHEN c.time_ms = s.time_ms THEN s.boot_clock_domain_id ELSE NULL END AS boot_clock_domain_id,
    s.legacy_lat, s.legacy_lon, s.legacy_alt_m
    FROM location_sample s JOIN location_point_correction c ON c.sample_id = s.id
    AND EXISTS (SELECT 1 FROM source_evidence_state e WHERE e.id = 1 AND (c.collected_data_epoch = e.collected_data_epoch OR instr(',' || e.restored_data_epochs || ',', ',' || c.collected_data_epoch || ',') > 0))"""
