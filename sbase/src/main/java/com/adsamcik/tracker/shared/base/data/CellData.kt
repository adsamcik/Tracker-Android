package com.adsamcik.tracker.shared.base.data

import android.os.Parcelable
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize

/**
 * Data class containing information about registered cells
 * (only cells with any reliable information) and total count.
 */
@JsonClass(generateAdapter = false)
@Parcelize
data class CellData(
		/**
		 * List of registered cells
		 * Null if not collected
		 */
		val registeredCells: List<CellInfo>,
		/**
		 * Total cell count
		 * default null if not collected.
		 */
		val totalCount: Int
) : Parcelable
