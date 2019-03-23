package com.adsamcik.signalcollector.utility

import com.adsamcik.signalcollector.data.MapLayer

/**
 * CoordinateBounds class allows passing of boundary information in GPS coordinates
 */
data class CoordinateBounds(private var topBound: Double = MapLayer.MIN_LATITUDE,
                            private var rightBound: Double = MapLayer.MIN_LONGITUDE,
                            private var bottomBound: Double = MapLayer.MAX_LATITUDE,
                            private var leftBound: Double = MapLayer.MAX_LONGITUDE) {

	val top get() = this.topBound
	val right get() = this.rightBound
	val bottom get() = this.bottomBound
	val left get() = this.leftBound

	val width get() = rightBound - leftBound
	val height get() = topBound - bottomBound

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
