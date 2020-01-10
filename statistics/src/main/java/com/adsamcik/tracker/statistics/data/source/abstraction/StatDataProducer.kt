package com.adsamcik.tracker.statistics.data.source.abstraction

import com.adsamcik.tracker.statistics.data.source.RawDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
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
	 *
	 * @param rawDataMap Map with raw data. Includes all data provided by raw producers specified
	 * in [requiredRawData], other data may or may not be available.
	 * @param dataMap Map with data provided by other producers. Includes all data provided by
	 * producers specified in [dependsOn], other data may or may not be available.
	 */
	fun produce(rawDataMap: RawDataMap, dataMap: StatDataMap): String
}
