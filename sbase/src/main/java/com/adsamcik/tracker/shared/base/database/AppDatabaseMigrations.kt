package com.adsamcik.tracker.shared.base.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
					elapsedRealtimeNanos INTEGER NOT NULL,
					lat_e7 INTEGER,
					lon_e7 INTEGER,
					alt_m REAL,
					h_acc_m REAL,
					v_acc_m REAL,
					speed_mps REAL,
					speed_accuracy_mps REAL,
					provider TEXT NOT NULL,
					quality TEXT NOT NULL,
					motionState TEXT,
					policy TEXT,
					bucketId INTEGER,
					createdAt INTEGER NOT NULL
				)
			""".trimIndent())
			execSQL("CREATE INDEX IF NOT EXISTS idx_location_sample_time ON location_sample(time_ms)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_location_sample_coords ON location_sample(lat_e7, lon_e7)")
			execSQL("CREATE INDEX IF NOT EXISTS idx_location_sample_bucket ON location_sample(bucketId)")

			// 2. Create step_interval table
			execSQL("""
				CREATE TABLE IF NOT EXISTS step_interval (
					id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					start_time_ms INTEGER NOT NULL,
					end_time_ms INTEGER NOT NULL,
					stepCount INTEGER NOT NULL,
					sensorValueStart INTEGER NOT NULL,
					sensorValueEnd INTEGER NOT NULL,
					sensorReset INTEGER NOT NULL,
					createdAt INTEGER NOT NULL
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
					isTransition INTEGER NOT NULL,
					createdAt INTEGER NOT NULL
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
					networkType INTEGER NOT NULL,
					signalStrength INTEGER NOT NULL,
					lat_e7 INTEGER,
					lon_e7 INTEGER,
					provenance TEXT NOT NULL,
					createdAt INTEGER NOT NULL
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
					createdAt INTEGER NOT NULL
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
					policyParams TEXT,
					userInitiated INTEGER NOT NULL,
					createdAt INTEGER NOT NULL
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
					inferenceVersion TEXT,
					createdAt INTEGER NOT NULL
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
					elapsedRealtimeNanos,
					lat_e7,
					lon_e7,
					alt_m,
					h_acc_m,
					v_acc_m,
					speed_mps,
					speed_accuracy_mps,
					provider,
					quality,
					motionState,
					policy,
					bucketId,
					createdAt
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
			""".trimIndent())

			// 9. Migrate tracker_session to session_segment
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
					inferenceVersion,
					createdAt
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
					isTransition,
					createdAt
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

