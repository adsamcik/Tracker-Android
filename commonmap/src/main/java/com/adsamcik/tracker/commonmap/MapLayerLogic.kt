package com.adsamcik.tracker.commonmap

import android.content.Context
import com.google.android.gms.maps.GoogleMap

interface MapLayerLogic {
	val supportsAutoUpdate: Boolean
	var dateRange: LongRange
	var quality: Float
	val availableRange: LongRange

	val layerInfo: MapLayerInfo
	fun colorList(): List<Int>

	fun layerData(): MapLayerData

	fun onEnable(context: Context, map: GoogleMap, quality: Float)

	fun onDisable(map: GoogleMap)

	fun update(context: Context)
}
