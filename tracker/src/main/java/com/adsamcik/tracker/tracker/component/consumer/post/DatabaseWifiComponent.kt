package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.WifiDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.consumer.post.wifi.DefaultWifiLocationEstimator
import com.adsamcik.tracker.tracker.component.consumer.post.wifi.WifiLocationEstimator
import com.adsamcik.tracker.tracker.component.consumer.post.wifi.WifiLocationEstimate
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData


internal class DatabaseWifiComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var wifiDao: WifiDataDao? = null
	private var scope: CoroutineScope? = null
	private var estimator: WifiLocationEstimator? = null

	private var isEnabled = false

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		if (!isEnabled) return
		val wifiData = collectionData.wifi ?: return
		val estimator = estimator ?: return
		val updates = estimator.onScan(wifiData)
		if (updates.isEmpty()) return

		scope?.launch(Dispatchers.IO) {
			try {
				requireNotNull(wifiDao).upsert(updates.map(::toEntity))
			} catch (_: Throwable) { /* ignore individual failures */ }
		}
	}

	override suspend fun onDisable(context: Context) {
		flushEstimator()
		scope?.cancel()
		scope = null
		estimator = null
		wifiDao = null
		this.isEnabled = false
	}

	override suspend fun onEnable(context: Context) {
		val isEnabled = Preferences.getPref(context)
				.getBooleanRes(
						com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_network_enabled_key,
						com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_network_enabled_default
				)

		this.isEnabled = isEnabled
		if (isEnabled) {
			wifiDao = AppDatabase.database(context).wifiDao()
			scope = CoroutineScope(Job() + Dispatchers.Default)
			estimator = DefaultWifiLocationEstimator()
		}
	}

	private suspend fun flushEstimator() {
		val snapshot = estimator?.snapshot().orEmpty()
		if (snapshot.isEmpty()) return
		withContext(Dispatchers.IO) {
			try {
				requireNotNull(wifiDao).upsert(snapshot.map(::toEntity))
			} catch (_: Throwable) { }
		}
	}

	private fun toEntity(estimate: WifiLocationEstimate): DatabaseWifiData {
		return DatabaseWifiData(
			estimate.bssid,
			estimate.longitude,
			estimate.latitude,
			estimate.altitude,
			estimate.firstSeenMillis,
			estimate.lastSeenMillis,
			estimate.ssid,
			estimate.capabilities,
			estimate.frequency,
			estimate.maxRssi
		)
	}
}

