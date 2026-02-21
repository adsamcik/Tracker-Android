package com.adsamcik.tracker.game.minigame

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Interface for pluggable mini-games. Each implementation is registered via Hilt @IntoSet.
 * Adding a game: implement this + MiniGameSession, add Hilt binding.
 * Removing: delete folder + remove Hilt line.
 */
interface MiniGame {
	/** Unique stable identifier for this game (used in scores table). */
	val id: String

	/** Display name string resource. */
	@get:StringRes
	val nameRes: Int

	/** Short description string resource. */
	@get:StringRes
	val descriptionRes: Int

	/** Player level required to unlock this game. */
	val unlockLevel: Int

	/** Create a new game session. Called when the user starts a tracking session with this game active. */
	fun createSession(): MiniGameSession
}
