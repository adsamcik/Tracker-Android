package com.adsamcik.tracker.game.minigame.zenwalk

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import javax.inject.Inject

/**
 * Keep your pace steady in the zone. Score = total seconds in zone.
 *
 * Zen Walk integrates speed over time and EMA-smooths samples, so a sparse
 * BALANCED 5 s cadence is plenty. A 10 m displacement filter cuts wake-ups
 * when the player stops at a crossing without losing zone-time accounting.
 */
class ZenWalkGame @Inject constructor() : MiniGame {
	override val id: String = "zenwalk"
	override val nameRes: Int = R.string.minigame_zenwalk_name
	override val descriptionRes: Int = R.string.minigame_zenwalk_description
	override val unlockLevel: Int = 9

	override fun createSession(): MiniGameSession = ZenWalkSession()

	override fun desiredLocationRequest(): LocationRequest =
		LocationRequest.Builder(INTERVAL_MS)
			.setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
			.setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
			.setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
			.build()

	private companion object {
		private const val INTERVAL_MS = 5_000L
		private const val MIN_INTERVAL_MS = 3_000L
		private const val MIN_DISPLACEMENT_M = 10f
	}
}
