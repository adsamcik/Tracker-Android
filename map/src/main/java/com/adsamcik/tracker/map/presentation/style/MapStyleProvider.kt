package com.adsamcik.tracker.map.presentation.style

import android.content.Context
import com.google.android.gms.maps.model.MapStyleOptions
import com.adsamcik.tracker.shared.utils.style.StyleData

/** Simple provider for MapStyleOptions used by Maps Compose. */
object MapStyleProvider {
    /** Load the default style JSON from the shared raw resources. */
    fun default(context: Context): MapStyleOptions {
        val resId = com.adsamcik.tracker.shared.map.R.raw.map_style_default
        context.resources.openRawResource(resId).bufferedReader().use {
            return MapStyleOptions(it.readText())
        }
    }

    /** Map StyleData to the same raw style JSON used by ColorMap and return MapStyleOptions. */
    fun fromStyleData(context: Context, styleData: StyleData): MapStyleOptions {
        val resId = when {
            styleData.saturation > 0.5f && styleData.perceivedLuminance(false) > -70 -> com.adsamcik.tracker.shared.map.R.raw.map_style_vibrant
            styleData.saturation > 0.2f && styleData.perceivedLuminance(false) > -48 -> com.adsamcik.tracker.shared.map.R.raw.map_style_default
            styleData.perceivedLuminance(false) > 60 -> com.adsamcik.tracker.shared.map.R.raw.map_style_light
            styleData.perceivedLuminance(false) < -48 -> com.adsamcik.tracker.shared.map.R.raw.map_style_dark
            else -> com.adsamcik.tracker.shared.map.R.raw.map_style_grey
        }
        context.resources.openRawResource(resId).bufferedReader().use {
            return MapStyleOptions(it.readText())
        }
    }

    /** Lightweight selector based on dark/light mode. */
    fun fromIsDark(context: Context, isDark: Boolean): MapStyleOptions {
        val resId = if (isDark) com.adsamcik.tracker.shared.map.R.raw.map_style_dark else com.adsamcik.tracker.shared.map.R.raw.map_style_light
        return try {
            context.resources.openRawResource(resId).bufferedReader().use { MapStyleOptions(it.readText()) }
        } catch (_: Exception) {
            default(context)
        }
    }
}
