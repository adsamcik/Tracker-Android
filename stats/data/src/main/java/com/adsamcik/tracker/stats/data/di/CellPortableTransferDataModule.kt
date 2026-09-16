package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.shared.base.database.ReexportImportedPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.RoomReexportImportedPortableCapturedCell
import com.adsamcik.tracker.stats.data.repository.CellImportedHistoryEligibleReader
import com.adsamcik.tracker.stats.data.repository.DefaultCellHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Cell-only imported-history bindings; they register no provider, demand, writer, or action. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CellPortableTransferDataModule {
	@Binds
	@Singleton
	abstract fun bindImportedCellReexport(
		impl: RoomReexportImportedPortableCapturedCell,
	): ReexportImportedPortableCapturedCell

	@Binds
	@Singleton
	abstract fun bindCellImportedHistoryEligibleReader(
		impl: DefaultCellHistoryRepository,
	): CellImportedHistoryEligibleReader
}
