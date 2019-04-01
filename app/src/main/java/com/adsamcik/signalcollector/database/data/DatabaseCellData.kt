package com.adsamcik.signalcollector.database.data

import androidx.room.*
import com.adsamcik.signalcollector.tracker.data.CellInfo
import com.adsamcik.signalcollector.tracker.data.CellType

@Entity(tableName = "cell_data", foreignKeys = [ForeignKey(entity = DatabaseLocation::class,
		parentColumns = ["id"],
		childColumns = ["location_id"],
		onDelete = ForeignKey.SET_NULL,
		onUpdate = ForeignKey.NO_ACTION)])
data class DatabaseCellData(
		@ColumnInfo(name = "location_id", index = true) val locationId: Long?,
		@ColumnInfo(name = "first_seen") var firstSeen: Long,
		@ColumnInfo(name = "last_seen") var lastSeen: Long,
		@Embedded val cellInfo: CellInfo) {
	@PrimaryKey(autoGenerate = false)
	var id: String = when (cellInfo.type) {
		CellType.Unknown -> throw IllegalAccessException()
		CellType.GSM, CellType.WCDMA, CellType.LTE -> cellInfo.mcc + cellInfo.mnc + cellInfo.cellId
		CellType.CDMA -> cellInfo.cellId.toString()
		CellType.NR -> TODO()
	}
}

class CellTypeTypeConverter {
	@TypeConverter
	fun fromCellType(value: CellType): Int = value.ordinal

	@TypeConverter
	fun toCellType(ordinal: Int): CellType = CellType.values()[ordinal]
}