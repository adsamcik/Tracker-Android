package com.adsamcik.tracker.game.minigame

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Hilt module for mini-game registration.
 * Each game adds a @Binds @IntoSet binding here.
 * Empty set is valid (no games registered yet).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MiniGameModule {
	@Multibinds
	abstract fun bindMiniGames(): Set<MiniGame>

	@dagger.Binds
	@dagger.multibindings.IntoSet
	abstract fun bindOutrun(impl: com.adsamcik.tracker.game.minigame.outrun.OutrunGame): MiniGame

	@dagger.Binds
	@dagger.multibindings.IntoSet
	abstract fun bindTerritory(impl: com.adsamcik.tracker.game.minigame.territory.TerritoryGame): MiniGame

	@dagger.Binds
	@dagger.multibindings.IntoSet
	abstract fun bindZenWalk(impl: com.adsamcik.tracker.game.minigame.zenwalk.ZenWalkGame): MiniGame
}
