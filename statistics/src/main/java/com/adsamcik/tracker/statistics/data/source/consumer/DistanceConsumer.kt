package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import kotlin.reflect.KClass

class DistanceConsumer : StatDataConsumer {
	override fun getName(context: Context): String =
			context.getString(R.string.stats_distance_total)

	override fun getStat(
			context: Context,
			data: StatDataMap
	): String {
		val session = data[TrackerSessionProducer::class]
		context.resources.formatDistance(session.distanceInM, 1, lengthSystem)
	}

	override val dependsOn: List<KClass<StatDataProducer>>
		get() = listOf()

}
