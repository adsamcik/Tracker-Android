package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import android.graphics.Color
import com.adsamcik.tracker.commonmap.MapLayerData
import com.adsamcik.tracker.commonmap.MapLayerInfo
import com.adsamcik.tracker.commonmap.MapLayerLogic
import com.adsamcik.tracker.commonmap.MapLegend
import com.adsamcik.tracker.commonmap.MapLegendValue
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.map.heatmap.creators.LocationHeatmapTileCreator

internal class LocationHeatmapLogic : HeatmapLayerLogic() {
	override fun getTileCreator(context: Context): HeatmapTileCreator {
		return LocationHeatmapTileCreator(context, layerData())
	}

	@Suppress("unchecked_cast")
	override val layerInfo: MapLayerInfo = MapLayerInfo(
			this::class.java as Class<MapLayerLogic>,
			R.string.map_layer_location_heatmap_title
	)

	override fun colorList(): List<Int> = listOf(Color.BLUE, Color.YELLOW, Color.RED)

	override fun layerData(): MapLayerData {
		val colorList = colorList()
		val nameResList = listOf(
				R.string.map_layer_location_heatmap_low,
				R.string.map_layer_location_heatmap_medium,
				R.string.map_layer_location_heatmap_high
		)

		val legendList = nameResList.zip(colorList) { a, b -> MapLegendValue(a, b) }

		return MapLayerData(
				layerInfo,
				colorList,
				MapLegend(
						description = R.string.map_layer_location_heatmap_description,
						valueList = legendList
				)
		)
	}
}

