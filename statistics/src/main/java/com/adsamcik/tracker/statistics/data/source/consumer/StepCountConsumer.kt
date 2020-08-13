package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataDistanceConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.extension.requireData
import kotlin.reflect.KClass

/**
 * Distance on foot consumer.
 */
class StepCountConsumer : StatDataConsumer {
	override val nameRes: Int
		get() = R.string.stats_steps

	override val iconRes: Int
		get() = com.adsamcik.tracker.shared.base.R.drawable.ic_shoe_print

	override val displayType: StatisticDisplayType
		get() = StatisticDisplayType.Information

	override fun getData(context: Context, data: StatDataMap): Any {
		val session = data.requireData<TrackerSession>(TrackerSessionProducer::class)
		return session.steps
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = listOf(TrackerSessionProducer::class)
}
