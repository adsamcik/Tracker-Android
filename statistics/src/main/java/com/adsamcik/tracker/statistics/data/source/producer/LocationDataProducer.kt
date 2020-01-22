package com.adsamcik.tracker.statistics.data.source.producer

import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.statistics.data.source.RawDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.extension.requireData
import kotlin.reflect.KClass

/**
 * Produces list of locations.
 */
class LocationDataProducer : StatDataProducer {
	override val requiredRawData: List<StatDataSource>
		get() = listOf(StatDataSource.LOCATION)

	override fun produce(rawDataMap: RawDataMap, dataMap: StatDataMap): Any {
		return rawDataMap.requireData<List<DatabaseLocation>>(StatDataSource.LOCATION)
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = emptyList()
}
