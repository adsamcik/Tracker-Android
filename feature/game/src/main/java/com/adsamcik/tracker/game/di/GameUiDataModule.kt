package com.adsamcik.tracker.game.di

import com.adsamcik.tracker.game.data.ExplorationProgressRepository
import com.adsamcik.tracker.game.data.MiniGameScoreRepository
import com.adsamcik.tracker.game.data.RoomExplorationProgressRepository
import com.adsamcik.tracker.game.data.RoomMiniGameScoreRepository
import com.adsamcik.tracker.game.leaderboard.GhostLeaderboardProvider
import com.adsamcik.tracker.game.leaderboard.LeaderboardProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GameUiDataModule {
	@Binds
	@Singleton
	abstract fun bindExplorationProgressRepository(
		implementation: RoomExplorationProgressRepository,
	): ExplorationProgressRepository

	@Binds
	@Singleton
	abstract fun bindMiniGameScoreRepository(
		implementation: RoomMiniGameScoreRepository,
	): MiniGameScoreRepository

	@Binds
	@Singleton
	abstract fun bindLeaderboardProvider(
		implementation: GhostLeaderboardProvider,
	): LeaderboardProvider
}
