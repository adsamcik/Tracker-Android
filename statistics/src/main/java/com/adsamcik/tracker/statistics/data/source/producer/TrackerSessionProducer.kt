package com.adsamcik.tracker.statistics.data.source.producer

import com.adsamcik.tracker.statistics.data.source.RawDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import kotlin.reflect.KClass

/**
 * Tracker session producer.
 * Proxy producer that only passes data from session rawDataMap.
 */
class TrackerSessionProducer : StatDataProducer {
	override val requiredRawData: List<StatDataSource>
		get() = listOf(StatDataSource.SESSION)

	override fun produce(rawDataMap: RawDataMap, dataMap: StatDataMap): Any {
		return requireNotNull(rawDataMap[StatDataSource.SESSION]?.data)
	}

	override val dependsOn: List<KClass<StatDataProducer>>
		get() = emptyList()
}
