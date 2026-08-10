package com.adsamcik.tracker.shared.base.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ============================================================================
 * DATABASE VERSION TRACKING
 * ============================================================================
 *
 * This table maps database schema versions to app version codes to determine
 * when to CREATE NEW migrations vs UPDATE EXISTING migrations.
 *
 * RULE:
 * - If a database version has been RELEASED (shipped to production/beta),
 *   you MUST create a NEW migration for any schema changes.
 * - If a database version is UNRELEASED (only in dev/internal builds),
 *   you MAY update the existing migration.
 *
 * Latest Version Mapping:
 * ┌────────────┬─────────────┬──────────────────────────────────────────┐
 * │ DB Version │ App Version │ Status & Notes                           │
 * ├────────────┼─────────────┼──────────────────────────────────────────┤
 * │ 27         │ 400         │ 🚧 UNRELEASED - all schema work since   │
 * │            │             │    versionCode 385 is folded here       │
 * │ 26         │ 385         │ ✅ RELEASED - 2024.3.0 alpha 2          │
 * │ 12         │ 384         │ ✅ RELEASED - Last session-based schema  │
 * │ 11         │ 380-383     │ ✅ RELEASED                              │
 * │ 10         │ 370-379     │ ✅ RELEASED                              │
 * │ 9          │ 360-369     │ ✅ RELEASED                              │
 * │ 8          │ 350-359     │ ✅ RELEASED                              │
 * │ 7          │ 340-349     │ ✅ RELEASED                              │
 * │ 6          │ 330-339     │ ✅ RELEASED                              │
 * │ 5          │ 320-329     │ ✅ RELEASED                              │
 * │ 4          │ 310-319     │ ✅ RELEASED                              │
 * │ 3          │ 300-309     │ ✅ RELEASED                              │
 * │ 2          │ < 300       │ ✅ RELEASED (Legacy)                     │
 * └────────────┴─────────────┴──────────────────────────────────────────┘
 *
 * How to Update This Table:
 * 1. When you change the DB version in AppDatabase.kt, update the row above.
 * 2. Mark the previous version as RELEASED when you ship to production/beta.
 * 3. Add the new version code from app/build.gradle.kts.
 * 4. Include brief notes about what changed in the schema.
 *
 * Example workflow:
 * - You're adding a new column to an existing table.
 * - Current DB version is 27 (unreleased, versionCode 400).
 * - Action: Update MIGRATION_26_27 directly only if that version has not shipped.
 *
 * - You're adding a new table after version 26 ships.
 * - DB version 26 is now RELEASED (versionCode 385).
 * - Action: Bump DB version to 27, create MIGRATION_26_27, update table above.
 *
 * ============================================================================
 */

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("CREATE TABLE tmp_location_data (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `alt` REAL, `hor_acc` REAL, `ver_acc` REAL, `activity` INTEGER NOT NULL, `confidence` INTEGER NOT NULL)")
			execSQL("INSERT INTO tmp_location_data SELECT id, time, lat, lon, alt, hor_acc, null as ver_acc, activity, confidence FROM location_data")
			execSQL("DROP TABLE location_data")
			execSQL("ALTER TABLE tmp_location_data RENAME TO location_data")

			execSQL("CREATE TABLE IF NOT EXISTS map_max_heat (`zoom` INTEGER NOT NULL, `maxHeat` REAL NOT NULL, PRIMARY KEY(`zoom`))")
			execSQL("CREATE  INDEX index_location_data_time ON location_data (time)")
			execSQL("CREATE  INDEX index_location_data_lat ON location_data (lat)")
			execSQL("CREATE  INDEX index_location_data_lon ON location_data (lon)")

			execSQL("UPDATE location_data SET alt = null WHERE alt = 0.0")
			execSQL("UPDATE location_data SET hor_acc = null WHERE hor_acc = 0.0")
		}
	}
}

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("DROP TABLE map_max_heat")
			execSQL("CREATE TABLE map_max_heat (`layer_name` TEXT NOT NULL, `zoom` INTEGER NOT NULL, `max_heat` REAL NOT NULL, PRIMARY KEY(`layer_name`, `zoom`))")

			execSQL("DROP TABLE wifi_data")
			execSQL("CREATE TABLE wifi_data (`id` TEXT NOT NULL, `longitude` REAL NOT NULL, `latitude` REAL NOT NULL, `altitude` REAL, `first_seen` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `bssid` TEXT NOT NULL, `ssid` TEXT NOT NULL, `capabilities` TEXT NOT NULL, `frequency` INTEGER NOT NULL, `level` INTEGER NOT NULL, `isPasspoint` INTEGER NOT NULL, PRIMARY KEY(`id`))")
		}
	}
}

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("DROP TABLE wifi_data")
			execSQL("CREATE TABLE wifi_data (`bssid` TEXT NOT NULL, `longitude` REAL NOT NULL, `latitude` REAL NOT NULL, `altitude` REAL, `first_seen` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `ssid` TEXT NOT NULL, `capabilities` TEXT NOT NULL, `frequency` INTEGER NOT NULL, `level` INTEGER NOT NULL, PRIMARY KEY(`bssid`))")

			execSQL("CREATE TABLE IF NOT EXISTS `tracker_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `collections` INTEGER NOT NULL, `distance` REAL NOT NULL, `distance_on_foot` REAL NOT NULL, `distance_in_vehicle` REAL NOT NULL, `steps` INTEGER NOT NULL)")
			execSQL("INSERT INTO tracker_session SELECT id, start, `end`, collections, distance, 0.0, 0.0, steps FROM tracking_session")
			execSQL("DROP TABLE tracking_session")

			execSQL("DELETE FROM tracker_session WHERE start >= `end` OR collections <= 1")
		}
	}
}

val MIGRATION_5_6: Migration = object : Migration(5, 6) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("CREATE TABLE IF NOT EXISTS location_data_tmp (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `alt` REAL, `hor_acc` REAL, `ver_acc` REAL, `speed` REAL, `s_acc` REAL, `activity` INTEGER NOT NULL, `confidence` INTEGER NOT NULL)")

			execSQL("INSERT INTO location_data_tmp SELECT id, time, lat, lon, alt, hor_acc, ver_acc, null, null, activity, confidence from location_data")
			execSQL("DROP TABLE location_data")
			execSQL("ALTER TABLE location_data_tmp RENAME TO location_data")

			execSQL("CREATE INDEX `index_wifi_data_longitude` ON wifi_data (`longitude`)")
			execSQL("CREATE INDEX `index_wifi_data_latitude` ON wifi_data (`latitude`)")
			execSQL("CREATE INDEX `index_wifi_data_last_seen` ON wifi_data (`last_seen`)")

			execSQL("CREATE  INDEX index_location_data_time ON location_data (time)")
			execSQL("CREATE  INDEX index_location_data_lat ON location_data (lat)")
			execSQL("CREATE  INDEX index_location_data_lon ON location_data (lon)")
		}
	}
}

val MIGRATION_6_7: Migration = object : Migration(6, 7) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("CREATE TABLE IF NOT EXISTS tracker_session_tmp (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `user_initiated` INTEGER NOT NULL, `collections` INTEGER NOT NULL, `distance` REAL NOT NULL, `distance_on_foot` REAL NOT NULL, `distance_in_vehicle` REAL NOT NULL, `steps` INTEGER NOT NULL, `session_activity_id` INTEGER)")
			execSQL("CREATE TABLE IF NOT EXISTS activity (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `iconName` TEXT, PRIMARY KEY(`id`))")
			execSQL("CREATE  INDEX `index_activity_name` ON activity (`name`)")

			execSQL("INSERT INTO tracker_session_tmp SELECT `id`, `start`, `end`, 0, `collections`, `distance`, `distance_on_foot`, `distance_in_vehicle`, `steps`, null from tracker_session")
			execSQL("DROP TABLE tracker_session")
			execSQL("ALTER TABLE tracker_session_tmp RENAME TO tracker_session")
		}
	}
}

val MIGRATION_7_8: Migration = object : Migration(7, 8) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("DROP TABLE IF EXISTS activity")
			execSQL("CREATE TABLE IF NOT EXISTS activity (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `iconName` TEXT)")
			execSQL("CREATE  INDEX `index_activity_name` ON activity (`name`)")

			execSQL("CREATE TABLE IF NOT EXISTS tracker_session_tmp (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `user_initiated` INTEGER NOT NULL, `collections` INTEGER NOT NULL, `distance` REAL NOT NULL, `distance_on_foot` REAL NOT NULL, `distance_in_vehicle` REAL NOT NULL, `steps` INTEGER NOT NULL, `session_activity_id` INTEGER, FOREIGN KEY(`session_activity_id`) REFERENCES `activity`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
			execSQL("INSERT INTO tracker_session_tmp SELECT * from tracker_session")
			execSQL("DROP TABLE tracker_session")
			execSQL("ALTER TABLE tracker_session_tmp RENAME TO tracker_session")

			execSQL("DROP TABLE IF EXISTS map_max_heat")
		}
	}
}

val MIGRATION_8_9: Migration = object : Migration(8, 9) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("CREATE TABLE IF NOT EXISTS wifi_data_tmp (`bssid` TEXT NOT NULL, `longitude` REAL, `latitude` REAL, `altitude` REAL, `first_seen` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `ssid` TEXT NOT NULL, `capabilities` TEXT NOT NULL, `frequency` INTEGER NOT NULL, `level` INTEGER NOT NULL, PRIMARY KEY(`bssid`))")
			execSQL("INSERT INTO wifi_data_tmp SELECT * FROM wifi_data")
			execSQL("DROP TABLE wifi_data")
			execSQL("ALTER TABLE wifi_data_tmp RENAME TO wifi_data")

			execSQL("CREATE  INDEX `index_wifi_data_longitude` ON wifi_data (`longitude`)")
			execSQL("CREATE  INDEX `index_wifi_data_latitude` ON wifi_data (`latitude`)")
			execSQL("CREATE  INDEX `index_wifi_data_last_seen` ON wifi_data (`last_seen`)")

			execSQL("CREATE  INDEX `index_tracker_session_session_activity_id` ON tracker_session (`session_activity_id`)")

			execSQL("DROP TABLE cell_data")
			execSQL("CREATE TABLE IF NOT EXISTS network_operator (`mcc` TEXT NOT NULL, `mnc` TEXT NOT NULL, `name` TEXT, PRIMARY KEY(`mcc`, `mnc`))")
			execSQL("CREATE TABLE IF NOT EXISTS cell_location (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `mcc` TEXT NOT NULL, `mnc` TEXT NOT NULL, `cell_id` INTEGER NOT NULL, `type` INTEGER NOT NULL, `asu` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `alt` REAL)")
			execSQL("CREATE  INDEX `index_cell_location_mcc_mnc_cell_id` ON cell_location (`mcc`, `mnc`, `cell_id`)")
			execSQL("CREATE  INDEX `index_cell_location_time` ON cell_location (`time`)")
		}
	}
}

val MIGRATION_9_10: Migration = object : Migration(9, 10) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("CREATE TABLE IF NOT EXISTS location_wifi_count (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `count` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `alt` REAL)")
			execSQL("CREATE  INDEX `index_location_wifi_count_lon` ON location_wifi_count (`lon`)")
			execSQL("CREATE  INDEX `index_location_wifi_count_lat` ON location_wifi_count (`lat`)")
			execSQL("CREATE  INDEX `index_location_wifi_count_time` ON location_wifi_count (`time`)")
		}
	}
}

val MIGRATION_10_11: Migration = object : Migration(10, 11) {
	override fun migrate(db: SupportSQLiteDatabase) {
		// Keep the detailed 2024.1 activity id intact. MIGRATION_12_13 stores it in
		// session_segment.legacy_activity_id before the legacy table is removed.
	}
}

// Option B: Replace single-column indices on location_data with composite indices
// Add new indices first, then drop old to minimize disruption.
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// Create composite indices
			execSQL("CREATE INDEX IF NOT EXISTS idx_location_time_lat_lon ON location_data(time, lat, lon)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_location_lat_lon ON location_data(lat, lon)")

			// Drop old single-column indices if they exist (names from earlier migrations)
			// Safe even if already absent
			execSQL("DROP INDEX IF EXISTS index_location_data_time")
			execSQL("DROP INDEX IF EXISTS index_location_data_lat")
			execSQL("DROP INDEX IF EXISTS index_location_data_lon")
		}
	}
}

