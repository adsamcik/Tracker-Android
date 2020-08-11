package com.adsamcik.tracker.statistics.data.source.producer

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.statistics.data.LocationExtractor
import com.adsamcik.tracker.statistics.data.source.RawDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.extension.requireData
import com.goebl.simplify.Simplify3D
import kotlin.reflect.KClass

/**
 * Produces optimized list of locations.
 */
class OptimizedLocationDataProducer : StatDataProducer {
	override val requiredRawData: List<StatDataSource>
		get() = listOf(StatDataSource.LOCATION)

	override fun produce(rawDataMap: RawDataMap, dataMap: StatDataMap): Any {
		val locations = rawDataMap.requireData<List<DatabaseLocation>>(StatDataSource.LOCATION)

		val mappedLocations = locations.map { it.location }.toTypedArray()
		if (locations.size <= 2) return mappedLocations

		val simplify = Simplify3D(emptyArray(), LocationExtractor())
		return simplify.simplify(
				mappedLocations,
				POSITION_TOLERANCE,
				false
		).toList()
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = emptyList()

	companion object {
		private const val POSITION_TOLERANCE = 500.0
	}
}
