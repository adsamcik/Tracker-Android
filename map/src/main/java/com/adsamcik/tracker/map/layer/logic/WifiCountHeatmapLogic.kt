package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import com.adsamcik.tracker.shared.utils.style.utility.ColorConstants
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.MapLayerInfo
import com.adsamcik.tracker.shared.map.MapLegend
import com.adsamcik.tracker.shared.map.MapLegendValue
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.map.heatmap.creators.WifiCountHeatmapTileCreator

internal class WifiCountHeatmapLogic : HeatmapLayerLogic() {
	override fun getTileCreator(context: Context): HeatmapTileCreator {
		return WifiCountHeatmapTileCreator(context, layerData())
	}

	override val layerInfo: MapLayerInfo =
			MapLayerInfo(this::class.java, R.string.map_layer_wifi_count_heatmap_title)

	override fun colorList(): List<Int> = listOf(
			ColorConstants.GREEN,
			ColorConstants.ORANGE,
			ColorConstants.RED
	)

	override fun layerData(): MapLayerData {
		val colorList = colorList()
		val nameResList = listOf(
				R.string.map_layer_wifi_heatmap_low,
				R.string.map_layer_wifi_heatmap_medium,
				R.string.map_layer_wifi_heatmap_high
		)

		val legendList = nameResList
				.zip(colorList) { a, b -> MapLegendValue(a, b) }

		return MapLayerData(
				layerInfo,
				colorList = colorList(),
				legend = MapLegend(R.string.map_layer_wifi_heatmap_description, legendList)
		)
	}
}

