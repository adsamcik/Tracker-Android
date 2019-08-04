package com.adsamcik.signalcollector.common.data

import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = false)
data class CellData(
		/**
		 * List of registered cells
		 * Null if not collected
		 */
		val registeredCells: Collection<CellInfo>,
		/**
		 * Total cell count
		 * default null if not collected.
		 */
		val totalCount: Int)