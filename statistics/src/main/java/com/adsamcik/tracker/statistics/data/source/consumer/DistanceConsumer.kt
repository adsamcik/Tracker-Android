package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.extension.requireData
import kotlin.reflect.KClass

/**
 * Consumer that returns distance from session.
 */
class DistanceConsumer : StatDataConsumer {
	override val nameRes: Int = R.string.stats_distance_total

	override val iconRes: Int = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_directions_24px

	override val displayType: StatisticDisplayType = StatisticDisplayType.Information

	override fun getData(
			context: Context,
			data: StatDataMap
	): Any {
		val session = data.requireData<TrackerSession>(TrackerSessionProducer::class)
		val lengthSystem = Preferences.getLengthSystem(context)
		return context.resources.formatDistance(session.distanceInM, 1, lengthSystem)
	}

	override val dependsOn: List<KClass<StatDataProducer>>
		get() = listOf(TrackerSessionProducer::class as KClass<StatDataProducer>)

}
