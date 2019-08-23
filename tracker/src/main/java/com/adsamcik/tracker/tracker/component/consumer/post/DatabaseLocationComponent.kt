package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.common.data.ActivityInfo
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.dao.LocationDataDao
import com.adsamcik.tracker.common.database.data.DatabaseLocation
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class DatabaseLocationComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var locationDao: LocationDataDao? = null


	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		val location = collectionData.location
		if (location != null) {
			val activity = collectionData.activity ?: ActivityInfo.UNKNOWN
			saveLocation(location, activity)
		}
	}

	private fun saveLocation(location: Location, activityInfo: ActivityInfo) {
		requireNotNull(locationDao).insert(DatabaseLocation(location, activityInfo))
	}

	override suspend fun onDisable(context: Context) {
		locationDao = null
	}

	override suspend fun onEnable(context: Context) {
		locationDao = AppDatabase.getDatabase(context).locationDao()
	}
}

