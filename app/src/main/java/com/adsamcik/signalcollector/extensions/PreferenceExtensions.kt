package com.adsamcik.signalcollector.extensions

import android.content.Context
import android.content.SharedPreferences
import android.support.annotation.ColorInt
import android.support.annotation.ColorRes
import android.support.annotation.StringRes
import android.support.v4.content.ContextCompat
import android.support.v7.preference.Preference
import android.support.v7.preference.PreferenceFragmentCompat
import android.support.v7.preference.PreferenceGroup

/**
 * Searches for preference using title
 *
 * @param title Title used for the search
 */
fun PreferenceFragmentCompat.findDirectPreferenceByTitle(title: CharSequence): Preference? {
    return preferenceScreen?.findDirectPreferenceByTitle(title)
}

/**
 * Searches for preference using title
 *
 * @param title Title used for the search
 */
fun PreferenceGroup.findDirectPreferenceByTitle(title: CharSequence): Preference? {
    val count = preferenceCount
    for (i in 0 until count) {
        val preference = getPreference(i)
        val prefTitle = preference.title

        if (prefTitle == title)
            return preference
    }
    return null
}

fun SharedPreferences.getInt(context: Context, @StringRes key: Int, @StringRes defaultResource: Int): Int {
    val resources = context.resources
    return getInt(resources.getString(key), resources.getString(defaultResource).toInt())
}

fun SharedPreferences.getString(context: Context, @StringRes key: Int, @StringRes defaultResource: Int): String {
    val resources = context.resources
    return getString(resources.getString(key), resources.getString(defaultResource))
}

@ColorInt
fun SharedPreferences.getColor(context: Context, @StringRes key: Int, @ColorRes defaultResource: Int): Int {
    val defaultColor = ContextCompat.getColor(context, defaultResource)
    return getInt(context.getString(key), defaultColor)
}