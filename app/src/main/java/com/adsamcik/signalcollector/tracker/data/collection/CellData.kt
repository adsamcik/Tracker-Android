package com.adsamcik.signalcollector.tracker.data.collection

import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = false)
data class CellData(
		/**
		 * List of registered cells
		 * Null if not collected
		 */
		val registeredCells: Array<CellInfo>,
		/**
		 * Total cell count
		 * default null if not collected.
		 */
		val totalCount: Int) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as CellData

		if (!Arrays.equals(registeredCells, other.registeredCells)) return false
		if (totalCount != other.totalCount) return false

		return true
	}

	override fun hashCode(): Int {
		var result = Arrays.hashCode(registeredCells)
		result = 31 * result + totalCount
		return result
	}
}