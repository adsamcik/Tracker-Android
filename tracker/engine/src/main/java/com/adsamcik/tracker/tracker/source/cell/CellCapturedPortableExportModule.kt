package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.RoomExportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CellCapturedPortableExportModule {
	@Binds abstract fun bindCellImporter(impl: RoomImportPortableCapturedCell): ImportPortableCapturedCell

	companion object {
		@Provides
		fun provideCellExporter(
			database: AppDatabase,
			authority: SourceProductLaneExecutionAuthority,
			@IoDispatcher dispatcher: CoroutineDispatcher,
		): ExportPortableCapturedCell = RoomExportPortableCapturedCell(database, authority, dispatcher)
	}
}
