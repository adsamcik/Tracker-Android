package com.adsamcik.signalcollector.map

import com.adsamcik.signalcollector.database.data.DatabaseLocation

/**
 * CoordinateBounds class allows passing of boundary information in GPS coordinates
 */
data class CoordinateBounds(private var topBound: Double = MapLayer.MIN_LATITUDE,
                            private var rightBound: Double = MapLayer.MIN_LONGITUDE,
                            private var bottomBound: Double = MapLayer.MAX_LATITUDE,
                            private var leftBound: Double = MapLayer.MAX_LONGITUDE) {

	val top: Double get() = this.topBound
	val right: Double get() = this.rightBound
	val bottom: Double get() = this.bottomBound
	val left: Double get() = this.leftBound

	val width: Double get() = rightBound - leftBound
	val height: Double get() = topBound - bottomBound


	fun updateBounds(collection: Collection<DatabaseLocation>) {
		if (collection.isEmpty()) return
		val first = collection.first()
		//todo improve this
		if (first.latitude > topBound)
			topBound = first.latitude
		if (first.latitude < bottomBound)
			bottomBound = first.latitude

		if (first.longitude > rightBound)
			rightBound = first.longitude
		if (first.longitude < leftBound)
			leftBound = first.longitude

		collection.forEach {
			if (it.latitude > topBound)
				topBound = it.latitude
			else if (it.latitude < bottomBound)
				bottomBound = it.latitude

			if (it.longitude > rightBound)
				rightBound = it.longitude
			else if (it.longitude < leftBound)
				leftBound = it.longitude
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
