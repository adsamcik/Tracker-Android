package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataDistanceConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.extension.requireData
import kotlin.reflect.KClass

/**
 * Consumer that returns distance from session.
 */
class DistanceConsumer : StatDataDistanceConsumer {
	override val nameRes: Int = R.string.stats_distance_total

	override val iconRes: Int = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_directions_24px

	override val displayType: StatisticDisplayType = StatisticDisplayType.Information

	override fun getDistance(context: Context, data: StatDataMap): Double {
		val session = data.requireData<TrackerSession>(TrackerSessionProducer::class)
		return session.distanceInM.toDouble()
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = listOf(TrackerSessionProducer::class)

}