// Migration to sessionless tracking architecture.
// Creates new time-series tables (location_sample, step_interval, activity_snapshot, etc.)
// and migrates data from legacy tables with coordinate conversion to E7 format.
val MIGRATION_12_13: Migration = object : Migration(12, 13) {
	override fun migrate(db: SupportSQLiteDatabase) {
	with(db) {
		addColumnIfMissing(this, "tracker_session", "migration_original_activity_id", "INTEGER")
		execSQL(
			"UPDATE tracker_session SET migration_original_activity_id = session_activity_id " +
				"WHERE migration_original_activity_id IS NULL"
		)

		// 1. Create location_sample table
		execSQL("""
			CREATE TABLE IF NOT EXISTS location_sample (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				time_ms INTEGER NOT NULL,
				elapsed_realtime_nanos INTEGER NOT NULL,
				lat_e7 INTEGER,
				lon_e7 INTEGER,
				alt_m REAL,
				h_acc_m REAL,
				v_acc_m REAL,
				speed_mps REAL,
				speed_accuracy_mps REAL,
				provider TEXT NOT NULL,
				quality TEXT NOT NULL,
				motion_state TEXT,
				policy TEXT,
				bucket_id INTEGER,
				created_at INTEGER NOT NULL,
				legacy_lat REAL,
				legacy_lon REAL,
				legacy_alt_m REAL
			)
		""".trimIndent())
		execSQL("CREATE INDEX IF NOT EXISTS idx_location_sample_time ON location_sample(time_ms)")
		execSQL("CREATE INDEX IF NOT EXISTS idx_location_sample_coords ON location_sample(lat_e7, lon_e7)")
		execSQL("CREATE INDEX IF NOT EXISTS idx_location_sample_bucket ON location_sample(bucket_id)")		// 2. Create step_interval table
		execSQL("""
			CREATE TABLE IF NOT EXISTS step_interval (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				start_time_ms INTEGER NOT NULL,
				end_time_ms INTEGER NOT NULL,
				step_count INTEGER NOT NULL,
				sensor_value_start INTEGER NOT NULL,
				sensor_value_end INTEGER NOT NULL,
				sensor_reset INTEGER NOT NULL,
				created_at INTEGER NOT NULL
			)
	""".trimIndent())
	execSQL("CREATE INDEX IF NOT EXISTS idx_step_interval_time_range ON step_interval(start_time_ms, end_time_ms)")

	// 3. Create activity_snapshot table
	execSQL("""
		CREATE TABLE IF NOT EXISTS activity_snapshot (
			id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			time_ms INTEGER NOT NULL,
			activity_type INTEGER NOT NULL,
			confidence INTEGER NOT NULL,
			is_transition INTEGER NOT NULL,
			created_at INTEGER NOT NULL
		)
	""".trimIndent())
	execSQL("CREATE INDEX IF NOT EXISTS idx_activity_snapshot_time ON activity_snapshot(time_ms)")

	// 4. Create cell_sample table
	execSQL("""
		CREATE TABLE IF NOT EXISTS cell_sample (
			id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			time_ms INTEGER NOT NULL,
			cell_id INTEGER NOT NULL,
			lac INTEGER NOT NULL,
			mcc INTEGER NOT NULL,
			mnc INTEGER NOT NULL,
			network_type INTEGER NOT NULL,
			signal_strength INTEGER NOT NULL,
			lat_e7 INTEGER,
			lon_e7 INTEGER,
			provenance TEXT NOT NULL,
			created_at INTEGER NOT NULL,
			legacy_alt_m REAL,
			legacy_mcc TEXT,
			legacy_mnc TEXT,
			legacy_source_id INTEGER,
			legacy_lat REAL,
			legacy_lon REAL
		)
	""".trimIndent())
	execSQL("CREATE INDEX IF NOT EXISTS idx_cell_sample_time ON cell_sample(time_ms)")
	execSQL("CREATE INDEX IF NOT EXISTS idx_cell_sample_cell_id ON cell_sample(cell_id)")
	execSQL("CREATE INDEX IF NOT EXISTS idx_cell_sample_coords ON cell_sample(lat_e7, lon_e7)")

	// 5. Create wifi_observation table
	execSQL("""
		CREATE TABLE IF NOT EXISTS wifi_observation (
			id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			time_ms INTEGER NOT NULL,
			bssid TEXT NOT NULL,
			ssid TEXT NOT NULL,
			capabilities TEXT NOT NULL,
			frequency INTEGER NOT NULL,
			level INTEGER NOT NULL,
			lat_e7 INTEGER,
			lon_e7 INTEGER,
			provenance TEXT NOT NULL,
			created_at INTEGER NOT NULL,
			legacy_first_seen_ms INTEGER,
			legacy_alt_m REAL,
			legacy_lat REAL,
			legacy_lon REAL
		)
	""".trimIndent())
	execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_obs_time ON wifi_observation(time_ms)")
	execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_obs_bssid ON wifi_observation(bssid)")
	execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_obs_coords ON wifi_observation(lat_e7, lon_e7)")

	// 6. Create tracker_run table
	execSQL("""
		CREATE TABLE IF NOT EXISTS tracker_run (
			id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER,
			policy TEXT NOT NULL,
			policy_params TEXT,
			user_initiated INTEGER NOT NULL,
			created_at INTEGER NOT NULL
		)
	""".trimIndent())
	execSQL("CREATE INDEX IF NOT EXISTS idx_tracker_run_time_range ON tracker_run(start_time_ms, end_time_ms)")

	// 7. Create session_segment table
	execSQL("""
		CREATE TABLE IF NOT EXISTS session_segment (
			id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER NOT NULL,
			distance_m REAL NOT NULL,
			steps INTEGER,
			primary_activity INTEGER,
			activity_confidence INTEGER,
			sample_count INTEGER NOT NULL,
			source TEXT NOT NULL,
			inference_version TEXT,
			created_at INTEGER NOT NULL,
			legacy_user_initiated INTEGER,
			legacy_distance_on_foot_m REAL,
			legacy_distance_in_vehicle_m REAL,
			legacy_activity_id INTEGER
		)
	""".trimIndent())
	execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_time_range ON session_segment(start_time_ms, end_time_ms)")
	execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_source ON session_segment(source)")
	// SessionSegment entity declares this index; mirror it in the migration so a freshly
	// migrated schema matches the entity-generated schema.
	execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_primary_activity ON session_segment(primary_activity)")

	execSQL("""
		CREATE TABLE IF NOT EXISTS legacy_rejected_tracker_session (
			id INTEGER NOT NULL,
			start INTEGER NOT NULL,
			`end` INTEGER NOT NULL,
			user_initiated INTEGER NOT NULL,
			collections INTEGER NOT NULL,
			distance REAL NOT NULL,
			distance_on_foot REAL NOT NULL,
			distance_in_vehicle REAL NOT NULL,
			steps INTEGER NOT NULL,
			session_activity_id INTEGER,
			PRIMARY KEY(id)
		)
	""".trimIndent())

	execSQL("""
		CREATE TABLE IF NOT EXISTS legacy_location_wifi_count (
			id INTEGER NOT NULL,
			time INTEGER NOT NULL,
			count INTEGER NOT NULL,
			lat REAL NOT NULL,
			lon REAL NOT NULL,
			alt REAL,
			PRIMARY KEY(id)
		)
	""".trimIndent())
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_legacy_location_wifi_count_time " +
			"ON legacy_location_wifi_count(time)"
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_legacy_location_wifi_count_coords " +
			"ON legacy_location_wifi_count(lat, lon)"
	)

	// 8. Migrate data from location_data to location_sample
	// Convert lat/lon from Double to E7 integers (degrees * 1e7)
	// Classify quality based on horizontal accuracy
	val currentTimeMs = System.currentTimeMillis()
	execSQL("""
		INSERT INTO location_sample (
			id,
			time_ms,
			elapsed_realtime_nanos,
			lat_e7,
			lon_e7,
			alt_m,
			h_acc_m,
			v_acc_m,
			speed_mps,
			speed_accuracy_mps,
			provider,
			quality,
			motion_state,
			policy,
			bucket_id,
			created_at,
			legacy_lat,
			legacy_lon,
			legacy_alt_m
		)
		SELECT
			id,
			time,
			0,
			CAST(ROUND(lat * 10000000) AS INTEGER),
			CAST(ROUND(lon * 10000000) AS INTEGER),
			alt,
			hor_acc,
			ver_acc,
			speed,
			s_acc,
			'legacy',
			CASE
				WHEN hor_acc IS NULL THEN 'COARSE'
				WHEN hor_acc < 10 THEN 'HIGH'
				WHEN hor_acc < 50 THEN 'MEDIUM'
				ELSE 'LOW'
			END,
			CASE
				WHEN activity = 3 THEN 'STILL'
				WHEN activity IN (0, 1, 2, 7, 8) THEN 'MOVING'
				ELSE 'UNKNOWN'
			END,
			NULL,
			NULL,
			$currentTimeMs,
			lat,
			lon,
			alt
		FROM location_data
		ORDER BY time
	""".trimIndent())			// 9. Migrate tracker_session to session_segment
			// Mark all legacy sessions with LEGACY_MIGRATION source
			execSQL("""
				INSERT INTO session_segment (
					id,
					start_time_ms,
					end_time_ms,
					distance_m,
					steps,
					primary_activity,
					activity_confidence,
					sample_count,
					source,
					inference_version,
					created_at,
					legacy_user_initiated,
					legacy_distance_on_foot_m,
					legacy_distance_in_vehicle_m,
					legacy_activity_id
				)
				SELECT
					id,
					start,
					end,
					distance,
					steps,
					NULL,
					NULL,
					collections,
					'LEGACY_MIGRATION',
					'v12_migration',
					$currentTimeMs,
					user_initiated,
					distance_on_foot,
					distance_in_vehicle,
					migration_original_activity_id
				FROM tracker_session
				WHERE start < end AND collections > 1
				ORDER BY start
			""".trimIndent())

			execSQL("""
				INSERT INTO legacy_rejected_tracker_session (
					id, start, `end`, user_initiated, collections, distance,
					distance_on_foot, distance_in_vehicle, steps, session_activity_id
				)
				SELECT
					id, start, `end`, user_initiated, collections, distance,
					distance_on_foot, distance_in_vehicle, steps,
					migration_original_activity_id
				FROM tracker_session
				WHERE start >= `end` OR collections <= 1
				ORDER BY id
			""".trimIndent())

			execSQL("""
				INSERT INTO legacy_location_wifi_count(id, time, count, lat, lon, alt)
				SELECT id, time, count, lat, lon, alt
				FROM location_wifi_count
				ORDER BY id
			""".trimIndent())

			// 10. Migrate activity data from location_data to activity_snapshot
			// Extract unique activity changes (transitions)
			execSQL("""
				INSERT INTO activity_snapshot (
					id,
					time_ms,
					activity_type,
					confidence,
					is_transition,
					created_at
				)
				SELECT
					id,
					time,
					activity,
					confidence,
					0,
					$currentTimeMs
				FROM location_data
				WHERE activity IS NOT NULL
				ORDER BY time
			""".trimIndent())

			// 11. Validation: Count migrated records
			val locationCount = query("SELECT COUNT(*) FROM location_data").use { cursor ->
				if (cursor.moveToFirst()) cursor.getLong(0) else 0L
			}
			val sampleCount = query("SELECT COUNT(*) FROM location_sample").use { cursor ->
				if (cursor.moveToFirst()) cursor.getLong(0) else 0L
			}

			// Fail migration if counts don't match (data loss check)
			if (locationCount != sampleCount) {
				throw IllegalStateException(
					"Migration validation failed: location_data count ($locationCount) != " +
					"location_sample count ($sampleCount)"
				)
			}

			val trackerSessionCount = query("SELECT COUNT(*) FROM tracker_session").use { cursor ->
				if (cursor.moveToFirst()) cursor.getLong(0) else 0L
			}
			val migratedSessionCount = query("SELECT COUNT(*) FROM session_segment").use { cursor ->
				if (cursor.moveToFirst()) cursor.getLong(0) else 0L
			}
			val rejectedSessionCount =
				query("SELECT COUNT(*) FROM legacy_rejected_tracker_session").use { cursor ->
					if (cursor.moveToFirst()) cursor.getLong(0) else 0L
				}
			if (trackerSessionCount != migratedSessionCount + rejectedSessionCount) {
				throw IllegalStateException(
					"Migration validation failed: tracker_session count ($trackerSessionCount) != " +
						"session_segment ($migratedSessionCount) + rejected " +
						"($rejectedSessionCount)"
				)
			}

		}
	}
}

// Migration to add aggregator and live stats tables for Phase 3 (streaming aggregator/summary).
// Creates daily_summary (materialized daily aggregates) and live_stats (single-row dashboard stats).
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// 1. Create daily_summary table
			execSQL("""
				CREATE TABLE IF NOT EXISTS daily_summary (
					date_epoch_day INTEGER NOT NULL,
					total_distance_m REAL NOT NULL,
					total_steps INTEGER NOT NULL,
					total_duration_ms INTEGER NOT NULL,
					trip_count INTEGER NOT NULL,
					active_tracking_ms INTEGER NOT NULL,
					last_updated_ms INTEGER NOT NULL,
					created_at INTEGER NOT NULL,
					PRIMARY KEY(date_epoch_day)
				)
			""".trimIndent())
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_daily_summary_date_epoch_day ON daily_summary(date_epoch_day)")

			// 2. Create live_stats table
			execSQL("""
				CREATE TABLE IF NOT EXISTS live_stats (
					id INTEGER NOT NULL,
					date_epoch_day INTEGER NOT NULL,
					session_distance_m REAL NOT NULL,
					session_steps INTEGER NOT NULL,
					session_duration_ms INTEGER NOT NULL,
					day_total_distance_m REAL NOT NULL,
					day_total_steps INTEGER NOT NULL,
					day_total_duration_ms INTEGER NOT NULL,
					last_updated_ms INTEGER NOT NULL,
					PRIMARY KEY(id)
				)
			""".trimIndent())

		}
	}
}

