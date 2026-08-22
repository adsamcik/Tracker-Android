package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Deterministic released-v27 rows used to exercise migration semantics, not just schema shape. */
internal object PopulatedV27Fixture {
	const val LOGICAL_TRACKING_ID = "v27-interrupted-automatic"
	const val SERVICE_RUN_ID = "v27-service-run"
	const val LOCATION_EVENT_ID = "v27-location-event"
	const val LOCATION_SIGNAL_ID = "v27-location-signal"
	const val WAL_EVENT_ID = "v27-wal-location-event"
	const val IMPORT_JOB_ID = "v27-import-job"
	const val DAY_EPOCH = 19_675L
	const val START_MS = 1_700_000_000_000L
	const val END_MS = START_MS + 60_000L
	const val START_ELAPSED_NANOS = 10_000_000_000L
	const val END_ELAPSED_NANOS = START_ELAPSED_NANOS + 60_000_000_000L
	const val LAT_E7 = 501_234_567
	const val LON_E7 = 144_567_890

	fun seed(database: SupportSQLiteDatabase) = with(database) {
		execSQL(
			"INSERT INTO source_evidence_state " +
				"(id, revision, collected_data_epoch, retained_from_ms, updated_at_ms) " +
				"VALUES (1, 41, 7, 123456, 999)",
		)
		execSQL(
			"INSERT INTO logical_tracking_session " +
				"(logical_tracking_id, state, lifecycle_revision, desired_plan_revision, " +
				"rollout_revision, start_origin, clock_domain_id, started_at_ms, " +
				"started_elapsed_nanos, cutoff_at_ms, cutoff_elapsed_nanos, completed_at_ms, " +
				"final_admission_ordinal, failure_code) VALUES " +
				"('$LOGICAL_TRACKING_ID', 'RUNNING', 3, 7, 2, 'AUTOMATIC_BACKGROUND_START', " +
				"'boot-v27', $START_MS, $START_ELAPSED_NANOS, NULL, NULL, NULL, NULL, NULL)",
		)
		execSQL(
			"INSERT INTO source_service_run " +
				"(service_run_id, logical_tracking_id, state, desired_plan_revision, " +
				"rollout_revision, foreground_capability_flags, started_at_ms, " +
				"started_elapsed_nanos, completed_at_ms, completion_reason) VALUES " +
				"('$SERVICE_RUN_ID', '$LOGICAL_TRACKING_ID', 'RUNNING', 7, 2, 5, " +
				"$START_MS, $START_ELAPSED_NANOS, NULL, NULL)",
		)
		execSQL(
			"INSERT INTO tracker_run " +
				"(id, start_time_ms, end_time_ms, policy, policy_params, user_initiated, created_at) " +
				"VALUES (1, $START_MS, NULL, 'BALANCED', '{}', 0, $START_MS)",
		)

		// Released typed observations for every source family.
		execSQL(
			"INSERT INTO location_sample " +
				"(id, time_ms, elapsed_realtime_nanos, lat_e7, lon_e7, alt_m, h_acc_m, " +
				"provider, quality, created_at, received_elapsed_realtime_nanos, " +
				"source_signal_id, source_event_id, clock_domain_id, source_revision, " +
				"boot_clock_domain_id) VALUES " +
				"(101, ${START_MS + 1_000}, ${START_ELAPSED_NANOS + 1_000_000_000}, " +
				"$LAT_E7, $LON_E7, 245.5, 6.0, 'gps', 'HIGH', ${START_MS + 1_010}, " +
				"${START_ELAPSED_NANOS + 1_010_000_000}, '$LOCATION_SIGNAL_ID', " +
				"'$LOCATION_EVENT_ID', 'boot-v27', 9, 'boot-v27')",
		)
		execSQL(
			"INSERT INTO location_observation " +
				"(id, fix_time_ms, fix_elapsed_realtime_nanos, received_at_ms, " +
				"received_elapsed_realtime_nanos, delivery_age_ms, lat_e7, lon_e7, " +
				"h_acc_m, provider, acquisition_mode, request_priority, permission_precision, " +
				"batch_index, batch_size, is_mock, ingress_disposition, estimator_version, " +
				"calibration_version, created_at, source_signal_id, source_event_id, callback_id, " +
				"clock_domain_id, source_revision, boot_clock_domain_id) VALUES " +
				"(111, ${START_MS + 1_000}, ${START_ELAPSED_NANOS + 1_000_000_000}, " +
				"${START_MS + 1_010}, ${START_ELAPSED_NANOS + 1_010_000_000}, 10, " +
				"$LAT_E7, $LON_E7, 6.0, 'gps', 'ACTIVE', 'BALANCED', 'PRECISE', " +
				"0, 1, 0, 'ACCEPTED', 1, 0, ${START_MS + 1_010}, " +
				"'$LOCATION_SIGNAL_ID', '$LOCATION_EVENT_ID', 'v27-callback', 'boot-v27', 9, " +
				"'boot-v27')",
		)
		execSQL(
			"INSERT INTO location_projection_observation " +
				"(event_id, logical_tracking_id, admission_ordinal, elapsed_realtime_nanos, " +
				"wall_time_ms, latitude_degrees, longitude_degrees, horizontal_accuracy_meters, " +
				"altitude_meters, vertical_accuracy_meters, speed_meters_per_second) VALUES " +
				"('$LOCATION_EVENT_ID', '$LOGICAL_TRACKING_ID', 1, " +
				"${START_ELAPSED_NANOS + 1_000_000_000}, ${START_MS + 1_000}, " +
				"50.1234567, 14.456789, 6.0, 245.5, 8.0, 1.5)",
		)
		execSQL(
			"INSERT INTO location_projection_point " +
				"(event_id, logical_tracking_id, revision, accepted, rejection, latitude_degrees, " +
				"longitude_degrees, segment_distance_meters, cumulative_distance_meters, " +
				"estimated_speed_meters_per_second, raw_wgs84_altitude_meters, " +
				"vertical_accuracy_meters, elapsed_realtime_nanos) VALUES " +
				"('$LOCATION_EVENT_ID', '$LOGICAL_TRACKING_ID', 2, 1, NULL, 50.1234567, " +
				"14.456789, 125.5, 125.5, 1.5, 245.5, 8.0, " +
				"${START_ELAPSED_NANOS + 1_000_000_000})",
		)
		execSQL(
			"INSERT INTO step_interval " +
				"(id, start_time_ms, end_time_ms, step_count, sensor_value_start, " +
				"sensor_value_end, sensor_reset, created_at, source_signal_id, " +
				"source_time_ms, source_elapsed_realtime_nanos, source_first_elapsed_realtime_nanos, " +
				"received_time_ms, received_elapsed_realtime_nanos, source_sequence, " +
				"source_first_sequence, clock_domain_id, boot_clock_domain_id, source_age_ms, " +
				"time_uncertainty_ms, capability_flags, permission_precision) VALUES " +
				"(201, ${START_MS + 2_000}, ${START_MS + 12_000}, 23, 1000, 1023, 0, " +
				"${START_MS + 12_010}, 'v27-steps-positive', ${START_MS + 12_000}, " +
				"${START_ELAPSED_NANOS + 12_000_000_000}, " +
				"${START_ELAPSED_NANOS + 2_000_000_000}, ${START_MS + 12_010}, " +
				"${START_ELAPSED_NANOS + 12_010_000_000}, 12, 2, 'boot-v27', 'boot-v27', " +
				"10, 5, 'STEP_COUNTER', 'SENSOR'), " +
				"(202, ${START_MS + 12_000}, ${START_MS + 13_000}, 0, 1023, 4, 1, " +
				"${START_MS + 13_010}, 'v27-steps-reset', ${START_MS + 13_000}, " +
				"${START_ELAPSED_NANOS + 13_000_000_000}, " +
				"${START_ELAPSED_NANOS + 12_000_000_000}, ${START_MS + 13_010}, " +
				"${START_ELAPSED_NANOS + 13_010_000_000}, 13, 12, 'boot-v27', 'boot-v27', " +
				"10, 5, 'STEP_COUNTER_RESET', 'SENSOR')",
		)
		execSQL(
			"INSERT INTO activity_snapshot " +
				"(id, time_ms, activity_type, confidence, is_transition, created_at, " +
				"source_signal_id, source_time_ms, source_elapsed_realtime_nanos, " +
				"source_first_elapsed_realtime_nanos, received_time_ms, " +
				"received_elapsed_realtime_nanos, source_sequence, source_first_sequence, " +
				"clock_domain_id, boot_clock_domain_id, source_age_ms, time_uncertainty_ms, " +
				"capability_flags, permission_precision) VALUES " +
				"(301, ${START_MS + 15_000}, 7, 87, 1, ${START_MS + 15_010}, " +
				"'v27-activity', ${START_MS + 15_000}, " +
				"${START_ELAPSED_NANOS + 15_000_000_000}, " +
				"${START_ELAPSED_NANOS + 15_000_000_000}, ${START_MS + 15_010}, " +
				"${START_ELAPSED_NANOS + 15_010_000_000}, 15, 15, 'boot-v27', 'boot-v27', " +
				"10, 5, 'ACTIVITY_TRANSITION', 'ACTIVITY_RECOGNITION')",
		)
		execSQL(
			"INSERT INTO wifi_observation " +
				"(id, time_ms, bssid, ssid, capabilities, frequency, level, lat_e7, lon_e7, " +
				"provenance, created_at, source_signal_id, source_item_index, source_time_ms, " +
				"source_elapsed_realtime_nanos, source_first_elapsed_realtime_nanos, " +
				"received_time_ms, received_elapsed_realtime_nanos, source_sequence, " +
				"source_first_sequence, clock_domain_id, boot_clock_domain_id, source_age_ms, " +
				"time_uncertainty_ms, capability_flags, permission_precision) VALUES " +
				"(401, ${START_MS + 20_000}, '02:00:00:00:00:01', 'v27-fixture-network', " +
				"'[WPA2-PSK-CCMP][ESS]', 5180, -54, $LAT_E7, $LON_E7, 'NEAREST_LOCATION', " +
				"${START_MS + 20_010}, 'v27-wifi', 0, ${START_MS + 20_000}, " +
				"${START_ELAPSED_NANOS + 20_000_000_000}, " +
				"${START_ELAPSED_NANOS + 20_000_000_000}, ${START_MS + 20_010}, " +
				"${START_ELAPSED_NANOS + 20_010_000_000}, 20, 20, 'boot-v27', 'boot-v27', " +
				"10, 5, 'WIFI_SCAN', 'PRECISE')",
		)
		execSQL(
			"INSERT INTO cell_sample " +
				"(id, time_ms, cell_id, lac, mcc, mnc, network_type, signal_strength, " +
				"lat_e7, lon_e7, provenance, created_at, source_signal_id, source_item_index, " +
				"source_time_ms, source_elapsed_realtime_nanos, source_first_elapsed_realtime_nanos, " +
				"received_time_ms, received_elapsed_realtime_nanos, source_sequence, " +
				"source_first_sequence, clock_domain_id, boot_clock_domain_id, source_age_ms, " +
				"time_uncertainty_ms, capability_flags, permission_precision) VALUES " +
				"(501, ${START_MS + 25_000}, 987654321, 321, 230, 3, 13, -95, NULL, NULL, " +
				"'UNKNOWN', ${START_MS + 25_010}, 'v27-cell', 0, ${START_MS + 25_000}, " +
				"${START_ELAPSED_NANOS + 25_000_000_000}, " +
				"${START_ELAPSED_NANOS + 25_000_000_000}, ${START_MS + 25_010}, " +
				"${START_ELAPSED_NANOS + 25_010_000_000}, 25, 25, 'boot-v27', 'boot-v27', " +
				"10, 5, 'CELL_INFO', 'PHONE_STATE')",
		)
		execSQL(
			"INSERT INTO pressure_sample " +
				"(id, time_ms, elapsed_realtime_nanos, pressure_hpa, altitude_m, bucket_id, " +
				"created_at, source_signal_id, sample_count, min_pressure_hpa, max_pressure_hpa, " +
				"pressure_stddev_hpa, window_start_elapsed_realtime_nanos, " +
				"window_end_elapsed_realtime_nanos, source_time_ms, source_elapsed_realtime_nanos, " +
				"source_first_elapsed_realtime_nanos, received_time_ms, " +
				"received_elapsed_realtime_nanos, source_sequence, source_first_sequence, " +
				"clock_domain_id, boot_clock_domain_id, source_age_ms, time_uncertainty_ms, " +
				"capability_flags, permission_precision) VALUES " +
				"(601, ${START_MS + 30_000}, ${START_ELAPSED_NANOS + 30_000_000_000}, " +
				"1001.25, 101.5, NULL, ${START_MS + 30_010}, 'v27-pressure', 8, 1001.0, " +
				"1001.5, 0.16, ${START_ELAPSED_NANOS + 29_000_000_000}, " +
				"${START_ELAPSED_NANOS + 30_000_000_000}, ${START_MS + 30_000}, " +
				"${START_ELAPSED_NANOS + 30_000_000_000}, " +
				"${START_ELAPSED_NANOS + 29_000_000_000}, ${START_MS + 30_010}, " +
				"${START_ELAPSED_NANOS + 30_010_000_000}, 30, 29, 'boot-v27', 'boot-v27', " +
				"10, 5, 'PRESSURE_SENSOR', 'SENSOR')",
		)

		// Released user-visible summaries remain historical facts; migration does not recreate them.
		execSQL(
			"INSERT INTO session_segment " +
				"(id, start_time_ms, end_time_ms, distance_m, steps, primary_activity, " +
				"activity_confidence, sample_count, source, inference_version, created_at, " +
				"has_distance_anomaly) VALUES " +
				"(701, $START_MS, $END_MS, 125.5, 23, 7, 87, 1, 'LEGACY_MIGRATION', " +
				"'v27', $END_MS, 0)",
		)
		execSQL(
			"INSERT INTO daily_summary " +
				"(date_epoch_day, total_distance_m, total_steps, total_duration_ms, trip_count, " +
				"active_tracking_ms, last_updated_ms, created_at) VALUES " +
				"($DAY_EPOCH, 125.5, 23, 60000, 1, 60000, $END_MS, $START_MS)",
		)

		// Stale v27 ownership and queue state must remain diagnostic/inert, never gain v28 authority.
		execSQL(
			"INSERT INTO source_coordinator_lease " +
				"(lease_name, owner_token, acquired_at_ms, expires_at_ms) VALUES " +
				"('source-coordinator', 'dead-v27-process', $START_MS, ${END_MS + 60_000})",
		)
		execSQL(
			"INSERT INTO source_registration_state " +
				"(source_kind, owner_scope, source_instance_id, clock_domain_id, " +
				"registration_generation, next_sequence, applied_revision, collected_data_epoch, " +
				"updated_at_ms) VALUES " +
				"(1, 'SESSION', 'v27-location-instance', 'boot-v27', 4, 42, 7, 7, $END_MS)",
		)
		execSQL(
			"INSERT INTO source_runtime_state " +
				"(source_kind, owner_scope, source_instance_id, clock_domain_id, " +
				"registration_generation, last_provider_sequence, last_admitted_source_sequence, " +
				"last_admission_ordinal, state_version, payload, updated_at_ms) VALUES " +
				"(1, 'SESSION', 'v27-location-instance', 'boot-v27', 4, 42, 41, 1, 3, ?, $END_MS)",
			arrayOf(byteArrayOf(1, 2, 3)),
		)
		val walPayload = expectedWalPayload()
		execSQL(
			"INSERT INTO source_event_wal " +
				"(admission_ordinal, event_id, provider_dedup_key, logical_tracking_id, " +
				"service_run_id, source_kind, source_instance_id, registration_generation, " +
				"source_sequence, config_revision, plan_attribution, clock_domain_id, " +
				"observed_elapsed_nanos, received_elapsed_nanos, wall_time_ms, " +
				"wall_time_uncertainty_ms, captured_collected_data_epoch, acquired_at_ms, " +
				"quality_flags, quality_confidence, payload_version, payload, payload_checksum, " +
				"created_at_ms) VALUES " +
				"(1, '$WAL_EVENT_ID', 'v27-provider-dedup', '$LOGICAL_TRACKING_ID', " +
				"'$SERVICE_RUN_ID', 1, 'v27-location-instance', 4, 41, 7, 0, 'boot-v27', " +
				"${START_ELAPSED_NANOS + 40_000_000_000}, " +
				"${START_ELAPSED_NANOS + 40_010_000_000}, ${START_MS + 40_000}, 10, 7, " +
				"${START_MS + 40_000}, 1, 0.9, 1, ?, ?, ${START_MS + 40_010})",
			arrayOf(walPayload, walPayload.sha256()),
		)
		execSQL(
			"INSERT INTO source_projection_registration " +
				"(projection_id, projection_version, activation_ordinal, retention_required, " +
				"status, created_at_ms) VALUES ('location-domain', 1, 1, 1, 'ACTIVE', $START_MS)",
		)
		execSQL(
			"INSERT INTO source_projection_checkpoint " +
				"(projection_id, projection_version, contiguous_admission_ordinal, state_version, " +
				"updated_at_ms) VALUES ('location-domain', 1, 0, 1, $START_MS)",
		)
		execSQL(
			"INSERT INTO source_projection_outbox " +
				"(stable_id, projection_id, projection_version, admission_ordinal, effect_kind, " +
				"payload_version, payload, created_at_ms, delivered_at_ms) VALUES " +
				"('v27-outbox', 'location-domain', 1, 1, 'LOCATION', 1, ?, $START_MS, NULL)",
			arrayOf(byteArrayOf(4, 5, 6)),
		)
		execSQL(
			"INSERT INTO source_session_completeness " +
				"(logical_tracking_id, source_kind, source_instance_id, registration_generation, " +
				"last_admission_ordinal, last_source_sequence, app_drain_complete, provider_coverage, " +
				"stop_status, unresolved_sequence_start, unresolved_sequence_end, updated_at_ms) " +
				"VALUES ('$LOGICAL_TRACKING_ID', 1, 'v27-location-instance', 4, 1, 41, 0, " +
				"'UNKNOWN', 'INCOMPLETE', 42, 42, $END_MS)",
		)
		val pendingJson = "{\"type\":\"v27-fixture\"}"
		execSQL(
			"INSERT INTO pending_signal " +
				"(id, signal_id, session_id, envelope_version, payload_checksum, signal_json, " +
				"created_at, captured_epoch, acquired_at_ms, claim_token, claim_expires_at, " +
				"delivery_attempt_count) VALUES " +
				"(801, 'v27-pending-signal', 1, 1, ?, ?, $END_MS, 7, $END_MS, " +
				"'dead-v27-claim', ${END_MS + 60_000}, 2)",
			arrayOf(pendingJson.toByteArray().sha256(), pendingJson),
		)
		execSQL(
			"INSERT INTO import_job_receipt " +
				"(job_id, source_name, source_size_bytes, status, started_at, completed_at, " +
				"updated_at) VALUES ('$IMPORT_JOB_ID', 'v27-fixture.zip', 4096, 'COMPLETE', " +
				"$START_MS, $END_MS, $END_MS)",
		)
		execSQL(
			"INSERT INTO import_entry_receipt " +
				"(job_id, entry_key, entry_name, status, success_count, skipped_count, " +
				"failed_count, error_message, updated_at) VALUES " +
				"('$IMPORT_JOB_ID', 'entry-1', 'fixture.gpx', 'COMPLETE', 1, 0, 0, NULL, $END_MS)",
		)
	}

	fun expectedWalPayload(): ByteArray = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeInt(1) // DefaultSourcePayloadCodec.TYPE_LOCATION_FIX
			output.writeDouble(LAT_E7 / 1e7)
			output.writeDouble(LON_E7 / 1e7)
			output.writeFloat(6f)
			output.writeBoolean(false) // altitude
			output.writeBoolean(false) // vertical accuracy
			output.writeBoolean(false) // speed
			output.writeBoolean(false) // bearing
			output.writeUTF("gps")
		}
		buffer.toByteArray()
	}

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
