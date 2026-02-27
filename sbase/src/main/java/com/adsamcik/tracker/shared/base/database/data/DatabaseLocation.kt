package com.adsamcik.tracker.shared.base.database.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location

/**
 * Database location object containing various location data and activity info.
 */
// TODO: Migrate to LocationSample. Active writers: DatabaseLocationComponent, GpxImport, JsonImport.
//  Active readers: map (DefaultLayerRegistry), statistics (RawLocationDataProducer, SummaryGenerator,
//  TripDetailPresenterViewModel), impexp (export), game (ExplorerChallengeProcessor).
@Entity(
	tableName = "location_data",
	indices = [
		Index(value = ["time", "lat", "lon"], name = "idx_location_time_lat_lon"),
		Index(value = ["lat", "lon"], name = "idx_location_lat_lon")
	]
)
data class DatabaseLocation(
		@Embedded val location: Location,
		@Embedded val activityInfo: ActivityInfo
) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0

	val latitude: Double get() = location.latitude

	val longitude: Double get() = location.longitude

	val altitude: Double? get() = location.altitude

	val time: Long get() = location.time
}

