package com.adsamcik.tracker.shared.map

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class MapLayerData(
		val info: MapLayerInfo,
		val colorList: List<Int>,
		val legend: MapLegend
) : Parcelable
