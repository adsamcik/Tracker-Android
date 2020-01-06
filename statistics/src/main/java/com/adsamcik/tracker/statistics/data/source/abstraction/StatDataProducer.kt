package com.adsamcik.tracker.statistics.data.source.abstraction

import com.adsamcik.tracker.statistics.data.source.SessionDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataSource

/**
 * Statistic data producer.
 * Creates data that can be used by other producers or consumers to generate statistics.
 */
interface StatDataProducer : BaseStatDataSource {

	/**
	 * List of required raw data sources.
	 */
	val requiredRawData: List<StatDataSource>

	/**
	 * Produces statistics data for given session
	 */
	fun produce(sessionData: SessionDataMap,, data: StatDataProducer): String
}
