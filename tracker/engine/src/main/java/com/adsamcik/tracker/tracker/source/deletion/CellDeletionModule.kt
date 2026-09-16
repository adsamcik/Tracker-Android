package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCell
import com.adsamcik.tracker.shared.base.database.RoomDeleteSelectedImportedCell
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CellDeletionModule {
	@Binds
	@Singleton
	abstract fun bindDeleteCellHistory(
		implementation: RoomCellSelectedHistoryDeletion,
	): DeleteCellHistory

	@Binds
	@Singleton
	abstract fun bindDeleteSelectedImportedCell(
		implementation: RoomDeleteSelectedImportedCell,
	): DeleteSelectedImportedCell
}
