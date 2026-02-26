package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.dao.WifiDataDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.consumer.post.wifi.DefaultWifiLocationEstimator
import com.adsamcik.tracker.tracker.component.consumer.post.wifi.WifiLocationEstimator
import com.adsamcik.tracker.tracker.component.consumer.post.wifi.WifiLocationEstimate
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData


internal class DatabaseWifiComponent(
	private val wifiDataDao: WifiDataDao,
	private val wifiObservationDao: WifiObservationDao,
) : PostTrackerComponent {
	companion object {
		private const val TAG = "DatabaseWifiComponent"
	}

	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var scope: CoroutineScope? = null
	private var estimator: WifiLocationEstimator? = null
	private var errorCollector: PersistenceErrorCollector? = null

	private var isEnabled = false
	private var enableDualWrite = true // Dual-write mode during migration

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		if (!isEnabled) return
		val wifiData = collectionData.wifi ?: return
		val location = collectionData.location
		
		// Legacy table write (aggregated estimates)
		if (enableDualWrite) {
			val estimator = estimator ?: return
			val updates = estimator.onScan(wifiData)
			if (updates.isNotEmpty()) {
				scope?.launch(Dispatchers.IO) {
					try {
						wifiDataDao.upsert(updates.map(::toEntity))
					} catch (e: Throwable) {
						Log.e(TAG, "Failed to upsert wifi data: ${e.message}", e)
						errorCollector?.reportErrorAsync(
							PersistenceError(
								source = TAG,
								operation = "upsert wifi data",
								recordCount = updates.size,
								cause = e
							)
						)
					}
				}
			}
		}
		
		// New table write (raw observations, even without location)
		saveWifiObservations(collectionData.time, wifiData.inRange, location)
	}

	/**
	 * Sets the error collector for reporting persistence failures.
	 * Should be called before onEnable.
	 */
	fun setErrorCollector(collector: PersistenceErrorCollector) {
		this.errorCollector = collector
	}

	override suspend fun onDisable(context: Context) {
		if (enableDualWrite) {
			flushEstimator()
		}
		scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.children?.toList()?.forEach { it.join() }
		scope?.cancel()
		scope = null
		estimator = null
		errorCollector = null
		this.isEnabled = false
	}

	override suspend fun onEnable(context: Context) {
		val isEnabled = Preferences.getPref(context)
				.fetchBooleanRes(
						com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_network_enabled_key,
						com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_network_enabled_default
				)

		this.isEnabled = isEnabled
		if (isEnabled) {
			scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			if (enableDualWrite) {
				estimator = DefaultWifiLocationEstimator()
			}
		}
	}

	private suspend fun flushEstimator() {
		val snapshot = estimator?.snapshot().orEmpty()
		if (snapshot.isEmpty()) return
		withContext(Dispatchers.IO) {
			try {
				wifiDataDao.upsert(snapshot.map(::toEntity))
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to flush wifi estimator: ${e.message}", e)
				errorCollector?.reportError(
					PersistenceError(
						source = TAG,
						operation = "flush wifi estimator",
						recordCount = snapshot.size,
						cause = e
					)
				)
			}
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
	
	// New: Save raw Wi-Fi observations to sessionless table (with or without coordinates)
	private fun saveWifiObservations(
		time: Long,
		networks: List<com.adsamcik.tracker.shared.base.data.WifiInfo>,
		location: com.adsamcik.tracker.shared.base.data.Location?
	) {
		val now = Time.nowMillis
		
		// Convert location to E7 format if available
		val latE7 = location?.let { (it.latitude * 1e7).toInt() }
		val lonE7 = location?.let { (it.longitude * 1e7).toInt() }
		val provenance = if (location != null) CoordinateProvenance.DIRECT else CoordinateProvenance.UNKNOWN
		
		scope?.launch(Dispatchers.IO) {
			try {
				val observations = networks.map { network ->
					WifiObservation(
						timeMs = time,
						bssid = network.bssid,
						ssid = network.ssid ?: "<unknown>",
						capabilities = network.capabilities,
						frequency = network.frequency,
						level = network.level,
						latE7 = latE7,
						lonE7 = lonE7,
						provenance = provenance,
						createdAt = now
					)
				}
				wifiObservationDao.insert(observations)
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to insert wifi observations: ${e.message}", e)
				errorCollector?.reportErrorAsync(
					PersistenceError(
						source = TAG,
						operation = "insert wifi observations",
						recordCount = networks.size,
						cause = e
					)
				)
			}
		}
	}
}

