package com.adsamcik.tracker.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute

@Serializable
data object Dashboard : AppRoute

@Serializable
data object Setup : AppRoute

@Serializable
data object Stats : AppRoute

@Serializable
data object Map : AppRoute

@Serializable
data class MapTripContext(
    val tripId: Long,
    val startMs: Long,
    val endMs: Long,
) : AppRoute

@Serializable
data object Game : AppRoute

@Serializable
data object TrophyCase : AppRoute

@Serializable
data class TripDetail(val tripId: Long) : AppRoute

@Serializable
data object History : AppRoute

@Serializable
data object Debug : AppRoute

@Serializable
data class Settings(
    val origin: SettingsOrigin = SettingsOrigin.DASHBOARD,
    val nonce: Long = 0L,
    val section: SettingsSection = SettingsSection.ROOT,
) : AppRoute

@Serializable
data object About : AppRoute

@Serializable
enum class SettingsOrigin {
    DASHBOARD,
    STATS,
    MAP,
    GAME,
}

@Serializable
enum class SettingsSection {
    ROOT,
    DATA,
}

@Serializable
data object ActivitySettings : AppRoute

@Serializable
data object NotificationManagement : AppRoute
