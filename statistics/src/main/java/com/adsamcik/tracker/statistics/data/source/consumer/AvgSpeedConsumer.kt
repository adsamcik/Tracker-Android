package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.extension.averageIfFloat
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataSpeedConsumer
import com.adsamcik.tracker.statistics.data.source.producer.LocationDataProducer
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.extension.requireData
import kotlin.reflect.KClass

/**
 * Consumer that returns average speed in session.
 */
class AvgSpeedConsumer : StatDataSpeedConsumer {
	override val nameRes: Int = R.string.stats_avg_speed

	override val iconRes: Int = com.adsamcik.tracker.shared.base.R.drawable.ic_speedometer

	override val displayType: StatisticDisplayType = StatisticDisplayType.Information

	override fun getSpeed(context: Context, data: StatDataMap): Double {
		val locationData = data.requireData<List<Location>>(LocationDataProducer::class)
		val average = locationData.averageIfFloat({ it.speed != null }) { requireNotNull(it.speed) }
		return average.toDouble()
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = listOf(LocationDataProducer::class)

}
