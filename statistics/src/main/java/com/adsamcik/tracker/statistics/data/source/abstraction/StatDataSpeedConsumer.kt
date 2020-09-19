package com.adsamcik.tracker.statistics.data.source.abstraction

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.statistics.data.source.StatDataMap

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
		return context.resources.formatSpeed(context, speed, 1)
	}
}
