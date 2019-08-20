package com.adsamcik.tracker.commonmap

import android.content.Context
import com.google.android.libraries.maps.GoogleMap

interface MapLayerLogic {
	val data: MapLayerData
	val supportsAutoUpdate: Boolean
	var dateRange: LongRange
	var quality: Float
	val availableRange: LongRange

	fun onEnable(context: Context, map: GoogleMap, quality: Float)

	fun onDisable(map: GoogleMap)

	fun update(context: Context)
}
