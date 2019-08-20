package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.commonmap.MapLayerData
import com.adsamcik.tracker.commonmap.MapLayerLogic
import com.adsamcik.tracker.commonmap.MapLegend
import com.google.android.gms.maps.GoogleMap
import kotlin.reflect.KClass

internal class NoMapLayerLogic : MapLayerLogic {
	override var quality: Float
		get() = 0f
		set(_) {}

	override var dateRange: LongRange = LongRange.EMPTY

	@Suppress("unchecked_cast")
	override val data: MapLayerData
		get() = MapLayerData(this::class.java as Class<MapLayerLogic>,
				legend = MapLegend(R.string.map_layer_none))

	override val supportsAutoUpdate: Boolean
		get() = false

	override val availableRange: LongRange
		get() = LongRange.EMPTY

	override fun update(context: Context) {}
	override fun onEnable(context: Context,
	                      map: GoogleMap,
	                      quality: Float) {}
	override fun onDisable(map: GoogleMap) {}
}
