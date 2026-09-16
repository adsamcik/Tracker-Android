package com.adsamcik.tracker.app.di

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPageEvaluator
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.data.repository.TrackingHistoryIntegrationObserver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Exact production singletons exercised by the five-source history assembly instrumentation. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FiveSourceTrackingHistoryEntryPoint {
	fun collectedDataLifecycleStore(): CollectedDataLifecycleStore
	fun database(): AppDatabase
	fun history(): TrackingHistoryRepository
	fun historyIntegrationObserver(): TrackingHistoryIntegrationObserver
	fun importActivity(): ImportPortableCapturedActivity
	fun importCell(): RoomImportPortableCapturedCell
	fun importPressure(): ImportPortablePressure
	fun importWifi(): ImportPortableCapturedWifi
	fun startupGate(): TrackingStartupGate
	fun wifiEvaluator(): ImportedWifiProductRecentPageEvaluator
}
