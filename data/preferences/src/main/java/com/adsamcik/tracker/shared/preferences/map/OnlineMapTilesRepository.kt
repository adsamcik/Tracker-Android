package com.adsamcik.tracker.shared.preferences.map

import kotlinx.coroutines.flow.Flow

/**
 * Repository boundary for online map tile preferences.
 *
 * # Privacy contract
 *
 * Defaults are OFF + the safest built-in provider. Calling [setEnabled] with
 * `true` is the only way traffic flows to a remote tile provider; the gateway
 * (see `com.adsamcik.tracker.network.NetworkGateway`) must additionally be
 * armed by the map feature when this flips on.
 */
interface OnlineMapTilesRepository {
	/** Continuous stream of current online-tile preferences; never completes. */
	val data: Flow<OnlineMapTilesState>

	/** Opt in or out of online tile fetching. */
	suspend fun setEnabled(enabled: Boolean)

	/**
	 * Persist the chosen provider id (e.g. "openfreemap", "protomaps", "custom").
	 * Unknown ids round-trip unchanged — the consumer (see `TileProvider.resolve`
	 * in `:map`) is responsible for falling back to a safe default.
	 */
	suspend fun setProviderId(providerId: String)

	/**
	 * Persist a user-supplied style.json URL used when the active provider id
	 * is "custom". An empty string clears the URL.
	 */
	suspend fun setCustomUrl(customUrl: String)
}