// Migration to add trip inference tables for Phase 3b (place clustering, enriched trips, trip legs).
// Creates frequent_place (place clusters), inferred_trip (enriched trips with FK to places),
// and trip_leg (trip segments with FK CASCADE to inferred_trip).
val MIGRATION_14_15: Migration = object : Migration(14, 15) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// 1. Create frequent_place table
			execSQL("""
				CREATE TABLE IF NOT EXISTS frequent_place (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					center_lat_e7 INTEGER NOT NULL,
					center_lon_e7 INTEGER NOT NULL,
					radius_m REAL NOT NULL,
					visit_count INTEGER NOT NULL,
					first_visit_ms INTEGER NOT NULL,
					last_visit_ms INTEGER NOT NULL,
					auto_category TEXT,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS idx_frequent_place_coords ON frequent_place(center_lat_e7, center_lon_e7)")

			// 2. Create inferred_trip table (FKs to frequent_place, ON DELETE SET NULL)
			execSQL("""
				CREATE TABLE IF NOT EXISTS inferred_trip (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					segment_id INTEGER NOT NULL,
					start_time_ms INTEGER NOT NULL,
					end_time_ms INTEGER NOT NULL,
					distance_m REAL NOT NULL,
					steps INTEGER,
					primary_activity INTEGER,
					transport_mode TEXT NOT NULL,
					departure_place_id INTEGER,
					arrival_place_id INTEGER,
					source TEXT NOT NULL,
					inference_version TEXT,
					leg_count INTEGER NOT NULL,
					created_at INTEGER NOT NULL,
					FOREIGN KEY(departure_place_id) REFERENCES frequent_place(id) ON UPDATE NO ACTION ON DELETE SET NULL,
					FOREIGN KEY(arrival_place_id) REFERENCES frequent_place(id) ON UPDATE NO ACTION ON DELETE SET NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS idx_inferred_trip_time_range ON inferred_trip(start_time_ms, end_time_ms)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_inferred_trip_departure ON inferred_trip(departure_place_id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_inferred_trip_arrival ON inferred_trip(arrival_place_id)")

			// 3. Create trip_leg table (FK CASCADE to inferred_trip)
			execSQL("""
				CREATE TABLE IF NOT EXISTS trip_leg (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					trip_id INTEGER NOT NULL,
					sequence_index INTEGER NOT NULL,
					start_time_ms INTEGER NOT NULL,
					end_time_ms INTEGER NOT NULL,
					distance_m REAL NOT NULL,
					transport_mode TEXT NOT NULL,
					created_at INTEGER NOT NULL,
					FOREIGN KEY(trip_id) REFERENCES inferred_trip(id) ON UPDATE NO ACTION ON DELETE CASCADE
				)
			""".trimIndent())
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_trip_leg_trip_seq ON trip_leg(trip_id, sequence_index)")

		}
	}
}
// Migration to add S2 cell exploration tracking tables (Phase 4).
// Creates exploration_cell (discovered geographic cells) and exploration_streak (daily/weekly streaks).
val MIGRATION_15_16: Migration = object : Migration(15, 16) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// 1. Create exploration_cell table
			execSQL("""
				CREATE TABLE IF NOT EXISTS exploration_cell (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					cell_token TEXT NOT NULL,
					level INTEGER NOT NULL,
					quality INTEGER NOT NULL,
					first_discovered_at INTEGER NOT NULL,
					last_visited_at INTEGER NOT NULL,
					visit_count INTEGER NOT NULL DEFAULT 1,
					season_bitmask INTEGER NOT NULL DEFAULT 0,
					center_lat_e7 INTEGER NOT NULL,
					center_lon_e7 INTEGER NOT NULL,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_exploration_cell_cell_token ON exploration_cell(cell_token)")
			execSQL("CREATE INDEX IF NOT EXISTS index_exploration_cell_level ON exploration_cell(level)")
			execSQL("CREATE INDEX IF NOT EXISTS index_exploration_cell_first_discovered_at ON exploration_cell(first_discovered_at)")

			// 2. Create exploration_streak table
			execSQL("""
				CREATE TABLE IF NOT EXISTS exploration_streak (
					type TEXT NOT NULL,
					current_count INTEGER NOT NULL DEFAULT 0,
					best_count INTEGER NOT NULL DEFAULT 0,
					last_increment_day INTEGER NOT NULL DEFAULT 0,
					updated_at INTEGER NOT NULL DEFAULT 0,
					PRIMARY KEY(type)
				)
			""".trimIndent())

			// 3. Create achievement_progress table
			execSQL("""
				CREATE TABLE IF NOT EXISTS achievement_progress (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					achievement_id TEXT NOT NULL,
					current_value INTEGER NOT NULL,
					target_value INTEGER NOT NULL,
					tier INTEGER NOT NULL,
					unlocked_at INTEGER,
					updated_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_achievement_progress_achievement_id ON achievement_progress(achievement_id)")

			// 4. Create personal_record table
			execSQL("""
				CREATE TABLE IF NOT EXISTS personal_record (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					metric TEXT NOT NULL,
					value REAL NOT NULL,
					achieved_at INTEGER NOT NULL,
					updated_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_personal_record_metric ON personal_record(metric)")

		}
	}
}

// Migration to add route compression cache, export log, and storage size snapshot tables (Phase 6a).
// Creates route_cache (compressed polylines), export_log (export history), and storage_size_snapshot (daily metrics).
val MIGRATION_16_17: Migration = object : Migration(16, 17) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			if (columnExists(this, "step_interval", "createdAt")) {
				execSQL(
					"""
					CREATE TABLE step_interval_v17 (
						id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
						start_time_ms INTEGER NOT NULL,
						end_time_ms INTEGER NOT NULL,
						step_count INTEGER NOT NULL,
						sensor_value_start INTEGER NOT NULL,
						sensor_value_end INTEGER NOT NULL,
						sensor_reset INTEGER NOT NULL,
						created_at INTEGER NOT NULL
					)
					""".trimIndent()
				)
				execSQL(
					"""
					INSERT INTO step_interval_v17(
						id, start_time_ms, end_time_ms, step_count,
						sensor_value_start, sensor_value_end, sensor_reset, created_at
					)
					SELECT
						id, start_time_ms, end_time_ms, step_count,
						sensor_value_start, sensor_value_end, sensor_reset, createdAt
					FROM step_interval
					""".trimIndent()
				)
				execSQL("DROP TABLE step_interval")
				execSQL("ALTER TABLE step_interval_v17 RENAME TO step_interval")
				execSQL(
					"CREATE INDEX idx_step_interval_time_range " +
						"ON step_interval(start_time_ms, end_time_ms)"
				)
			}

			// 1. Create route_cache table
			execSQL("""
				CREATE TABLE IF NOT EXISTS route_cache (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					session_id INTEGER,
					segment_id INTEGER,
					encoded_polyline TEXT NOT NULL,
					point_count INTEGER NOT NULL,
					simplified_count INTEGER NOT NULL,
					start_time INTEGER NOT NULL,
					end_time INTEGER NOT NULL,
					distance_meters REAL NOT NULL,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS index_route_cache_session_id ON route_cache(session_id)")
			execSQL("CREATE INDEX IF NOT EXISTS index_route_cache_start_time ON route_cache(start_time)")

			// 2. Create export_log table
			execSQL("""
				CREATE TABLE IF NOT EXISTS export_log (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					format TEXT NOT NULL,
					scope TEXT NOT NULL,
					file_name TEXT NOT NULL,
					file_size_bytes INTEGER NOT NULL,
					record_count INTEGER NOT NULL,
					started_at INTEGER NOT NULL,
					completed_at INTEGER NOT NULL,
					status TEXT NOT NULL,
					error_message TEXT,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())

			// 3. Create storage_size_snapshot table
			execSQL("""
				CREATE TABLE IF NOT EXISTS storage_size_snapshot (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					epoch_day INTEGER NOT NULL,
					database_size_bytes INTEGER NOT NULL,
					location_count INTEGER NOT NULL,
					session_count INTEGER NOT NULL,
					wifi_count INTEGER NOT NULL,
					cell_count INTEGER NOT NULL,
					exploration_cell_count INTEGER NOT NULL,
					route_cache_count INTEGER NOT NULL,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_storage_size_snapshot_epoch_day ON storage_size_snapshot(epoch_day)")

			// 4. Create domain_event table (stats pipeline)
			execSQL("""
				CREATE TABLE IF NOT EXISTS domain_event (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					event_type TEXT NOT NULL,
					processor_id TEXT NOT NULL,
					timestamp_ms INTEGER NOT NULL,
					payload TEXT NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS index_domain_event_timestamp_ms ON domain_event(timestamp_ms)")

			// 5. Create domain_event_cursor table (per-consumer offset tracking)
			execSQL("""
				CREATE TABLE IF NOT EXISTS domain_event_cursor (
					consumer_id TEXT NOT NULL PRIMARY KEY,
					last_processed_ms INTEGER NOT NULL
				)
			""".trimIndent())

		}
	}
}

val MIGRATION_17_18: Migration = object : Migration(17, 18) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// 1. Create pressure_sample table for barometer data
			execSQL("""
				CREATE TABLE IF NOT EXISTS pressure_sample (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					time_ms INTEGER NOT NULL,
					elapsed_realtime_nanos INTEGER NOT NULL,
					pressure_hpa REAL NOT NULL,
					altitude_m REAL NOT NULL,
					bucket_id INTEGER,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS idx_pressure_sample_time ON pressure_sample(time_ms)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_pressure_sample_bucket ON pressure_sample(bucket_id)")

			// 2. Create ski_run_segment table for ski activity detection results
			execSQL("""
				CREATE TABLE IF NOT EXISTS ski_run_segment (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					session_id INTEGER NOT NULL,
					run_index INTEGER NOT NULL,
					segment_type TEXT NOT NULL,
					start_time_ms INTEGER NOT NULL,
					end_time_ms INTEGER NOT NULL,
					vertical_m REAL NOT NULL,
					distance_m REAL NOT NULL,
					max_speed_mps REAL NOT NULL,
					avg_speed_mps REAL NOT NULL,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS idx_ski_run_segment_session ON ski_run_segment(session_id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_ski_run_segment_start_time ON ski_run_segment(start_time_ms)")

		}
	}
}

/**
 * Migration 18 → 19: Add raw_gps_alt_m to location_sample and lift_type to ski_run_segment.
 *
 * raw_gps_alt_m preserves the original GPS altitude (before geoid correction and Kalman fusion)
 * alongside the processed altitude for diagnostics and potential re-processing.
 *
 * lift_type tags LIFT_UP ski_run_segment rows with the OSM lift type (chairlift, gondola, etc.).
 */
val MIGRATION_18_19: Migration = object : Migration(18, 19) {
	override fun migrate(db: SupportSQLiteDatabase) {
		addColumnIfMissing(db, "location_sample", "raw_gps_alt_m", "REAL")
		addColumnIfMissing(db, "ski_run_segment", "lift_type", "TEXT")
		db.execSQL(
			"""
			UPDATE location_sample
			SET raw_gps_alt_m = alt_m
			WHERE provider = 'legacy' AND raw_gps_alt_m IS NULL
			""".trimIndent()
		)
	}
}

/**
 * Adds a column to [table] only when it isn't already present. Defensive against
 * dev devices whose schema may have drifted from a partially-applied prior build.
 */
private fun addColumnIfMissing(
	db: SupportSQLiteDatabase,
	table: String,
	column: String,
	type: String,
) {
	if (!columnExists(db, table, column)) {
		db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
	}
}

private fun columnExists(
	db: SupportSQLiteDatabase,
	table: String,
	column: String,
): Boolean =
	db.query("PRAGMA table_info($table)").use { cursor ->
		val nameIndex = cursor.getColumnIndex("name").takeIf { it >= 0 } ?: return@use false
		var found = false
		while (cursor.moveToNext()) {
			if (cursor.getString(nameIndex) == column) {
				found = true
				break
			}
		}
		found
	}

private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
	db.query(
		"SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?)",
		arrayOf<Any?>(table),
	).use { cursor ->
		cursor.moveToFirst() && cursor.getInt(0) != 0
	}

/**
 * Migration 19 → 20: Add has_distance_anomaly flag to session_segment.
 *
 * Persists the GPS plausibility check result at write time so the flag
 * is authoritative rather than recomputed in the UI layer.
 * Defaults to 0 (false) for all existing rows.
 */
val MIGRATION_19_20: Migration = object : Migration(19, 20) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE session_segment ADD COLUMN has_distance_anomaly INTEGER NOT NULL DEFAULT 0")
	}
}

/**
 * Migration 20 → 21: Drop all legacy session-based tracking tables.
 *
 * Phase 5 of the sessionless architecture migration. All data now lives in
 * location_sample, session_segment, wifi_observation, cell_sample, etc.
 * Legacy tables are no longer read or written by any code path.
 *
 * NOTE: network_operator is KEPT — it is a reference table still used by the app
 * (operator name lookups for cell info display). It has no FK from cell_sample
 * but is conceptually referenced via mcc/mnc.
 */
val MIGRATION_20_21: Migration = object : Migration(20, 21) {
	override fun migrate(db: SupportSQLiteDatabase) {
		val currentTimeMs = System.currentTimeMillis()

		addColumnIfMissing(db, "location_sample", "legacy_lat", "REAL")
		addColumnIfMissing(db, "location_sample", "legacy_lon", "REAL")
		addColumnIfMissing(db, "location_sample", "legacy_alt_m", "REAL")
		addColumnIfMissing(db, "session_segment", "legacy_user_initiated", "INTEGER")
		addColumnIfMissing(db, "session_segment", "legacy_distance_on_foot_m", "REAL")
		addColumnIfMissing(db, "session_segment", "legacy_distance_in_vehicle_m", "REAL")
		addColumnIfMissing(db, "session_segment", "legacy_activity_id", "INTEGER")
		addColumnIfMissing(db, "cell_sample", "legacy_alt_m", "REAL")
		addColumnIfMissing(db, "cell_sample", "legacy_mcc", "TEXT")
		addColumnIfMissing(db, "cell_sample", "legacy_mnc", "TEXT")
		addColumnIfMissing(db, "cell_sample", "legacy_source_id", "INTEGER")
		addColumnIfMissing(db, "cell_sample", "legacy_lat", "REAL")
		addColumnIfMissing(db, "cell_sample", "legacy_lon", "REAL")
		addColumnIfMissing(db, "wifi_observation", "legacy_first_seen_ms", "INTEGER")
		addColumnIfMissing(db, "wifi_observation", "legacy_alt_m", "REAL")
		addColumnIfMissing(db, "wifi_observation", "legacy_lat", "REAL")
		addColumnIfMissing(db, "wifi_observation", "legacy_lon", "REAL")
		addColumnIfMissing(db, "tracker_session", "migration_original_activity_id", "INTEGER")
		db.execSQL(
			"UPDATE tracker_session SET migration_original_activity_id = session_activity_id " +
				"WHERE migration_original_activity_id IS NULL"
		)

		db.execSQL("""
			CREATE TABLE IF NOT EXISTS legacy_rejected_tracker_session (
				id INTEGER NOT NULL,
				start INTEGER NOT NULL,
				`end` INTEGER NOT NULL,
				user_initiated INTEGER NOT NULL,
				collections INTEGER NOT NULL,
				distance REAL NOT NULL,
				distance_on_foot REAL NOT NULL,
				distance_in_vehicle REAL NOT NULL,
				steps INTEGER NOT NULL,
				session_activity_id INTEGER,
				PRIMARY KEY(id)
			)
		""".trimIndent())
		db.execSQL("""
			CREATE TABLE IF NOT EXISTS legacy_location_wifi_count (
				id INTEGER NOT NULL,
				time INTEGER NOT NULL,
				count INTEGER NOT NULL,
				lat REAL NOT NULL,
				lon REAL NOT NULL,
				alt REAL,
				PRIMARY KEY(id)
			)
		""".trimIndent())
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS idx_legacy_location_wifi_count_time " +
				"ON legacy_location_wifi_count(time)"
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS idx_legacy_location_wifi_count_coords " +
				"ON legacy_location_wifi_count(lat, lon)"
		)

		db.execSQL("""
			UPDATE location_sample
			SET legacy_lat = (
					SELECT ld.lat FROM location_data ld
					WHERE ld.id = location_sample.id
				),
				legacy_lon = (
					SELECT ld.lon FROM location_data ld
					WHERE ld.id = location_sample.id
				),
				legacy_alt_m = (
					SELECT ld.alt FROM location_data ld
					WHERE ld.id = location_sample.id
				),
				raw_gps_alt_m = COALESCE(
					raw_gps_alt_m,
					(
						SELECT ld.alt FROM location_data ld
						WHERE ld.id = location_sample.id
					)
				)
			WHERE provider = 'legacy'
				AND legacy_lat IS NULL
				AND EXISTS (
					SELECT 1 FROM location_data ld WHERE ld.id = location_sample.id
				)
		""".trimIndent())
		db.execSQL("""
			UPDATE session_segment
			SET legacy_user_initiated = (
					SELECT ts.user_initiated FROM tracker_session ts
					WHERE ts.id = session_segment.id
				),
				legacy_distance_on_foot_m = (
					SELECT ts.distance_on_foot FROM tracker_session ts
					WHERE ts.id = session_segment.id
				),
				legacy_distance_in_vehicle_m = (
					SELECT ts.distance_in_vehicle FROM tracker_session ts
					WHERE ts.id = session_segment.id
				),
				legacy_activity_id = (
					SELECT COALESCE(ts.migration_original_activity_id, ts.session_activity_id)
					FROM tracker_session ts
					WHERE ts.id = session_segment.id
				)
			WHERE source = 'LEGACY_MIGRATION'
				AND legacy_user_initiated IS NULL
				AND EXISTS (
					SELECT 1 FROM tracker_session ts WHERE ts.id = session_segment.id
				)
		""".trimIndent())
		db.execSQL("""
			INSERT OR IGNORE INTO legacy_rejected_tracker_session (
				id, start, `end`, user_initiated, collections, distance,
				distance_on_foot, distance_in_vehicle, steps, session_activity_id
			)
			SELECT
				id, start, `end`, user_initiated, collections, distance,
				distance_on_foot, distance_in_vehicle, steps,
				COALESCE(migration_original_activity_id, session_activity_id)
			FROM tracker_session
			WHERE start >= `end` OR collections <= 1
		""".trimIndent())
		db.execSQL("""
			INSERT OR IGNORE INTO legacy_location_wifi_count(id, time, count, lat, lon, alt)
			SELECT id, time, count, lat, lon, alt FROM location_wifi_count
		""".trimIndent())

		val existingCellCount = db.query("SELECT COUNT(*) FROM cell_sample").use { cursor ->
			if (cursor.moveToFirst()) cursor.getLong(0) else 0L
		}
		val legacyCellCount = db.query("SELECT COUNT(*) FROM cell_location").use { cursor ->
			if (cursor.moveToFirst()) cursor.getLong(0) else 0L
		}
		val existingWifiCount = db.query("SELECT COUNT(*) FROM wifi_observation").use { cursor ->
			if (cursor.moveToFirst()) cursor.getLong(0) else 0L
		}
		val legacyWifiCount = db.query("SELECT COUNT(*) FROM wifi_data").use { cursor ->
			if (cursor.moveToFirst()) cursor.getLong(0) else 0L
		}

		// Backfill cell_sample from legacy cell_location.
		// cell_location schema: id, time, mcc TEXT, mnc TEXT, cell_id, type, asu, lat, lon, alt
		db.execSQL("""
			INSERT INTO cell_sample (
				time_ms, cell_id, lac, mcc, mnc, network_type,
				signal_strength, lat_e7, lon_e7, provenance, created_at, legacy_alt_m,
				legacy_mcc, legacy_mnc, legacy_source_id, legacy_lat, legacy_lon
			)
			SELECT
				cl.time,
				cl.cell_id,
				0,
				CAST(cl.mcc AS INTEGER),
				CAST(cl.mnc AS INTEGER),
				cl.type,
				cl.asu,
				CASE WHEN cl.lat IS NOT NULL THEN CAST(ROUND(cl.lat * 10000000) AS INTEGER) ELSE NULL END,
				CASE WHEN cl.lon IS NOT NULL THEN CAST(ROUND(cl.lon * 10000000) AS INTEGER) ELSE NULL END,
				'LEGACY_MIGRATION',
				$currentTimeMs,
				cl.alt,
				cl.mcc,
				cl.mnc,
				cl.id,
				cl.lat,
				cl.lon
			FROM cell_location cl
		""".trimIndent())

		// Backfill wifi_observation from legacy wifi_data (one observation per AP).
		// wifi_data schema: bssid PK, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level
		db.execSQL("""
			INSERT INTO wifi_observation (
				time_ms, bssid, ssid, capabilities, frequency, level,
				lat_e7, lon_e7, provenance, created_at, legacy_first_seen_ms, legacy_alt_m,
				legacy_lat, legacy_lon
			)
			SELECT
				wd.last_seen,
				wd.bssid,
				wd.ssid,
				wd.capabilities,
				wd.frequency,
				wd.level,
				CASE WHEN wd.latitude IS NOT NULL THEN CAST(ROUND(wd.latitude * 10000000) AS INTEGER) ELSE NULL END,
				CASE WHEN wd.longitude IS NOT NULL THEN CAST(ROUND(wd.longitude * 10000000) AS INTEGER) ELSE NULL END,
				'LEGACY_MIGRATION',
				$currentTimeMs,
				wd.first_seen,
				wd.altitude,
				wd.latitude,
				wd.longitude
			FROM wifi_data wd
		""".trimIndent())

		val migratedCellCount = db.query("SELECT COUNT(*) FROM cell_sample").use { cursor ->
			if (cursor.moveToFirst()) cursor.getLong(0) else 0L
		}
		val expectedCellCount = existingCellCount + legacyCellCount
		if (expectedCellCount != migratedCellCount) {
			throw IllegalStateException(
				"Migration validation failed: expected $expectedCellCount cell_sample rows " +
					"($existingCellCount existing + $legacyCellCount legacy), found $migratedCellCount"
			)
		}

		val migratedWifiCount = db.query("SELECT COUNT(*) FROM wifi_observation").use { cursor ->
			if (cursor.moveToFirst()) cursor.getLong(0) else 0L
		}
		val expectedWifiCount = existingWifiCount + legacyWifiCount
		if (expectedWifiCount != migratedWifiCount) {
			throw IllegalStateException(
				"Migration validation failed: expected $expectedWifiCount wifi_observation rows " +
					"($existingWifiCount existing + $legacyWifiCount legacy), found $migratedWifiCount"
			)
		}

		db.execSQL("DROP TABLE IF EXISTS tracker_session")
		db.execSQL("DROP TABLE IF EXISTS location_data")
		db.execSQL("DROP TABLE IF EXISTS wifi_data")
		db.execSQL("DROP TABLE IF EXISTS cell_location")
		db.execSQL("DROP TABLE IF EXISTS location_wifi_count")
	}
}

/**
 * Version 22: Widen cell_sample.cell_id from Int to Long for 5G NR NCI support.
 *
 * SQLite stores INTEGER as up to 8 bytes natively, so no physical column change
 * is required. This migration is a no-op that bumps the version so Room's
 * schema validator accepts the entity change from Kotlin Int → Long.
 */
val MIGRATION_21_22: Migration = object : Migration(21, 22) {
	override fun migrate(db: SupportSQLiteDatabase) {
		// No-op: SQLite INTEGER already supports 64-bit values.
		// Room maps both Kotlin Int and Long to "INTEGER NOT NULL".
	}
}

/**
 * Version 23: Add missing query indices for route cache, export logs,
 * frequent places, exploration cells, and achievement progress.
 */
val MIGRATION_22_23: Migration = object : Migration(22, 23) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("CREATE INDEX IF NOT EXISTS index_route_cache_segment_id ON route_cache(segment_id)")
			execSQL("CREATE INDEX IF NOT EXISTS index_export_log_completed_at ON export_log(completed_at)")
			execSQL("CREATE INDEX IF NOT EXISTS index_frequent_place_last_visit_ms ON frequent_place(last_visit_ms)")
			execSQL("CREATE INDEX IF NOT EXISTS index_exploration_cell_level_first_discovered_at ON exploration_cell(level, first_discovered_at)")
			execSQL("CREATE INDEX IF NOT EXISTS index_achievement_progress_updated_at ON achievement_progress(updated_at)")
			execSQL("CREATE INDEX IF NOT EXISTS index_achievement_progress_unlocked_at ON achievement_progress(unlocked_at)")
		}
	}
}

/**
 * Version 23 → 24: Add notified_at to achievement_progress and align the table
 * with the current nullable tier / null-default schema contract.
 */
val MIGRATION_23_24: Migration = object : Migration(23, 24) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS achievement_progress_new (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					achievement_id TEXT NOT NULL,
					current_value INTEGER NOT NULL,
					target_value INTEGER NOT NULL,
					tier INTEGER,
					unlocked_at INTEGER,
					updated_at INTEGER NOT NULL,
					notified_at INTEGER
				)
				""".trimIndent()
			)
			execSQL(
				"""
				INSERT INTO achievement_progress_new (
					id,
					achievement_id,
					current_value,
					target_value,
					tier,
					unlocked_at,
					updated_at,
					notified_at
				)
				SELECT
					id,
					achievement_id,
					current_value,
					target_value,
					tier,
					unlocked_at,
					updated_at,
					NULL
				FROM achievement_progress
				""".trimIndent()
			)
			execSQL("DROP TABLE achievement_progress")
			execSQL("ALTER TABLE achievement_progress_new RENAME TO achievement_progress")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_achievement_progress_achievement_id ON achievement_progress(achievement_id)")
			execSQL("CREATE INDEX IF NOT EXISTS index_achievement_progress_updated_at ON achievement_progress(updated_at)")
			execSQL("CREATE INDEX IF NOT EXISTS index_achievement_progress_unlocked_at ON achievement_progress(unlocked_at)")
		}
	}
}

val MIGRATION_24_25: Migration = object : Migration(24, 25) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS pending_signal (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					session_id INTEGER NOT NULL,
					signal_json TEXT NOT NULL,
					created_at INTEGER NOT NULL
				)
					""".trimIndent(),
				)
			execSQL(
				"""
				CREATE INDEX IF NOT EXISTS idx_pending_signal_session_time
				ON pending_signal (session_id, created_at)
				""".trimIndent(),
			)
		}
	}
}

/**
 * Version 25 → 26: Add missing query indices for recent analytics and export lookups.
 */
val MIGRATION_25_26: Migration = object : Migration(25, 26) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("CREATE INDEX IF NOT EXISTS index_domain_event_event_type_processor_id ON domain_event(event_type, processor_id)")
			execSQL("CREATE INDEX IF NOT EXISTS index_export_log_started_at ON export_log(started_at)")
			execSQL("CREATE INDEX IF NOT EXISTS index_inferred_trip_segment_id ON inferred_trip(segment_id)")
			// Declared on SessionSegment and present in v26.json, but no earlier
			// migration created it. Keep it here for upgrades crossing 25 -> 26;
			// MIGRATION_26_27 repeats it for installations already on released v26.
			execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_primary_activity ON session_segment(primary_activity)")
			// Index end_time_ms so the cross-midnight overlap query (introduced in
			// commit 415f7ff4f) can choose the more selective predicate when
			// materializing today's daily summary against a large segment history.
			execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_end_time_ms ON session_segment(end_time_ms)")
			// Composite domain-event cursor: timestamp alone could skip events that
			// share a millisecond with the last-acked one. Existing rows get 0 as
			// the default last_processed_id, which is safely below any real event id.
			execSQL("ALTER TABLE domain_event_cursor ADD COLUMN last_processed_id INTEGER NOT NULL DEFAULT 0")
			// Composite (timestamp_ms, id) index for the cursor seek query — replaces
			// the standalone timestamp_ms index (Room recreates implicitly). Without
			// this the cursor predicate falls back to an ordered scan over the
			// timestamp_ms index, O(rows) per fetch.
			execSQL("DROP INDEX IF EXISTS index_domain_event_timestamp_ms")
			execSQL("CREATE INDEX IF NOT EXISTS index_domain_event_timestamp_ms_id ON domain_event(timestamp_ms, id)")
		}
	}
}

/**
 * Version 26 → 27: Add game progression tables.
 */
val MIGRATION_26_27: Migration = object : Migration(26, 27) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// Version 26 shipped in versionCode 385. Those installations do not rerun
			// MIGRATION_25_26, so reconcile the indexes and cursor column here as
			// idempotent DDL before applying the rest of the unreleased v27 schema.
			execSQL("DROP INDEX IF EXISTS index_domain_event_timestamp_ms")
			execSQL("CREATE INDEX IF NOT EXISTS index_domain_event_timestamp_ms_id ON domain_event(timestamp_ms, id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_end_time_ms ON session_segment(end_time_ms)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_primary_activity ON session_segment(primary_activity)")
			addColumnIfMissing(this, "domain_event_cursor", "last_processed_id", "INTEGER NOT NULL DEFAULT 0")

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS xp_ledger (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					amount INTEGER NOT NULL,
					source TEXT NOT NULL,
					source_id INTEGER,
					earned_at INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_xp_ledger_source_source_id ON xp_ledger(source, source_id)")

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS player_profile (
					id INTEGER NOT NULL,
					total_xp INTEGER NOT NULL,
					level INTEGER NOT NULL,
					xp_into_current_level INTEGER NOT NULL,
					xp_for_next_level INTEGER NOT NULL,
					PRIMARY KEY(id)
				)
				""".trimIndent(),
			)

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS minigame_score (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					game_id TEXT NOT NULL,
					score REAL NOT NULL,
					xp_awarded INTEGER NOT NULL,
					played_at INTEGER NOT NULL
				)
				""".trimIndent(),
			)
		}

		// Version 26 is the schema shipped in versionCode 385. None of the
		// following schema work has shipped, so apply it as implementation
		// detail of this single release-boundary migration. The individual
		// steps remain separately testable, but are deliberately not
		// registered with Room as database versions.
		MIGRATION_27_28.migrate(db)
		MIGRATION_28_29.migrate(db)
		MIGRATION_29_30.migrate(db)
		MIGRATION_30_31.migrate(db)
		MIGRATION_31_32.migrate(db)
		MIGRATION_32_33.migrate(db)
		MIGRATION_33_34.migrate(db)
		MIGRATION_34_35.migrate(db)
		MIGRATION_35_36.migrate(db)
		MIGRATION_36_37.migrate(db)
		MIGRATION_37_38.migrate(db)
		MIGRATION_38_39.migrate(db)
		MIGRATION_39_40.migrate(db)

		// Final v27 query-plan reconciliation. These replace left-prefix indexes with the
		// composite keys used by cursor pagination and add the end-time key used by frequent
		// step lookups and retention. Version 27 has not shipped, so this remains part of the
		// single released 26 -> 27 boundary rather than consuming more database versions.
		with(db) {
			execSQL("CREATE INDEX IF NOT EXISTS idx_step_interval_end_time ON step_interval(end_time_ms)")
			execSQL("DROP INDEX IF EXISTS idx_cell_sample_time")
			execSQL("DROP INDEX IF EXISTS idx_cell_sample_cell_id")
			execSQL("CREATE INDEX IF NOT EXISTS idx_cell_sample_time_id ON cell_sample(time_ms, id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_cell_sample_identity ON cell_sample(mcc, mnc, cell_id)")
			execSQL("DROP INDEX IF EXISTS idx_wifi_obs_time")
			execSQL("DROP INDEX IF EXISTS idx_wifi_obs_bssid")
			execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_obs_time_id ON wifi_observation(time_ms, id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_obs_bssid_time ON wifi_observation(bssid, time_ms)")
			// `(time_ms, id)` is a left-prefix replacement for the single-column index and
			// avoids maintaining two B-trees for every high-frequency location insert.
			execSQL("DROP INDEX IF EXISTS idx_location_sample_time")
			createSourceEventPipelineTables()
		}
	}
}

private fun SupportSQLiteDatabase.createSourceEventPipelineTables() {
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_event_wal (
			admission_ordinal INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			event_id TEXT NOT NULL,
			provider_dedup_key TEXT,
			logical_tracking_id TEXT,
			service_run_id TEXT,
			source_kind INTEGER NOT NULL,
			source_instance_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL,
			source_sequence INTEGER NOT NULL,
			config_revision INTEGER,
			plan_attribution INTEGER NOT NULL,
			clock_domain_id TEXT NOT NULL,
			observed_elapsed_nanos INTEGER NOT NULL,
			received_elapsed_nanos INTEGER NOT NULL,
			wall_time_ms INTEGER,
			wall_time_uncertainty_ms INTEGER,
			captured_collected_data_epoch INTEGER NOT NULL,
			acquired_at_ms INTEGER NOT NULL,
			quality_flags INTEGER NOT NULL,
			quality_confidence REAL,
			payload_version INTEGER NOT NULL,
			payload BLOB NOT NULL,
			payload_checksum TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL
		)
		""".trimIndent(),
	)
	execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_source_event_wal_event_id ON source_event_wal(event_id)")
	execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_source_event_wal_provider_dedup " +
			"ON source_event_wal(source_kind, provider_dedup_key)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_event_wal_tracking_ordinal " +
			"ON source_event_wal(logical_tracking_id, admission_ordinal)",
	)
	execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_source_event_wal_source_sequence " +
			"ON source_event_wal(source_kind, source_instance_id, source_sequence)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_event_wal_lifecycle " +
			"ON source_event_wal(captured_collected_data_epoch, acquired_at_ms)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_event_wal_retention " +
			"ON source_event_wal(created_at_ms, admission_ordinal)",
	)

	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_projection_registration (
			projection_id TEXT NOT NULL,
			projection_version INTEGER NOT NULL,
			activation_ordinal INTEGER NOT NULL,
			retention_required INTEGER NOT NULL,
			status TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL,
			PRIMARY KEY(projection_id, projection_version)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_registration_status " +
			"ON source_projection_registration(status, activation_ordinal)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_projection_checkpoint (
			projection_id TEXT NOT NULL,
			projection_version INTEGER NOT NULL,
			contiguous_admission_ordinal INTEGER NOT NULL,
			state_version INTEGER NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(projection_id, projection_version)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_checkpoint_ordinal " +
			"ON source_projection_checkpoint(contiguous_admission_ordinal)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_projection_failure (
			projection_id TEXT NOT NULL,
			projection_version INTEGER NOT NULL,
			admission_ordinal INTEGER NOT NULL,
			attempt_count INTEGER NOT NULL,
			failure_code TEXT NOT NULL,
			terminal INTEGER NOT NULL,
			last_attempt_at_ms INTEGER NOT NULL,
			PRIMARY KEY(projection_id, projection_version, admission_ordinal)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_failure_retry " +
			"ON source_projection_failure(terminal, last_attempt_at_ms)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_projection_join_state (
			projection_id TEXT NOT NULL,
			projection_version INTEGER NOT NULL,
			state_key TEXT NOT NULL,
			logical_tracking_id TEXT,
			minimum_required_ordinal INTEGER NOT NULL,
			payload_version INTEGER NOT NULL,
			payload BLOB NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(projection_id, projection_version, state_key)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_join_tracking " +
			"ON source_projection_join_state(logical_tracking_id)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_join_updated " +
			"ON source_projection_join_state(updated_at_ms)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_join_retention " +
			"ON source_projection_join_state(minimum_required_ordinal)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_projection_outbox (
			stable_id TEXT NOT NULL,
			projection_id TEXT NOT NULL,
			projection_version INTEGER NOT NULL,
			admission_ordinal INTEGER NOT NULL,
			effect_kind TEXT NOT NULL,
			payload_version INTEGER NOT NULL,
			payload BLOB NOT NULL,
			created_at_ms INTEGER NOT NULL,
			delivered_at_ms INTEGER,
			PRIMARY KEY(stable_id)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_outbox_delivery " +
			"ON source_projection_outbox(delivered_at_ms, created_at_ms)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_projection_outbox_kind_pending " +
			"ON source_projection_outbox(effect_kind, delivered_at_ms, admission_ordinal)",
	)

	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS location_projection_observation (
			event_id TEXT NOT NULL,
			logical_tracking_id TEXT NOT NULL,
			admission_ordinal INTEGER NOT NULL,
			elapsed_realtime_nanos INTEGER NOT NULL,
			wall_time_ms INTEGER NOT NULL,
			latitude_degrees REAL NOT NULL,
			longitude_degrees REAL NOT NULL,
			horizontal_accuracy_meters REAL NOT NULL,
			altitude_meters REAL,
			vertical_accuracy_meters REAL,
			speed_meters_per_second REAL,
			PRIMARY KEY(event_id)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_location_projection_observation_order " +
			"ON location_projection_observation(" +
			"logical_tracking_id, elapsed_realtime_nanos, wall_time_ms, event_id)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_location_projection_observation_retention " +
			"ON location_projection_observation(wall_time_ms)",
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_location_projection_observation_ordinal " +
			"ON location_projection_observation(admission_ordinal)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS location_projection_point (
			event_id TEXT NOT NULL,
			logical_tracking_id TEXT NOT NULL,
			revision INTEGER NOT NULL,
			accepted INTEGER NOT NULL,
			rejection TEXT,
			latitude_degrees REAL NOT NULL,
			longitude_degrees REAL NOT NULL,
			segment_distance_meters REAL NOT NULL,
			cumulative_distance_meters REAL NOT NULL,
			estimated_speed_meters_per_second REAL,
			raw_wgs84_altitude_meters REAL,
			vertical_accuracy_meters REAL,
			elapsed_realtime_nanos INTEGER NOT NULL,
			PRIMARY KEY(event_id),
			FOREIGN KEY(event_id) REFERENCES location_projection_observation(event_id)
				ON UPDATE CASCADE ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_location_projection_point_tracking " +
			"ON location_projection_point(logical_tracking_id)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_coordinator_lease (
			lease_name TEXT NOT NULL,
			owner_token TEXT NOT NULL,
			acquired_at_ms INTEGER NOT NULL,
			expires_at_ms INTEGER NOT NULL,
			PRIMARY KEY(lease_name)
		)
		""".trimIndent(),
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_registration_state (
			source_kind INTEGER NOT NULL,
			owner_scope TEXT NOT NULL,
			source_instance_id TEXT NOT NULL,
			clock_domain_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL,
			next_sequence INTEGER NOT NULL,
			applied_revision INTEGER,
			collected_data_epoch INTEGER NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(source_kind, owner_scope)
		)
		""".trimIndent(),
	)

	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS logical_tracking_session (
			logical_tracking_id TEXT NOT NULL,
			state TEXT NOT NULL,
			lifecycle_revision INTEGER NOT NULL,
			desired_plan_revision INTEGER NOT NULL,
			rollout_revision INTEGER NOT NULL,
			start_origin TEXT NOT NULL,
			clock_domain_id TEXT NOT NULL,
			started_at_ms INTEGER NOT NULL,
			started_elapsed_nanos INTEGER NOT NULL,
			cutoff_at_ms INTEGER,
			cutoff_elapsed_nanos INTEGER,
			completed_at_ms INTEGER,
			final_admission_ordinal INTEGER,
			failure_code TEXT,
			PRIMARY KEY(logical_tracking_id)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_logical_tracking_session_state " +
			"ON logical_tracking_session(state, started_at_ms)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_service_run (
			service_run_id TEXT NOT NULL,
			logical_tracking_id TEXT NOT NULL,
			state TEXT NOT NULL,
			desired_plan_revision INTEGER NOT NULL,
			rollout_revision INTEGER NOT NULL,
			foreground_capability_flags INTEGER NOT NULL,
			started_at_ms INTEGER NOT NULL,
			started_elapsed_nanos INTEGER NOT NULL,
			completed_at_ms INTEGER,
			completion_reason TEXT,
			PRIMARY KEY(service_run_id)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_service_run_tracking " +
			"ON source_service_run(logical_tracking_id, started_at_ms)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_event_session_binding (
			event_id TEXT NOT NULL,
			binding_revision INTEGER NOT NULL,
			admission_ordinal INTEGER NOT NULL,
			logical_tracking_id TEXT NOT NULL,
			service_run_id TEXT,
			binding_reason TEXT NOT NULL,
			decision_status TEXT NOT NULL,
			clock_domain_id TEXT NOT NULL,
			interval_start_elapsed_nanos INTEGER NOT NULL,
			interval_end_elapsed_nanos INTEGER,
			bound_at_ms INTEGER NOT NULL,
			PRIMARY KEY(event_id, binding_revision)
		)
		""".trimIndent(),
	)
	execSQL(
		"CREATE INDEX IF NOT EXISTS idx_source_event_binding_tracking " +
			"ON source_event_session_binding(logical_tracking_id, admission_ordinal)",
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_session_completeness (
			logical_tracking_id TEXT NOT NULL,
			source_kind INTEGER NOT NULL,
			source_instance_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL,
			last_admission_ordinal INTEGER,
			last_source_sequence INTEGER,
			app_drain_complete INTEGER NOT NULL,
			provider_coverage TEXT NOT NULL,
			stop_status TEXT NOT NULL,
			unresolved_sequence_start INTEGER,
			unresolved_sequence_end INTEGER,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(logical_tracking_id, source_kind, source_instance_id)
		)
		""".trimIndent(),
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_runtime_state (
			source_kind INTEGER NOT NULL,
			owner_scope TEXT NOT NULL,
			source_instance_id TEXT NOT NULL,
			clock_domain_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL,
			last_provider_sequence INTEGER NOT NULL,
			last_admitted_source_sequence INTEGER,
			last_admission_ordinal INTEGER,
			state_version INTEGER NOT NULL,
			payload BLOB NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(source_kind, owner_scope)
		)
		""".trimIndent(),
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS acquisition_plan_revision (
			revision INTEGER NOT NULL,
			plan_id TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL,
			status TEXT NOT NULL,
			PRIMARY KEY(revision)
		)
		""".trimIndent(),
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_desired_plan (
			revision INTEGER NOT NULL,
			source_kind INTEGER NOT NULL,
			payload_version INTEGER NOT NULL,
			payload BLOB NOT NULL,
			payload_checksum TEXT NOT NULL,
			PRIMARY KEY(revision, source_kind)
		)
		""".trimIndent(),
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_applied_plan_state (
			source_kind INTEGER NOT NULL,
			desired_revision INTEGER NOT NULL,
			applied_revision INTEGER,
			source_instance_id TEXT,
			registration_generation INTEGER,
			applied_at_elapsed_nanos INTEGER,
			status TEXT NOT NULL,
			degraded_reasons TEXT NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(source_kind)
		)
		""".trimIndent(),
	)
	execSQL(
		"""
		CREATE TABLE IF NOT EXISTS tracking_rollout_state (
			id INTEGER NOT NULL,
			revision INTEGER NOT NULL,
			schema_version INTEGER NOT NULL,
			coordinator_mode TEXT NOT NULL,
			projection_mode TEXT NOT NULL,
			source_owners TEXT NOT NULL,
			semantic_settings_enabled INTEGER NOT NULL,
			battery_estimate_mode TEXT NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(id)
		)
		""".trimIndent(),
	)
}

