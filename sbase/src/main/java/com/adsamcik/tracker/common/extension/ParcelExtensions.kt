package com.adsamcik.tracker.common.extension

import android.os.Parcel
import android.os.Parcelable

fun Parcel.requireString(): String {
	val value = readString()
	return requireNotNull(value)
}

fun <T : Parcelable> Parcel.requireArrayList(creator: Parcelable.Creator<T>): ArrayList<T> {
	val value = createTypedArrayList(creator)
	return requireNotNull(value)
}

fun <T : Parcelable> Parcel.requireParcelable(loader: ClassLoader?): T {
	val value = readParcelable<T>(loader)
	return requireNotNull(value)
}
