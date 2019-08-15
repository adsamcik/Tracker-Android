package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.creators.CellHeatmapTileCreator
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.map.layer.MapLayerData
import com.adsamcik.tracker.map.layer.MapLayerLogic
import kotlin.reflect.KClass

internal class CellHeatmapLogic : HeatmapLayerLogic() {
	override fun getTileCreator(context: Context): HeatmapTileCreator {
		return CellHeatmapTileCreator(context)
	}

	override val data: MapLayerData = MapLayerData(this::class as KClass<MapLayerLogic>,
			R.string.map_layer_cell_heatmap)
}

