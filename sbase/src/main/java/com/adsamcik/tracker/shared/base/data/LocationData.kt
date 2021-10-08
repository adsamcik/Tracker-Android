package com.adsamcik.tracker.shared.base.data

import android.location.Location

/**
 * Location data from raw provider in a single collection.
 */
data class LocationData(
		val locations: List<Location>,
		val previousLocation: Location?,
		val distance: Float?
) {
	val lastLocation: Location get() = locations.last()

	/**
	 * Builder for creating location data instance.
	 */
	class Builder {
		private var locationList: List<Location>? = null

		private var previousLocation: Location? = null

		private var distance: Float? = null

		/**
		 * Set a single location.
		 *
		 * @param location Location
		 */
		fun setLocation(location: Location) {
			setLocations(listOf(location))
		}

		/**
		 * Set a list of locations since last collection.
		 *
		 * @param locations List of locations
		 */
		fun setLocations(locations: List<Location>) {
			require(locationList == null)
			locationList = locations
		}

		/**
		 * Set previous location. Should be a location of previous collection,
		 * or any last location if this is first collection.
		 */
		fun setPreviousLocation(previousLocation: Location, distance: Float) {
			require(this.previousLocation == null)
			require(this.distance == null)

			this.previousLocation = previousLocation
			this.distance = distance
		}

		/**
		 * Build new instance of location data.
		 */
		fun build(): LocationData {
			val locationList = requireNotNull(locationList)
			return LocationData(locationList, previousLocation, distance)
		}
	}
}
