package com.adsamcik.signalcollector.map.layer

import android.content.Context
import com.google.android.gms.maps.GoogleMap

internal interface MapLayerLogic {
	val data: MapLayerData
	val supportsAutoUpdate: Boolean
	var dateRange: LongRange
	var quality: Float

	fun onEnable(context: Context, map: GoogleMap)

	fun onDisable(map: GoogleMap)

	fun update(context: Context)
}