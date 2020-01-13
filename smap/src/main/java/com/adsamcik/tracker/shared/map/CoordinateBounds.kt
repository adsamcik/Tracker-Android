package com.adsamcik.tracker.shared.map

import android.os.Parcelable
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MAX_LATITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MAX_LONGITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MIN_LATITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MIN_LONGITUDE
import com.adsamcik.tracker.shared.base.data.Location
import kotlinx.android.parcel.Parcelize

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


	fun updateBounds(collection: Iterable<Location>) {
		if (collection.none()) return
		val first = collection.first()
		//todo improve this
		if (first.latitude > topBound) topBound = first.latitude
		if (first.latitude < bottomBound) bottomBound = first.latitude

		if (first.longitude > rightBound) rightBound = first.longitude
		if (first.longitude < leftBound) leftBound = first.longitude

		collection.forEach {
			if (it.latitude > topBound) {
				topBound = it.latitude
			} else if (it.latitude < bottomBound) {
				bottomBound = it.latitude
			}

			if (it.longitude > rightBound) {
				rightBound = it.longitude
			} else if (it.longitude < leftBound) {
				leftBound = it.longitude
			}
		}
	}

	/**
	 * Updates bounds of the CoordinateBounds object
	 */
	fun updateBounds(top: Double, right: Double, bottom: Double, left: Double) {
		this.topBound = top
		this.rightBound = right
		this.bottomBound = bottom
		this.leftBound = left
	}
}

