package com.adsamcik.tracker.statistics.data.source.producer

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.data.LocationSample
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
		return rawDataMap
			.requireData<List<LocationSample>>(StatDataSource.LOCATION)
			.mapNotNull { sample ->
				val lat = sample.latE7 ?: return@mapNotNull null
				val lon = sample.lonE7 ?: return@mapNotNull null
				Location(
					time = sample.timeMs,
					latitude = lat / 1e7,
					longitude = lon / 1e7,
					altitude = sample.altitudeM?.toDouble(),
					horizontalAccuracy = sample.hAccM,
					verticalAccuracy = null,
					speed = sample.speedMps,
					speedAccuracy = null,
				)
			}
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = emptyList()
}
