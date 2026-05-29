package com.adsamcik.tracker.game.challenge.engine

import com.adsamcik.tracker.stats.api.rule.ChallengeRules
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [ChallengeRuleRegistry] to the [RuleRegistry] interface, qualified with
 * [ChallengeRules] so injection sites are explicit and a future composite registry
 * (or achievement side injecting [com.adsamcik.tracker.stats.api.rule.AchievementRules])
 * cannot accidentally receive the wrong implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChallengeEngineModule {

	@Binds
	@Singleton
	@ChallengeRules
	abstract fun bindChallengeRuleRegistry(impl: ChallengeRuleRegistry): RuleRegistry
}
