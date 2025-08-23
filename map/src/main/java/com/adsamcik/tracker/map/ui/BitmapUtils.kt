package com.adsamcik.tracker.map.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlin.math.roundToInt

fun bitmapDescriptorFromVector(
    context: Context,
    @DrawableRes drawableResId: Int,
    scale: Float = 1f
): BitmapDescriptor {
    val drawable = AppCompatResources.getDrawable(context, drawableResId)
        ?: return BitmapDescriptorFactory.defaultMarker()

    val width = (drawable.intrinsicWidth * scale).coerceAtLeast(1f).roundToInt()
    val height = (drawable.intrinsicHeight * scale).coerceAtLeast(1f).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
