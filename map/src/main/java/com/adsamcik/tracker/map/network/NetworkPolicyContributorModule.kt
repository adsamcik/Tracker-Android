package com.adsamcik.tracker.map.network

import com.adsamcik.tracker.network.NetworkPolicyContributor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Hilt module wiring `:map`'s [NetworkPolicyContributor] bindings.
 *
 * Declares the `Set<NetworkPolicyContributor>` multibinding (via [Multibinds])
 * and contributes `MapTilesPolicyContributor` into it. Other modules — e.g.
 * a future `:osm`-download module — add their own contributor via
 * `@Binds @IntoSet` in their own Hilt module; Dagger merges every `@IntoSet`
 * contribution into the single set injected into `NetworkPolicyAggregator`.
 *
 * # Why a multibinds declaration here
 *
 * [Multibinds] tells Dagger the set is allowed to be empty (e.g. in a
 * variant or test that only includes `:network`). Without it, an empty
 * `Set<NetworkPolicyContributor>` would fail dependency resolution at
 * compile time. The aggregator handles the empty case explicitly — see
 * `NetworkPolicyAggregator`'s zero-contributors short-circuit.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkPolicyContributorModule {

	/**
	 * Declares the multibinding so Dagger accepts an empty set. Concrete
	 * `@IntoSet` bindings live below (and in other modules).
	 */
	@Multibinds
	abstract fun bindNetworkPolicyContributors(): Set<NetworkPolicyContributor>

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
