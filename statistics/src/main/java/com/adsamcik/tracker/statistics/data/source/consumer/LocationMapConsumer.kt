package com.adsamcik.tracker.statistics.data.source.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.constant.ResourcesConstants
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.statistics.data.adapters.EntryJsonAdapter
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedLocationDataProducer
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.extension.requireData
import com.github.mikephil.charting.data.Entry
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlin.reflect.KClass

/**
 * Creates map statistic using optimized location data.
 */
class LocationMapConsumer : StatDataConsumer {
	override val nameRes: Int
		get() = ResourcesConstants.ID_NULL
	override val iconRes: Int
		get() = ResourcesConstants.ID_NULL

	override val displayType: StatisticDisplayType
		get() = StatisticDisplayType.Map

	override fun getData(context: Context, data: StatDataMap): Any {
		return data.requireData<List<Location>>(OptimizedLocationDataProducer::class)
	}

	override val dependsOn: List<KClass<out StatDataProducer>>
		get() = listOf(OptimizedLocationDataProducer::class)

	override val requiredMoshiAdapter: Any? = null

	override fun serializeData(data: Any, moshi: Moshi): String {
		val arrayType = Types.newParameterizedType(List::class.java, Location::class.java)
		@Suppress("UNCHECKED_CAST")
		return moshi.adapter<List<Location>>(arrayType).toJson(data as List<Location>)
	}

	override fun deserializeData(data: String, moshi: Moshi): Any {
		val arrayType = Types.newParameterizedType(List::class.java, Location::class.java)
		@Suppress("UNCHECKED_CAST")
		return moshi.adapter<List<Location>>(arrayType).fromJson(data) as Any
	}
}
