package com.adsamcik.tracker.map.heatmap.engine

/** Minimal Lanczos-3 downsampler on ARGB buffers. */
object Lanczos {
    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else kotlin.math.sin(Math.PI * x) / (Math.PI * x)
    private fun lanczos(x: Double, a: Int = 3): Double = if (kotlin.math.abs(x) < a) sinc(x) * sinc(x / a) else 0.0

    fun downsample(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (dw == sw && dh == sh) return src.copyOf()
        val tmp = IntArray(dw * sh)
        val out = IntArray(dw * dh)
        val sx = sw.toDouble() / dw
        val sy = sh.toDouble() / dh
        val a = 3
        // Horizontal
        for (y in 0 until sh) {
            for (x in 0 until dw) {
                val cx = (x + 0.5) * sx
                var aw = 0.0
                var r = 0.0; var g = 0.0; var b = 0.0; var aAcc = 0.0
                val ix0 = kotlin.math.floor(cx - a).toInt()
                val ix1 = kotlin.math.ceil(cx + a).toInt()
                for (ix in ix0..ix1) {
                    val sxClamped = ix.coerceIn(0, sw - 1)
                    val w = lanczos(cx - (ix + 0.5), a)
                    val c = src[y * sw + sxClamped]
                    val ca = (c ushr 24) and 0xFF
                    val cr = (c ushr 16) and 0xFF
                    val cg = (c ushr 8) and 0xFF
                    val cb = c and 0xFF
                    aAcc += ca * w
                    r += cr * w; g += cg * w; b += cb * w; aw += w
                }
                val ia = if (aw != 0.0) (aAcc / aw).coerceIn(0.0,255.0) else 0.0
                val ir = if (aw != 0.0) (r / aw).coerceIn(0.0,255.0) else 0.0
                val ig = if (aw != 0.0) (g / aw).coerceIn(0.0,255.0) else 0.0
                val ib = if (aw != 0.0) (b / aw).coerceIn(0.0,255.0) else 0.0
                tmp[y * dw + x] = (ia.toInt() shl 24) or (ir.toInt() shl 16) or (ig.toInt() shl 8) or ib.toInt()
            }
        }
        // Vertical
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                val cy = (y + 0.5) * sy
                var aw = 0.0
                var r = 0.0; var g = 0.0; var b = 0.0; var aAcc = 0.0
                val iy0 = kotlin.math.floor(cy - a).toInt()
                val iy1 = kotlin.math.ceil(cy + a).toInt()
                for (iy in iy0..iy1) {
                    val syClamped = iy.coerceIn(0, sh - 1)
                    val w = lanczos(cy - (iy + 0.5), a)
                    val c = tmp[syClamped * dw + x]
                    val ca = (c ushr 24) and 0xFF
                    val cr = (c ushr 16) and 0xFF
                    val cg = (c ushr 8) and 0xFF
                    val cb = c and 0xFF
                    aAcc += ca * w
                    r += cr * w; g += cg * w; b += cb * w; aw += w
                }
                val ia = if (aw != 0.0) (aAcc / aw).coerceIn(0.0,255.0) else 0.0
                val ir = if (aw != 0.0) (r / aw).coerceIn(0.0,255.0) else 0.0
                val ig = if (aw != 0.0) (g / aw).coerceIn(0.0,255.0) else 0.0
                val ib = if (aw != 0.0) (b / aw).coerceIn(0.0,255.0) else 0.0
                out[y * dw + x] = (ia.toInt() shl 24) or (ir.toInt() shl 16) or (ig.toInt() shl 8) or ib.toInt()
            }
        }
        return out
    }
}
