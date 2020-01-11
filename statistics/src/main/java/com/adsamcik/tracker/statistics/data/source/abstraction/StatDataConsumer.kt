package com.adsamcik.tracker.statistics.data.source.abstraction

import android.content.Context
import com.adsamcik.tracker.statistics.data.source.StatDataMap

/**
 * Statistics data consumer.
 * Generates statistics
 */
interface StatDataConsumer : BaseStatDataSource {
	/**
	 * List of session activity id's that this stat is meant for.
	 * Empty if no restriction is placed.
	 */
	val allowedSessionActivity: List<Long> get() = listOf()

	/**
	 * Id used for identification of this consumer.
	 */
	val providerId: String get() = this::class.java.simpleName

	/**
	 * Returns localized name for this consumer.
	 *
	 * @param context Context
	 *
	 * @return Localized name
	 */
	fun getName(context: Context): String

	/**
	 * Creates statistic instance
	 */
	fun getStat(
			context: Context,
			data: StatDataMap
	): String
}
