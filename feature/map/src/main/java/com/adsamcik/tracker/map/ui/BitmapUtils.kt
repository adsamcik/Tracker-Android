package com.adsamcik.tracker.map.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val DEFAULT_SDF_RADIUS_DP = 4f
private const val DEFAULT_SDF_CUTOFF = 0.4
private const val DISTANCE_INFINITY = 1e20

/**
 * Converts a vector drawable to a Bitmap suitable for use as a MapLibre symbol image.
 */
fun bitmapFromVector(
    context: Context,
    @DrawableRes drawableResId: Int,
    scale: Float = 1f,
): Bitmap? {
    val drawable = AppCompatResources.getDrawable(context, drawableResId) ?: return null

    val width = (drawable.intrinsicWidth * scale).coerceAtLeast(1f).roundToInt()
    val height = (drawable.intrinsicHeight * scale).coerceAtLeast(1f).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * Rasterizes a vector at its requested on-map size and converts its alpha mask to an SDF sprite.
 * Both the Compose map and snapshot renderer use this path so tint and halo rendering stay equal.
 */
fun sdfBitmapFromVector(
    context: Context,
    @DrawableRes drawableResId: Int,
    sizeDp: Float,
): Bitmap? {
    require(sizeDp > 0f) { "SDF icon size must be positive" }
    val drawable = AppCompatResources.getDrawable(context, drawableResId) ?: return null
    val density = context.resources.displayMetrics.density
    val targetMaxPx = (sizeDp * density).roundToInt().coerceAtLeast(1)
    val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1)
    val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1)
    val scale = targetMaxPx.toFloat() / max(intrinsicWidth, intrinsicHeight)
    val width = (intrinsicWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (intrinsicHeight * scale).roundToInt().coerceAtLeast(1)
    val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(Canvas(source))

    return try {
        source.toSdfBitmap(radiusPx = DEFAULT_SDF_RADIUS_DP * density)
    } finally {
        source.recycle()
    }
}

internal fun Bitmap.toSdfBitmap(
    radiusPx: Float,
    cutoff: Double = DEFAULT_SDF_CUTOFF,
): Bitmap {
    require(radiusPx > 0f) { "SDF radius must be positive" }
    require(cutoff in 0.0..1.0) { "SDF cutoff must be within [0, 1]" }
    val buffer = ceil(radiusPx * (1.0 - cutoff)).toInt()
    val outputWidth = width + buffer * 2
    val outputHeight = height + buffer * 2
    val pixels = IntArray(outputWidth * outputHeight)
    getPixels(
        pixels,
        buffer * outputWidth + buffer,
        outputWidth,
        0,
        0,
        width,
        height,
    )
    convertAlphaToSdf(pixels, outputWidth, radiusPx.toDouble(), cutoff)
    return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
}

/**
 * Converts the alpha channel in [pixels] to an exact Euclidean signed-distance field in place.
 * Output RGB channels are cleared because MapLibre colors SDF sprites in the symbol layer.
 */
internal fun convertAlphaToSdf(
    pixels: IntArray,
    width: Int,
    radiusPx: Double,
    cutoff: Double,
) {
    require(width > 0 && pixels.size % width == 0) { "Pixels must form a non-empty rectangle" }
    require(radiusPx > 0.0) { "SDF radius must be positive" }
    require(cutoff in 0.0..1.0) { "SDF cutoff must be within [0, 1]" }
    val height = pixels.size / width
    val outside = DoubleArray(pixels.size)
    val inside = DoubleArray(pixels.size)

    pixels.forEachIndexed { index, pixel ->
        when (val alpha = pixel ushr 24) {
            0 -> {
                outside[index] = DISTANCE_INFINITY
                inside[index] = 0.0
            }
            255 -> {
                outside[index] = 0.0
                inside[index] = DISTANCE_INFINITY
            }
            else -> {
                val distance = 0.5 - alpha.toDouble() / 255.0
                outside[index] = if (distance > 0.0) distance * distance else 0.0
                inside[index] = if (distance < 0.0) distance * distance else 0.0
            }
        }
    }

    val workspaceSize = max(width, height)
    val values = DoubleArray(workspaceSize)
    val boundaries = DoubleArray(workspaceSize + 1)
    val nearest = IntArray(workspaceSize)
    euclideanDistanceTransform(outside, width, height, values, nearest, boundaries)
    euclideanDistanceTransform(inside, width, height, values, nearest, boundaries)

    pixels.indices.forEach { index ->
        val signedDistance = sqrt(outside[index]) - sqrt(inside[index])
        val alpha = (255.0 - 255.0 * (signedDistance / radiusPx + cutoff))
            .roundToInt()
            .coerceIn(0, 255)
        pixels[index] = alpha shl 24
    }
}

private fun euclideanDistanceTransform(
    distances: DoubleArray,
    width: Int,
    height: Int,
    values: DoubleArray,
    nearest: IntArray,
    boundaries: DoubleArray,
) {
    for (x in 0 until width) {
        distanceTransform1d(distances, x, width, height, values, nearest, boundaries)
    }
    for (y in 0 until height) {
        distanceTransform1d(distances, y * width, 1, width, values, nearest, boundaries)
    }
}

private fun distanceTransform1d(
    grid: DoubleArray,
    offset: Int,
    stride: Int,
    length: Int,
    values: DoubleArray,
    nearest: IntArray,
    boundaries: DoubleArray,
) {
    nearest[0] = 0
    boundaries[0] = -DISTANCE_INFINITY
    boundaries[1] = DISTANCE_INFINITY
    values[0] = grid[offset]
    var envelopeIndex = 0

    for (position in 1 until length) {
        values[position] = grid[offset + position * stride]
        val positionSquared = position * position
        var intersection: Double
        do {
            val candidate = nearest[envelopeIndex]
            intersection = (
                values[position] - values[candidate] +
                    positionSquared - candidate * candidate
                ) / (position - candidate) / 2.0
        } while (intersection <= boundaries[envelopeIndex] && --envelopeIndex >= 0)

        envelopeIndex++
        nearest[envelopeIndex] = position
        boundaries[envelopeIndex] = intersection
        boundaries[envelopeIndex + 1] = DISTANCE_INFINITY
    }

    envelopeIndex = 0
    for (position in 0 until length) {
        while (boundaries[envelopeIndex + 1] < position) envelopeIndex++
        val candidate = nearest[envelopeIndex]
        val delta = position - candidate
        grid[offset + position * stride] = values[candidate] + delta * delta
    }
}
