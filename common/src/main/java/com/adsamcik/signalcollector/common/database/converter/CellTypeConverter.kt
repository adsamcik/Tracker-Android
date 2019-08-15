package com.adsamcik.signalcollector.common.database.converter

import androidx.room.TypeConverter
import com.adsamcik.signalcollector.common.data.CellType

class CellTypeConverter {
	@TypeConverter
	fun fromCellType(value: CellType): Int = value.ordinal

	@TypeConverter
	fun toCellType(ordinal: Int): CellType = CellType.values()[ordinal]
}
