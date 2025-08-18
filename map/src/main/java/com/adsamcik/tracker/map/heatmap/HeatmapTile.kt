package com.adsamcik.tracker.map.heatmap

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.base.extension.toByteArray
import kotlin.math.roundToInt

@ExperimentalUnsignedTypes
internal class HeatmapTile(
	val data: HeatmapTileData
) {
    private val paddedSize = data.heatmapSize + data.pad * 2
	private val heatmap = AgeWeightedHeatmap(
		paddedSize,
		paddedSize,
		data.config.ageThreshold,
		data.config.maxHeat,
		data.config.dynamicHeat,
		data.config.revisitIntervalSec,
		data.config.revisitEasing,
		data.config.revisitEasingStrength
	)

	private val tileCount: Int = MapFunctions.getTileCount(data.zoom)

	var maxHeat: Float
		get() = heatmap.maxHeat
		set(value) {
			heatmap.maxHeat = value
		}

	fun addAll(list: List<TimeLocation2DWeighted>) {
		if (list.isEmpty()) return

		val sortedList = list.sortedBy { it.time }
		val minTime = sortedList.first().time
		sortedList.forEach { add(it, minTime) }
	}

	fun add(location: TimeLocation2DWeighted, minTime: Long) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
	val x = ((tx - data.x) * data.heatmapSize).roundToInt() + data.pad
	val y = ((ty - data.y) * data.heatmapSize).roundToInt() + data.pad

		val ageInSeconds = ((location.time - minTime) / Time.SECOND_IN_MILLISECONDS).toInt()

		val stamp = data.stampProvider?.invoke(location) ?: data.stamp

		heatmap.addPoint(
				x,
				y,
				ageInSeconds,
				location.normalizedWeight.toFloat(),
				stamp,
				data.config.weightMergeFunction,
				data.config.alphaMergeFunction
		)

		// Optional ambient pass: apply a wider, weaker stamp to ensure continuity between sparse nodes
		if (data.ambientStampProvider != null && data.ambientWeightScale > 0f) {
			val ambientStamp = data.ambientStampProvider.invoke(location)
			if (ambientStamp.width > stamp.width || ambientStamp.height > stamp.height) {
				heatmap.addPoint(
					x,
					y,
					ageInSeconds,
					(location.normalizedWeight.toFloat() * data.ambientWeightScale),
					ambientStamp,
					data.config.weightMergeFunction,
					data.config.alphaMergeFunction
				)
			}
		}
	}


	fun toByteArray(bitmapSize: Int, bitmapPool: com.adsamcik.tracker.map.graphics.BitmapPool? = null): ByteArray {
		// Compute coverage early to adapt percentile for sparse tiles
		val preCoverage = heatmap.activeCoverage()
		// Robust saturation: choose percentile based on zoom and sparsity
		fun percentileFor(zoom: Int, coverage: Float): Float = when {
			zoom <= 10 -> 0.90f
			coverage < 0.05f -> if (zoom <= 13) 0.92f else 0.96f
			coverage < 0.15f -> if (zoom <= 13) 0.95f else 0.98f
			else -> if (zoom <= 13) 0.95f else 0.985f
		}

		val p = percentileFor(data.zoom, preCoverage)
		val histP = heatmap.estimatePercentileHist(p)
		// Fallback to non-zero percentile to avoid using maxHeat in sparse tiles (which makes colors too faint)
		val nonZeroP = if (histP <= 0f) heatmap.estimatePercentile(p) else histP
		val localP = if (nonZeroP > 0f) nonZeroP else heatmap.maxHeat
	var saturation = data.saturationOverride ?: localP
	// Guardrails: keep override within a sane band relative to local percentile
	val minSat = localP * 0.75f
	val maxSat = localP * 1.50f
	saturation = saturation.coerceIn(minSat, maxSat)
		// Quantize saturation slightly to reduce cross-tile tone seams
	if (data.config.maxHeat > 0f) {
			val step = (data.config.maxHeat * 0.02f).coerceAtLeast(1f) // 2% bins
			saturation = kotlin.math.ceil(saturation / step) * step
		}

	// Build normalized buffer for optional smoothing
	val normalized = heatmap.buildNormalizedBuffer(saturation) { data.config.valueCurve?.invoke(it) ?: it }

	// Light Gaussian blur in normalized space to connect neighboring nodes.
	// Skip blur for dense tiles to preserve detail; apply for sparse tiles to avoid "beads".
	val coverage = preCoverage
		val blurred = if (coverage < 0.25f) gaussianBlur(normalized, paddedSize, paddedSize, radius = 2) else normalized

		// Soft floor to remove faint haze that makes tile squares visible
		val cutoff = if (coverage < 0.25f) 0.055f else 0.03f
		for (i in blurred.indices) {
			val v = blurred[i]
			if (v <= cutoff) {
				blurred[i] = 0f
			} else {
				blurred[i] = ((v - cutoff) / (1f - cutoff)).coerceIn(0f, 1f)
			}
		}

		val array = heatmap.renderFromNormalized(
			data.config.colorScheme,
			blurred,
			alphaFromNormalized = data.config.alphaFromNormalized,
			opacity = data.config.opacity
		)

	// Acquire or create padded base bitmap and copy pixels
	val base = Bitmap.createBitmap(paddedSize, paddedSize, Bitmap.Config.ARGB_8888)
	base.setPixels(array, 0, paddedSize, 0, 0, paddedSize, paddedSize)

		// Crop the center (remove padding) to the target heatmap size
		val cropped = Bitmap.createBitmap(base, data.pad, data.pad, data.heatmapSize, data.heatmapSize)
		val scaled = if (data.heatmapSize != bitmapSize) {
			// Use bilinear filtering when scaling to avoid blocky artifacts
			cropped.scale(bitmapSize, bitmapSize, true)
		} else cropped

		val bytes = scaled.toByteArray()
		if (scaled !== cropped) scaled.recycle()
		cropped.recycle()
		base.recycle()
		return bytes
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