/**
 * Version 27 → 28: Drop challenges and convert achievement progress to per-metric state.
 */
val MIGRATION_27_28: Migration = object : Migration(27, 28) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("DROP TABLE IF EXISTS challenge")
			execSQL("DROP TABLE IF EXISTS challenge_history")
			execSQL("DROP TABLE IF EXISTS challenge_streak")
			execSQL("DROP TABLE IF EXISTS challenge_personal_record")
			execSQL("DROP TABLE IF EXISTS achievement_progress")
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS achievement_progress (
					metric_key TEXT NOT NULL,
					last_tier_index INTEGER NOT NULL DEFAULT -1,
					last_value REAL NOT NULL DEFAULT 0,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY(metric_key)
				)
				""".trimIndent(),
			)
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_achievement_progress_metric_key ON achievement_progress(metric_key)")
		}
	}
}

/**
 * Version 28 → 29: Add OSM (OpenStreetMap) road graph tables backing the
 * Phase 2 "Vehicle speed compliance" feature.
 *
 * Three new tables:
 *
 *  - `osm_import` — one row per user-imported `.osm.pbf` file. Acts as the
 *    "is the OSM source active?" signal for `DefaultSpeedLimitSource`.
 *  - `osm_way` — one immutable, import-scoped instance per driveable OSM
 *    way; inline-encoded geometry, explicit `maxspeed_kmh`, road class, and
 *    bbox. The local surrogate key is distinct from the upstream OSM id so
 *    overlapping retained regions cannot replace one another.
 *  - `osm_way_cell` — coarse-grid spatial index linking each way to every
 *    grid cell its bbox overlaps. Cell key is
 *    `(latE7 / 800_000) << 24 | (lonE7 / 800_000) & 0xFFFFFF`.
 *
 * All OSM data is © OpenStreetMap contributors and licensed under ODbL 1.0;
 * the presence of any row in `osm_import` triggers the in-app attribution UI.
 *
 * No data migration needed — these tables are net-new.
 */
val MIGRATION_28_29: Migration = object : Migration(28, 29) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS osm_import (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					display_name TEXT NOT NULL,
					file_uri TEXT NOT NULL,
					imported_at INTEGER NOT NULL,
					way_count INTEGER NOT NULL,
					node_count INTEGER NOT NULL,
					min_lat_e7 INTEGER NOT NULL,
					max_lat_e7 INTEGER NOT NULL,
					min_lon_e7 INTEGER NOT NULL,
					max_lon_e7 INTEGER NOT NULL
				)
				""".trimIndent(),
			)

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS osm_way (
					way_instance_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					osm_way_id INTEGER NOT NULL,
					import_id INTEGER NOT NULL,
					osm_version INTEGER,
					name TEXT,
					road_class TEXT NOT NULL,
					maxspeed_kmh INTEGER NOT NULL,
					maxspeed_explicit INTEGER NOT NULL,
					is_oneway INTEGER NOT NULL,
					geom_polyline_e7 BLOB NOT NULL,
					bbox_min_lat_e7 INTEGER NOT NULL,
					bbox_max_lat_e7 INTEGER NOT NULL,
					bbox_min_lon_e7 INTEGER NOT NULL,
					bbox_max_lon_e7 INTEGER NOT NULL,
					FOREIGN KEY(import_id) REFERENCES osm_import(id) ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_import ON osm_way(import_id)")
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_osm_way_import_osm_id " +
					"ON osm_way(import_id, osm_way_id)",
			)
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_osm_id ON osm_way(osm_way_id)")

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS osm_way_cell (
					cell_key INTEGER NOT NULL,
					way_id INTEGER NOT NULL,
					PRIMARY KEY(cell_key, way_id),
					FOREIGN KEY(way_id) REFERENCES osm_way(way_instance_id) ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_cell_cell ON osm_way_cell(cell_key)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_cell_way ON osm_way_cell(way_id)")

		}
	}
}

