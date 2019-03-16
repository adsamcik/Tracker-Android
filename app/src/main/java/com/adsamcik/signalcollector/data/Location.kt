package com.adsamcik.signalcollector.data

import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Location(
        @PrimaryKey
        val time: Long,
        @Json(name = "lat")
        @ColumnInfo(name = "lat")
        val latitude: Double,
        @Json(name = "lon")
        @ColumnInfo(name = "lon")
        val longitude: Double,
        @Json(name = "alt")
        @ColumnInfo(name = "alt")
        val altitude: Double,
        @Json(name = "acc")
        @ColumnInfo(name = "hor_acc")
        val horizontalAccuracy: Float) {

    constructor(location: android.location.Location) : this(location.time, location.latitude, location.longitude, location.altitude, location.accuracy)
    constructor(location: Location) : this(location.time, location.latitude, location.longitude, location.altitude, location.horizontalAccuracy)
}