package com.adsamcik.tracker.tracker.component.consumer

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.lifecycle.Observer
import com.adsamcik.tracker.logger.assertMoreOrEqual
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

internal class SessionTrackerComponent(private val isUserInitiated: Boolean) : DataTrackerComponent,
		CoroutineScope {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private var mutableSession: MutableTrackerSession = MutableTrackerSession(
			Time.nowMillis,
			isUserInitiated
	)

	val session: TrackerSession
		get() = mutableSession

	var isNewSession: Boolean = false
		private set

	private var minUpdateDelayInSeconds = -1
	private var minDistanceInMeters = -1

	private val minDistanceInMetersObserver = Observer<Int> { minDistanceInMeters = it }
	private val minUpdateDelayInSecondsObserver = Observer<Int> { minUpdateDelayInSeconds = it }

	private lateinit var sessionDao: SessionDataDao

	override suspend fun onDataUpdated(
			tempData: CollectionTempData,
			collectionData: MutableCollectionData
	) {
		mutableSession.run {
			val locationData = tempData.tryGetLocationData()
			val distance = locationData?.distance
			distance?.let {
				distanceInM += it

				tempData.tryGetActivity()?.let { activity ->
					validateActivity(
							distance, tempData.elapsedRealtimeNanos,
							activity.groupedActivity
					)
				}
			}

			collections++
			end = Time.nowMillis

			tempData.tryGet<Int>(StepDataProducer.NEW_STEPS_ARG)?.let { newSteps ->
				assertMoreOrEqual(newSteps, 0)
				steps += newSteps
			}

			withContext(coroutineContext) {
				sessionDao.update(this@run)
			}
		}
	}

	private fun MutableTrackerSession.validateActivity(
			distance: Float,
			elapsedRealtimeNanos: Long,
			groupedActivity: GroupedActivity
	) {
		if (elapsedRealtimeNanos < max(
						Time.SECOND_IN_NANOSECONDS * 20,
						minUpdateDelayInSeconds * 2 * Time.SECOND_IN_NANOSECONDS
				) ||
				distance <= minDistanceInMeters * 2f) {

			when (groupedActivity) {
				GroupedActivity.ON_FOOT -> distanceOnFootInM += distance
				GroupedActivity.IN_VEHICLE -> distanceInVehicleInM += distance
				else -> {
				}
			}
		}
	}

	override suspend fun onDisable(context: Context) {
		PreferenceObserver.removeObserver(
				context, R.string.settings_tracking_min_distance_key,
				minDistanceInMetersObserver
		)
		PreferenceObserver.removeObserver(
				context, R.string.settings_tracking_min_time_key,
				minUpdateDelayInSecondsObserver
		)

		mutableSession.apply {
			end = Time.nowMillis
		}

		withContext(coroutineContext) {
			sessionDao.update(mutableSession)
		}
	}

	override suspend fun onEnable(context: Context) {
		PreferenceObserver.observeIntRes(
				context, R.string.settings_tracking_min_distance_key,
				R.integer.settings_tracking_min_distance_default, minDistanceInMetersObserver
		)
		PreferenceObserver.observeIntRes(
				context, R.string.settings_tracking_min_time_key,
				R.integer.settings_tracking_min_time_default, minUpdateDelayInSecondsObserver
		)

		withContext(coroutineContext) {
			initializeSession(context)
		}
	}

	@WorkerThread
	private fun initializeSession(context: Context) {
		sessionDao = AppDatabase.database(context).sessionDao()

		val lastSession = sessionDao.getLast(1)
		val now = Time.nowMillis

		var isNewSession = true
		if (lastSession != null) {
			val lastSessionEnd = lastSession.end
			val lastSessionAge = now - lastSessionEnd

			if (lastSessionAge in 0..SESSION_RESUME_TIMEOUT &&
					lastSession.isUserInitiated == isUserInitiated &&
					!isUserInitiated) {
				mutableSession = MutableTrackerSession(lastSession)
				isNewSession = false
			}
		}

		if (isNewSession) {
			val session = MutableTrackerSession(now, isUserInitiated)
			session.id = sessionDao.insert(session)
			mutableSession = session
		}

		this.isNewSession = isNewSession
	}

	companion object {
		const val SESSION_RESUME_TIMEOUT = 0 * Time.MINUTE_IN_MILLISECONDS
	}
}

