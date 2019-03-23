package com.adsamcik.signalcollector.extensions

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

fun Bitmap.toByteArray(): ByteArray {
	val stream = ByteArrayOutputStream()
	compress(Bitmap.CompressFormat.PNG, 100, stream)
	return stream.toByteArray()
}