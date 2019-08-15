package com.adsamcik.signalcollector.map.layer.logic

import android.content.Context
import com.adsamcik.signalcollector.map.R
import com.adsamcik.signalcollector.map.heatmap.creators.CellHeatmapTileCreator
import com.adsamcik.signalcollector.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.signalcollector.map.layer.MapLayerData
import com.adsamcik.signalcollector.map.layer.MapLayerLogic
import kotlin.reflect.KClass

internal class CellHeatmapLogic : HeatmapLayerLogic() {
	override fun getTileCreator(context: Context): HeatmapTileCreator {
		return CellHeatmapTileCreator(context)
	}

	override val data: MapLayerData = MapLayerData(this::class as KClass<MapLayerLogic>,
			R.string.map_layer_cell_heatmap)
}
