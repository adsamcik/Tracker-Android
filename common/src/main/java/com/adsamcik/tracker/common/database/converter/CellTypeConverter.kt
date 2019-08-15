package com.adsamcik.tracker.common.database.converter

import androidx.room.TypeConverter
import com.adsamcik.tracker.common.data.CellType

class CellTypeConverter {
	@TypeConverter
	fun fromCellType(value: CellType): Int = value.ordinal

	@TypeConverter
	fun toCellType(ordinal: Int): CellType = CellType.values()[ordinal]
}
