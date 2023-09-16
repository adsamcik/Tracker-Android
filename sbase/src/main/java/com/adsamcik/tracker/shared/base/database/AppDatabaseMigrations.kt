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

