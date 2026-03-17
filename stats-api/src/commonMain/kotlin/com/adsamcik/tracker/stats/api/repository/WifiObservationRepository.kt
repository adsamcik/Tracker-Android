package com.adsamcik.tracker.stats.api.repository

import arrow.core.Either
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.value.EpochMs

const val DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT = 1_000

data class WifiObservationBrowseFilter(
	val bssid: String? = null,
	val ssid: String? = null,
	val capabilities: String? = null,
	val frequencyPrefix: String? = null,
	val limit: Int = DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT,
)

data class WifiObservationBrowseItem(
	val bssid: String,
	val ssid: String,
	val capabilities: String,
	val frequency: Int,
	val firstSeenAt: EpochMs,
	val lastSeenAt: EpochMs,
)

data class WifiObservationStatsSummary(
	val uniqueNetworks: Long,
	val totalScans: Long,
	val averageNetworksPerScan: Double,
)

interface WifiObservationRepository {
	suspend fun getBrowseItems(
		filter: WifiObservationBrowseFilter,
	): Either<StatsError, List<WifiObservationBrowseItem>>

	suspend fun getStatsSummary(): Either<StatsError, WifiObservationStatsSummary>
}
