package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import com.adsamcik.tracker.commonmap.MapLayerData
import com.adsamcik.tracker.commonmap.MapLayerLogic
import com.adsamcik.tracker.commonmap.MapLegend
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.map.heatmap.creators.WifiHeatmapTileCreator

internal class WifiHeatmapLogic : HeatmapLayerLogic() {
	override fun getTileCreator(context: Context): HeatmapTileCreator {
		return WifiHeatmapTileCreator(context)
	}

	@Suppress("Unchecked_cast")
	override val data: MapLayerData = MapLayerData(
			this::class.java as Class<MapLayerLogic>,
			legend = MapLegend(R.string.map_layer_wifi_heatmap))
}

