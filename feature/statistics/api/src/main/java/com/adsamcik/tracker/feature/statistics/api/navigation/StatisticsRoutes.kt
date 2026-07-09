package com.adsamcik.tracker.feature.statistics.api.navigation

import kotlinx.serialization.Serializable

@Serializable
data object Stats

@Serializable
data object StatsSummary

@Serializable
data object StatsWifi

@Serializable
data object StatsSignalReport

@Serializable
data class TripDetail(val tripId: Long)

@Serializable
data object History
