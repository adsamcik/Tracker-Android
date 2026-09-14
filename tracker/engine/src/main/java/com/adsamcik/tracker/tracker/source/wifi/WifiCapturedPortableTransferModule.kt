package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface WifiCapturedPortableTransferModule {
	@Binds
	fun bindExportPortableCapturedWifi(
		implementation: RoomExportPortableCapturedWifi,
	): ExportPortableCapturedWifi
}
