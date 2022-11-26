package com.adsamcik.tracker.shared.base.extension

import android.content.Intent
import android.os.Build
import android.os.Bundle
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

inline fun <reified T : Parcelable> Parcel.requireParcelable(loader: ClassLoader?): T {
    val value = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> readParcelable(
            loader,
            T::class.java
        )
        else -> @Suppress("DEPRECATION") readParcelable<T>(loader)
    }
    return requireNotNull(value)
}

inline fun <reified T : Parcelable> Bundle.getParcelableSafe(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelable(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelable(key)
}

inline fun <reified T : Parcelable> Bundle.requireParcelableSafe(key: String): T =
    requireNotNull(getParcelableSafe<T>(key))

inline fun <reified T : Parcelable> Intent.requireParcelableExtraSafe(key: String): T =
    requireNotNull(
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(
                key,
                T::class.java
            )
            else -> @Suppress("DEPRECATION") getParcelableExtra(key)
        }
    )
