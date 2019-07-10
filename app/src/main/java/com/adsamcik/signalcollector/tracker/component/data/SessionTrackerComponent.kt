package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.annotation.WorkerThread
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.data.GroupedActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.SessionDataDao
import com.adsamcik.signalcollector.common.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.component.DataTrackerComponent
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.session.MutableTrackerSession
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

class SessionTrackerComponent(private val isUserInitiated: Boolean) : DataTrackerComponent, SensorEventListener, CoroutineScope {
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

	private var lastStepCount = -1

	private lateinit var sessionDao: SessionDataDao

	override suspend fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		val location = locationResult.lastLocation
		mutableSession.run {
			distanceInM += distance
			collections++
			end = Time.nowMillis

			if (previousLocation != null &&
					(location.elapsedRealtimeNanos - previousLocation.elapsedRealtimeNanos < max(Time.SECOND_IN_NANOSECONDS * 20, minUpdateDelayInSeconds * 2 * Time.SECOND_IN_NANOSECONDS) ||
							distance <= minDistanceInMeters * 2f)) {
				when (activity.groupedActivity) {
					GroupedActivity.ON_FOOT -> distanceOnFootInM += distance
					GroupedActivity.IN_VEHICLE -> distanceInVehicleInM += distance
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

		val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		sensorManager.unregisterListener(this)

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

		val packageManager = context.packageManager
		if (packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
			val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
			val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
			sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
		}

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

	override fun onSensorChanged(event: SensorEvent) {
		val sensor = event.sensor
		if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
			val stepCount = event.values.first().toInt()
			if (lastStepCount >= 0) {
				//in case sensor would overflow and reset to 0 at some point
				if (lastStepCount > stepCount) {
					mutableSession.steps += stepCount
				} else {
					mutableSession.steps += stepCount - lastStepCount
				}
			}

			lastStepCount = stepCount
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}


	companion object {
		const val MERGE_SESSION_MAX_AGE = 10 * Time.MINUTE_IN_MILLISECONDS
	}
}