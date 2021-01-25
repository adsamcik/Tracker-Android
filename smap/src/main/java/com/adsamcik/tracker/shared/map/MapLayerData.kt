package com.adsamcik.tracker.shared.map

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Layer data.
 * Contains important description data for layer.
 */
@Parcelize
data class MapLayerData(
		val info: MapLayerInfo,
		val colorList: List<Int>,
		val legend: MapLegend
) : Parcelable
