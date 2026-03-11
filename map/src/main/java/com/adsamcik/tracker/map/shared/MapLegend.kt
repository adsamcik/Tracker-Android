package com.adsamcik.tracker.map.shared

import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize

@Parcelize
data class MapLegend(
		@StringRes val description: Int? = null,
		val valueList: List<MapLegendValue> = emptyList()
) : Parcelable

@Parcelize
data class MapLegendValue(
		@StringRes val nameRes: Int,
		@ColorInt val color: Int
) : Parcelable
