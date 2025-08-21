package com.adsamcik.tracker.map.heatmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.scale
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.base.extension.toByteArray
import kotlin.math.roundToInt
import kotlin.math.floor
 

@ExperimentalUnsignedTypes
internal class HeatmapTile(
	val data: HeatmapTileData
) {
	private val renderPolicy: RenderPolicy = data.renderPolicy ?: DefaultRenderPolicy
    private val paddedSize = data.heatmapSize + data.pad * 2
	private val heatmap = AgeWeightedHeatmap(
		paddedSize,
		paddedSize,
		data.config.ageThreshold,
		data.config.maxHeat,
		data.config.dynamicHeat
	)

	private val tileCount: Int = MapFunctions.getTileCount(data.zoom)

	// Cache render normalization so multiple outputs (IntArray, ByteArray) don't recompute
	private data class RenderContext(val adjusted: FloatArray, val coverage: Float, val saturation: Float)
	private var renderContext: RenderContext? = null

	var maxHeat: Float
		get() = heatmap.maxHeat
		set(value) {
			heatmap.maxHeat = value
		}

	fun addAll(list: List<TimeLocation2DWeighted>) {
		if (list.isEmpty()) return
	renderContext = null

		val sortedList = list.sortedBy { it.time }
		val minTime = sortedList.first().time
		sortedList.forEach { add(it, minTime) }
	}

	// Accepts a list already sorted by time (ascending); avoids redundant sorting
	fun addAllSorted(sortedByTime: List<TimeLocation2DWeighted>) {
		if (sortedByTime.isEmpty()) return
		val minTime = sortedByTime.first().time
		sortedByTime.forEach { add(it, minTime) }
	}

	fun add(location: TimeLocation2DWeighted, minTime: Long) {
	renderContext = null
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
	// Seam-safe binning: prefer floor with a tiny epsilon to avoid border double-hits
	val eps = 1e-6
	val x = floor(((tx - data.x) * data.heatmapSize) - eps).toInt() + data.pad
	val y = floor(((ty - data.y) * data.heatmapSize) - eps).toInt() + data.pad

		val ageInSeconds = ((location.time - minTime) / Time.SECOND_IN_MILLISECONDS).toInt()

		val stamp = data.stampProvider?.invoke(location) ?: data.stamp

		val weightPolicy = data.config.weightPolicy
		val effectiveWeight = if (weightPolicy != null) {
			val base = location.normalizedWeight.toFloat()
			weightPolicy(base, heatmap, x, y, ageInSeconds)
		} else location.normalizedWeight.toFloat()

		heatmap.addPoint(
			x,
			y,
			ageInSeconds,
			effectiveWeight,
			stamp,
			data.config.weightMergeFunction,
			data.config.alphaMergeFunction
		)

		// Optional ambient pass: apply a wider, weaker stamp to ensure continuity between sparse nodes
		if (data.ambientStampProvider != null && data.ambientWeightScale > 0f) {
			val ambientStamp = data.ambientStampProvider.invoke(location)
			if (ambientStamp.width > stamp.width || ambientStamp.height > stamp.height) {
				val effAmbientW = (effectiveWeight * data.ambientWeightScale)
				heatmap.addPoint(
					x,
					y,
					ageInSeconds,
					effAmbientW,
					ambientStamp,
					data.config.weightMergeFunction,
					data.config.alphaMergeFunction
				)
			}
		}
	}

	// Policy previously embedded in engine moved out; if needed, use WeightPolicy in HeatmapConfig.



	private fun computeAdjustedNormalized(): Pair<FloatArray, Float> {
		renderContext?.let { return it.adjusted to it.coverage }

		val preCoverage = heatmap.activeCoverage()
		val p = NormalizationPolicy.percentileFor(data.zoom, preCoverage)
		val localP = NormalizationPolicy.robustPercentile(heatmap, p)
		val saturation = NormalizationPolicy.chooseSaturation(localP, data.saturationOverride)

		val normalized = heatmap.buildNormalizedBuffer(saturation) { data.config.valueCurve?.invoke(it) ?: it }
		val coverage = preCoverage
		val blurRadius = renderPolicy.blurRadiusFor(coverage)
		val blurred = if (blurRadius > 0) gaussianBlur(normalized, paddedSize, paddedSize, radius = blurRadius) else normalized
		val cutoff = renderPolicy.cutoffFor(coverage)
		val adjusted: FloatArray = run {
			var anyNonZero = false
			val out = FloatArray(blurred.size)
			for (i in blurred.indices) {
				val v = blurred[i]
				if (v <= cutoff) {
					out[i] = 0f
				} else {
					val vv = ((v - cutoff) / (1f - cutoff)).coerceIn(0f, 1f)
					out[i] = vv
					if (vv > 0f) anyNonZero = true
				}
			}
			if (anyNonZero) out else blurred
		}
		renderContext = RenderContext(adjusted, coverage, saturation)
		return adjusted to coverage
	}

	internal fun buildCroppedColorArray(): IntArray {
		val (adjusted, _) = computeAdjustedNormalized()
		val full = heatmap.renderFromNormalized(
			data.config.colorScheme,
			adjusted,
			alphaFromNormalized = data.config.alphaFromNormalized,
			opacity = data.config.opacity
		)
		// Crop to center window (remove pad)
		val out = IntArray(data.heatmapSize * data.heatmapSize)
		var di = 0
		for (y in 0 until data.heatmapSize) {
			val sy = y + data.pad
			val si = sy * paddedSize + data.pad
			for (x in 0 until data.heatmapSize) {
				out[di++] = full[si + x]
			}
		}
		return out
	}

	fun toByteArray(bitmapSize: Int, bitmapPool: com.adsamcik.tracker.map.graphics.BitmapPool? = null): ByteArray {
		val (adjusted, _) = computeAdjustedNormalized()
		val array = heatmap.renderFromNormalized(
			data.config.colorScheme,
			adjusted,
			alphaFromNormalized = data.config.alphaFromNormalized,
			opacity = data.config.opacity
		)

		// If no pool provided, use the original allocation path
		if (bitmapPool == null) {
			val base = Bitmap.createBitmap(paddedSize, paddedSize, Bitmap.Config.ARGB_8888)
			base.setPixels(array, 0, paddedSize, 0, 0, paddedSize, paddedSize)
			val cropped = Bitmap.createBitmap(base, data.pad, data.pad, data.heatmapSize, data.heatmapSize)
			val scaled = if (data.heatmapSize != bitmapSize) {
				cropped.scale(bitmapSize, bitmapSize, true)
			} else cropped
			val bytes = scaled.toByteArray()
			if (scaled !== cropped) scaled.recycle()
			cropped.recycle()
			base.recycle()
			return bytes
		}

		// Pooled path: draw into pooled bitmaps using Canvas to avoid region-created bitmaps
		val pool = bitmapPool
		val base = pool.acquire(paddedSize, paddedSize, Bitmap.Config.ARGB_8888)
		base.setPixels(array, 0, paddedSize, 0, 0, paddedSize, paddedSize)

		val srcRect = Rect(data.pad, data.pad, data.pad + data.heatmapSize, data.pad + data.heatmapSize)
		val paint = Paint(Paint.FILTER_BITMAP_FLAG)

		return if (data.heatmapSize == bitmapSize) {
			val out = pool.acquire(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
			Canvas(out).drawBitmap(base, srcRect, Rect(0, 0, bitmapSize, bitmapSize), paint)
			val bytes = out.toByteArray()
			pool.release(out)
			pool.release(base)
			bytes
		} else {
			val tmp = pool.acquire(data.heatmapSize, data.heatmapSize, Bitmap.Config.ARGB_8888)
			Canvas(tmp).drawBitmap(base, srcRect, Rect(0, 0, data.heatmapSize, data.heatmapSize), null)
			val out = pool.acquire(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
			Canvas(out).drawBitmap(tmp, Rect(0, 0, data.heatmapSize, data.heatmapSize), Rect(0, 0, bitmapSize, bitmapSize), paint)
			val bytes = out.toByteArray()
			pool.release(out)
			pool.release(tmp)
			pool.release(base)
			bytes
		}
	}

	// Simple separable Gaussian blur on a float buffer
	private fun gaussianBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
		if (radius <= 0) return src
		val sigma = radius / 1.5f
		val kernelRadius = radius
		val kernelSize = kernelRadius * 2 + 1
		val kernel = FloatArray(kernelSize)
		var sum = 0f
		for (i in -kernelRadius..kernelRadius) {
			val v = kotlin.math.exp(-(i * i) / (2f * sigma * sigma))
			kernel[i + kernelRadius] = v
			sum += v
		}
		for (i in 0 until kernelSize) kernel[i] /= sum

		val tmp = FloatArray(w * h)
		val out = FloatArray(w * h)

		// Horizontal
		for (y in 0 until h) {
			val row = y * w
			for (x in 0 until w) {
				var acc = 0f
				var k = 0
				var xi = x - kernelRadius
				while (k < kernelSize) {
					val xc = xi.coerceIn(0, w - 1)
					acc += src[row + xc] * kernel[k]
					k++; xi++
				}
				tmp[row + x] = acc
			}
		}
		// Vertical
		for (x in 0 until w) {
			for (y in 0 until h) {
				var acc = 0f
				var k = 0
				var yi = y - kernelRadius
				while (k < kernelSize) {
					val yc = yi.coerceIn(0, h - 1)
					acc += tmp[yc * w + x] * kernel[k]
					k++; yi++
				}
				out[y * w + x] = acc
			}
		}
		return out
	}

	companion object {
		const val BASE_HEATMAP_SIZE: Int = 128
	}
}

