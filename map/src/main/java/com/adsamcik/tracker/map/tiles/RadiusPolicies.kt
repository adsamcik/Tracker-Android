package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.MapConstants
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/** Common helpers for computing stamp radius in pixels. */
internal object RadiusPolicies {
    /** Radius proportional to a base physical size scaled by zoom. */
    internal fun scaledMeters(
        baseMeters: Double,
        zoom: Int,
        metersPerPixel: Double,
        zoomScale: Double = 1.4
    ): RadiusInfo {
        val baseMeterSize = baseMeters * zoomScale.pow((MapConstants.MAX_ZOOM - zoom).toDouble())
        val r = ceil(baseMeterSize / metersPerPixel).toInt()
        val clamped = max(1, r)
        return RadiusInfo(clamped, clamped)
    }

    /** Separate base and max radius in meters. */
    internal fun scaledMetersRange(
        baseMeters: Double,
        maxMeters: Double,
        zoom: Double,
        metersPerPixel: Double,
        zoomScale: Double = 1.4
    ): RadiusInfo {
        val baseMeterSize = baseMeters * zoomScale.pow((MapConstants.MAX_ZOOM - zoom).toDouble())
        val maxMeterSize = maxMeters * zoomScale.pow((MapConstants.MAX_ZOOM - zoom).toDouble())
        val baseR = ceil(baseMeterSize / metersPerPixel).toInt().coerceAtLeast(1)
        val maxR = ceil(maxMeterSize / metersPerPixel).toInt().coerceAtLeast(baseR)
        return RadiusInfo(baseR, maxR)
    }

    /** Fixed physical size regardless of zoom. */
    internal fun fixedMeters(meters: Double, metersPerPixel: Double): RadiusInfo {
        val r = ceil(meters / metersPerPixel).toInt().coerceAtLeast(1)
        return RadiusInfo(r, r)
    }
}
