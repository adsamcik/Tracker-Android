package com.adsamcik.tracker.shared.preferences.map

/**
 * Default identifier for the online tile provider. Mirrors
 * `TileProvider.DEFAULT_ID` in `:map`; duplicated here to avoid a
 * `:spreferences -> :map` dependency. Both values must stay in sync.
 */
const val DEFAULT_ONLINE_TILE_PROVIDER_ID: String = "openfreemap"

/**
 * Immutable snapshot of online map tile preferences.
 *
 * @property enabled `true` when the user has opted in to fetching map tiles
 *   from a remote provider. Default `false` (offline-only).
 * @property providerId Stable id of the active provider (matches
 *   `TileProvider.id` in `:map`). Defaults to [DEFAULT_ONLINE_TILE_PROVIDER_ID].
 * @property customUrl User-supplied style.json URL used when
 *   [providerId] == "custom". Empty for built-in providers.
 */
data class OnlineMapTilesState(
	val enabled: Boolean = false,
	val providerId: String = DEFAULT_ONLINE_TILE_PROVIDER_ID,
	val customUrl: String = "",
)
