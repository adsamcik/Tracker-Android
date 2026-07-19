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

	/** Default setup shown when no remembered player selection exists. */
	val defaultConfiguration: MiniGameConfiguration
		get() = requireNotNull(MiniGameConfigurations.defaultFor(id)) {
			"Mini-game '$id' must declare a default configuration"
		}

	/** Every configuration accepted by [createSession]. */
	val supportedConfigurations: List<MiniGameConfiguration>
		get() = MiniGameConfigurations.supportedFor(id)

	/** Unit used to render scores and personal-best history. */
	val scoreUnit: MiniGameScoreUnit
		get() = when (id) {
			OutrunConfiguration.GAME_ID -> MiniGameScoreUnit.DISTANCE_METERS
			TerritoryConfiguration.GAME_ID -> MiniGameScoreUnit.CELL_COUNT
			ZenWalkConfiguration.GAME_ID -> MiniGameScoreUnit.DURATION_SECONDS
			FuseRunConfiguration.GAME_ID -> MiniGameScoreUnit.DEFUSAL_COUNT
			SwitchbackConfiguration.GAME_ID -> MiniGameScoreUnit.TURN_COUNT
			else -> error("Mini-game '$id' must declare its score unit")
		}

	/** Framework-neutral UI identity. Compose maps these tokens to concrete types. */
	val presentation: MiniGamePresentation
		get() = when (id) {
			OutrunConfiguration.GAME_ID -> MiniGamePresentation(
				icon = MiniGameIcon.GHOST,
				accentRole = MiniGameAccentRole.TERTIARY,
				shapeRole = MiniGameShapeRole.MOMENTUM,
			)
			TerritoryConfiguration.GAME_ID -> MiniGamePresentation(
				icon = MiniGameIcon.GRID_FLAG,
				accentRole = MiniGameAccentRole.PRIMARY,
				shapeRole = MiniGameShapeRole.TERRAIN,
			)
			ZenWalkConfiguration.GAME_ID -> MiniGamePresentation(
				icon = MiniGameIcon.PACE,
				accentRole = MiniGameAccentRole.SECONDARY,
				shapeRole = MiniGameShapeRole.WAYPOINT,
			)
			FuseRunConfiguration.GAME_ID -> MiniGamePresentation(
				icon = MiniGameIcon.FUSE,
				accentRole = MiniGameAccentRole.TERTIARY,
				shapeRole = MiniGameShapeRole.MOMENTUM,
			)
			SwitchbackConfiguration.GAME_ID -> MiniGamePresentation(
				icon = MiniGameIcon.SWITCHBACK,
				accentRole = MiniGameAccentRole.PRIMARY,
				shapeRole = MiniGameShapeRole.TERRAIN,
			)
			else -> error("Mini-game '$id' must declare presentation metadata")
		}

	/** Create a new game session. Called when the user taps Start. */
	fun createSession(): MiniGameSession

	/**
	 * Create a session for a validated setup.
	 *
	 * Compatibility bridge: current engines still implement the no-argument
	 * factory. They keep compiling while engine workstreams migrate each game to
	 * consume its typed configuration, then override this method.
	 */
	fun createSession(configuration: MiniGameConfiguration): MiniGameSession {
		require(configuration.gameId == id && configuration in supportedConfigurations) {
			"Unsupported configuration for mini-game '$id'"
		}
		return createSession()
	}

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

internal enum class MiniGameScoreUnit {
	DISTANCE_METERS,
	CELL_COUNT,
	DURATION_SECONDS,
	DEFUSAL_COUNT,
	TURN_COUNT,
}

internal data class MiniGamePresentation(
	val icon: MiniGameIcon,
	val accentRole: MiniGameAccentRole,
	val shapeRole: MiniGameShapeRole,
)

internal enum class MiniGameIcon {
	GHOST,
	GRID_FLAG,
	PACE,
	FUSE,
	SWITCHBACK,
}

internal enum class MiniGameAccentRole {
	PRIMARY,
	SECONDARY,
	TERTIARY,
}

internal enum class MiniGameShapeRole {
	MOMENTUM,
	TERRAIN,
	WAYPOINT,
}
