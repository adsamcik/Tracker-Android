package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layer.MapLayerData
import com.adsamcik.tracker.map.layer.MapLayerLogic
import com.google.android.gms.maps.GoogleMap
import kotlin.reflect.KClass

internal class NoMapLayerLogic : MapLayerLogic {
	override var quality: Float
		get() = 0f
		set(_) {}

	override var dateRange: LongRange = LongRange.EMPTY

	@Suppress("unchecked_cast")
	override val data: MapLayerData
		get() = MapLayerData(this::class as KClass<MapLayerLogic>, R.string.map_layer_none)

	override val supportsAutoUpdate: Boolean
		get() = false

	override val availableRange: LongRange
		get() = LongRange.EMPTY

	override fun update(context: Context) {}
	override fun onEnable(context: Context, map: GoogleMap) {}
	override fun onDisable(map: GoogleMap) {}
}
