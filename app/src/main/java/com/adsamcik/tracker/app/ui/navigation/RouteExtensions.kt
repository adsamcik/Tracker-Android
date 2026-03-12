package com.adsamcik.tracker.app.ui.navigation

/**
 * Maps an [AppRoute] to the corresponding [SettingsOrigin] for back-navigation.
 */
internal fun AppRoute.toSettingsOrigin(): SettingsOrigin = when (this) {
    Dashboard -> SettingsOrigin.DASHBOARD
    Stats -> SettingsOrigin.STATS
    Map -> SettingsOrigin.MAP
    Game -> SettingsOrigin.GAME
    else -> SettingsOrigin.DASHBOARD
}

/**
 * Maps a [SettingsOrigin] back to the corresponding top-level [AppRoute].
 */
internal fun SettingsOrigin.toAppRoute(): AppRoute = when (this) {
    SettingsOrigin.DASHBOARD -> Dashboard
    SettingsOrigin.STATS -> Stats
    SettingsOrigin.MAP -> Map
    SettingsOrigin.GAME -> Game
}
