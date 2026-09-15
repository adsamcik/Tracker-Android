package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistory
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPageEvaluator
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifi
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
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

	@Binds
	fun bindImportPortableCapturedWifi(
		implementation: RoomImportPortableCapturedWifi,
	): ImportPortableCapturedWifi

	@Binds
	fun bindReadLocalPortableCapturedWifi(
		implementation: RoomExportPortableCapturedWifi,
	): ReadLocalPortableCapturedWifi

	@Binds
	fun bindImportedWifiProductEvaluator(
		implementation: RoomImportedWifiProductEvaluator,
	): ImportedWifiProductEvaluator

	@Binds
	fun bindImportedWifiProductRecentPageEvaluator(
		implementation: RoomImportedWifiProductEvaluator,
	): ImportedWifiProductRecentPageEvaluator

	@Binds
	fun bindReexportImportedCapturedWifi(
		implementation: RoomReexportImportedCapturedWifi,
	): ReexportImportedCapturedWifi

	@Binds
	fun bindDeleteSelectedWifiHistory(
		implementation: RoomDeleteSelectedWifiHistory,
	): DeleteSelectedWifiHistory

	@Binds
	fun bindWifiDeletedHistoryReader(
		implementation: RoomDeleteSelectedWifiHistory,
	): WifiDeletedHistoryReader
}
