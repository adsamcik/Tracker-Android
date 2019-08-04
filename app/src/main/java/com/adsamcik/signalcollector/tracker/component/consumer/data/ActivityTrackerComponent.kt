package com.adsamcik.signalcollector.tracker.component.consumer.data

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.tracker.component.DataTrackerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerComponentRequirement
import com.adsamcik.signalcollector.tracker.component.consumer.pre.StepPreTrackerComponent
import com.adsamcik.signalcollector.tracker.data.collection.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationResult

internal class ActivityTrackerComponent : DataTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> get() = mutableListOf(TrackerComponentRequirement.ACTIVITY)

	private fun isNotConfidentWalk(activity: ActivityInfo): Boolean {
		return activity.activity == DetectedActivity.WALKING &&
				activity.confidence < CONFIDENT_CONFIDENCE
	}

	private fun isUnknownOnFootActivity(activity: ActivityInfo) = activity.activity == DetectedActivity.ON_FOOT
	private fun isRunning(activity: ActivityInfo) = activity.activity == DetectedActivity.RUNNING
	private fun isWalking(activity: ActivityInfo) = activity.activity == DetectedActivity.WALKING

	private fun isOnFoot(activity: ActivityInfo): Boolean {
		return isUnknownOnFootActivity(activity) || isWalking(activity) || isRunning(activity)
	}

	private fun isUnknown(activity: ActivityInfo): Boolean {
		return activity.activity == DetectedActivity.UNKNOWN ||
				activity.activity == DetectedActivity.TILTING ||
				activity.activity == DetectedActivity.STILL
	}

	private fun determineActivityBySpeed(speed: Float, activity: ActivityInfo): ActivityInfo {
		return if (speed > MAX_RUN_SPEED_METERS_PER_SECOND && isOnFoot(activity)) {
			ActivityInfo(DetectedActivity.IN_VEHICLE, 90)
		} else if (speed > MAX_ON_FOOT_SPEED && isUnknownOnFootActivity(activity)) {
			ActivityInfo(DetectedActivity.IN_VEHICLE, 75)
		} else if (speed > MAX_WALK_SPEED_METERS_PER_SECOND &&
				(isNotConfidentWalk(activity) || isUnknownOnFootActivity(activity))) {
			ActivityInfo(DetectedActivity.RUNNING, 80)
		} else if (speed > DEFINITELY_VEHICLE_SPEED && isUnknown(activity)) {
			ActivityInfo(DetectedActivity.IN_VEHICLE, 75)
		} else {
			activity
		}
	}


	private fun determineActivity(speed: Float?, tempData: CollectionTempData): ActivityInfo {
		val activity = tempData.getActivity(this)

		//Bicycle activity is impossible to guess from position
		if (activity.activity == DetectedActivity.ON_BICYCLE) return activity

		val stepCount = tempData.tryGet<Int>(StepPreTrackerComponent.NEW_STEPS_ARG)
		if (stepCount != null &&
				stepCount >= tempData.elapsedRealtimeNanos / Time.SECOND_IN_NANOSECONDS &&
				(speed == null || speed <= MAX_GUESS_RUN_SPEED_METERS_PER_SECOND)) {
			if (isOnFoot(activity)) return activity
			else if (isUnknown(activity)) return ActivityInfo(DetectedActivity.ON_FOOT, 90)
		}

		return if (speed != null) {
			determineActivityBySpeed(speed, activity)
		} else {
			activity
		}
	}

	private fun getSpeed(locationResult: LocationResult, previousLocation: Location?, distance: Float): Float? {
		val location = locationResult.lastLocation
		return when {
			location.hasSpeed() -> location.speed
			previousLocation != null -> distance / ((location.time - previousLocation.time) / Time.SECOND_IN_MILLISECONDS)
			else -> null
		}
	}

	override suspend fun onDataUpdated(tempData: CollectionTempData, collectionData: MutableCollectionData) {
		val locationResult = tempData.tryGetLocationResult()
		val previousLocation = tempData.tryGetPreviousLocation()
		val distance = tempData.tryGetDistance()
		val speed = if (locationResult != null &&
				previousLocation != null &&
				distance != null) {
			getSpeed(locationResult, previousLocation, distance)
		} else {
			null
		}

		collectionData.activity = determineActivity(speed, tempData)
	}

	override suspend fun onDisable(context: Context) {}

	override suspend fun onEnable(context: Context) {}

	companion object {
		const val CONFIDENT_CONFIDENCE = 70
		const val MAX_WALK_SPEED_METERS_PER_SECOND = 2.0f

		const val MAX_RUN_SPEED_METERS_PER_SECOND = 12.5f
		const val MAX_GUESS_RUN_SPEED_METERS_PER_SECOND = 9.0f
		const val MAX_ON_FOOT_SPEED = 4.5f

		const val DEFINITELY_VEHICLE_SPEED = 15
	}

}
