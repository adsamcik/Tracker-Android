package com.adsamcik.signalcollector.map.heatmap.creators

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal

class LocationHeatmapTileCreator(context: Context) : HeatmapTileCreator {
	private val dao = AppDatabase.getDatabase(context).locationDao()

	override val weightNormalizationValue: Double = Preferences
			.getPref(context)
			.getIntRes(R.string.settings_tracking_required_accuracy_key, R.integer.settings_tracking_required_accuracy_default)
			.toDouble()

	override val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal> = dao::getAllInsideAndBetween

	override val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal> = dao::getAllInside
}