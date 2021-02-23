package com.adsamcik.tracker.statistics.data.adapters

import com.github.mikephil.charting.data.Entry
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson

/**
 * Entry class for moshi
 */
@JsonClass(generateAdapter = true)
data class EntryJson(val x: Float, val y: Float)

/**
 * Entry class adapter for moshi
 */
class EntryJsonAdapter {
	@ToJson
	fun toJson(entry: Entry): EntryJson {
		return EntryJson(entry.x, entry.y)
	}

	@FromJson
	fun fromJson(entry: EntryJson): Entry {
		return Entry(entry.x, entry.y)
	}
}
