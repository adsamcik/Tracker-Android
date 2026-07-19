package com.adsamcik.tracker.game.di

import com.adsamcik.tracker.game.session.DefaultGameSessionController
import com.adsamcik.tracker.game.session.GameSessionController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GameSessionModule {
	@Binds
	@Singleton
	abstract fun bindGameSessionController(
		impl: DefaultGameSessionController,
	): GameSessionController
}
