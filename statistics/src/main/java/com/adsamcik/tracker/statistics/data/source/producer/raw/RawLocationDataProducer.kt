package com.adsamcik.tracker.statistics.data.source.producer.raw

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer

/**
 * Produces raw ordered list of locations.
 */
class RawLocationDataProducer : RawDataProducer {
	override val type: StatDataSource
		get() = StatDataSource.LOCATION

	override fun produce(
			context: Context,
			startTime: Long,
			endTime: Long
	): Any {
		return AppDatabase.database(context).locationDao()
				.getAllBetweenOrdered(startTime, endTime)
	}
}
