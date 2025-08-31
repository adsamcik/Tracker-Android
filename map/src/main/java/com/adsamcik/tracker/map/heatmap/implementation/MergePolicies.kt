package com.adsamcik.tracker.map.heatmap.implementation

/** Common reusable merge policies for heatmaps. */
object MergePolicies {
    // Weight merges
    val additive: WeightMergeFunction = { current, _, stampValue, value -> current + stampValue * value }

    val maximum: WeightMergeFunction = { current, _, _, value -> kotlin.math.max(current, value) }

    /** Lerp new into current using existing alpha as blend factor (0..255). */
    val alphaLerp: WeightMergeFunction = { current, currentAlpha, _, newValue ->
        val a = (currentAlpha.coerceIn(0, 255)) / 255f
        (newValue * (1f - a)) + (current * a)
    }

    // Alpha merges
    /** Max of current alpha and stamp contribution. */
    val alphaMax: AlphaMergeFunction = { current, stampValue, _ ->
        val newAlpha = (stampValue * 255f).toInt()
        kotlin.math.max(current, newAlpha).coerceIn(0, 255)
    }

    /** Porter-Duff Over-like compositing for alpha: 1 - (1-a)*(1-sv*w). */
    val alphaOver: AlphaMergeFunction = { current, stampValue, weight ->
        val a = (current.coerceIn(0, 255)) / 255f
        val out = 1f - (1f - a) * (1f - stampValue * weight.coerceIn(0f, 1f))
        (out * 255f).toInt().coerceIn(0, 255)
    }

    /** Normalized alpha average: (currentAlpha + stampValue*weight) / 2, operating in [0,1] domain. */
    val alphaAverageNormalized: AlphaMergeFunction = { current, stampValue, weight ->
        val currentNorm = (current.coerceIn(0, 255)) / 255f
        val stampContrib = (stampValue * weight.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val avgNorm = (currentNorm + stampContrib) / 2f
        (avgNorm * 255f).toInt().coerceIn(0, 255)
    }

    /** Normalized alpha average: (currentAlpha + stampValue) / 2, operating in [0,1] domain. */
    val alphaAverageStampNormalized: AlphaMergeFunction = { current, stampValue, _ ->
        val currentNorm = (current.coerceIn(0, 255)) / 255f
        val stampNorm = stampValue.coerceIn(0f, 1f)
        val avgNorm = (currentNorm + stampNorm) / 2f
        (avgNorm * 255f).toInt().coerceIn(0, 255)
    }

    /** Average of current alpha (0..255) and stampValue*weight (0..1) without rescaling; preserves historical behavior. */
    // Removed deprecated legacy variants (alphaAverageWeighted, alphaAverageStamp) as unused.

    /** Alpha equals stampValue scaled by a normalized weight (weight/divisor), both 0..1, mapped to 0..255. */
    fun alphaScaledByWeightDiv(divisor: Float): AlphaMergeFunction = { _, stampValue, weight ->
        val normalizedWeight = (weight / divisor).coerceIn(0f, 1f)
        val b = (stampValue * normalizedWeight).coerceIn(0f, 1f)
        (b * 255f).toInt().coerceIn(0, 255)
    }
}
