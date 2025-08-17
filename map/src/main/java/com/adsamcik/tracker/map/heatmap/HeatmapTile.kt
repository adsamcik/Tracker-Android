package com.adsamcik.tracker.map.heatmap

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.base.extension.toByteArray
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants.EMPTY_COMPONENT
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants.FULL_COMPONENT
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

@ExperimentalUnsignedTypes
internal class HeatmapTile(
	val data: HeatmapTileData
) {
	private val paddedSize = data.heatmapSize + data.pad * 2

	// Accumulators for weights and alpha; we keep alpha only for merge functions, rendering uses normalized alpha
	private val weightArray: FloatArray = FloatArray(paddedSize * paddedSize)
	private val alphaArray: IntArray = IntArray(paddedSize * paddedSize) { 0 }

	private val tileCount: Int = MapFunctions.getTileCount(data.zoom)

	// Tracking min/max times for symmetric temporal kernel
	private var minTime: Long = Long.MAX_VALUE
	private var maxTime: Long = Long.MIN_VALUE
	private var medianTime: Long = 0L

	// Track max heat for diagnostics/compat
	var maxHeat: Float = 0f

	fun addAll(list: List<TimeLocation2DWeighted>) {
		if (list.isEmpty()) return
		val sortedList = list.sortedBy { it.time }
	minTime = sortedList.first().time
	maxTime = sortedList.last().time
	medianTime = sortedList[sortedList.size / 2].time
		sortedList.forEach { add(it) }
	}

	private fun add(location: TimeLocation2DWeighted) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
	// Map world position to local tile pixel coordinates without clamping.
	// Leaving values outside [0, size) ensures we only deposit when the stamp actually overlaps
	// this tile's padded buffer (seamless and no false edge contributions on far tiles).
	val dx = tx - data.x
	val dy = ty - data.y
	// If the event lies exactly on this tile's right/bottom boundary (dx or dy integer and > 0),
	// shift by a tiny epsilon toward inside so floor() aligns with the neighbor's 0 on the other side.
	fun isInt(v: Double): Boolean = kotlin.math.abs(v - kotlin.math.floor(v)) < 1e-9
	val epsPix = 1e-6 / data.heatmapSize
	val adjDx = if (dx > 0.0 && isInt(dx)) dx - epsPix else dx
	val adjDy = if (dy > 0.0 && isInt(dy)) dy - epsPix else dy
	val fx = adjDx * data.heatmapSize
	val fy = adjDy * data.heatmapSize
	val x = floor(fx).toInt() + data.pad
	val y = floor(fy).toInt() + data.pad

	val centerTime = if (maxTime >= minTime && medianTime != 0L) medianTime else location.time
		val ageInSeconds = ((location.time - centerTime) / Time.SECOND_IN_MILLISECONDS).toInt()

		val stamp = data.stampProvider?.invoke(location) ?: data.stamp
		depositPoint(x, y, ageInSeconds, location.normalizedWeight.toFloat(), stamp)

		// Optional ambient pass: apply a wider, weaker stamp to ensure continuity between sparse nodes
		if (data.ambientStampProvider != null && data.ambientWeightScale > 0f) {
			val ambientStamp = data.ambientStampProvider.invoke(location)
			if (ambientStamp.width > stamp.width || ambientStamp.height > stamp.height) {
				depositPoint(
					x,
					y,
					ageInSeconds,
					(location.normalizedWeight.toFloat() * data.ambientWeightScale),
					ambientStamp
				)
			}
		}
	}

	// Symmetric temporal kernel (Gaussian), spatial stamp deposit with per-pixel merges
	private fun depositPoint(
		x: Int,
		y: Int,
		ageInSeconds: Int,
		weight: Float,
		stamp: HeatmapStamp
	) {
		val halfStampHeight = stamp.height / 2
		val halfStampWidth = stamp.width / 2

		val x0 = if (x < halfStampWidth) halfStampWidth - x else 0
		val y0 = if (y < halfStampHeight) halfStampHeight - y else 0
		val x1 = if (x + halfStampWidth < paddedSize) stamp.width else halfStampWidth + paddedSize - x
		val y1 = if (y + halfStampHeight < paddedSize) stamp.height else halfStampHeight + paddedSize - y

		// Temporal Gaussian weight; map ageThreshold (seconds) -> sigma in seconds
		val ht = max(1, data.config.ageThreshold)
		val tNorm = ageInSeconds.toFloat() / ht.toFloat()
		val timeWeight = exp(-(tNorm * tNorm)) // exp(-(t/ht)^2)
		if (timeWeight <= 1e-6f) return

		for (itY in y0 until y1) {
			var pixIndex = (y + itY - halfStampHeight) * paddedSize + (x + x0) - halfStampWidth
			var stampIndex = itY * stamp.width + x0
			for (itX in x0 until x1) {
				val stampValue = stamp.stampData[stampIndex]
				if (stampValue > 0f) {
					val prevAlpha = alphaArray[pixIndex]
					val currentWeight = weightArray[pixIndex]
					// Compute merged weight using previous alpha, matching layer merge semantics
					val mergedWeight = data.config.weightMergeFunction(
						currentWeight,
						prevAlpha,
						stampValue,
						weight * timeWeight
					)
					// Then update alpha
					val newAlpha = data.config.alphaMergeFunction(prevAlpha, stampValue, weight * timeWeight)
						.coerceIn(EMPTY_COMPONENT, FULL_COMPONENT)
					weightArray[pixIndex] = mergedWeight
					alphaArray[pixIndex] = newAlpha
					if (data.config.dynamicHeat && mergedWeight > maxHeat) maxHeat = mergedWeight
				}
				pixIndex++
				stampIndex++
			}
		}
	}

	fun toByteArray(bitmapSize: Int, bitmapPool: com.adsamcik.tracker.map.graphics.BitmapPool? = null): ByteArray {
		// Compute coverage early to adapt percentile for sparse tiles
		val preCoverage = activeCoverage(weightArray)
		// Robust saturation: choose percentile based on zoom and sparsity
		fun percentileFor(zoom: Int, coverage: Float): Float = when {
			zoom <= 10 -> 0.90f
			coverage < 0.05f -> if (zoom <= 13) 0.92f else 0.96f
			coverage < 0.15f -> if (zoom <= 13) 0.95f else 0.98f
			else -> if (zoom <= 13) 0.95f else 0.985f
		}

		val p = percentileFor(data.zoom, preCoverage)
		val histP = estimatePercentileHist(weightArray, p)
		val nonZeroP = if (histP <= 0f) estimatePercentile(weightArray, p) else histP
		val localP = if (nonZeroP > 0f) nonZeroP else maxHeat
		var saturation = data.saturationOverride ?: localP
		val minSat = localP * 0.75f
		val maxSat = localP * 1.50f
		saturation = saturation.coerceIn(minSat, maxSat)
		if (data.config.maxHeat > 0f) {
			val step = (data.config.maxHeat * 0.02f).coerceAtLeast(1f) // 2% bins
			saturation = ceil(saturation / step) * step
		}

		// Build normalized buffer for optional smoothing
		val normalized = buildNormalizedBuffer(weightArray, saturation) { v -> data.config.valueCurve?.invoke(v) ?: v }

		// Light Gaussian blur in normalized space to connect neighboring nodes.
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

		val argb = renderFromNormalized(
			data.config.colorScheme,
			blurred,
			width = paddedSize,
			height = paddedSize,
			alphaFromNormalized = data.config.alphaFromNormalized,
			opacity = data.config.opacity
		)

		// Create bitmap and colorize
		val base = Bitmap.createBitmap(paddedSize, paddedSize, Bitmap.Config.ARGB_8888)
		base.setPixels(argb, 0, paddedSize, 0, 0, paddedSize, paddedSize)

		// Crop center, then scale to 256 tile
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

	/**
	 * Test-only helper: render to a cropped ARGB IntArray (size = heatmapSize x heatmapSize),
	 * avoiding android.graphics.Bitmap. No scaling is applied.
	 */
	internal fun renderIntArrayCroppedForTest(
		disableBlur: Boolean = false,
		cutoffOverride: Float? = null
	): IntArray {
		val preCoverage = activeCoverage(weightArray)
		fun percentileFor(zoom: Int, coverage: Float): Float = when {
			zoom <= 10 -> 0.90f
			coverage < 0.05f -> if (zoom <= 13) 0.92f else 0.96f
			coverage < 0.15f -> if (zoom <= 13) 0.95f else 0.98f
			else -> if (zoom <= 13) 0.95f else 0.985f
		}

		val p = percentileFor(data.zoom, preCoverage)
		val histP = estimatePercentileHist(weightArray, p)
		val nonZeroP = if (histP <= 0f) estimatePercentile(weightArray, p) else histP
		val localP = if (nonZeroP > 0f) nonZeroP else maxHeat
		var saturation = data.saturationOverride ?: localP
		val minSat = localP * 0.75f
		val maxSat = localP * 1.50f
		saturation = saturation.coerceIn(minSat, maxSat)
		if (data.config.maxHeat > 0f) {
			val step = (data.config.maxHeat * 0.02f).coerceAtLeast(1f)
			saturation = ceil(saturation / step) * step
		}

	val normalized = buildNormalizedBuffer(weightArray, saturation) { v -> data.config.valueCurve?.invoke(v) ?: v }
	val coverage = preCoverage
	val blurred = if (!disableBlur && coverage < 0.25f) gaussianBlur(normalized, paddedSize, paddedSize, radius = 2) else normalized

	val cutoff = cutoffOverride ?: if (coverage < 0.25f) 0.055f else 0.03f
		for (i in blurred.indices) {
			val v = blurred[i]
			if (v <= cutoff) blurred[i] = 0f else blurred[i] = ((v - cutoff) / (1f - cutoff)).coerceIn(0f, 1f)
		}

		val argbFull = renderFromNormalized(
			data.config.colorScheme,
			blurred,
			width = paddedSize,
			height = paddedSize,
			alphaFromNormalized = data.config.alphaFromNormalized,
			opacity = data.config.opacity
		)

		// Crop center region (remove padding) into output
		val outW = data.heatmapSize
		val outH = data.heatmapSize
		val out = IntArray(outW * outH)
		val pad = data.pad
		var dst = 0
		var srcRow = pad * paddedSize + pad
		for (row in 0 until outH) {
			System.arraycopy(argbFull, srcRow, out, dst, outW)
			dst += outW
			srcRow += paddedSize
		}
		return out
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

	// Estimate percentile via histogram for speed
	private fun estimatePercentileHist(buffer: FloatArray, p: Float, bins: Int = 1024): Float {
		if (buffer.isEmpty()) return 0f
		var minV = Float.POSITIVE_INFINITY
		var maxV = Float.NEGATIVE_INFINITY
		var i = 0
		while (i < buffer.size) { val v = buffer[i]; if (v < minV) minV = v; if (v > maxV) maxV = v; i++ }
		if (!minV.isFinite() || !maxV.isFinite() || minV == maxV) return maxV
		val hist = IntArray(bins)
		val scale = (bins - 1) / (maxV - minV)
		i = 0
		while (i < buffer.size) { val v = buffer[i]; val b = (((v - minV) * scale).toInt()).coerceIn(0, bins - 1); hist[b]++; i++ }
		val target = (p.coerceIn(0f,1f) * buffer.size).toInt().coerceAtLeast(1)
		var cum = 0
		var b = 0
		while (b < bins) { cum += hist[b]; if (cum >= target) return minV + b / scale; b++ }
		return maxV
	}

	private fun estimatePercentile(buffer: FloatArray, p: Float, maxSamples: Int = 2048): Float {
		if (buffer.isEmpty()) return 0f
		val total = buffer.size
		val step = (total / maxSamples).coerceAtLeast(1)
		val arr = FloatArray((total + step - 1) / step)
		var idx = 0
		var i = 0
		while (i < total) { val v = buffer[i]; if (v > 0f) { arr[idx++] = v }; i += step }
		if (idx == 0) return 0f
		java.util.Arrays.sort(arr, 0, idx)
		val pos = ((idx - 1) * p.coerceIn(0f, 1f)).toInt()
		return arr[pos]
	}

	private fun activeCoverage(buffer: FloatArray, epsilon: Float = 1e-6f): Float {
		if (buffer.isEmpty()) return 0f
		val total = buffer.size
		var active = 0
		var i = 0
		while (i < total) { if (buffer[i] > epsilon) active++; i++ }
		return active.toFloat() / total.toFloat()
	}

	private inline fun buildNormalizedBuffer(
		buffer: FloatArray,
		saturation: Float,
		transform: (Float) -> Float = { it }
	): FloatArray {
		val out = FloatArray(buffer.size)
		val sat = if (saturation > 0f) saturation else 1f
		var i = 0
		while (i < buffer.size) {
			val n = (min(buffer[i], sat) / sat)
			out[i] = transform(n)
			i++
		}
		return out
	}

	private fun renderFromNormalized(
		colorScheme: HeatmapColorScheme,
		normalized: FloatArray,
		width: Int,
		height: Int,
		alphaFromNormalized: Boolean,
		opacity: Float
	): IntArray {
		val buffer = IntArray(width * height)
		var index = 0
		val maxIdx = (colorScheme.colors.size - 1).coerceAtLeast(0)
		for (y in 0 until height) {
			for (x in 0 until width) {
				val n = normalized[index].coerceIn(0f, 1f)
				val colorId = (maxIdx * n).roundToInt().coerceIn(0, maxIdx)
				val base = colorScheme.colors[colorId]
				val a = if (alphaFromNormalized) {
					(opacity * n * FULL_COMPONENT).roundToInt().coerceIn(EMPTY_COMPONENT, FULL_COMPONENT)
				} else {
					FULL_COMPONENT
				}
				buffer[index] = (a shl 24) or (base and 0x00FFFFFF)
				index++
			}
		}
		return buffer
	}

	companion object {
		const val BASE_HEATMAP_SIZE: Int = 128
	}
}

