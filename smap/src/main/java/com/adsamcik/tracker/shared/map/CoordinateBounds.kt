package com.adsamcik.tracker.shared.map

import android.os.Parcelable
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MAX_LATITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MAX_LONGITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MIN_LATITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MIN_LONGITUDE
import com.adsamcik.tracker.shared.base.data.Location
import kotlinx.parcelize.Parcelize
import kotlin.math.max
import kotlin.math.min

/**
 * CoordinateBounds class allows passing of boundary information in GPS coordinates
 */
@Parcelize
data class CoordinateBounds(
		private var topBound: Double = MIN_LATITUDE,
		private var rightBound: Double = MIN_LONGITUDE,
		private var bottomBound: Double = MAX_LATITUDE,
		private var leftBound: Double = MAX_LONGITUDE
) : Parcelable {

	val top: Double get() = this.topBound
	val right: Double get() = this.rightBound
	val bottom: Double get() = this.bottomBound
	val left: Double get() = this.leftBound

	val width: Double get() = rightBound - leftBound
	val height: Double get() = topBound - bottomBound


	/**
	 * Update location bounds.
	 */
	fun updateBounds(collection: Iterable<Location>) {
		collection.forEach {
			topBound = max(it.latitude, topBound)
			bottomBound = min(it.latitude, bottomBound)
			rightBound = max(it.longitude, rightBound)
			leftBound = min(it.longitude, leftBound)
		}
	}
}

