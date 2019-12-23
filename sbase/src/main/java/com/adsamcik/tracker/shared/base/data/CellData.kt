package com.adsamcik.tracker.shared.base.data

import android.os.Parcelable
import com.adsamcik.tracker.shared.base.data.CellInfo
import com.squareup.moshi.JsonClass
import kotlinx.android.parcel.Parcelize

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
