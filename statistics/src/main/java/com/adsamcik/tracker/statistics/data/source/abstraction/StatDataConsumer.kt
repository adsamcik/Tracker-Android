package com.adsamcik.tracker.statistics.data.source.abstraction

import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.data.source.StatDataMap

/**
 * Statistics data consumer.
 * Generates statistics
 */
interface StatDataConsumer : BaseStatDataSource {

	val allowedSessionActivity: List<Long> get() = listOf<Long>()

	/**
	 * Creates statistic instance
	 */
	fun getStat(data: StatDataMap): Stat
}
