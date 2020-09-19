package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.misc.Double2
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataDistanceConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedAltitudeProducer
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.extension.requireData
import kotlin.reflect.KClass

/**
 * Finds maximal altitude from optimized location data.
 */
class AscensionConsumer : StatDataDistanceConsumer {
	override val nameRes: Int
		get() = R.string.stats_ascended
	override val iconRes: Int
		get() = R.drawable.arrow_top_right_bold_outline
	override val displayType: StatisticDisplayType
		get() = StatisticDisplayType.Information

	override fun getDistance(context: Context, data: StatDataMap): Double {
		val locationData = data.requireData<List<Double2>>(OptimizedAltitudeProducer::class)
		return locationData
				.asSequence()
				.zipWithNext()
				.fold(0.0) { acc, pair ->
					val diff = pair.second.y - pair.first.y
					if (diff > 0) {
						acc + diff
					} else {
						acc
					}
				}
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = listOf(OptimizedAltitudeProducer::class)

}
