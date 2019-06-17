package com.adsamcik.signalcollector.common.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
	override fun migrate(database: SupportSQLiteDatabase) {
		with(database) {
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
	override fun migrate(database: SupportSQLiteDatabase) {
		with(database) {
			execSQL("DROP TABLE map_max_heat")
			execSQL("CREATE TABLE map_max_heat (`layer_name` TEXT NOT NULL, `zoom` INTEGER NOT NULL, `max_heat` REAL NOT NULL, PRIMARY KEY(`layer_name`, `zoom`))")

			execSQL("DROP TABLE wifi_data")
			execSQL("CREATE TABLE wifi_data (`id` TEXT NOT NULL, `longitude` REAL NOT NULL, `latitude` REAL NOT NULL, `altitude` REAL, `first_seen` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `bssid` TEXT NOT NULL, `ssid` TEXT NOT NULL, `capabilities` TEXT NOT NULL, `frequency` INTEGER NOT NULL, `level` INTEGER NOT NULL, `isPasspoint` INTEGER NOT NULL, PRIMARY KEY(`id`))")
		}
	}
}

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
	override fun migrate(database: SupportSQLiteDatabase) {
		with(database) {
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
	override fun migrate(database: SupportSQLiteDatabase) {
		with(database) {
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
	override fun migrate(database: SupportSQLiteDatabase) {
		with(database) {
			execSQL("CREATE TABLE IF NOT EXISTS tracker_session_tmp (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `collections` INTEGER NOT NULL, `distance` REAL NOT NULL, `distance_on_foot` REAL NOT NULL, `distance_in_vehicle` REAL NOT NULL, `steps` INTEGER NOT NULL, `session_activity_id` INTEGER)")

			execSQL("INSERT INTO tracker_session_tmp SELECT `id`, `start`, `end`, `collections`, `distance`, `distance_on_foot`, `distance_in_vehicle`, `steps`, null from tracker_session")
			execSQL("DROP TABLE tracker_session")
			execSQL("ALTER TABLE tracker_session_tmp RENAME TO tracker_session")
		}
	}
}