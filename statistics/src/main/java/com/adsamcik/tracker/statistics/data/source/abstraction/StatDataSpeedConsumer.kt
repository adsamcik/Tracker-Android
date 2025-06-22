package com.adsamcik.tracker.statistics.data.source.abstraction

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.squareup.moshi.Moshi

/**
 * Consumer interface providing boiler-plate code to format speed.
 */
interface StatDataSpeedConsumer : StatDataConsumer {
	/**
	 * Returns speed value in meters per second.
	 */
	fun getSpeed(context: Context, data: StatDataMap): Double

	@CallSuper
	override fun getData(context: Context, data: StatDataMap): Any {
		val speed = getSpeed(context, data)
		// Get session activity from thread-local context if available
		val sessionActivity = com.adsamcik.tracker.statistics.preference.SessionActivityContext.getSessionActivity()
		val lengthSystem = Preferences.getLengthSystem(context, sessionActivity)
		val speedFormat = Preferences.getSpeedFormat(context)
		return context.resources.formatSpeed(speed, 1, lengthSystem, speedFormat)
	}

	override fun serializeData(data: Any, moshi: Moshi): String = data as String

	override fun deserializeData(data: String, moshi: Moshi): String = data

	override val requiredMoshiAdapter: Any? get() = null
}
