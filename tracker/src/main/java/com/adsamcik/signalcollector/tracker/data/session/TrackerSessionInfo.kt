package com.adsamcik.signalcollector.tracker.data.session

import android.os.Parcel
import android.os.Parcelable

data class TrackerSessionInfo(val isInitiatedByUser: Boolean) : Parcelable {
	constructor(parcel: Parcel) : this(parcel.readByte() != 0.toByte())

	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeByte(if (isInitiatedByUser) 1 else 0)
	}

	override fun describeContents(): Int {
		return 0
	}

	companion object CREATOR : Parcelable.Creator<TrackerSessionInfo> {
		override fun createFromParcel(parcel: Parcel): TrackerSessionInfo {
			return TrackerSessionInfo(parcel)
		}

		override fun newArray(size: Int): Array<TrackerSessionInfo?> {
			return arrayOfNulls(size)
		}
	}
}
