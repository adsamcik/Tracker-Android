package com.adsamcik.tracker.statistics.data.source.abstraction

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.squareup.moshi.Moshi

/**
 * Consumer interface providing boiler-plate code to format distance.
 */
interface StatDataDistanceConsumer : StatDataConsumer {
	/**
	 * Returns distance value in meters.
	 */
	fun getDistance(context: Context, data: StatDataMap): Double

	override fun serializeData(data: Any, moshi: Moshi): String = data as String

	override fun deserializeData(data: String, moshi: Moshi) = data

	override val requiredMoshiAdapter: Any? get() = null

	@CallSuper
	override fun getData(context: Context, data: StatDataMap): Any {
		val distance = getDistance(context, data)
		val lengthSystem = Preferences.getLengthSystem(context)
		return context.resources.formatDistance(distance, 1, lengthSystem)
	}
}
