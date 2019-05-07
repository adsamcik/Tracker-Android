package com.adsamcik.signalcollector.common.misc.extension

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

fun Bitmap.toByteArray(): ByteArray {
	val stream = ByteArrayOutputStream()
	compress(Bitmap.CompressFormat.PNG, 100, stream)
	return stream.toByteArray()
}