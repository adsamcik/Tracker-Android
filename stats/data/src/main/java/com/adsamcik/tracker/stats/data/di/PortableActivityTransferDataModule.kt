package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.RoomExportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/** Activity portable transfer bindings remain dormant until the parent registers the file format. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PortableActivityTransferDataModule {
	@Binds
	@Singleton
	abstract fun bindImportPortableCapturedActivity(
		implementation: RoomImportPortableCapturedActivity,
	): ImportPortableCapturedActivity

	companion object {
		@Provides
		@Singleton
		fun provideExportPortableCapturedActivity(
			database: AppDatabase,
			laneExecutionAuthority: SourceProductLaneExecutionAuthority,
			@IoDispatcher ioDispatcher: CoroutineDispatcher,
		): ExportPortableCapturedActivity = RoomExportPortableCapturedActivity(
			database = database,
			laneExecutionAuthority = laneExecutionAuthority,
			ioDispatcher = ioDispatcher,
		)
	}
}
