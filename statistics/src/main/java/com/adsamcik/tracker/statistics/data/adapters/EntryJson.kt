package com.adsamcik.tracker.statistics.data.adapters

import com.adsamcik.tracker.statistics.data.ChartPoint
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson

/**
 * Chart point class for moshi
 */
@JsonClass(generateAdapter = true)
data class EntryJson(val x: Float, val y: Float)

/**
 * Chart point adapter for moshi
 */
class EntryJsonAdapter {
	@ToJson
	fun toJson(entry: ChartPoint): EntryJson {
		return EntryJson(entry.x, entry.y)
	}

	@FromJson
	fun fromJson(entry: EntryJson): ChartPoint {
		return ChartPoint(entry.x, entry.y)
	}
}
