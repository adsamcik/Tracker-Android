package com.adsamcik.tracker.points.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PointsDatabaseModule {

	@Provides
	@Singleton
	fun providePointsDatabase(@ApplicationContext context: Context): PointsDatabase =
		PointsDatabase.database(context)
}
