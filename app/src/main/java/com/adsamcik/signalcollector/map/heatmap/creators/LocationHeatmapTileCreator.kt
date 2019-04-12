package com.adsamcik.signalcollector.map.heatmap.creators

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.preference.Preferences

class LocationHeatmapTileCreator(context: Context) : HeatmapTileCreator {
	private val dao = AppDatabase.getDatabase(context).locationDao()

	override val weightNormalizationValue: Double

	init {
		val resources = context.resources
		val nKey = resources.getString(R.string.settings_tracking_required_accuracy_key)
		val nDefault = resources.getInteger(R.integer.settings_tracking_required_accuracy_default)
		weightNormalizationValue = Preferences.getPref(context).getIntRes(nKey, nDefault).toDouble()
	}

	override val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal> = dao::getAllInsideAndBetween

	override val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal> = dao::getAllInside
}