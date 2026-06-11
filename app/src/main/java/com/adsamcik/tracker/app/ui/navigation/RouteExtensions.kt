package com.adsamcik.tracker.app.ui.navigation

import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
import com.adsamcik.tracker.feature.game.api.navigation.Game
import com.adsamcik.tracker.feature.map.api.navigation.Map
import com.adsamcik.tracker.feature.statistics.api.navigation.Stats

/**
 * Maps a route object to the corresponding [SettingsOrigin] for back-navigation.
 */
internal fun Any.toSettingsOrigin(): SettingsOrigin = when (this) {
    Dashboard -> SettingsOrigin.DASHBOARD
    Stats -> SettingsOrigin.STATS
    Map -> SettingsOrigin.MAP
    Game -> SettingsOrigin.GAME
    else -> SettingsOrigin.DASHBOARD
}

/**
 * Maps a [SettingsOrigin] back to the corresponding top-level route object.
 */
internal fun SettingsOrigin.toAppRoute(): Any = when (this) {
    SettingsOrigin.DASHBOARD -> Dashboard
    SettingsOrigin.STATS -> Stats
    SettingsOrigin.MAP -> Map
    SettingsOrigin.GAME -> Game
}



