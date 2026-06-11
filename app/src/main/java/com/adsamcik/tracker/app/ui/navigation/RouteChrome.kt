package com.adsamcik.tracker.app.ui.navigation

import kotlin.reflect.KClass
import com.adsamcik.tracker.feature.statistics.api.navigation.History
import com.adsamcik.tracker.feature.game.api.navigation.Achievements
import com.adsamcik.tracker.feature.game.api.navigation.MiniGameScores
import com.adsamcik.tracker.feature.game.api.navigation.MiniGameSession
import com.adsamcik.tracker.feature.statistics.api.navigation.TripDetail

/**
 * Routes that should hide the top-level navigation chrome (bottom-nav pill,
 * side-rail, global TopAppBar overlay) when active.
 *
 * Use this list as the single source of truth — `MainRoot` consults it instead
 * of hard-coding `destination.hasRoute<...>()` for each chromeless route. When
 * adding a new full-screen destination (setup flow, settings page, mini-game
 * session, etc.), add its route class here. When removing one, delete the
 * entry — the chrome will reappear on its own.
 *
 * Routes NOT listed here keep the top-level chrome visible, so the four
 * top-level tabs (Dashboard, Stats, Map, Game) don't need entries.
 */
internal val ROUTES_HIDING_TOP_LEVEL_CHROME: List<KClass<*>> = listOf(
	Setup::class,
	Settings::class,
	Debug::class,
	ActivitySettings::class,
	TripDetail::class,
	History::class,
	Achievements::class,
	MiniGameSession::class,
	MiniGameScores::class,
)



