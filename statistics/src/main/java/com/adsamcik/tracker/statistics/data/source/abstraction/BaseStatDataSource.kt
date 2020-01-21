package com.adsamcik.tracker.statistics.data.source.abstraction

import kotlin.reflect.KClass

/**
 * Defines common methods for statistic data consumers and producers.
 */
interface BaseStatDataSource {
	/**
	 * List of producer this depends on.
	 */
	val dependsOn: List<KClass<out StatDataProducer>>
}
