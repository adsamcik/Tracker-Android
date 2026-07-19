package com.adsamcik.tracker.shared.base.database.entity

/**
 * Identity-bearing Wi-Fi observation projection used by radio-aware map visualizations.
 */
data class GeoWifiRadioFeatureEntity(
	val lat: Double,
	val lon: Double,
	val time: Long,
	val bssid: String,
	val level: Int,
	val frequency: Int,
)
