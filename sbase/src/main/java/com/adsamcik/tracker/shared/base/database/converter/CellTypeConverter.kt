package com.adsamcik.tracker.shared.base.database.converter

import androidx.room.TypeConverter
import com.adsamcik.tracker.shared.base.data.CellType

class CellTypeConverter {
	@TypeConverter
	fun fromCellType(value: CellType): String = value.name

	@TypeConverter
	fun toCellType(name: String): CellType = CellType.valueOf(name)
}
