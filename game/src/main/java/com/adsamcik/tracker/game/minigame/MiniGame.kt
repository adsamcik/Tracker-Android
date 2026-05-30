package com.adsamcik.tracker.game.minigame

import androidx.annotation.StringRes
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * A pluggable mini-game definition.
 *
 * Implementations are registered into Hilt via `@IntoSet` (see
 * [com.adsamcik.tracker.game.minigame.MiniGameRegistry]) and exposed to the UI
 * through [com.adsamcik.tracker.game.minigame.MiniGameRegistry.findById].
 *
 * Lifecycle:
 *  - The UI resolves a [MiniGame] from its [id] and calls [createSession]
 *    when the player taps Start. Each session is independent — implementations
 *    are stateless and may be invoked multiple times.
 *  - The session consumes GPS samples emitted at the cadence returned by
 *    [desiredLocationRequest] until the player taps Stop.
 *  - No raw samples are persisted; only the final score row reaches disk.
 *
 * Adding a new mini-game: implement this + [MiniGameSession], add a Hilt
 * binding in `MiniGameModule`. Removing one: delete its folder + remove the
 * binding line.
 */
internal interface MiniGame {
	/** Stable identifier used as a foreign-key in the scores table. */
	val id: String

	/** Display name string resource shown on the game card. */
	@get:StringRes
	val nameRes: Int

	/** Short description string resource shown on the game card. */
	@get:StringRes
	val descriptionRes: Int

	/** Player level required to unlock this game. */
	val unlockLevel: Int

	/** Create a new game session. Called when the user taps Start. */
	fun createSession(): MiniGameSession

	/**
	 * GPS update cadence this game wants from the active location source.
	 *
	 * Each game balances responsiveness against battery. Outrun needs tight
	 * 1 s fixes to render the ghost-distance gap; Territory and Zen Walk
	 * are happy with 2-5 s fixes plus a minimum displacement filter.
	 *
	 * The default profile here is a sensible mid-range request (balanced
	 * power, 2 s) so games that don't care still get reasonable behaviour
	 * — overriding implementations should explicitly state their needs.
	 */
	fun desiredLocationRequest(): LocationRequest =
		LocationRequest.Builder(DEFAULT_INTERVAL_MS)
			.setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
			.setMinUpdateIntervalMillis(DEFAULT_MIN_INTERVAL_MS)
			.setMinUpdateDistanceMeters(0f)
			.build()

	companion object {
		private const val DEFAULT_INTERVAL_MS = 2_000L
		private const val DEFAULT_MIN_INTERVAL_MS = 1_000L
	}
}
