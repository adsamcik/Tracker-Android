package com.adsamcik.tracker.map.heatmap

/**
 * User preference tile data
 * @param maxHeat Maximum heat
 * @param ageThreshold Age threshold
 * @param quality Quality
 */
data class UserHeatmapData(
		val maxHeat: Float,
		val ageThreshold: Int,
		val quality: Float
)
