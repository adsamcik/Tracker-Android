package com.adsamcik.signalcollector.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Location(
        @Json(name = "lat")
        val latitude: Double,
        @Json(name = "lon")
        val longitude: Double,
        @Json(name = "alt")
        val altitude: Double,
        @Json(name = "acc")
        val horizontalAccuracy: Float) {

    constructor(location: android.location.Location) : this(location.latitude, location.longitude, location.altitude, location.accuracy)
    constructor(location: Location) : this(location.latitude, location.longitude, location.altitude, location.horizontalAccuracy)
}

data class TimeLocation(val location: Location, val time: Long) {
    constructor(rawData: RawData) : this(Location(rawData.location!!), rawData.time)
}