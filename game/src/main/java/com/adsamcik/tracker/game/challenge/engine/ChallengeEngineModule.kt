package com.adsamcik.tracker.game.challenge.engine

import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [ChallengeRuleRegistry] to the abstract [RuleRegistry] contract so consumers
 * inject the interface rather than the concrete implementation. Concrete-only injection
 * would lock challenge enumeration to the engine and prevent a future composite registry
 * (e.g. `@JvmSuppressWildcards Set<RuleRegistry>` multibinding) from being introduced
 * when the achievement side migrates onto the same shared rule API.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChallengeEngineModule {

	@Binds
	@Singleton
	abstract fun bindChallengeRuleRegistry(impl: ChallengeRuleRegistry): RuleRegistry
}
