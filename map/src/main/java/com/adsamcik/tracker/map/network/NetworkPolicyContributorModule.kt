package com.adsamcik.tracker.map.network

import com.adsamcik.tracker.network.NetworkPolicyContributor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module wiring `:map`'s [NetworkPolicyContributor] bindings.
 *
 * Contributes `MapTilesPolicyContributor` into the `Set<NetworkPolicyContributor>`
 * owned by `:network`. Other modules — e.g. a future `:osm`-download module —
 * add their own contributor via
 * `@Binds @IntoSet` in their own Hilt module; Dagger merges every `@IntoSet`
 * contribution into the single set injected into `NetworkPolicyAggregator`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkPolicyContributorModule {

	/**
	 * Contributes [MapTilesPolicyContributor] to the set the
	 * `NetworkPolicyAggregator` consumes.
	 */
	@Binds
	@IntoSet
	abstract fun bindMapTilesContributor(
		impl: MapTilesPolicyContributor,
	): NetworkPolicyContributor
}
