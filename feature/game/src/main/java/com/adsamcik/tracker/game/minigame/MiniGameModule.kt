package com.adsamcik.tracker.game.minigame

import com.adsamcik.tracker.game.minigame.location.FusedMiniGameLocationSource
import com.adsamcik.tracker.game.minigame.location.LiveOrFusedMiniGameLocationSource
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSource
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
internal abstract class MiniGameModule {
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

	@dagger.Binds
	@dagger.multibindings.IntoSet
	abstract fun bindFuseRun(impl: com.adsamcik.tracker.game.minigame.fuserun.FuseRunGame): MiniGame

	@dagger.Binds
	@dagger.multibindings.IntoSet
	abstract fun bindSwitchback(
		impl: com.adsamcik.tracker.game.minigame.switchback.SwitchbackGame,
	): MiniGame

	/**
	 * Live-location feed for mini-game sessions.
	 *
	 * Bound to [LiveOrFusedMiniGameLocationSource] which prefers the tracker
	 * service's existing GPS stream when it is running (zero extra battery
	 * cost) and transparently falls back to [FusedMiniGameLocationSource]
	 * when the tracker is off, so mini-games remain playable without
	 * enabling background tracking.
	 */
	@dagger.Binds
	abstract fun bindMiniGameLocationSource(
		impl: LiveOrFusedMiniGameLocationSource,
	): MiniGameLocationSource
}

