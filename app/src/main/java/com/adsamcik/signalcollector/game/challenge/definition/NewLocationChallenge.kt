package com.adsamcik.signalcollector.game.challenge.definition

import android.content.Context
import com.adsamcik.signalcollector.database.AppDatabase

class NewLocationChallenge(context: Context) {
	private val locationDao = AppDatabase.getAppDatabase(context).locationDao()

	fun onBatchProcess(from: Long, to: Long) {
		val locations = locationDao.getAllBetween(from, to)
		locations.groupBy {  }
	}

}