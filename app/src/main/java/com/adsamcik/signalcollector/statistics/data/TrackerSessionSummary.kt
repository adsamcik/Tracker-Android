package com.adsamcik.signalcollector.statistics.data

import androidx.room.ColumnInfo

data class TrackerSessionSummary(var duration: Long,
                                 var collections: Int,
                                 @ColumnInfo(name = "distance")
                                 var distanceInM: Float,
                                 var steps: Int)


data class TrackerSessionTimeSummary(var time: Long,
                                     var duration: Long,
                                     var collections: Int,
                                     @ColumnInfo(name = "distance")
                                     var distanceInM: Float,
                                     var steps: Int)