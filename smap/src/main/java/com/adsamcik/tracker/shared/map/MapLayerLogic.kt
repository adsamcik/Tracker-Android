package com.adsamcik.tracker.shared.map

import android.content.Context
import androidx.lifecycle.LiveData
import com.google.android.gms.maps.GoogleMap

interface MapLayerLogic {
	val supportsAutoUpdate: Boolean
	var dateRange: LongRange
	var quality: Float
	val availableRange: LongRange

	val tileCountInGeneration: LiveData<Int>

	val layerInfo: MapLayerInfo
	fun colorList(): List<Int>

	fun layerData(): MapLayerData

	fun onEnable(context: Context, map: GoogleMap, quality: Float)

	fun onDisable(map: GoogleMap)

	fun update(context: Context)
}
