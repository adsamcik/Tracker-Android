package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.rule.AchievementRules
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.data.achievement.AchievementRuleRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AchievementRegistryModule {

	@Binds
	@Singleton
	@AchievementRules
	abstract fun bindAchievementRuleRegistry(impl: AchievementRuleRegistry): RuleRegistry
}
