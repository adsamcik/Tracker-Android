package com.adsamcik.tracker.shared.base.database.converter

import androidx.room.TypeConverter
import com.adsamcik.tracker.shared.base.data.CellType

class CellTypeConverter {
	@TypeConverter
	fun fromCellType(value: CellType): Int = value.ordinal

	@TypeConverter
	fun toCellType(ordinal: Int): CellType = CellType.values()[ordinal]
}
