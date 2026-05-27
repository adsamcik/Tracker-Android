package com.adsamcik.tracker.game.challenge.catalog

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module exposing the [ChallengeCatalog] singleton via a `@Provides` shim so
 * downstream collaborators (the upcoming `ChallengeEngine` in p2-3, UI formatters,
 * etc.) can `@Inject` it without referencing the `object` directly.
 *
 * Mirrors the existing `ChallengeProcessorModule` style but uses `@Provides`
 * rather than `@Binds` because [ChallengeCatalog] is a Kotlin `object`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChallengeCatalogModule {

	@Provides
	@Singleton
	fun provideChallengeCatalog(): ChallengeCatalog = ChallengeCatalog
}
