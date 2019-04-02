package com.adsamcik.signalcollector.database

import android.content.Context

class DatabaseMaintenance {

	fun run(context: Context) {
		val database = AppDatabase.getAppDatabase(context)
		val clearInvalidSessions = database.compileStatement("DELETE FROM tracker_session WHERE start >= `end` OR collections <= 1")
		clearInvalidSessions.executeUpdateDelete()
	}
}