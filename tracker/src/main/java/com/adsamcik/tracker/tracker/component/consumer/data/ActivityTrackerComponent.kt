package com.adsamcik.tracker.tracker.component.consumer.data

import android.content.Context
import android.location.Location
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import com.google.android.gms.location.DetectedActivity

internal class ActivityTrackerComponent : DataTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement>
		get() = mutableListOf(TrackerComponentRequirement.ACTIVITY)

	private fun isNotConfidentWalk(activity: ActivityInfo): Boolean {
		return activity.activityType == DetectedActivity.WALKING &&
				activity.confidence < CONFIDENT_CONFIDENCE
	}

	private fun isUnknownOnFootActivity(activity: ActivityInfo) =
			activity.activityType == DetectedActivity.ON_FOOT

	private fun isRunning(activity: ActivityInfo) =
			activity.activityType == DetectedActivity.RUNNING

	private fun isWalking(activity: ActivityInfo) =
			activity.activityType == DetectedActivity.WALKING

	private fun isOnFoot(activity: ActivityInfo): Boolean {
		return isUnknownOnFootActivity(activity) || isWalking(activity) || isRunning(activity)
	}

	private fun isUnknown(activity: ActivityInfo): Boolean {
		return activity.activityType == DetectedActivity.UNKNOWN ||
				activity.activityType == DetectedActivity.TILTING ||
				activity.activityType == DetectedActivity.STILL
	}

	@Suppress("MagicNumber", "ComplexMethod")
	//todo add confidence calculation
	private fun determineActivityBySpeed(speed: Float, activity: ActivityInfo): ActivityInfo {
		return when {
			speed > MAX_RUN_SPEED_METERS_PER_SECOND && isOnFoot(activity) -> {
				ActivityInfo(DetectedActivity.IN_VEHICLE, 90)
			}
			speed > MAX_ON_FOOT_SPEED && isUnknownOnFootActivity(activity) -> {
				ActivityInfo(DetectedActivity.IN_VEHICLE, 75)
			}
			speed > MAX_WALK_SPEED_METERS_PER_SECOND &&
					(isNotConfidentWalk(activity) || isUnknownOnFootActivity(activity)) -> {
				ActivityInfo(DetectedActivity.RUNNING, 80)
			}
			speed > DEFINITELY_VEHICLE_SPEED && isUnknown(activity) -> {
				ActivityInfo(DetectedActivity.IN_VEHICLE, 75)
			}
			else -> {
				activity
			}
		}
	}

	@Suppress("ReturnCount", "MagicNumber")
	private fun determineActivity(speed: Float?, tempData: CollectionTempData): ActivityInfo {
		val activity = tempData.getActivity(this)

		//Bicycle activity is impossible to guess from position
		if (activity.activityType == DetectedActivity.ON_BICYCLE) return activity

		val stepCount = tempData.tryGet<Int>(StepDataProducer.NEW_STEPS_ARG)

		@Suppress("ComplexCondition")
		if (stepCount != null &&
				stepCount >= tempData.elapsedRealtimeNanos / Time.SECOND_IN_NANOSECONDS &&
				(speed == null || speed <= MAX_GUESS_RUN_SPEED_METERS_PER_SECOND)) {
			if (isOnFoot(activity)) {
				return activity
			} else if (isUnknown(activity)) {
				return ActivityInfo(DetectedActivity.ON_FOOT, 90)
			}
		}

		return if (speed != null) {
			determineActivityBySpeed(speed, activity)
		} else {
			activity
		}
	}

	private fun getSpeed(
			locationResult: LocationData,
			previousLocation: Location?,
			distance: Float
	): Float? {
		val location = locationResult.lastLocation
		return when {
			location.hasSpeed() -> location.speed
			previousLocation != null -> distance / ((location.time - previousLocation.time) / Time.SECOND_IN_MILLISECONDS)
			else -> null
		}
	}

	private fun determineSpeed(tempData: CollectionTempData): Float? {
		val locationData = tempData.tryGetLocationData() ?: return null
		val previousLocation = locationData.previousLocation ?: return null
		val distance = requireNotNull(locationData.distance)

		return getSpeed(locationData, previousLocation, distance)
	}

	override suspend fun onDataUpdated(
			tempData: CollectionTempData,
			collectionData: MutableCollectionData
	) {
		val speed = determineSpeed(tempData)

		collectionData.activity = determineActivity(speed, tempData)
	}

	override suspend fun onDisable(context: Context) = Unit

	override suspend fun onEnable(context: Context) = Unit

	companion object {
		const val CONFIDENT_CONFIDENCE = 70
		const val MAX_WALK_SPEED_METERS_PER_SECOND = 2.0f

		const val MAX_RUN_SPEED_METERS_PER_SECOND = 12.5f
		const val MAX_GUESS_RUN_SPEED_METERS_PER_SECOND = 9.0f
		const val MAX_ON_FOOT_SPEED = 4.5f

		const val DEFINITELY_VEHICLE_SPEED = 15
	}

}

