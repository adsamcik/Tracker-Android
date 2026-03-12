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
 * │ 21         │ 385         │ 🚧 UNRELEASED - Drop legacy tables       │
 * │            │             │    (tracker_session, location_data,      │
 * │            │             │    wifi_data, cell_location,             │
 * │            │             │    location_wifi_count)                  │
 * │ 20         │ 385         │ 🚧 UNRELEASED - has_distance_anomaly     │
 * │ 18         │ 385         │ 🚧 UNRELEASED - Ski detection tables     │
 * │            │             │    (pressure_sample, ski_run_segment)     │
 * │ 17         │ 385         │ 🚧 UNRELEASED - Route compression & storage │
 * │            │             │    (route_cache, export_log,             │
 * │            │             │    storage_size_snapshot)                │
 * │ 16         │ 385         │ 🚧 UNRELEASED - Exploration & gamification  │
 * │            │             │    (exploration_cell, exploration_streak,│
 * │            │             │    achievement_progress, personal_record)│
 * │ 15         │ 385         │ 🚧 UNRELEASED - Trip inference tables     │
 * │            │             │    (frequent_place, inferred_trip,       │
 * │            │             │    trip_leg)                             │
 * │ 14         │ 385         │ 🚧 UNRELEASED - Aggregator/summary       │
 * │            │             │    tables (daily_summary, live_stats)    │
 * │ 13         │ 385         │ 🚧 UNRELEASED - Sessionless tracking     │
 * │            │             │    foundation (7 new tables)             │
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
 * - Current DB version is 13 (unreleased, versionCode 385).
 * - Action: Update MIGRATION_12_13 directly (no new migration needed).
 *
 * - You're adding a new table after version 13 ships.
 * - DB version 13 is now RELEASED (versionCode 385).
 * - Action: Bump DB version to 14, create MIGRATION_13_14, update table above.
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
		with(db) {
			// Migration from old detailed activity system to new simplified activity system
			// Map old activity IDs to new simplified categories
			
			// Walking activities: WALK(-2) -> WALKING(-2) (already matches)
			// Running activities: RUN(-3) -> RUNNING(-3) (already matches)
			// Cycling activities: BICYCLE(-4) -> BICYCLE(-4) (already matches)
			// Vehicle activities: VEHICLE(-5) -> VEHICLE(-5) (already matches)
			
			// Map slope sports activities to SLOPE_SPORTS(-22)
			// SKI(-23), SNOWBOARD(-24), SKATE(-22) -> SLOPE_SPORTS(-22)
			execSQL("UPDATE tracker_session SET session_activity_id = -22 WHERE session_activity_id IN (-23, -24)")
			
			// Map land vehicle activities to LAND_VEHICLE(-34)  
			// TRAIN(-34), RACE(-21) -> LAND_VEHICLE(-34)
			execSQL("UPDATE tracker_session SET session_activity_id = -34 WHERE session_activity_id = -21")
			
			// Map water vehicle activities to WATER_VEHICLE(-26)
			// SAILING(-26), CANOE(-27), KAYAK(-28), ROWING(-29), FERRY(-32) -> WATER_VEHICLE(-26)
			execSQL("UPDATE tracker_session SET session_activity_id = -26 WHERE session_activity_id IN (-27, -28, -29, -32)")
			
			// Map air vehicle activities to AIR_VEHICLE(-31)
			// AIRPLANE(-31), AIRBALLOON(-33) -> AIR_VEHICLE(-31)
			execSQL("UPDATE tracker_session SET session_activity_id = -31 WHERE session_activity_id = -33")
			
			// Map sports that don't fit well into the new categories to generic activities
			// This includes: SWIM(-6), TENIS(-7), VOLLEYBALL(-8), FOOTBALL(-9), RUGBY(-10), 
			// MARTIAL_ARTS(-11), HOCKEY(-12), HANDBALL(-13), GOLF(-14), BASKETBALL(-15), 
			// BASEBALL(-16), SOFTBALL(-17), BADMINTON(-18), HIKING(-19), CRICKET(-20), 
			// HORSERIDE(-25), DIVE(-30)
			execSQL("""
				UPDATE tracker_session 
				SET session_activity_id = CASE 
					WHEN session_activity_id = -19 THEN -2  -- HIKING -> WALKING
					WHEN session_activity_id = -25 THEN -5  -- HORSERIDE -> VEHICLE
					WHEN session_activity_id IN (-6, -30) THEN -26  -- SWIM, DIVE -> WATER_VEHICLE
					ELSE -5  -- All other sports -> VEHICLE (generic activity)
				END 
				WHERE session_activity_id IN (-6, -7, -8, -9, -10, -11, -12, -13, -14, -15, -16, -17, -18, -19, -20, -25, -30)
			""".trimIndent())
		}
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
				created_at INTEGER NOT NULL
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
			created_at INTEGER NOT NULL
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
			created_at INTEGER NOT NULL
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
			created_at INTEGER NOT NULL
		)
	""".trimIndent())
	execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_time_range ON session_segment(start_time_ms, end_time_ms)")
	execSQL("CREATE INDEX IF NOT EXISTS idx_session_segment_source ON session_segment(source)")

	// 8. Migrate data from location_data to location_sample
	// Convert lat/lon from Double to E7 integers (degrees * 1e7)
	// Classify quality based on horizontal accuracy
	val currentTimeMs = System.currentTimeMillis()
	execSQL("""
		INSERT INTO location_sample (
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
			created_at
		)
		SELECT
			time,
			0,
			CAST(lat * 10000000 AS INTEGER),
			CAST(lon * 10000000 AS INTEGER),
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
				WHEN activity IN (0, 3) THEN 'STILL'
				WHEN activity IN (2, 7, 8) THEN 'MOVING'
				ELSE 'UNKNOWN'
			END,
			NULL,
			NULL,
			$currentTimeMs
		FROM location_data
		ORDER BY time
	""".trimIndent())			// 9. Migrate tracker_session to session_segment
			// Mark all legacy sessions with LEGACY_MIGRATION source
			execSQL("""
				INSERT INTO session_segment (
					start_time_ms,
					end_time_ms,
					distance_m,
					steps,
					primary_activity,
					activity_confidence,
					sample_count,
					source,
					inference_version,
					created_at
				)
				SELECT
					start,
					end,
					distance,
					steps,
					NULL,
					NULL,
					collections,
					'LEGACY_MIGRATION',
					'v12_migration',
					$currentTimeMs
				FROM tracker_session
				WHERE start < end AND collections > 1
				ORDER BY start
			""".trimIndent())

			// 10. Migrate activity data from location_data to activity_snapshot
			// Extract unique activity changes (transitions)
			execSQL("""
				INSERT INTO activity_snapshot (
					time_ms,
					activity_type,
					confidence,
					is_transition,
					created_at
				)
				SELECT DISTINCT
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

			// Log successful migration (visible in logcat during migration)
			android.util.Log.i("AppDatabase", "Migration 12→13: Migrated $sampleCount location samples successfully")
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

			android.util.Log.i("AppDatabase", "Migration 13→14: Created daily_summary and live_stats tables")
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

			android.util.Log.i("AppDatabase", "Migration 14→15: Created frequent_place, inferred_trip, and trip_leg tables")
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

			android.util.Log.i("AppDatabase", "Migration 15→16: Created exploration_cell, exploration_streak, achievement_progress, and personal_record tables")
		}
	}
}

