package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.MapLayerInfo
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.map.MapLegend
import com.google.android.gms.maps.GoogleMap

internal class NoMapLayerLogic : MapLayerLogic {
	override var quality: Float
		get() = 0f
		set(_) {}

	override var dateRange: LongRange = LongRange.EMPTY

	override val tileCountInGeneration: LiveData<Int> = MutableLiveData()

	override val supportsAutoUpdate: Boolean
		get() = false

	override val availableRange: LongRange
		get() = LongRange.EMPTY

	override fun colorList(): List<Int> = emptyList()

	override fun layerData(): MapLayerData {
		return MapLayerData(
				layerInfo,
				colorList = colorList(),
				legend = MapLegend()
		)
	}

	override val layerInfo: MapLayerInfo
		get() = MapLayerInfo(this::class.java, R.string.map_layer_none)

	override fun update(context: Context) = Unit
	override fun onEnable(
			context: Context,
			map: GoogleMap,
			quality: Float
	) = Unit

	override fun onDisable(map: GoogleMap) = Unit
}
