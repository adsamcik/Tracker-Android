package com.adsamcik.tracker.app.ui.navigation

import kotlinx.serialization.Serializable

interface AppRoute

@Serializable
data object Onboarding : AppRoute

@Serializable
data object Setup : AppRoute

@Serializable
data object Game : AppRoute

@Serializable
data class MiniGameSession(val gameId: String) : AppRoute

@Serializable
data object MiniGameScores : AppRoute

@Serializable
data object Achievements : AppRoute

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


