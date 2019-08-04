package com.adsamcik.signalcollector.common.extension

import android.os.Parcel
import android.os.Parcelable

fun Parcel.requireString(): String {
	val value = readString()
	return requireNotNull(value)
}

fun <T> Parcel.requireArray(creator: Parcelable.Creator<T>): ArrayList<T> {
	val value = createTypedArrayList(creator)
	return requireNotNull(value)
}