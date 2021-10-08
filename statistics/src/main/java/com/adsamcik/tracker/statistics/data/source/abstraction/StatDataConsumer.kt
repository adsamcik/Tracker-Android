package com.adsamcik.tracker.statistics.data.source.abstraction

import android.content.Context
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.squareup.moshi.Moshi

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
	 * String resource id for name.
	 */
	val nameRes: Int

	/**
	 * Drawable resource id for icon.
	 */
	val iconRes: Int

	/**
	 * How stats should be displayed to the user.
	 */
	val displayType: StatisticDisplayType

	/**
	 * Moshi adapter required for serialization
	 */
	val requiredMoshiAdapter: Any?

	/**
	 * Creates statistic instance
	 */
	fun getData(
			context: Context,
			data: StatDataMap
	): Any

	/**
	 * Serializes data to string
	 */
	fun serializeData(data: Any, moshi: Moshi): String

	/**
	 * Deserializes data from String
	 */
	fun deserializeData(data: String, moshi: Moshi): Any
}
