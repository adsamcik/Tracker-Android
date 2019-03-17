package com.adsamcik.signalcollector.data

import androidx.room.*
import com.adsamcik.signalcollector.utility.ActivityInfo

data class TimeActivity(val time: Long, @Embedded val activityInfo: ActivityInfo) {
}

@Entity(tableName = "activity", foreignKeys = [ForeignKey(entity = DatabaseLocation::class, parentColumns = ["id"], childColumns = ["location_id"])])
data class DatabaseActivity(@PrimaryKey val id: Int, @ColumnInfo(name = "location_id") val locationId: Int, @Embedded val timeActivity: TimeActivity)

@Entity(tableName = "debug_activity")
data class DatabaseDebugActivity(@PrimaryKey(autoGenerate = true) val id: Int, @Embedded val timeActivity: TimeActivity, val action: String?)