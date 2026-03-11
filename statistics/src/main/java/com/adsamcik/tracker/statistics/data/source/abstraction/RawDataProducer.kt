package com.adsamcik.tracker.statistics.data.source.abstraction

import android.content.Context
import com.adsamcik.tracker.statistics.data.source.StatDataSource

/**
 * Produces any raw data.
 */
interface RawDataProducer {
	/**
	 * Type of data provided by the producer.
	 */
	val type: StatDataSource

	/**
	 * Method that returns required data.
	 *
	 * @return Returns data or null if the data is not available.
	 */
	suspend fun produce(
			context: Context,
			startTime: Long,
			endTime: Long
	): Any?
}
