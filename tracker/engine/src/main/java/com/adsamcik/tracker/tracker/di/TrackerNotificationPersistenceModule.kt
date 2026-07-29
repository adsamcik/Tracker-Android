package com.adsamcik.tracker.tracker.di

import android.content.Context
import com.adsamcik.tracker.shared.base.database.PreferenceDatabase
import com.adsamcik.tracker.shared.base.database.dao.NotificationPreferenceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrackerNotificationPersistenceModule {
	@Provides
	@Singleton
	fun provideNotificationPreferenceDao(
		@ApplicationContext context: Context,
	): NotificationPreferenceDao = PreferenceDatabase.database(context).getNotificationDao()
}
