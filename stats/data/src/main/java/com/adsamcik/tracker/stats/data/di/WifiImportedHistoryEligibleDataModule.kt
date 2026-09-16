package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.data.repository.WifiImportedHistoryEligibleReader
import com.adsamcik.tracker.stats.data.repository.WifiImportedHistoryEligibleReaderAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Source-local binding for the imported-only Wi-Fi shared-history bridge. */
@Module
@InstallIn(SingletonComponent::class)
internal interface WifiImportedHistoryEligibleDataModule {
	@Binds
	@Singleton
	fun bindWifiImportedHistoryEligibleReader(
		implementation: WifiImportedHistoryEligibleReaderAdapter,
	): WifiImportedHistoryEligibleReader
}
