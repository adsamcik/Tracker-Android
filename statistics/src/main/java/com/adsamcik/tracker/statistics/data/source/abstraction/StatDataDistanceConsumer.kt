package com.adsamcik.tracker.statistics.data.source.abstraction

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import com.adsamcik.tracker.statistics.extension.requireData

/**
 * Consumer interface providing boiler-plate code to format distance.
 */
interface StatDataDistanceConsumer : StatDataConsumer {
	/**
	 * Returns distance value in meters.
	 */
	fun getDistance(context: Context, data: StatDataMap): Double

	@CallSuper
	override fun getData(context: Context, data: StatDataMap): Any {
		val distance = getDistance(context, data)
		val lengthSystem = Preferences.getLengthSystem(context)
		return context.resources.formatDistance(distance, 1, lengthSystem)
	}
}
