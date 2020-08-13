package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.utils.style.color.ColorGenerator
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.MapLayerInfo
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.map.MapLegend
import com.adsamcik.tracker.shared.map.MapLegendValue
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.creators.CellHeatmapTileCreator
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator

internal class CellHeatmapLogic : HeatmapLayerLogic() {
	override fun getTileCreator(context: Context): HeatmapTileCreator {
		return CellHeatmapTileCreator(context, layerData())
	}

	@Suppress("unchecked_cast")
	override val layerInfo: MapLayerInfo = MapLayerInfo(
			this::class.java as Class<MapLayerLogic>,
			R.string.map_layer_cell_heatmap_title
	)

	override fun colorList(): List<Int> {
		val cellTypeCount = CellType.values().size
		return ColorGenerator.generateWithGolden(COLOR_START_HUE, cellTypeCount)
	}

	override fun layerData(): MapLayerData {
		val colorList = colorList()
		val legendColorMap = colorList.mapIndexed { index, color ->
			MapLegendValue(CellType.values()[index].nameRes, color)
		}

		@Suppress("unchecked_cast")
		return MapLayerData(
				layerInfo,
				colorList = colorList,
				legend = MapLegend(
						R.string.map_layer_cell_heatmap_description,
						legendColorMap
				)
		)
	}

	companion object {
		const val COLOR_START_HUE = 0.129
	}
}

