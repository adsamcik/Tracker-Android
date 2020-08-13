package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.misc.Double2
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedAltitudeProducer
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.extension.requireData
import com.github.mikephil.charting.data.Entry
import kotlin.reflect.KClass

/**
 * Creates elevation chart stat
 */
class ElevationChartConsumer : StatDataConsumer {
	override val nameRes: Int
		get() = R.string.stats_elevation
	override val iconRes: Int
		get() = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_terrain

	override val displayType: StatisticDisplayType
		get() = StatisticDisplayType.LineChart

	override fun getData(context: Context, data: StatDataMap): Any {
		val altitudeList = data.requireData<List<Double2>>(OptimizedAltitudeProducer::class)
		return altitudeList.map { Entry(it.x.toFloat(), it.y.toFloat()) }
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = listOf(OptimizedAltitudeProducer::class)

}
