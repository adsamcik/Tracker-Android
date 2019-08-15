package com.adsamcik.signalcollector.common.database.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.common.data.BaseLocation
import com.adsamcik.signalcollector.common.data.CellType

@Entity(tableName = "cell_location", indices = [Index(value = ["mcc", "mnc", "cell_id"]), Index("time")])
data class DatabaseCellLocation(
		val time: Long,
		val mcc: String,
		val mnc: String,
		@ColumnInfo(name = "cell_id")
		val cellId: Long,
		val type: CellType,
		val asu: Int,
		@Embedded
		val location: BaseLocation
) {
	@PrimaryKey(autoGenerate = true)
	var id: Long = 0L
}
