package com.adsamcik.tracker.map.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.roundToInt

/**
 * Converts a vector drawable to a Bitmap suitable for use as a MapLibre symbol image.
 */
fun bitmapFromVector(
    context: Context,
    @DrawableRes drawableResId: Int,
    scale: Float = 1f
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
