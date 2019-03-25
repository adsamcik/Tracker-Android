package com.adsamcik.signalcollector.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
	override fun migrate(database: SupportSQLiteDatabase) {
		database.execSQL("CREATE TABLE IF NOT EXISTS map_max_heat (`zoom` INTEGER NOT NULL, `maxHeat` REAL NOT NULL, PRIMARY KEY(`zoom`))")
	}
}
