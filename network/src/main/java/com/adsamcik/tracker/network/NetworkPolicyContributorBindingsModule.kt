package com.adsamcik.tracker.network

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Owns the empty-set declaration for [NetworkPolicyContributor] multibinding.
 *
 * The interface lives in `:network`, so the multibinds contract belongs here
 * too. Feature modules only contribute concrete implementations via `@IntoSet`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkPolicyContributorBindingsModule {

	@Multibinds
	abstract fun bindNetworkPolicyContributors(): Set<NetworkPolicyContributor>
}
