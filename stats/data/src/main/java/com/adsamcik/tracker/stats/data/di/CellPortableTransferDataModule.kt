package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.shared.base.database.ReexportImportedPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.RoomReexportImportedPortableCapturedCell
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Cell-only transfer binding; it does not register a provider, demand, writer, or action. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CellPortableTransferDataModule {
	@Binds
	@Singleton
	abstract fun bindImportedCellReexport(
		impl: RoomReexportImportedPortableCapturedCell,
	): ReexportImportedPortableCapturedCell
}
