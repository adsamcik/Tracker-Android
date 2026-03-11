package com.adsamcik.tracker.statistics.data.source.producer.raw

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer
import kotlinx.coroutines.runBlocking

/**
 * Produces raw ordered list of location samples.
 */
class RawLocationDataProducer : RawDataProducer {
	override val type: StatDataSource
		get() = StatDataSource.LOCATION

	override fun produce(
			context: Context,
			startTime: Long,
			endTime: Long
	): Any {
		return runBlocking {
			AppDatabase.database(context).locationSampleDao()
				.getAllBetween(startTime, endTime)
		}
	}
}
