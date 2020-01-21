package com.adsamcik.tracker.statistics.data.source.producer

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.misc.Double2
import com.adsamcik.tracker.statistics.data.source.RawDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.extension.requireData
import kotlin.reflect.KClass

/**
 * Produces altitude from optimized location data.
 */
class OptimizedAltitudeProducer : StatDataProducer {
	override val requiredRawData: List<StatDataSource> = emptyList()

	override fun produce(rawDataMap: RawDataMap, dataMap: StatDataMap): Any {
		val optimizedLocation = dataMap.requireData<List<Location>>(OptimizedLocationDataProducer::class)
		val firstTime = optimizedLocation.first().time
		return optimizedLocation
				.mapNotNull {
					val altitude = it.altitude
					if (altitude != null) {
						Double2((it.time - firstTime).toDouble(), altitude)
					} else {
						null
					}
				}
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = listOf(OptimizedLocationDataProducer::class)


}
