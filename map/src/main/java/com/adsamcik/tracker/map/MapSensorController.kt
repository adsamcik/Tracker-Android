package com.adsamcik.tracker.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import androidx.appcompat.widget.AppCompatImageButton
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.ActivityInfo
import com.adsamcik.tracker.common.extension.hasLocationPermission
import com.adsamcik.tracker.common.extension.sensorManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

@Suppress("TooManyFunctions")
internal class MapSensorController(
		context: Context,
		private val map: GoogleMap,
		private val eventListener: MapEventListener
) : SensorEventListener, CoroutineScope {
	private var followMyPosition: Boolean = false

	private val sensorManager: SensorManager = context.sensorManager
	private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

	private var lastUserPos: LatLng? = null
	private var targetPosition: LatLng = LatLng(0.0, 0.0)
	private var targetTilt: Float = 0f
	private var targetBearing: Float = 0f
	private var targetZoom: Float = 0f

	private val mapPositionController = MapPositionController(context, map)

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job


	//Orientation
	private var prevRotation: Float = 0f

	private var orientation = FloatArray(3)
	private var rotationMatrix = FloatArray(9)

	//Location
	private var isSubscribed = false
	private val locationCallback = object : LocationCallback() {
		override fun onLocationResult(locationResult: LocationResult?) {
			locationResult ?: return

			onNewLocationAvailable(locationResult.lastLocation)
		}
	}

	private fun onNewLocationAvailable(location: Location) {
		val latlng = LatLng(location.latitude, location.longitude)
		mapPositionController.onNewPosition(latlng, location.accuracy)
		setUserPosition(latlng)
	}


	init {
		initializePositions()
		subscribeToLocationUpdates(context, true)

		ActivityRequestManager.requestActivity(
				context,
				ActivityRequestData(
						this::class,
						ActivityChangeRequestData(10, this::onActivityUpdate)
				)
		)

		sensorManager.registerListener(
				this,
				rotationVector,
				SensorManager.SENSOR_DELAY_NORMAL,
				SensorManager.SENSOR_DELAY_UI
		)
	}

	private fun onActivityUpdate(context: Context, activity: ActivityInfo, elapsedTime: Long) {
		launch {
			mapPositionController.onNewActivity(activity.groupedActivity)
		}
	}

	/**
	 * Registers map to the [MapSensorController]. Initializing camera position and registering camera listeners.
	 */
	private fun initializePositions() {
		val cameraPosition = map.cameraPosition
		targetPosition = cameraPosition.target ?: LatLng(0.0, 0.0)
		targetTilt = cameraPosition.tilt
		targetBearing = cameraPosition.bearing
		targetZoom = cameraPosition.zoom
	}

	fun subscribeToLocationUpdates(context: Context, moveToCurrentLocation: Boolean = false) {
		if (!isSubscribed && context.hasLocationPermission) {
			val locationClient = LocationServices.getFusedLocationProviderClient(context)
			val locationRequest = LocationRequest().apply {
				this.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
				this.interval = LOCATION_UPDATE_INTERVAL
			}

			Assist.ensureLooper()

			locationClient.requestLocationUpdates(
					locationRequest,
					locationCallback,
					Looper.myLooper()
			)
			if (moveToCurrentLocation) {
				locationClient.lastLocation.addOnCompleteListener {
					if (it.isSuccessful) {
						it.result?.let { result ->
							onNewLocationAvailable(result)
							moveTo(LatLng(result.latitude, result.longitude), false)
						}
					}
				}
			}

			isSubscribed = true
		}
	}

	fun onDestroy(context: Context) {
		val locationClient = LocationServices.getFusedLocationProviderClient(context)
		locationClient.removeLocationUpdates(locationCallback)
		isSubscribed = false

		if (rotationVector != null) sensorManager.unregisterListener(this, rotationVector)

		ActivityRequestManager.removeActivityRequest(context, this::class)
	}


	private fun setUserPosition(latlng: LatLng) {
		this.lastUserPos = latlng

		if (followMyPosition) {
			moveTo(latlng)
		}
	}

	/**
	 * Call to stop updating camera position to look at user's position.
	 *
	 * @param returnToDefault True if you want to return any tilt to default orientation
	 */
	fun stopUsingUserPosition(button: AppCompatImageButton, returnToDefault: Boolean) {
		if (followMyPosition) {
			this.followMyPosition = false
			button.setImageResource(com.adsamcik.tracker.common.R.drawable.ic_gps_not_fixed_black_24dp)
		}

		onCameraMoveStartedListener?.run {
			onCameraMoveStartedListener = null
			eventListener -= this
		}
	}


	/**
	 * Animates movement from current position to target position and zoom.
	 *
	 * @param position Target position
	 * @param zoom Target zoom
	 */
	fun animateToPositionZoom(position: LatLng, zoom: Float) {
		targetPosition = position
		targetZoom = zoom
		animateTo(position, zoom, targetTilt, targetBearing, DURATION_STANDARD)
	}

	fun teleportToPositionZoom(position: LatLng, zoom: Float) {
		targetPosition = position
		targetZoom = zoom
		map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, zoom))
	}

	/**
	 * Animates bearing to the desired bearing value.
	 *
	 * @param bearing Target bearing
	 */
	fun animateToBearing(bearing: Float) {
		animateTo(targetPosition, targetZoom, targetTilt, bearing, DURATION_SHORT)
		targetBearing = bearing
	}

	/**
	 * Animates tilt to the target tilt value.
	 *
	 * @param tilt target tilt value
	 */
	fun animateToTilt(tilt: Float) {
		targetTilt = tilt
		animateTo(targetPosition, targetZoom, tilt, targetBearing, DURATION_SHORT)
	}

	private fun animateTo(
			position: LatLng?,
			zoom: Float,
			tilt: Float,
			bearing: Float,
			duration: Int
	) {
		val builder = CameraPosition.Builder(map.cameraPosition).target(position).zoom(zoom)
				.tilt(tilt).bearing(bearing)
		map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), duration, null)
	}

	private var onCameraMoveStartedListener: GoogleMap.OnCameraMoveStartedListener? = null

	fun onMyPositionButtonClick(button: AppCompatImageButton) {
		if (followMyPosition) {
			stopUsingUserPosition(button, false)
		} else {
			button.setImageResource(com.adsamcik.tracker.common.R.drawable.ic_gps_fixed_black_24dp)
			this.followMyPosition = true

			if (lastUserPos != null) {
				moveTo(requireNotNull(lastUserPos))
			}

			onCameraMoveStartedListener = GoogleMap.OnCameraMoveStartedListener {
				if (it == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
					stopUsingUserPosition(button, true)
				}
			}.also {
				eventListener += it
			}
		}
	}

	private fun moveTo(latlng: LatLng, animate: Boolean = true) {
		val zoom = map.cameraPosition.zoom.coerceIn(MIN_MOVE_ZOOM, MAX_MOVE_ZOOM)
		if (animate) {
			animateToPositionZoom(latlng, zoom)
		} else {
			teleportToPositionZoom(latlng, zoom)
		}

	}

	private fun updateRotation(rotation: Float) {
		if (prevRotation != rotation) {
			animateToBearing(rotation)
		}
	}

	override fun onSensorChanged(event: SensorEvent) {
		if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
			// calculate th rotation matrix
			SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
			// getPref the azimuth value (orientation[0]) in degree
			val orientation = SensorManager.getOrientation(
					rotationMatrix,
					orientation
			)[0].toDouble()
			//updateRotation()
			val directionRadians = (orientation + 2.0).rem(2.0)
			mapPositionController.onDirectionChanged(directionRadians)
		}
	}

	override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
		//no need to check
	}

	companion object {
		private const val DURATION_STANDARD = 1000
		private const val DURATION_SHORT = 200

		private const val MAX_MOVE_ZOOM = 17f
		private const val MIN_MOVE_ZOOM = 16f

		private const val LOCATION_UPDATE_INTERVAL = 2 * Time.SECOND_IN_MILLISECONDS
	}
}

