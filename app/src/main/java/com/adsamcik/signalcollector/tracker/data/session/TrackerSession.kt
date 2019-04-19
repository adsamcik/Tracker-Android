package com.adsamcik.signalcollector.tracker.data.session

interface TrackerSession {
	val id: Long
	val start: Long
	val end: Long
	val collections: Int
	val distanceInM: Float
	val distanceOnFootInM: Float
	val distanceInVehicleInM: Float
	val steps: Int
}