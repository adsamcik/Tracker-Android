package com.adsamcik.tracker.common.data

import android.os.Parcel
import android.os.Parcelable
import com.adsamcik.tracker.common.extension.requireArrayList
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
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
) : Parcelable {
	constructor(parcel: Parcel) : this(
			parcel.requireArrayList(CellInfo),
			parcel.readInt())

	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeTypedList(registeredCells)
		parcel.writeInt(totalCount)
	}

	override fun describeContents(): Int {
		return 0
	}

	companion object CREATOR : Parcelable.Creator<CellData> {
		override fun createFromParcel(parcel: Parcel): CellData {
			return CellData(parcel)
		}

		override fun newArray(size: Int): Array<CellData?> {
			return arrayOfNulls(size)
		}
	}

}

