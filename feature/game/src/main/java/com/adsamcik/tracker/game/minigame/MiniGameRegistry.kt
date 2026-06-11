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

	/** Get all games sorted by unlock level. */
	fun allSorted(): List<MiniGame> = games.sortedBy { it.unlockLevel }
}

