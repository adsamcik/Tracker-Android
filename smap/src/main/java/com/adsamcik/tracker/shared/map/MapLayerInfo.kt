package com.adsamcik.tracker.shared.map

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MapLayerInfo(
		val layerClass: String, // Store class name as string instead of Class reference
		val nameRes: Int
) : Parcelable
