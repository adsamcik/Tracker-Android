package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifi
import com.adsamcik.tracker.stats.data.repository.RoomAmbientCellPortableTransfer
import com.adsamcik.tracker.stats.data.repository.RoomAmbientWifiPortableTransfer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AmbientRadioPortableTransferModule {
	@Binds abstract fun bindWifiExporter(impl: RoomAmbientWifiPortableTransfer): ExportPortableAmbientWifi
	@Binds abstract fun bindWifiImporter(impl: RoomAmbientWifiPortableTransfer): ImportPortableAmbientWifi
	@Binds abstract fun bindCellExporter(impl: RoomAmbientCellPortableTransfer): ExportPortableAmbientCell
	@Binds abstract fun bindCellImporter(impl: RoomAmbientCellPortableTransfer): ImportPortableAmbientCell
}
