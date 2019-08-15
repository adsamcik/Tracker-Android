package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.map.heatmap.creators.WifiHeatmapTileCreator
import com.adsamcik.tracker.map.layer.MapLayerData
import com.adsamcik.tracker.map.layer.MapLayerLogic
import kotlin.reflect.KClass

internal class WifiHeatmapLogic : HeatmapLayerLogic() {
	override fun getTileCreator(context: Context): HeatmapTileCreator {
		return WifiHeatmapTileCreator(context)
	}

	@Suppress("Unchecked_cast")
	override val data: MapLayerData = MapLayerData(this::class as KClass<MapLayerLogic>,
			R.string.map_layer_wifi_heatmap)
}

