package com.adsamcik.tracker.map.network

import com.adsamcik.tracker.map.online.TileProvider
import com.adsamcik.tracker.network.NetworkPolicy
import com.adsamcik.tracker.network.NetworkPolicyContribution
import com.adsamcik.tracker.network.NetworkPolicyContributor
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [NetworkPolicyContributor] for online map tile fetching.
 *
 * Translates the user's [OnlineMapTilesRepository] preference into a
 * [NetworkPolicyContribution]:
 *
 *  - Preference `enabled=false` → [NetworkPolicyContribution.Inactive]
 *    (no hosts contributed; doesn't vote to arm the kill switch).
 *  - Preference `enabled=true` → [NetworkPolicyContribution.Active] with
 *    `allowedHosts` resolved from the chosen [TileProvider] and a bursty
 *    per-host rate limit ([TILE_HOST_RATE_LIMIT_PER_MIN]) suitable for the
 *    20-50 tile fetches a single viewport pan can trigger.
 *
 * This was previously inlined into `MapStore` as a `viewModelScope.launch`
 * block that directly called `NetworkGateway.setEnabled` / `setPolicy`.
 * Splitting it out lets a second consumer (e.g. an OSM PMTiles offline
 * pre-cache download) publish its own contribution alongside without
 * either consumer overwriting the other's policy.
 *
 * # Scope
 *
 * Singleton — the contributor is meaningful for process lifetime (the
 * preference is process-global; map UI may or may not be on-screen). The
 * underlying [contribution] StateFlow is `stateIn(scope, Eagerly, Inactive)`
 * so the aggregator always has a non-suspending initial value.
 */
@Singleton
class MapTilesPolicyContributor @Inject constructor(
	repository: OnlineMapTilesRepository,
	@ApplicationScope scope: CoroutineScope,
) : NetworkPolicyContributor {

	override val id: String = ID

	override val contribution: StateFlow<NetworkPolicyContribution> =
		repository.data
			.map { prefs -> prefs.toContribution() }
			// Eagerly subscribe so the aggregator sees this contributor's
			// current state immediately at process start — otherwise the
			// gateway would be left in its deny-all default until the first
			// UI subscriber arrives, then flip on/off as the user opens
			// settings. Eagerly + a StateFlow upstream means at most one
			// active subscription on the preference store.
			.stateIn(
				scope = scope,
				started = SharingStarted.Eagerly,
				initialValue = NetworkPolicyContribution.Inactive,
			)

	private fun OnlineMapTilesState.toContribution(): NetworkPolicyContribution {
		if (!enabled) return NetworkPolicyContribution.Inactive
		val provider = TileProvider.resolve(providerId, customUrl)
		return NetworkPolicyContribution.Active(
			allowedHosts = provider.allowedHosts,
			perHostRateLimit = TILE_HOST_RATE_LIMIT_PER_MIN,
			perHostRateWindowMs = NetworkPolicy.DEFAULT_RATE_WINDOW_MS,
		)
	}

	companion object {
		/** Stable identifier for this contributor. Used in audit logs. */
		const val ID: String = "map-tiles"

		/**
		 * Per-host rate limit (requests / minute) applied to online tile
		 * providers. Tile loads are bursty — a single viewport pan can
		 * request 20-50 vector tiles in a few seconds plus sprite + glyph
		 * fetches. The 600/min ([NetworkPolicy.DEFAULT_RATE_LIMIT]) per-host
		 * default is too tight for that; 3600/min (60/s sustained) covers
		 * a vigorous pan/zoom without ever spuriously rate-limiting the
		 * user, while still gating a runaway loop.
		 *
		 * (Lifted verbatim from the previous inline `MapStore.applyNetworkPolicy`
		 * to preserve behaviour during the refactor.)
		 */
		const val TILE_HOST_RATE_LIMIT_PER_MIN: Int = 3600
	}
}
