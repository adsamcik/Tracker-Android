package com.adsamcik.tracker.map.ui

import android.graphics.Bitmap
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitmapUtilsTest {

	@Test
	fun `SDF conversion preserves a visible center and clears RGB channels`() {
		val width = 9
		val pixels = IntArray(width * width)
		pixels[4 * width + 4] = 0xFFFFFFFF.toInt()

		convertAlphaToSdf(pixels, width, radiusPx = 2.0, cutoff = 0.25)

		alpha(pixels[4 * width + 4]) shouldBeGreaterThan alpha(pixels.first())
		alpha(pixels[4 * width + 4]) shouldBeGreaterThan 0
		pixels.all { it and 0x00FFFFFF == 0 } shouldBe true
	}

	@Test
	fun `bitmap conversion returns a buffered precomputed SDF sprite`() {
		val source = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
		source.setPixel(2, 2, 0xFFFFFFFF.toInt())

		val bitmap = source.toSdfBitmap(radiusPx = 2f)
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

		bitmap.width shouldBeGreaterThan source.width
		pixels.count { alpha(it) > 0 } shouldBeGreaterThan 0
		pixels.count { alpha(it) in 1..254 } shouldBeGreaterThan 0
		pixels.all { it and 0x00FFFFFF == 0 } shouldBe true
		source.recycle()
		bitmap.recycle()
	}

	private fun alpha(argb: Int): Int = argb ushr 24
}
