package com.adsamcik.signalcollector.common.database.data

import androidx.room.ColumnInfo

open class TrackerSessionSummary(var duration: Long,
                                 var collections: Int,
                                 @ColumnInfo(name = "distance")
                                 var distanceInM: Float,
                                 @ColumnInfo(name = "distance_on_foot")
                                 var distanceOnFootInM: Float,
                                 @ColumnInfo(name = "distance_in_vehicle")
                                 var distanceInVehicleInM: Float,
                                 var steps: Int
)


open class TrackerSessionTimeSummary(var time: Long,
                                     duration: Long,
                                     collections: Int,
                                     distanceInM: Float,
                                     distanceOnFootInM: Float,
                                     distanceInVehicleInM: Float,
                                     steps: Int
) : TrackerSessionSummary(duration, collections, distanceInM, distanceOnFootInM, distanceInVehicleInM, steps)
