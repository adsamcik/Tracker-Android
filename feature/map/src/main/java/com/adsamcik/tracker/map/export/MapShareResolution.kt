package com.adsamcik.tracker.map.export

import com.adsamcik.tracker.map.R
import kotlin.math.roundToInt

/**
 * User-facing resolution presets for "share map as image". Each defines a target long-edge pixel
 * count; the short edge is derived from the source viewport's aspect ratio via
 * [resolveOutputSizePx].
 *
 * Rendering goes through [MapSnapshotRenderer] (backed by MapLibre's classic `MapSnapshotter`),
 * which renders fully off-screen at the exact resolution requested — this is NOT an upscaled
 * screenshot, so [HIGH] and [ULTRA] genuinely add map/label/line detail rather than just
 * interpolating more pixels.
 */
enum class MapShareResolution(val longEdgePx: Int, val labelRes: Int) {
	STANDARD(longEdgePx = 1080, labelRes = R.string.map_share_resolution_standard),
	HIGH(longEdgePx = 2160, labelRes = R.string.map_share_resolution_high),
	ULTRA(longEdgePx = 3840, labelRes = R.string.map_share_resolution_ultra),
	;

	companion object {
		/**
		 * Hard safety cap on the long edge. MapSnapshotter renders via GLES off-screen; staying
		 * within the historical GLES2 guaranteed-minimum max texture size (4096) keeps capture
		 * reliable across low-end/emulated GPUs instead of risking an allocation failure.
		 */
		const val MAX_LONG_EDGE_PX = 4096
	}
}

/** Pixel dimensions resolved for a [MapShareResolution] request. */
data class MapShareImageSize(val widthPx: Int, val heightPx: Int)

/**
 * Resolves the output pixel dimensions for this resolution preset given the aspect ratio
 * ([widthPx] / [heightPx]) of the on-screen viewport the user was looking at, so the exported
 * image frames the same content the user saw. The long edge follows [MapShareResolution.longEdgePx]
 * (capped at [MapShareResolution.MAX_LONG_EDGE_PX]); the short edge is derived from [aspectRatio].
 */
fun MapShareResolution.resolveOutputSizePx(aspectRatio: Float): MapShareImageSize {
	val safeAspect = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio else 1f
	val longEdge = longEdgePx.coerceAtMost(MapShareResolution.MAX_LONG_EDGE_PX)
	return if (safeAspect >= 1f) {
		MapShareImageSize(
			widthPx = longEdge,
			heightPx = (longEdge / safeAspect).roundToInt().coerceAtLeast(1),
		)
	} else {
		MapShareImageSize(
			widthPx = (longEdge * safeAspect).roundToInt().coerceAtLeast(1),
			heightPx = longEdge,
		)
	}
}
