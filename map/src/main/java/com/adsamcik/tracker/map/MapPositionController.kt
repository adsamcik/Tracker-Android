package com.adsamcik.tracker.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.toDegrees
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.round

class MapPositionController(context: Context, map: GoogleMap) {
	private val positionSize: Int = 36.dp

	private var hasLocation = false
	private var hasValidActivity = false
	private var hasValidDirection = false

	private var userRadius: Circle = map.addCircle(
		CircleOptions()
			.fillColor(ContextCompat.getColor(context, R.color.color_user_accuracy))
			.visible(false)
			.center(LatLng(0.0, 0.0))
			.radius(0.0)
			.zIndex(-1f)
			.strokeWidth(0f)
	)

	private var userCenter: Marker? = map.addMarker(
		MarkerOptions()
			.flat(true)
			.visible(false)
			.position(LatLng(0.0, 0.0))
			.icon(
				getBitmapDescriptorFromVectorDrawable(
					context,
					R.drawable.ic_user_location
				)
			)
			.anchor(0.5f, 0.5f)
	)

	private var userDirection: Marker? = map.addMarker(
		MarkerOptions()
			.flat(true)
			.visible(false)
			.position(LatLng(0.0, 0.0))
			.icon(
				getBitmapDescriptorFromVectorDrawable(
					context,
					R.drawable.ic_user_direction
				)
			)
			.zIndex(1f)
			.anchor(0.5f, 0.5f)
	)

	private val activityIconList = GroupedActivity.values()
		.asSequence()
		.map { Pair(it, getBitmapDescriptorFromVectorDrawable(context, it.iconRes, 0.375f)) }
		.toMap()

	private var userActivity: Marker? = map.addMarker(
		MarkerOptions()
			.flat(true)
			.visible(false)
			.position(LatLng(0.0, 0.0))
			.anchor(0.5f, 0.5f)
			.zIndex(10f)
			.icon(activityIconList.entries.first().value)
	)


	private fun getBitmapDescriptorFromVectorDrawable(
		context: Context,
		drawableId: Int,
		factor: Float = 1f
	): BitmapDescriptor {
		val bitmap = getBitmapFromVectorDrawable(context, drawableId, factor)
		return BitmapDescriptorFactory.fromBitmap(bitmap)
	}

	private fun getBitmapFromVectorDrawable(
		context: Context,
		drawableId: Int,
		factor: Float = 1f
	): Bitmap {
		val drawable = requireNotNull(ContextCompat.getDrawable(context, drawableId))

		val bitmap = Bitmap.createBitmap(
			drawable.intrinsicWidth,
			drawable.intrinsicHeight,
			Bitmap.Config.ARGB_8888
		)
		val canvas = Canvas(bitmap)
		drawable.setBounds(0, 0, canvas.width, canvas.height)
		drawable.draw(canvas)

		return bitmap.scale(
			round(positionSize * factor).toInt(),
			round(positionSize * factor).toInt(),
			false
		)
	}

	@MainThread
	fun onNewPosition(latlng: LatLng, accuracy: Float) {
		hasLocation = true

		userRadius.apply {
			center = latlng
			radius = accuracy.toDouble()
			isVisible = true
		}

		userCenter?.apply {
			position = latlng
			isVisible = true
		}

		userDirection?.apply {
			position = latlng
			isVisible = hasValidDirection
		}

		userActivity?.apply {
			position = latlng
			isVisible = hasValidActivity
		}

	}

	@MainThread
	fun onNewActivity(groupedActivity: GroupedActivity) {
		if (groupedActivity == GroupedActivity.UNKNOWN) {
			hasValidActivity = false
			userActivity?.isVisible = false
		} else {
			hasValidActivity = true
			userActivity?.apply {
				setIcon(activityIconList[groupedActivity])
				isVisible = hasLocation
			}
		}
	}

	@MainThread
	fun onDirectionChanged(radians: Double) {
		hasValidDirection = true
		userDirection?.apply {
			isVisible = hasLocation
			rotation = radians.toDegrees().toFloat()
		}

	}
}
