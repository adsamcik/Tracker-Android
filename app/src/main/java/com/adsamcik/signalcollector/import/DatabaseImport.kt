package com.adsamcik.signalcollector.import

//Todo write this, it's really a hassle and needs to be done as generic as possible. Gave up after constructor params were api 26+
/*class DatabaseImport : IImport {
	override fun import(database: AppDatabase, file: File) {
		//val database = SQLiteDatabase.openDatabase(file.path, null, 0)

	}

	private fun getConstructor(type: Class<*>, columnNames: Array<out String>): Constructor<*>? {
		type.constructors.forEach {
			it.parameters.forEach {

			}
		}
	}

	private fun importLocations(locationsDao: LocationDataDao, database: SQLiteDatabase) {
		try {
			database.rawQuery("SELECT * FROM location_data", arrayOf()).use { cursor ->
				val location = Location()
				val activityInfo = ActivityInfo(0, 0)
				val columnCount = cursor.columnCount
				val columnNames = cursor.columnNames

				val hasActivityData = true
				val foundActivityConstructor

				val activityInfoConstructor = getConstructor(activityInfo::class.java, columnNames)


				while (cursor.moveToNext()) {

				}
			}
		} catch (e: SQLiteException) {

		}
	}

}*/