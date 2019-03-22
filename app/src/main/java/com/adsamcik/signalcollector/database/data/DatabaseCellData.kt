package com.adsamcik.signalcollector.database.data

import androidx.room.*
import com.adsamcik.signalcollector.data.CellInfo

@Entity(tableName = "cell_data", foreignKeys = [ForeignKey(entity = DatabaseLocation::class,
		parentColumns = ["id"],
		childColumns = ["location_id"],
		onDelete = ForeignKey.SET_NULL,
		onUpdate = ForeignKey.NO_ACTION)])
data class DatabaseCellData(
		@ColumnInfo(name = "location_id") val locationId: Long?,
		@Embedded val cellInfo: CellInfo) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0
}