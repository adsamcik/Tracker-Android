package com.adsamcik.signalcollector.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
	override fun migrate(database: SupportSQLiteDatabase) {
		database.execSQL("CREATE TABLE tmp_location_data (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `alt` REAL, `hor_acc` REAL, `ver_acc` REAL, `activity` INTEGER NOT NULL, `confidence` INTEGER NOT NULL)")
		database.execSQL("INSERT INTO tmp_location_data SELECT id, time, lat, lon, alt, hor_acc, null as ver_acc, activity, confidence FROM location_data")
		database.execSQL("DROP TABLE location_data")
		database.execSQL("ALTER TABLE tmp_location_data RENAME TO location_data")

		database.execSQL("CREATE TABLE IF NOT EXISTS map_max_heat (`zoom` INTEGER NOT NULL, `maxHeat` REAL NOT NULL, PRIMARY KEY(`zoom`))")
		database.execSQL("CREATE  INDEX index_location_data_time ON location_data (time)")
		database.execSQL("CREATE  INDEX index_location_data_lat ON location_data (lat)")
		database.execSQL("CREATE  INDEX index_location_data_lon ON location_data (lon)")

		database.execSQL("UPDATE location_data SET alt = null WHERE alt = 0.0")
		database.execSQL("UPDATE location_data SET hor_acc = null WHERE hor_acc = 0.0")
	}
}
