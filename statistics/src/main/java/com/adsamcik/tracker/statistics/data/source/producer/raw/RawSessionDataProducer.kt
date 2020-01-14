package com.adsamcik.tracker.statistics.data.source.producer.raw

import android.content.Context
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer

/**
 * Currently is only a placeholder
 */
class RawSessionDataProducer : RawDataProducer {
	override val type: StatDataSource
		get() = StatDataSource.SESSION

	override fun produce(context: Context, startTime: Long, endTime: Long): Any? {
		return null
	}
}
