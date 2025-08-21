package com.adsamcik.tracker.map.heatmap

/** Strategy for post-normalization shaping (blur and cutoff). */
internal interface RenderPolicy {
    /** Choose blur radius based on active coverage fraction. Return 0 for no blur. */
    fun blurRadiusFor(coverage: Float): Int

    /** Choose cutoff threshold in normalized [0,1] based on coverage. */
    fun cutoffFor(coverage: Float): Float
}

/** Default policy used when layers do not override rendering behavior. */
internal object DefaultRenderPolicy : RenderPolicy {
    override fun blurRadiusFor(coverage: Float): Int = NormalizationPolicy.blurRadiusFor(coverage)
    override fun cutoffFor(coverage: Float): Float = NormalizationPolicy.cutoffFor(coverage)
}
