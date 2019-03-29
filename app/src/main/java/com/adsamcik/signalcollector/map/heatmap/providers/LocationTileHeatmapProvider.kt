package com.adsamcik.signalcollector.map.heatmap.providers

import android.content.Context
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal

class LocationTileHeatmapProvider(context: Context) : MapTileHeatmapProvider {
	private val dao = AppDatabase.getAppDatabase(context).locationDao()

	override val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>
		 = dao::getAllInsideAndBetween
	override val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>
		 = dao::getAllInside
}