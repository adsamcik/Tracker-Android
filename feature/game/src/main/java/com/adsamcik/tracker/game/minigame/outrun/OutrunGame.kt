package com.adsamcik.tracker.game.minigame.outrun

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import javax.inject.Inject

/**
 * Ghost race: a virtual ghost runs at a constant pace. Stay ahead.
 * Score = max distance ahead of ghost.
 *
 * Requests HIGH_ACCURACY fixes at ~1 s so the gap-to-ghost number on screen
 * updates smoothly; sub-second cadence would burn battery without changing
 * what the player perceives.
 */
internal class OutrunGame @Inject constructor() : MiniGame {
	override val id: String = "outrun"
	override val nameRes: Int = R.string.minigame_outrun_name
	override val descriptionRes: Int = R.string.minigame_outrun_description
	override val unlockLevel: Int = 3

	override fun createSession(): MiniGameSession = OutrunSession()

	override fun desiredLocationRequest(): LocationRequest =
		LocationRequest.Builder(INTERVAL_MS)
			.setPriority(Priority.PRIORITY_HIGH_ACCURACY)
			.setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
			.setMinUpdateDistanceMeters(0f)
			.build()

	private companion object {
		private const val INTERVAL_MS = 1_000L
		private const val MIN_INTERVAL_MS = 500L
	}
}
