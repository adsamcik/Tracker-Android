package com.adsamcik.tracker.game.minigame

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds all registered mini-games from Hilt @IntoSet multibinding.
 */
@Singleton
internal class MiniGameRegistry @Inject constructor(
	val games: Set<@JvmSuppressWildcards MiniGame>,
) {
	/** Get game by ID, or null if not found. */
	fun findById(id: String): MiniGame? = games.find { it.id == id }

	/** Get all games in deterministic unlock order. */
	fun allSorted(): List<MiniGame> = games.sortedWith(
		compareBy<MiniGame> { it.unlockLevel }.thenBy { it.id },
	)
}
