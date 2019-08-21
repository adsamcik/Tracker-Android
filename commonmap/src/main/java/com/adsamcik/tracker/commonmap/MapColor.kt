package com.adsamcik.tracker.commonmap

import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import kotlinx.android.parcel.Parcelize

@Parcelize
data class MapColor(
		@FloatRange(from = 0.0, to = 1.0)
		val progress: Double,
		@ColorInt
		val color: Int
) : Parcelable
