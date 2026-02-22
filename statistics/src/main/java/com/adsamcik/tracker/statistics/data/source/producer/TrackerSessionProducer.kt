package com.adsamcik.tracker.statistics.data.source.producer

import android.util.Log
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.statistics.data.source.RawDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import kotlin.reflect.KClass

/**
 * Tracker session producer.
 * Proxy producer that only passes data from session rawDataMap.
 */
class TrackerSessionProducer : StatDataProducer {
	override val requiredRawData: List<StatDataSource>
		get() = listOf(StatDataSource.SESSION)

	override fun produce(rawDataMap: RawDataMap, dataMap: StatDataMap): Any {
		val data = rawDataMap[StatDataSource.SESSION]?.data
		if (data == null) {
			Log.w(TAG, "SESSION key missing from RawDataMap; returning empty session list")
			return emptyList<TrackerSession>()
		}
		return data
	}

	override val dependsOn: List<KClass<StatDataProducer>>
		get() = emptyList()

	companion object {
		private const val TAG = "TrackerSessionProducer"
	}
}
