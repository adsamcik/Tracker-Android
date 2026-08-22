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
 * Release-boundary workflow:
 * - Versions through 26 are frozen public migrations used only on a disposable legacy copy.
 * - V27 starts in a new stable active database file and imports the normalized v26 data.
 * - After v27 ships, add ordinary in-place migrations from v27 onward.
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
			// Fresh v27 databases create this index directly; this remains for upgrades to v26.
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


/** The released v26 database is imported into a fresh current-schema active file. */

/**
 * Version 27 -> 28: add the authoritative, immutable source-policy ledger.
 *
 * Existing v27 installs are migrated additively. The singleton authority starts fail-closed and
 * is activated only after the application has transactionally imported the legacy source settings.
 */
internal const val V28_MIGRATION_INTERRUPTION_REASON = "V28_MIGRATION_INTERRUPTED"

val MIGRATION_27_28: Migration = object : Migration(27, 28) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL(
				"ALTER TABLE acquisition_plan_revision " +
					"ADD COLUMN source_policy_revision INTEGER",
			)
			execSQL("ALTER TABLE source_event_wal ADD COLUMN source_policy_revision INTEGER")
			execSQL("ALTER TABLE source_event_wal ADD COLUMN capture_consent_epoch INTEGER")
			execSQL("ALTER TABLE source_event_wal ADD COLUMN session_manifest_revision INTEGER")
			execSQL("ALTER TABLE source_event_wal ADD COLUMN lifecycle_lease_generation INTEGER")
			execSQL("ALTER TABLE source_event_wal ADD COLUMN physical_configuration_fingerprint TEXT")
			execSQL("ALTER TABLE source_event_wal ADD COLUMN authorization_revision INTEGER")
			execSQL(
				"ALTER TABLE source_event_wal ADD COLUMN " +
					"authorization_purpose_eligibility_mask INTEGER NOT NULL DEFAULT 0",
			)
			execSQL("ALTER TABLE source_event_wal ADD COLUMN authorization_fingerprint TEXT")
			execSQL(
				"ALTER TABLE source_event_wal " +
					"ADD COLUMN integrity_identity TEXT NOT NULL DEFAULT 'LEGACY_PENDING_CHECKSUM'",
			)
			execSQL(
				"ALTER TABLE logical_tracking_session " +
					"ADD COLUMN session_mode TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
			)
			execSQL("ALTER TABLE logical_tracking_session ADD COLUMN current_manifest_revision INTEGER")
			execSQL("ALTER TABLE logical_tracking_session ADD COLUMN current_intent_revision INTEGER")
			execSQL(
				"ALTER TABLE logical_tracking_session " +
					"ADD COLUMN lifecycle_lease_generation INTEGER NOT NULL DEFAULT 0",
			)
			execSQL("ALTER TABLE logical_tracking_session ADD COLUMN lifecycle_boot_id TEXT")
			execSQL("ALTER TABLE logical_tracking_session ADD COLUMN automation_epoch INTEGER")
			execSQL(
				"ALTER TABLE source_service_run " +
					"ADD COLUMN boot_id TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
			)
			execSQL("ALTER TABLE source_service_run ADD COLUMN lease_generation INTEGER NOT NULL DEFAULT 0")
			execSQL(
				"ALTER TABLE source_service_run " +
					"ADD COLUMN start_origin TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
			)
			execSQL(
				"ALTER TABLE source_service_run " +
					"ADD COLUMN desired_foreground_capability_flags INTEGER NOT NULL DEFAULT 0",
			)
			execSQL("ALTER TABLE source_service_run ADD COLUMN applied_foreground_capability_flags INTEGER")
			execSQL(
				"ALTER TABLE source_service_run " +
					"ADD COLUMN runtime_acknowledgement TEXT NOT NULL DEFAULT 'PENDING'",
			)
			execSQL("ALTER TABLE source_service_run ADD COLUMN runtime_failure_code TEXT")
			execSQL("ALTER TABLE source_service_run ADD COLUMN run_revision INTEGER NOT NULL DEFAULT 0")
			execSQL(
				"UPDATE source_service_run SET " +
					"desired_foreground_capability_flags = foreground_capability_flags, " +
					"applied_foreground_capability_flags = CASE " +
					"WHEN state = 'RUNNING' THEN foreground_capability_flags ELSE NULL END, " +
					"start_origin = COALESCE((SELECT session.start_origin " +
					"FROM logical_tracking_session AS session " +
					"WHERE session.logical_tracking_id = source_service_run.logical_tracking_id), " +
					"'LEGACY_UNKNOWN'), " +
					"runtime_acknowledgement = CASE " +
					"WHEN state = 'RUNNING' THEN 'LEGACY_ACTIVE' " +
					"WHEN state IN ('CLOSED', 'FAILED') THEN 'LEGACY_TERMINAL' ELSE 'PENDING' END",
			)
			// A v27 runtime cannot survive the binary replacement that performs this migration. Its
			// manifest, consent, boot lease, and provider acknowledgement are unprovable in v28, so
			// retaining a nonterminal row would expose a ghost session before recovery can run. Use
			// only existing factual boundaries; never extend legacy activity to migration wall time.
			execSQL(
				"UPDATE logical_tracking_session SET " +
					"state = 'FINALIZED', lifecycle_revision = lifecycle_revision + 1, " +
					"completed_at_ms = COALESCE(completed_at_ms, cutoff_at_ms, started_at_ms), " +
					"failure_code = '$V28_MIGRATION_INTERRUPTION_REASON' " +
					"WHERE state NOT IN ('FINALIZED', 'CLOSED', 'FAILED')",
			)
			execSQL(
				"UPDATE source_service_run SET " +
					"state = 'FINALIZED', completed_at_ms = COALESCE(completed_at_ms, started_at_ms), " +
					"completion_reason = '$V28_MIGRATION_INTERRUPTION_REASON', " +
					"runtime_acknowledgement = 'TERMINAL_FAILURE', " +
					"runtime_failure_code = '$V28_MIGRATION_INTERRUPTION_REASON', " +
					"run_revision = run_revision + 1 " +
					"WHERE state NOT IN ('FINALIZED', 'CLOSED', 'FAILED')",
			)
			// tracker_run has no interruption/completeness field. Closing at its own known start is
			// conservative: downstream queries cannot fabricate an open-ended interval, while typed
			// observations and materialized session segments retain the actual released history.
			execSQL("UPDATE tracker_run SET end_time_ms = start_time_ms WHERE end_time_ms IS NULL")
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS source_policy_authority (
					id INTEGER NOT NULL,
					bootstrap_state TEXT NOT NULL,
					current_policy_revision INTEGER NOT NULL,
					legacy_settings_fingerprint TEXT,
					updated_at_ms INTEGER NOT NULL,
					PRIMARY KEY(id)
				)
				""".trimIndent(),
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS source_policy (
					policy_revision INTEGER NOT NULL,
					source_kind INTEGER NOT NULL,
					enabled INTEGER NOT NULL,
					qos_code INTEGER NOT NULL,
					location_min_time_seconds INTEGER,
					location_min_distance_meters INTEGER,
					location_required_accuracy_meters INTEGER,
					capture_persistence_eligible INTEGER NOT NULL,
					control_persistence_eligible INTEGER NOT NULL,
					ambient_persistence_eligible INTEGER NOT NULL,
					capture_consent_epoch INTEGER,
					control_consent_epoch INTEGER,
					ambient_consent_epoch INTEGER,
					effective_boot_id TEXT NOT NULL,
					effective_elapsed_realtime_nanos INTEGER NOT NULL,
					effective_wall_time_ms INTEGER NOT NULL,
					change_reason TEXT NOT NULL,
					PRIMARY KEY(policy_revision, source_kind)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_source_policy_source_revision " +
					"ON source_policy(source_kind, policy_revision)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS source_consent_epoch (
					source_kind INTEGER NOT NULL,
					purpose TEXT NOT NULL,
					epoch INTEGER NOT NULL,
					eligible INTEGER NOT NULL,
					persistence_eligible INTEGER NOT NULL,
					policy_revision INTEGER NOT NULL,
					effective_boot_id TEXT NOT NULL,
					effective_elapsed_realtime_nanos INTEGER NOT NULL,
					effective_wall_time_ms INTEGER NOT NULL,
					change_reason TEXT NOT NULL,
					PRIMARY KEY(source_kind, purpose, epoch)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_source_consent_epoch_policy " +
					"ON source_consent_epoch(source_kind, purpose, policy_revision)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS source_demand (
					demand_id TEXT NOT NULL,
					consumer_id TEXT NOT NULL,
					source_kind INTEGER NOT NULL,
					purpose TEXT NOT NULL,
					logical_tracking_id TEXT,
					service_run_id TEXT,
					manifest_revision INTEGER,
					lifecycle_lease_generation INTEGER,
					source_policy_revision INTEGER NOT NULL,
					consent_epoch INTEGER NOT NULL,
					persistence_eligible INTEGER NOT NULL,
					qos_code INTEGER NOT NULL,
					maximum_age_ms INTEGER NOT NULL,
					desired_latency_ms INTEGER NOT NULL,
					requested_boot_id TEXT NOT NULL,
					requested_elapsed_realtime_nanos INTEGER NOT NULL,
					requested_at_ms INTEGER NOT NULL,
					status TEXT NOT NULL,
					retire_boot_id TEXT,
					retire_elapsed_realtime_nanos INTEGER,
					retired_at_ms INTEGER,
					PRIMARY KEY(demand_id)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_source_demand_consumer " +
					"ON source_demand(consumer_id, source_kind, purpose, status)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_source_demand_active " +
					"ON source_demand(source_kind, status, requested_elapsed_realtime_nanos)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_source_demand_manifest " +
					"ON source_demand(logical_tracking_id, manifest_revision)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS provider_registration_generation (
					source_kind INTEGER NOT NULL,
					registration_generation INTEGER NOT NULL,
					source_instance_id TEXT NOT NULL,
					owner_scope TEXT NOT NULL,
					clock_domain_id TEXT NOT NULL,
					physical_configuration_fingerprint TEXT NOT NULL,
					collected_data_epoch INTEGER NOT NULL,
					status TEXT NOT NULL,
					reserved_at_ms INTEGER NOT NULL,
					reserved_elapsed_realtime_nanos INTEGER NOT NULL,
					accepted_at_ms INTEGER,
					accepted_elapsed_realtime_nanos INTEGER,
					retired_at_ms INTEGER,
					retired_elapsed_realtime_nanos INTEGER,
					failure_code TEXT,
					PRIMARY KEY(source_kind, registration_generation)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_provider_registration_instance " +
					"ON provider_registration_generation(source_instance_id)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_provider_registration_status " +
					"ON provider_registration_generation(source_kind, status)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS source_authorization (
					source_kind INTEGER NOT NULL,
					registration_generation INTEGER NOT NULL,
					authorization_revision INTEGER NOT NULL,
					member_id TEXT NOT NULL,
					authorization_fingerprint TEXT NOT NULL,
					purpose_eligibility_mask INTEGER NOT NULL,
					demand_id TEXT,
					consumer_id TEXT,
					purpose TEXT,
					source_policy_revision INTEGER,
					consent_epoch INTEGER,
					persistence_eligible INTEGER NOT NULL,
					effective_boot_id TEXT NOT NULL,
					effective_elapsed_realtime_nanos INTEGER NOT NULL,
					effective_wall_time_ms INTEGER NOT NULL,
					logical_tracking_id TEXT,
					service_run_id TEXT,
					manifest_revision INTEGER,
					lifecycle_lease_generation INTEGER,
					PRIMARY KEY(source_kind, registration_generation, authorization_revision, member_id)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_source_authorization_manifest " +
					"ON source_authorization(logical_tracking_id, manifest_revision, purpose)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_source_authorization_observed_time " +
					"ON source_authorization(source_kind, registration_generation, effective_boot_id, " +
					"effective_elapsed_realtime_nanos, authorization_revision)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS session_manifest_version (
					logical_tracking_id TEXT NOT NULL,
					manifest_revision INTEGER NOT NULL,
					session_mode TEXT NOT NULL,
					source_policy_revision INTEGER NOT NULL,
					acquisition_plan_revision INTEGER NOT NULL,
					rollout_revision INTEGER NOT NULL,
					start_origin TEXT NOT NULL,
					effective_boot_id TEXT NOT NULL,
					effective_elapsed_realtime_nanos INTEGER NOT NULL,
					effective_wall_time_ms INTEGER NOT NULL,
					zone_id TEXT NOT NULL,
					automation_epoch INTEGER,
					change_reason TEXT NOT NULL,
					manifest_checksum TEXT NOT NULL,
					PRIMARY KEY(logical_tracking_id, manifest_revision)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_session_manifest_effective " +
					"ON session_manifest_version(logical_tracking_id, effective_elapsed_realtime_nanos)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_session_manifest_policy " +
					"ON session_manifest_version(source_policy_revision)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS session_manifest_source (
					logical_tracking_id TEXT NOT NULL,
					manifest_revision INTEGER NOT NULL,
					source_kind INTEGER NOT NULL,
					purpose TEXT NOT NULL,
					consent_epoch INTEGER NOT NULL,
					persistence_eligible INTEGER NOT NULL,
					qos_code INTEGER NOT NULL,
					PRIMARY KEY(logical_tracking_id, manifest_revision, source_kind, purpose)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_session_manifest_source_lookup " +
					"ON session_manifest_source(logical_tracking_id, source_kind, purpose, manifest_revision)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS session_lifecycle_intent_version (
					logical_tracking_id TEXT NOT NULL,
					intent_revision INTEGER NOT NULL,
					manifest_revision INTEGER NOT NULL,
					desired_state TEXT NOT NULL,
					start_origin TEXT NOT NULL,
					request_boot_id TEXT NOT NULL,
					requested_elapsed_realtime_nanos INTEGER NOT NULL,
					requested_wall_time_ms INTEGER NOT NULL,
					automation_epoch INTEGER,
					trigger_id TEXT,
					trigger_kind TEXT,
					trigger_boot_id TEXT,
					trigger_observed_elapsed_realtime_nanos INTEGER,
					trigger_received_elapsed_realtime_nanos INTEGER,
					trigger_expires_elapsed_realtime_nanos INTEGER,
					stop_reason TEXT,
					stop_deadline_boot_id TEXT,
					stop_deadline_elapsed_realtime_nanos INTEGER,
					intent_checksum TEXT NOT NULL,
					PRIMARY KEY(logical_tracking_id, intent_revision)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_session_lifecycle_intent_requested " +
					"ON session_lifecycle_intent_version(logical_tracking_id, requested_elapsed_realtime_nanos)",
			)
			execSQL(
				"""
				CREATE TABLE IF NOT EXISTS lifecycle_desired_action (
					action_id TEXT NOT NULL,
					logical_tracking_id TEXT NOT NULL,
					service_run_id TEXT NOT NULL,
					manifest_revision INTEGER NOT NULL,
					action_revision INTEGER NOT NULL,
					action_family TEXT NOT NULL,
					source_kind INTEGER,
					desired_state TEXT NOT NULL,
					desired_plan_revision INTEGER NOT NULL,
					source_policy_revision INTEGER NOT NULL,
					consent_epoch INTEGER,
					start_origin TEXT NOT NULL,
					boot_id TEXT NOT NULL,
					lease_generation INTEGER NOT NULL,
					requested_at_ms INTEGER NOT NULL,
					requested_elapsed_realtime_nanos INTEGER NOT NULL,
					status TEXT NOT NULL,
					attempt_count INTEGER NOT NULL,
					acknowledged_at_ms INTEGER,
					acknowledged_elapsed_realtime_nanos INTEGER,
					failure_code TEXT,
					retry_trigger TEXT,
					source_instance_id TEXT,
					registration_generation INTEGER,
					PRIMARY KEY(action_id)
				)
				""".trimIndent(),
			)
			execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS idx_lifecycle_action_revision " +
					"ON lifecycle_desired_action(logical_tracking_id, action_revision)",
			)
			execSQL(
				"CREATE INDEX IF NOT EXISTS idx_lifecycle_action_pending " +
					"ON lifecycle_desired_action(status, requested_at_ms)",
			)
			// v27 used a wall-clock-only projection lease. Lease rows are ephemeral, so discard any
			// in-flight owner and extend that shared table for boot-aware monotonic reconciliation.
			execSQL("DELETE FROM source_coordinator_lease")
			execSQL(
				"ALTER TABLE source_coordinator_lease ADD COLUMN " +
					"boot_id TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
			)
			execSQL(
				"ALTER TABLE source_coordinator_lease ADD COLUMN generation INTEGER NOT NULL DEFAULT 0",
			)
			execSQL(
				"ALTER TABLE source_coordinator_lease ADD COLUMN " +
					"acquired_elapsed_realtime_nanos INTEGER NOT NULL DEFAULT 0",
			)
			execSQL(
				"ALTER TABLE source_coordinator_lease ADD COLUMN " +
					"expires_elapsed_realtime_nanos INTEGER NOT NULL DEFAULT 0",
			)
			execSQL(
				"""
				INSERT OR IGNORE INTO source_policy_authority (
					id, bootstrap_state, current_policy_revision,
					legacy_settings_fingerprint, updated_at_ms
				) VALUES (1, 'UNINITIALIZED', 0, NULL, 0)
				""".trimIndent(),
			)
		}
	}
}