/**
 * Adds composite `(time_ms, id)` index on `location_sample`.
 *
 * The vehicle compliance map layer scans large date ranges with a stable
 * cursor predicate `(time_ms > :after) OR (time_ms = :after AND id > :afterId)`
 * and `ORDER BY time_ms, id`. Without the composite index, SQLite can fall
 * back to a SCAN+sort plan (the same risk the domain-event cursor refactor
 * addressed). The single-column `idx_location_sample_time` covers range
 * filtering but cannot fully cover the `(time_ms, id)` ordering.
 *
 * R2 round-6 perf review priority 2.
 */
val MIGRATION_29_30: Migration = object : Migration(29, 30) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS idx_location_sample_time_id " +
				"ON location_sample(time_ms, id)",
		)
	}
}

/**
 * v30 -> v31: Add `idx_minigame_score_played_at` covering the
 * `getRecent(...) ORDER BY played_at DESC LIMIT ?` query that drives the
 * dashboard "recent runs" panel. Without this index Room performs a full
 * SCAN of `minigame_score` plus a temp B-tree sort on every dashboard
 * recomposition; players who replay mini-games heavily end up with that
 * scan happening dozens of times per minute.
 *
 * IF NOT EXISTS guard so re-running the migration on a database that has
 * already been touched by Room's schema validator is a no-op.
 *
 * R3 round-7 perf review priority 2.
 */
