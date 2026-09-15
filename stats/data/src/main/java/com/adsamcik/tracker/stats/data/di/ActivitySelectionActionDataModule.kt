package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivity
import com.adsamcik.tracker.shared.base.database.RoomDeleteSelectedImportedActivity
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletion
import com.adsamcik.tracker.stats.data.repository.RoomActivitySelectionDeletion
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Exact-origin Activity history action binding; no shared UI registration occurs here. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ActivitySelectionActionDataModule {
	@Binds
	@Singleton
	abstract fun bindDeleteSelectedImportedActivity(
		implementation: RoomDeleteSelectedImportedActivity,
	): DeleteSelectedImportedActivity

	@Binds
	@Singleton
	abstract fun bindActivitySelectionDeletion(
		implementation: RoomActivitySelectionDeletion,
	): ActivitySelectionDeletion
}
