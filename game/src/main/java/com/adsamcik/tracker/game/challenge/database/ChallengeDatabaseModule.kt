package com.adsamcik.tracker.game.challenge.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChallengeDatabaseModule {

	@Provides
	@Singleton
	fun provideChallengeDatabase(@ApplicationContext context: Context): ChallengeDatabase =
		ChallengeDatabase.database(context)
}
