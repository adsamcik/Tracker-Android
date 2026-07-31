package com.adsamcik.tracker.app.ui.navigation

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

interface AppRoute

@Serializable
data object Onboarding : AppRoute

@Serializable
data object Setup : AppRoute

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
@Keep
enum class SettingsOrigin {
    DASHBOARD,
    STATS,
    MAP,
    GAME,
}

@Serializable
@Keep
enum class SettingsSection {
    ROOT,
    DATA,
}

@Serializable
data object ActivitySettings : AppRoute

@Serializable
data object NotificationManagement : AppRoute