// Migration to add route compression cache, export log, and storage size snapshot tables (Phase 6a).
// Creates route_cache (compressed polylines), export_log (export history), and storage_size_snapshot (daily metrics).
val MIGRATION_16_17: Migration = object : Migration(16, 17) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
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

			android.util.Log.i("AppDatabase", "Migration 16->17: Created route_cache, export_log, storage_size_snapshot, domain_event, and domain_event_cursor tables")
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
					lift_type TEXT,
					created_at INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS idx_ski_run_segment_session ON ski_run_segment(session_id)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_ski_run_segment_start_time ON ski_run_segment(start_time_ms)")

			android.util.Log.i("AppDatabase", "Migration 17->18: Created pressure_sample and ski_run_segment tables")
		}
	}
}

/**
 * Migration 18 → 19: Add raw_gps_alt_m column to location_sample.
 *
 * Preserves the original GPS altitude (before geoid correction and Kalman fusion)
 * alongside the processed altitude for diagnostics and potential re-processing.
 */
val MIGRATION_18_19: Migration = object : Migration(18, 19) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE location_sample ADD COLUMN raw_gps_alt_m REAL")
		android.util.Log.i("AppDatabase", "Migration 18->19: Added raw_gps_alt_m column to location_sample")
	}
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
		android.util.Log.i("AppDatabase", "Migration 19->20: Added has_distance_anomaly column to session_segment")
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

		// Backfill cell_sample from legacy cell_location (if not already migrated).
		// cell_location schema: id, time, mcc TEXT, mnc TEXT, cell_id, type, asu, lat, lon, alt
		db.execSQL("""
			INSERT OR IGNORE INTO cell_sample (
				time_ms, cell_id, lac, mcc, mnc, network_type,
				signal_strength, lat_e7, lon_e7, provenance, created_at
			)
			SELECT
				cl.time,
				cl.cell_id,
				0,
				CAST(cl.mcc AS INTEGER),
				CAST(cl.mnc AS INTEGER),
				cl.type,
				cl.asu,
				CASE WHEN cl.lat IS NOT NULL THEN CAST(cl.lat * 10000000 AS INTEGER) ELSE NULL END,
				CASE WHEN cl.lon IS NOT NULL THEN CAST(cl.lon * 10000000 AS INTEGER) ELSE NULL END,
				'LEGACY_MIGRATION',
				$currentTimeMs
			FROM cell_location cl
			WHERE NOT EXISTS (
				SELECT 1 FROM cell_sample cs
				WHERE cs.time_ms = cl.time AND cs.cell_id = cl.cell_id
			)
		""".trimIndent())

		// Backfill wifi_observation from legacy wifi_data (one observation per AP).
		// wifi_data schema: bssid PK, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level
		db.execSQL("""
			INSERT OR IGNORE INTO wifi_observation (
				time_ms, bssid, ssid, capabilities, frequency, level,
				lat_e7, lon_e7, provenance, created_at
			)
			SELECT
				wd.last_seen,
				wd.bssid,
				wd.ssid,
				wd.capabilities,
				wd.frequency,
				wd.level,
				CASE WHEN wd.latitude IS NOT NULL THEN CAST(wd.latitude * 10000000 AS INTEGER) ELSE NULL END,
				CASE WHEN wd.longitude IS NOT NULL THEN CAST(wd.longitude * 10000000 AS INTEGER) ELSE NULL END,
				'LEGACY_MIGRATION',
				$currentTimeMs
			FROM wifi_data wd
			WHERE NOT EXISTS (
				SELECT 1 FROM wifi_observation wo
				WHERE wo.bssid = wd.bssid AND wo.time_ms = wd.last_seen
			)
		""".trimIndent())

		db.execSQL("DROP TABLE IF EXISTS tracker_session")
		db.execSQL("DROP TABLE IF EXISTS location_data")
		db.execSQL("DROP TABLE IF EXISTS wifi_data")
		db.execSQL("DROP TABLE IF EXISTS cell_location")
		db.execSQL("DROP TABLE IF EXISTS location_wifi_count")
		android.util.Log.i(
			"AppDatabase",
			"Migration 20->21: Backfilled legacy wifi/cell data, then dropped legacy tables"
		)
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
		android.util.Log.i(
			"AppDatabase",
			"Migration 21->22: cell_sample.cell_id widened to Long (no-op, SQLite INTEGER is 64-bit)"
		)
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
			android.util.Log.i(
				"AppDatabase",
				"Migration 22->23: Added missing query indices for cache, export, place, exploration, and achievement tables"
			)
		}
	}
}

/**
 * Version 23 → 24: Add notified_at column to achievement_progress for tracking
 * whether the user has seen an unlock notification.
 */
val MIGRATION_23_24: Migration = object : Migration(23, 24) {
	override fun migrate(db: SupportSQLiteDatabase) {
		with(db) {
			execSQL("ALTER TABLE achievement_progress ADD COLUMN notified_at INTEGER DEFAULT NULL")
			android.util.Log.i(
				"AppDatabase",
				"Migration 23->24: Added notified_at column to achievement_progress"
			)
		}
	}
}