val MIGRATION_30_31: Migration = object : Migration(30, 31) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS idx_minigame_score_played_at " +
				"ON minigame_score(played_at)",
		)
	}
}

/**
 * v31 -> v32: Drop every `osm_way_cell` row so the cell-key spatial index
 * can be rebuilt under the new `OsmGridIndex` cell size (0.01° / ~1.1 km,
 * down from 0.08° / ~9 km). The keys produced by the old grid are not
 * compatible with the new grid — they would silently return wrong matches
 * for every speed-limit lookup — so the only safe option is to wipe and
 * reindex.
 *
 * What this migration touches:
 *  - `osm_way_cell`: all rows deleted (table schema is unchanged).
 *
 * What this migration deliberately leaves alone:
 *  - `osm_import`: still present, so [com.adsamcik.tracker.shared.base.database.dao.OsmImportDao.observeCount]
 *    keeps reporting >0 and `DefaultSpeedLimitSource` stays in OSM mode.
 *  - `osm_way`: rows + `bbox_min/max_lat/lon_e7` columns are preserved, so
 *    the reindexer can rebuild `osm_way_cell` purely from bbox columns
 *    without needing to redecode polylines or re-download a single tile.
 *
 * Behaviour during the reindex window (between this migration running and
 * the background reindexer finishing):
 *  - `OsmSpeedLimitSource.findRoadLimitMps` returns null because every
 *    cell-key lookup is empty.
 *  - `DefaultSpeedLimitSource.limitMpsAt` therefore falls back to the
 *    fixed baseline — no throws, no UI errors, just a temporary loss of
 *    OSM-specific precision. Pinned by the stats-data integration test
 *    `OsmDispatcherIntegrationTest`.
 *
 * `DELETE FROM` instead of `DROP TABLE`/recreate is intentional: the
 * table schema and indices remain valid, and Room's schema-hash check
 * after migration only verifies structure, not row count. Idempotent
 * by construction — re-running the migration on an empty `osm_way_cell`
 * is a no-op.
 */
val MIGRATION_31_32: Migration = object : Migration(31, 32) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("DELETE FROM osm_way_cell")
	}
}

/**
 * v32 → v33: Add `cell_index_built` progress marker to `osm_import`.
 *
 * Fixes the "sticky partial reindex" bug where a crash mid-reindex left some
 * `osm_way_cell` rows written, making the old `count == 0` heuristic believe
 * the index was complete. With this column the reindexer checks
 * `cell_index_built = 0` instead, so it always re-triggers after a crash.
 *
 * All existing rows get the default value 0, which correctly forces a full
 * reindex on next launch — desirable because v32 just cleared the table.
 */
val MIGRATION_32_33: Migration = object : Migration(32, 33) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"ALTER TABLE osm_import ADD COLUMN cell_index_built INTEGER NOT NULL DEFAULT 0",
		)
	}
}

/**
 * Adds the global chronological index used by cross-session WAL recovery.
 */
