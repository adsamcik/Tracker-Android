package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import androidx.annotation.WorkerThread
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.GroupedActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.SessionDataDao
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.component.DataTrackerComponent
import com.adsamcik.signalcollector.tracker.component.pre.StepPreTrackerComponent
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.session.MutableTrackerSession
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

class SessionTrackerComponent(private val isUserInitiated: Boolean) : DataTrackerComponent, CoroutineScope {
	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private var mutableSession: MutableTrackerSession = MutableTrackerSession(Time.nowMillis, isUserInitiated)

	val session: TrackerSession
		get() = mutableSession

	private var minUpdateDelayInSeconds = -1
	private var minDistanceInMeters = -1

	private val minDistanceInMetersObserver = Observer<Int> { minDistanceInMeters = it }
	private val minUpdateDelayInSecondsObserver = Observer<Int> { minUpdateDelayInSeconds = it }

	private lateinit var sessionDao: SessionDataDao

	override suspend fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, collectionData: MutableCollectionData, tempData: CollectionTempData) {
		mutableSession.run {
			distanceInM += tempData.distance
			collections++
			end = Time.nowMillis

			val newSteps = tempData.tryGet<Int>(StepPreTrackerComponent.NEW_STEPS_ARG)
			if (newSteps != null) steps += newSteps

			if (previousLocation != null &&
					(tempData.elapsedRealtimeNanos < max(Time.SECOND_IN_NANOSECONDS * 20, minUpdateDelayInSeconds * 2 * Time.SECOND_IN_NANOSECONDS) ||
							tempData.distance <= minDistanceInMeters * 2f)) {
				when (tempData.activity.groupedActivity) {
					GroupedActivity.ON_FOOT -> distanceOnFootInM += tempData.distance
					GroupedActivity.IN_VEHICLE -> distanceInVehicleInM += tempData.distance
					else -> {
					}
				}
			}

			withContext(coroutineContext) {
				sessionDao.update(this@run)
			}
		}
	}

	override suspend fun onDisable(context: Context) {
		PreferenceObserver.removeObserver(context, R.string.settings_tracking_min_distance_key, minDistanceInMetersObserver)
		PreferenceObserver.removeObserver(context, R.string.settings_tracking_min_time_key, minUpdateDelayInSecondsObserver)

		mutableSession.apply {
			end = Time.nowMillis
		}

		withContext(coroutineContext) {
			sessionDao.update(mutableSession)
		}
	}

	override suspend fun onEnable(context: Context) {
		PreferenceObserver.observeIntRes(context, R.string.settings_tracking_min_distance_key, R.integer.settings_tracking_min_distance_default, minDistanceInMetersObserver)
		PreferenceObserver.observeIntRes(context, R.string.settings_tracking_min_time_key, R.integer.settings_tracking_min_time_default, minUpdateDelayInSecondsObserver)

		withContext(coroutineContext) {
			initializeSession(context)
		}
	}

	@WorkerThread
	private fun initializeSession(context: Context) {
		sessionDao = AppDatabase.getDatabase(context).sessionDao()

		val lastSession = sessionDao.getLast(1)
		val now = Time.nowMillis

		var continuingSession = false
		if (lastSession != null) {
			val lastSessionEnd = lastSession.end
			val lastSessionAge = now - lastSessionEnd

			if (lastSessionAge in 1..MERGE_SESSION_MAX_AGE &&
					lastSession.isUserInitiated == isUserInitiated &&
					!isUserInitiated) {
				mutableSession = MutableTrackerSession(lastSession)
				continuingSession = true
			}
		}

		if (!continuingSession) {
			val session = MutableTrackerSession(now, isUserInitiated)
			session.id = sessionDao.insert(session)
			mutableSession = session
		}
	}

	companion object {
		const val MERGE_SESSION_MAX_AGE = 10 * Time.MINUTE_IN_MILLISECONDS
	}
}