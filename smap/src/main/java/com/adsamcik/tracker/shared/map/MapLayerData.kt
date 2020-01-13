package com.adsamcik.tracker.shared.map

import android.os.Parcelable
import com.adsamcik.tracker.shared.map.MapLayerInfo
import com.adsamcik.tracker.shared.map.MapLegend
import kotlinx.android.parcel.Parcelize

@Parcelize
data class MapLayerData(
		val info: MapLayerInfo,
		val colorList: List<Int>,
		val legend: MapLegend
) : Parcelable