val MIGRATION_33_34: Migration = object : Migration(33, 34) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS idx_pending_signal_recovery_order
			ON pending_signal(created_at, id)
			""".trimIndent(),
		)
	}
}

/**
 * Preserves every 2024.1 field that had no representation in the sessionless schema.
 */
val MIGRATION_34_35: Migration = object : Migration(34, 35) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			addColumnIfMissing(this, "session_segment", "legacy_user_initiated", "INTEGER")
			addColumnIfMissing(this, "session_segment", "legacy_distance_on_foot_m", "REAL")
			addColumnIfMissing(this, "session_segment", "legacy_distance_in_vehicle_m", "REAL")
			addColumnIfMissing(this, "session_segment", "legacy_activity_id", "INTEGER")
			addColumnIfMissing(this, "wifi_observation", "legacy_first_seen_ms", "INTEGER")
			addColumnIfMissing(this, "wifi_observation", "legacy_alt_m", "REAL")
			addColumnIfMissing(this, "cell_sample", "legacy_alt_m", "REAL")
			addColumnIfMissing(this, "cell_sample", "legacy_mcc", "TEXT")
			addColumnIfMissing(this, "cell_sample", "legacy_mnc", "TEXT")
			addColumnIfMissing(this, "cell_sample", "legacy_source_id", "INTEGER")
			addColumnIfMissing(this, "cell_sample", "legacy_lat", "REAL")
			addColumnIfMissing(this, "cell_sample", "legacy_lon", "REAL")
			addColumnIfMissing(this, "location_sample", "legacy_lat", "REAL")
			addColumnIfMissing(this, "location_sample", "legacy_lon", "REAL")
			addColumnIfMissing(this, "location_sample", "legacy_alt_m", "REAL")
			addColumnIfMissing(this, "wifi_observation", "legacy_lat", "REAL")
			addColumnIfMissing(this, "wifi_observation", "legacy_lon", "REAL")

			execSQL("""
				CREATE TABLE IF NOT EXISTS legacy_rejected_tracker_session (
					id INTEGER NOT NULL,
					start INTEGER NOT NULL,
					`end` INTEGER NOT NULL,
					user_initiated INTEGER NOT NULL,
					collections INTEGER NOT NULL,
					distance REAL NOT NULL,
					distance_on_foot REAL NOT NULL,
					distance_in_vehicle REAL NOT NULL,
					steps INTEGER NOT NULL,
					session_activity_id INTEGER,
					PRIMARY KEY(id)
				)
			""".trimIndent())
			execSQL("""
				CREATE TABLE IF NOT EXISTS legacy_location_wifi_count (
					id INTEGER NOT NULL,
					time INTEGER NOT NULL,
					count INTEGER NOT NULL,
					lat REAL NOT NULL,
					lon REAL NOT NULL,
					alt REAL,
					PRIMARY KEY(id)
				)
			""".trimIndent())
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_legacy_location_wifi_count_time " +
					"ON legacy_location_wifi_count(time)"
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_legacy_location_wifi_count_coords " +
					"ON legacy_location_wifi_count(lat, lon)"
			)
		}
	}
}

/**
 * v35 -> v36: lossless location observations.
 *
 * Existing `location_sample` semantics remain curated/accepted. New acquisition columns make those
 * accepted rows auditable, while `location_observation` stores pre-processing provider evidence.
 */
val MIGRATION_35_36: Migration = object : Migration(35, 36) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("ALTER TABLE location_sample ADD COLUMN received_elapsed_realtime_nanos INTEGER NOT NULL DEFAULT 0")
			execSQL("ALTER TABLE location_sample ADD COLUMN delivery_age_ms INTEGER")
			execSQL("ALTER TABLE location_sample ADD COLUMN acquisition_mode TEXT NOT NULL DEFAULT 'UNKNOWN'")
			execSQL("ALTER TABLE location_sample ADD COLUMN request_priority TEXT NOT NULL DEFAULT 'UNKNOWN'")
			execSQL("ALTER TABLE location_sample ADD COLUMN permission_precision TEXT NOT NULL DEFAULT 'UNKNOWN'")
			execSQL("ALTER TABLE location_sample ADD COLUMN batch_index INTEGER NOT NULL DEFAULT 0")
			execSQL("ALTER TABLE location_sample ADD COLUMN batch_size INTEGER NOT NULL DEFAULT 1")
			execSQL("ALTER TABLE location_sample ADD COLUMN is_mock INTEGER NOT NULL DEFAULT 0")
			execSQL("ALTER TABLE location_sample ADD COLUMN estimator_version INTEGER NOT NULL DEFAULT 1")
			execSQL("ALTER TABLE location_sample ADD COLUMN calibration_version INTEGER NOT NULL DEFAULT 0")

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS location_observation (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					fix_time_ms INTEGER NOT NULL,
					fix_elapsed_realtime_nanos INTEGER NOT NULL,
					received_at_ms INTEGER NOT NULL,
					received_elapsed_realtime_nanos INTEGER NOT NULL,
					delivery_age_ms INTEGER,
					lat_e7 INTEGER,
					lon_e7 INTEGER,
					raw_alt_m REAL,
					h_acc_m REAL,
					v_acc_m REAL,
					speed_mps REAL,
					speed_accuracy_mps REAL,
					provider TEXT NOT NULL,
					acquisition_mode TEXT NOT NULL,
					request_priority TEXT NOT NULL,
					permission_precision TEXT NOT NULL,
					batch_index INTEGER NOT NULL,
					batch_size INTEGER NOT NULL,
					is_mock INTEGER NOT NULL,
					ingress_disposition TEXT NOT NULL,
					estimator_version INTEGER NOT NULL,
					calibration_version INTEGER NOT NULL,
					created_at INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			// Preserve the accepted historical stream as replayable evidence. These rows did not pass
			// through the provider-observation path, so their disposition remains explicitly migrated.
			execSQL(
				"""
				INSERT INTO location_observation (
					fix_time_ms,
					fix_elapsed_realtime_nanos,
					received_at_ms,
					received_elapsed_realtime_nanos,
					delivery_age_ms,
					lat_e7,
					lon_e7,
					raw_alt_m,
					h_acc_m,
					v_acc_m,
					speed_mps,
					speed_accuracy_mps,
					provider,
					acquisition_mode,
					request_priority,
					permission_precision,
					batch_index,
					batch_size,
					is_mock,
					ingress_disposition,
					estimator_version,
					calibration_version,
					created_at
				)
				SELECT
					time_ms,
					elapsed_realtime_nanos,
					created_at,
					received_elapsed_realtime_nanos,
					delivery_age_ms,
					lat_e7,
					lon_e7,
					raw_gps_alt_m,
					h_acc_m,
					v_acc_m,
					speed_mps,
					speed_accuracy_mps,
					provider,
					acquisition_mode,
					request_priority,
					permission_precision,
					batch_index,
					batch_size,
					is_mock,
					'MIGRATED_ACCEPTED',
					estimator_version,
					calibration_version,
					created_at
				FROM location_sample
				""".trimIndent(),
			)
			execSQL("CREATE INDEX IF NOT EXISTS idx_location_observation_fix_time ON location_observation(fix_time_ms, id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_location_observation_delivery ON location_observation(received_elapsed_realtime_nanos, batch_index)")

		}
	}
}

/**
 * v36 -> v37: make pending-signal recovery idempotent and diagnosable.
 *
 * The producer-assigned `signal_id` becomes the durable identity used by every
 * final destination. Fan-out destinations retain an item index. Existing rows
 * receive table-prefixed legacy identities so unique constraints can be added
 * without dropping data. New rows use nullable destination identities only to
 * preserve compatibility with imported/direct legacy writers; the tracking
 * write path always supplies a non-null source identity.
 *
 * Pending rows gain an envelope version/checksum plus lease metadata. Recovery
 * ordering moves from wall-clock `created_at` to generated row id, which is
 * the durable admission order. `quarantined_signal` is an append-only error
 * ledger populated atomically with removal of an unrecoverable pending row.
 */
val MIGRATION_36_37: Migration = object : Migration(36, 37) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// v36 briefly carried the observed-presence tables. They are no longer
			// part of AppDatabase, so remove children before their referenced
			// parents to keep upgrades valid with foreign-key enforcement enabled.
			execSQL("DROP TABLE IF EXISTS presence_cell_contribution")
			execSQL("DROP TABLE IF EXISTS presence_compaction_checkpoint")
			execSQL("DROP TABLE IF EXISTS presence_compaction_block")
			execSQL("DROP TABLE IF EXISTS presence_interval")
			execSQL("DROP TABLE IF EXISTS analysis_cell")

			// Final destinations: one logical signal per single-row destination,
			// and one (signal, item index) pair per fan-out destination.
			addColumnIfMissing(this, "location_sample", "source_signal_id", "TEXT")
			addColumnIfMissing(this, "location_observation", "source_signal_id", "TEXT")
			addColumnIfMissing(this, "pressure_sample", "source_signal_id", "TEXT")
			addColumnIfMissing(this, "step_interval", "source_signal_id", "TEXT")
			addColumnIfMissing(this, "activity_snapshot", "source_signal_id", "TEXT")
			addColumnIfMissing(this, "cell_sample", "source_signal_id", "TEXT")
			addColumnIfMissing(this, "cell_sample", "source_item_index", "INTEGER")
			addColumnIfMissing(this, "wifi_observation", "source_signal_id", "TEXT")
			addColumnIfMissing(this, "wifi_observation", "source_item_index", "INTEGER")

			// Stable, unique values make historical destination rows compatible
			// with the new indexes without pretending they came from current WAL
			// records. The prefixes prevent cross-table identity collisions in an
			// operator export while uniqueness remains table-local.
			execSQL("UPDATE location_sample SET source_signal_id = 'legacy:location_sample:' || id WHERE source_signal_id IS NULL")
			execSQL("UPDATE location_observation SET source_signal_id = 'legacy:location_observation:' || id WHERE source_signal_id IS NULL")
			execSQL("UPDATE pressure_sample SET source_signal_id = 'legacy:pressure_sample:' || id WHERE source_signal_id IS NULL")
			execSQL("UPDATE step_interval SET source_signal_id = 'legacy:step_interval:' || id WHERE source_signal_id IS NULL")
			execSQL("UPDATE activity_snapshot SET source_signal_id = 'legacy:activity_snapshot:' || id WHERE source_signal_id IS NULL")
			execSQL("UPDATE cell_sample SET source_signal_id = 'legacy:cell_sample:' || id, source_item_index = 0 WHERE source_signal_id IS NULL")
			execSQL("UPDATE wifi_observation SET source_signal_id = 'legacy:wifi_observation:' || id, source_item_index = 0 WHERE source_signal_id IS NULL")

			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_location_sample_source_signal ON location_sample(source_signal_id)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_location_observation_source_signal ON location_observation(source_signal_id)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_pressure_sample_source_signal ON pressure_sample(source_signal_id)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_step_interval_source_signal ON step_interval(source_signal_id)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_snapshot_source_signal ON activity_snapshot(source_signal_id)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_cell_sample_source_item ON cell_sample(source_signal_id, source_item_index)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_wifi_observation_source_item ON wifi_observation(source_signal_id, source_item_index)")

			// Durable envelope and recovery ownership metadata.
			addColumnIfMissing(this, "pending_signal", "signal_id", "TEXT NOT NULL DEFAULT ''")
			addColumnIfMissing(this, "pending_signal", "envelope_version", "INTEGER NOT NULL DEFAULT 0")
			addColumnIfMissing(this, "pending_signal", "payload_checksum", "TEXT")
			addColumnIfMissing(this, "pending_signal", "claim_token", "TEXT")
			addColumnIfMissing(this, "pending_signal", "claim_expires_at", "INTEGER")
			addColumnIfMissing(this, "pending_signal", "delivery_attempt_count", "INTEGER NOT NULL DEFAULT 0")
			execSQL("UPDATE pending_signal SET signal_id = 'legacy:pending_signal:' || id WHERE signal_id = ''")

			// Session-local recovery benefits from this index. Global recovery uses
			// the primary key directly, so the old wall-clock ordering index is
			// deliberately removed rather than retained as misleading dead weight.
			execSQL("DROP INDEX IF EXISTS idx_pending_signal_session_time")
			execSQL("DROP INDEX IF EXISTS idx_pending_signal_recovery_order")
			execSQL("CREATE INDEX IF NOT EXISTS idx_pending_signal_session_time ON pending_signal(session_id, id)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_signal_signal_id ON pending_signal(signal_id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_pending_signal_claimable ON pending_signal(claim_token, claim_expires_at, id)")

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS quarantined_signal (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					source_pending_id INTEGER NOT NULL,
					signal_id TEXT NOT NULL,
					session_id INTEGER NOT NULL,
					envelope_version INTEGER NOT NULL,
					payload_checksum TEXT,
					signal_json TEXT NOT NULL,
					created_at INTEGER NOT NULL,
					delivery_attempt_count INTEGER NOT NULL,
					failure_reason TEXT NOT NULL,
					failure_detail TEXT,
					quarantined_at INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_quarantined_signal_source_pending ON quarantined_signal(source_pending_id)")
			execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_quarantined_signal_signal_id ON quarantined_signal(signal_id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_quarantined_signal_time ON quarantined_signal(quarantined_at, id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_quarantined_signal_reason ON quarantined_signal(failure_reason)")
		}
	}
}

/**
 * v37 -> v38: make raw location evidence reconstructable without value matching.
 *
 * Provider callback/fix identities and conservative clock domains are retained separately from
 * WAL replay identities. A terminal accepted/rejected decision is append-only and keyed by the
 * provider fix. Tracker-state events provide the future estimator with bounded, domain-aware
 * activity evidence, while the singleton source revision lets read-only consumers reject mixed
 * snapshots after a concurrent source mutation.
 */
val MIGRATION_37_38: Migration = object : Migration(37, 38) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			addColumnIfMissing(this, "location_observation", "source_event_id", "TEXT")
			addColumnIfMissing(this, "location_observation", "callback_id", "TEXT")
			addColumnIfMissing(this, "location_observation", "clock_domain_id", "TEXT")
			addColumnIfMissing(
				this,
				"location_observation",
				"source_revision",
				"INTEGER NOT NULL DEFAULT 0",
			)
			// Historical data has no provider identity. Give it stable legacy values rather than
			// fabricating a relationship to a future accepted-decision row.
			execSQL(
				"UPDATE location_observation SET source_event_id = " +
					"'legacy:location_observation_event:' || id " +
					"WHERE source_event_id IS NULL",
			)
			execSQL(
				"UPDATE location_observation SET callback_id = " +
					"'legacy:location_observation_callback:' || id " +
					"WHERE callback_id IS NULL",
			)
			execSQL(
				"UPDATE location_observation SET clock_domain_id = 'legacy:unknown' " +
					"WHERE clock_domain_id IS NULL",
			)
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_location_observation_source_event " +
					"ON location_observation(source_event_id)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_location_observation_clock " +
					"ON location_observation(clock_domain_id, fix_elapsed_realtime_nanos)",
			)

			addColumnIfMissing(this, "location_sample", "source_event_id", "TEXT")
			addColumnIfMissing(this, "location_sample", "clock_domain_id", "TEXT")
			addColumnIfMissing(
				this,
				"location_sample",
				"source_revision",
				"INTEGER NOT NULL DEFAULT 0",
			)
			addColumnIfMissing(
				this,
				"pending_signal",
				"captured_epoch",
				"INTEGER NOT NULL DEFAULT 0",
			)
			addColumnIfMissing(
				this,
				"pending_signal",
				"acquired_at_ms",
				"INTEGER NOT NULL DEFAULT 0",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_location_sample_source_event " +
					"ON location_sample(source_event_id)",
			)

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS location_observation_decision (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					observation_source_event_id TEXT NOT NULL,
					decision TEXT NOT NULL,
					reason TEXT,
					decision_version INTEGER NOT NULL,
					accepted_sample_source_signal_id TEXT,
					source_signal_id TEXT NOT NULL,
					clock_domain_id TEXT,
					decided_at_ms INTEGER NOT NULL,
					source_revision INTEGER NOT NULL DEFAULT 0
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_location_observation_decision_event " +
					"ON location_observation_decision(observation_source_event_id)",
			)
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_location_observation_decision_signal " +
					"ON location_observation_decision(source_signal_id)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_location_observation_decision_time " +
					"ON location_observation_decision(decision, decided_at_ms)",
			)

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS tracker_state_event (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					clock_domain_id TEXT NOT NULL,
					elapsed_realtime_nanos INTEGER NOT NULL,
					wall_time_ms INTEGER NOT NULL,
					state TEXT NOT NULL,
					policy TEXT NOT NULL,
					reason TEXT,
					active_lease_expires_elapsed_nanos INTEGER,
					created_at_ms INTEGER NOT NULL,
					source_revision INTEGER NOT NULL DEFAULT 0
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_tracker_state_event_clock " +
					"ON tracker_state_event(clock_domain_id, elapsed_realtime_nanos, id)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_tracker_state_event_wall " +
					"ON tracker_state_event(wall_time_ms, id)",
			)

			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS source_evidence_state (
					id INTEGER NOT NULL,
					revision INTEGER NOT NULL,
					collected_data_epoch INTEGER NOT NULL,
					retained_from_ms INTEGER,
					updated_at_ms INTEGER NOT NULL,
					PRIMARY KEY(id)
				)
				""".trimIndent(),
			)
			execSQL(
				"INSERT OR IGNORE INTO source_evidence_state(" +
					"id, revision, collected_data_epoch, retained_from_ms, updated_at_ms" +
					") VALUES (1, 0, 0, NULL, 0)",
			)
		}

	}
}

