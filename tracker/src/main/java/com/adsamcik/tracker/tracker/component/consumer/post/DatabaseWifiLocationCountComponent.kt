package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationWifiCountDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocationWifiCount
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class DatabaseWifiLocationCountComponent : PostTrackerComponent {
	companion object {
		private const val TAG = "DatabaseWifiLocationCountComponent"
	}

	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var wifiDao: LocationWifiCountDao? = null
	private var scope: CoroutineScope? = null

	private var isEnabled = false


	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		if (!isEnabled) return

		val wifiData = collectionData.wifi ?: return
		val tmpWifiLocation = wifiData.location ?: return

		val count = DatabaseLocationWifiCount(
				wifiData.time,
				tmpWifiLocation,
				wifiData.inRange.size.toShort()
		)

		scope?.launch(Dispatchers.IO) {
			try { requireNotNull(wifiDao).insert(count) } catch (e: Throwable) { Log.e(TAG, "Failed to insert wifi location count: ${e.message}", e) }
		}
	}

	override suspend fun onDisable(context: Context) {
		scope?.cancel(); scope = null
		wifiDao = null
		this.isEnabled = false
	}

	// TODO: DI Migration - This PostTrackerComponent is instantiated by TrackerService.
	//  Future refactor: Accept WifiLocationCountDao via constructor for testability.
	//  See Section 16A of copilot-instructions.md for DI composition patterns.
	override suspend fun onEnable(context: Context) {
		val isEnabled = Preferences.getPref(context)
				.fetchBooleanRes(
						com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_location_count_enabled_key,
						com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_location_count_enabled_default
				)

		this.isEnabled = isEnabled
		if (isEnabled) {
			wifiDao = AppDatabase.database(context).wifiLocationCountDao()
			scope = CoroutineScope(Job() + Dispatchers.Default)
		}
	}
}