/**
 * v38 -> v39: add an explicit OSM import publication state.
 *
 * Historical rows default to READY because they were already visible and usable
 * under the old count-based gate. BUILDING is only assigned by the new worker,
 * so startup cleanup can safely identify imports abandoned by process death.
 */
val MIGRATION_38_39: Migration = object : Migration(38, 39) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"ALTER TABLE osm_import ADD COLUMN status TEXT NOT NULL DEFAULT 'READY'",
		)
	}
}

/** Adds durable content-addressed import job and per-entry receipts. */
val MIGRATION_39_40: Migration = object : Migration(39, 40) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			// Schema 27 is unreleased. Preserve all earlier bare altitude rows as explicitly unknown
			// rather than claiming that an old `alt_m` value used an Android-model MSL datum.
			addColumnIfMissing(
				this,
				"location_sample",
				"alt_datum",
				"TEXT NOT NULL DEFAULT 'unknown_legacy'",
			)
			addColumnIfMissing(
				this,
				"location_sample",
				"alt_source",
				"TEXT NOT NULL DEFAULT 'unknown_legacy'",
			)
			addColumnIfMissing(
				this,
				"location_sample",
				"alt_conversion_status",
				"TEXT NOT NULL DEFAULT 'unknown_legacy'",
			)
			addColumnIfMissing(
				this,
				"location_sample",
				"raw_gps_alt_datum",
				"TEXT NOT NULL DEFAULT 'unknown_legacy'",
			)
			addColumnIfMissing(
				this,
				"location_sample",
				"alt_model_version",
				"INTEGER NOT NULL DEFAULT 0",
			)
			listOf(
				"raw_platform_speed_mps" to "REAL",
				"raw_platform_speed_accuracy_mps" to "REAL",
				"bearing_deg" to "REAL",
				"bearing_accuracy_deg" to "REAL",
				"boot_clock_domain_id" to "TEXT",
			).forEach { (column, type) ->
				addColumnIfMissing(this, "location_sample", column, type)
			}
			if (tableExists(this, "location_observation")) {
				listOf(
					"bearing_deg" to "REAL",
					"bearing_accuracy_deg" to "REAL",
					"boot_clock_domain_id" to "TEXT",
				).forEach { (column, type) ->
					addColumnIfMissing(this, "location_observation", column, type)
				}
			}
			val observationStampColumns = listOf(
				"source_time_ms" to "INTEGER",
				"source_elapsed_realtime_nanos" to "INTEGER",
				"source_first_elapsed_realtime_nanos" to "INTEGER",
				"received_time_ms" to "INTEGER",
				"received_elapsed_realtime_nanos" to "INTEGER",
				"source_sequence" to "INTEGER",
				"source_first_sequence" to "INTEGER",
				"clock_domain_id" to "TEXT",
				"boot_clock_domain_id" to "TEXT",
				"source_age_ms" to "INTEGER",
				"time_uncertainty_ms" to "INTEGER",
				"capability_flags" to "TEXT",
				"permission_precision" to "TEXT",
			)
			listOf(
				"activity_snapshot",
				"step_interval",
				"cell_sample",
				"wifi_observation",
				"pressure_sample",
			).forEach { table ->
				if (tableExists(this, table)) {
					observationStampColumns.forEach { (column, type) ->
						addColumnIfMissing(this, table, column, type)
					}
				}
			}
			if (tableExists(this, "pressure_sample")) {
				listOf(
					"sample_count" to "INTEGER NOT NULL DEFAULT 1",
					"min_pressure_hpa" to "REAL",
					"max_pressure_hpa" to "REAL",
					"pressure_stddev_hpa" to "REAL",
					"window_start_elapsed_realtime_nanos" to "INTEGER",
					"window_end_elapsed_realtime_nanos" to "INTEGER",
				).forEach { (column, type) ->
					addColumnIfMissing(this, "pressure_sample", column, type)
				}
			}
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS trajectory_reconstruction_run (
					run_id TEXT NOT NULL,
					source_start_ms INTEGER NOT NULL,
					source_end_ms INTEGER NOT NULL,
					source_clock_domain_id TEXT,
					source_boot_clock_domain_id TEXT,
					source_start_elapsed_realtime_nanos INTEGER,
					source_end_elapsed_realtime_nanos INTEGER,
					source_revision INTEGER NOT NULL,
					algorithm_version TEXT NOT NULL,
					configuration_version TEXT NOT NULL,
					permission_branch TEXT NOT NULL,
					status TEXT NOT NULL,
					created_at_ms INTEGER NOT NULL,
					completed_at_ms INTEGER,
					supersedes_run_id TEXT,
					failure_reason TEXT,
					PRIMARY KEY(run_id)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_reconstruction_run_range " +
					"ON trajectory_reconstruction_run(source_start_ms, source_end_ms)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_reconstruction_run_clock_range " +
					"ON trajectory_reconstruction_run(" +
					"source_clock_domain_id, source_start_elapsed_realtime_nanos, " +
					"source_end_elapsed_realtime_nanos)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_reconstruction_run_status " +
					"ON trajectory_reconstruction_run(status, created_at_ms)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS trajectory_state (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					run_id TEXT NOT NULL,
					state_index INTEGER NOT NULL,
					estimate_kind TEXT NOT NULL,
					source_event_id TEXT,
					time_ms INTEGER NOT NULL,
					elapsed_realtime_nanos INTEGER,
					clock_domain_id TEXT,
					boot_clock_domain_id TEXT,
					lat_e7 INTEGER NOT NULL,
					lon_e7 INTEGER NOT NULL,
					velocity_east_mps REAL NOT NULL,
					velocity_north_mps REAL NOT NULL,
					covariance_ee_m2 REAL NOT NULL,
					covariance_en_m2 REAL NOT NULL,
					covariance_nn_m2 REAL NOT NULL,
					stationary_probability REAL NOT NULL,
					observation_weight REAL NOT NULL,
					observation_health TEXT NOT NULL,
					FOREIGN KEY(run_id) REFERENCES trajectory_reconstruction_run(run_id)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_trajectory_state_order " +
					"ON trajectory_state(run_id, estimate_kind, state_index)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_trajectory_state_time " +
					"ON trajectory_state(run_id, time_ms)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_trajectory_state_source_event " +
					"ON trajectory_state(source_event_id)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS trajectory_source_link (
					run_id TEXT NOT NULL,
					state_index INTEGER NOT NULL,
					observation_id INTEGER NOT NULL,
					source_event_id TEXT,
					source_signal_id TEXT,
					step_interval_id INTEGER,
					activity_snapshot_id INTEGER,
					weight REAL NOT NULL,
					health TEXT NOT NULL,
					reason_codes TEXT,
					PRIMARY KEY(run_id, state_index, observation_id),
					FOREIGN KEY(run_id) REFERENCES trajectory_reconstruction_run(run_id)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_trajectory_source_state " +
					"ON trajectory_source_link(run_id, state_index)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_trajectory_source_observation " +
					"ON trajectory_source_link(observation_id)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_trajectory_source_step " +
					"ON trajectory_source_link(step_interval_id)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_trajectory_source_activity " +
					"ON trajectory_source_link(activity_snapshot_id)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS route_hypothesis (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					run_id TEXT NOT NULL,
					rank INTEGER NOT NULL,
					probability REAL NOT NULL,
					map_version TEXT,
					travel_mode TEXT NOT NULL,
					encoded_geometry BLOB,
					unmatched_spans TEXT,
					FOREIGN KEY(run_id) REFERENCES trajectory_reconstruction_run(run_id)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_route_hypothesis_rank " +
					"ON route_hypothesis(run_id, rank)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS visit_interval (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					run_id TEXT NOT NULL,
					start_time_ms INTEGER NOT NULL,
					end_time_ms INTEGER NOT NULL,
					start_elapsed_realtime_nanos INTEGER,
					end_elapsed_realtime_nanos INTEGER,
					clock_domain_id TEXT,
					boot_clock_domain_id TEXT,
					arrival_uncertainty_ms INTEGER NOT NULL,
					departure_uncertainty_ms INTEGER NOT NULL,
					centroid_lat_e7 INTEGER NOT NULL,
					centroid_lon_e7 INTEGER NOT NULL,
					covariance_ee_m2 REAL NOT NULL,
					covariance_en_m2 REAL NOT NULL,
					covariance_nn_m2 REAL NOT NULL,
					probability REAL NOT NULL,
					FOREIGN KEY(run_id) REFERENCES trajectory_reconstruction_run(run_id)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_visit_interval_run_time " +
					"ON visit_interval(run_id, start_time_ms)",
			)
			// OSM schemas are unreleased. Mark pre-directed development rows as
			// legacy so startup can delete/re-import them rather than reinterpret
			// ordinary longitude extrema as circular directed bounds.
			if (tableExists(this, "osm_import")) {
				addColumnIfMissing(
					this,
					"osm_import",
					"way_bbox_encoding_version",
					"INTEGER NOT NULL DEFAULT 0",
				)
				addColumnIfMissing(
					this,
					"osm_import",
					"published_revision",
					"INTEGER NOT NULL DEFAULT 0",
				)
			}
			// All OSM schemas are unreleased. Reset only the graph tables so a
			// development database cannot carry the former global-primary-key
			// layout into schema 27. Retained import headers are deliberately
			// marked legacy by the bbox default above and startup asks for a
			// re-import rather than guessing their old graph semantics.
			if (tableExists(this, "osm_way_cell")) {
				execSQL("DROP TABLE osm_way_cell")
			}
			if (tableExists(this, "osm_way")) {
				execSQL("DROP TABLE osm_way")
			}
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS osm_way (
					way_instance_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					osm_way_id INTEGER NOT NULL,
					import_id INTEGER NOT NULL,
					osm_version INTEGER,
					name TEXT,
					road_class TEXT NOT NULL,
					maxspeed_kmh INTEGER NOT NULL,
					maxspeed_explicit INTEGER NOT NULL,
					is_oneway INTEGER NOT NULL,
					geom_polyline_e7 BLOB NOT NULL,
					bbox_min_lat_e7 INTEGER NOT NULL,
					bbox_max_lat_e7 INTEGER NOT NULL,
					bbox_min_lon_e7 INTEGER NOT NULL,
					bbox_max_lon_e7 INTEGER NOT NULL,
					FOREIGN KEY(import_id) REFERENCES osm_import(id) ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_import ON osm_way(import_id)")
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_osm_way_import_osm_id " +
					"ON osm_way(import_id, osm_way_id)",
			)
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_osm_id ON osm_way(osm_way_id)")
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS osm_way_cell (
					cell_key INTEGER NOT NULL,
					way_id INTEGER NOT NULL,
					PRIMARY KEY(cell_key, way_id),
					FOREIGN KEY(way_id) REFERENCES osm_way(way_instance_id)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_cell_cell ON osm_way_cell(cell_key)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_cell_way ON osm_way_cell(way_id)")
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS import_job_receipt (
					job_id TEXT NOT NULL,
					source_name TEXT NOT NULL,
					source_size_bytes INTEGER NOT NULL,
					status TEXT NOT NULL,
					started_at INTEGER NOT NULL,
					completed_at INTEGER,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY(job_id)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS index_import_job_receipt_status " +
					"ON import_job_receipt(status)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS import_entry_receipt (
					job_id TEXT NOT NULL,
					entry_key TEXT NOT NULL,
					entry_name TEXT NOT NULL,
					status TEXT NOT NULL,
					success_count INTEGER NOT NULL,
					skipped_count INTEGER NOT NULL,
					failed_count INTEGER NOT NULL,
					error_message TEXT,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY(job_id, entry_key),
					FOREIGN KEY(job_id) REFERENCES import_job_receipt(job_id)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS index_import_entry_receipt_job_id_status " +
					"ON import_entry_receipt(job_id, status)",
			)
		}
	}
}
