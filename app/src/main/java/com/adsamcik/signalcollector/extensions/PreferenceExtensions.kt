package com.adsamcik.signalcollector.extensions

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup

/**
 * Finds a {@link Preference} based on its title.
 *
 * @param title Title used for the search
 */
fun <T : Preference> PreferenceFragmentCompat.findDirectPreferenceByTitleTyped(title: CharSequence): T {
    @Suppress("UNCHECKED_CAST")
    return findDirectPreferenceByTitle(title) as T
}

/**
 * Finds a {@link Preference} based on its title.
 *
 * @param title Title used for the search
 */
fun PreferenceFragmentCompat.findDirectPreferenceByTitle(title: CharSequence): Preference? {
    return preferenceScreen?.findDirectPreferenceByTitle(title)
}


/**
 * Finds a {@link Preference} based on its key.
 *
 * @param title The title of the preference to retrieve.
 * @return The {@link Preference} with the key, or null.
 * @see PreferenceGroup#findPreference(CharSequence)
 */
fun <T : Preference> PreferenceFragmentCompat.findPreferenceTyped(title: CharSequence): T {
    @Suppress("UNCHECKED_CAST")
    return findPreference(title) as T
}

/**
 * Finds a {@link Preference} based on its key.
 *
 * @param titleId Title resource id of the preference
 * @return The {@link Preference} with the key, or null.
 * @see PreferenceGroup#findPreference(CharSequence)
 */
fun <T : Preference> PreferenceFragmentCompat.findPreferenceTyped(@StringRes titleId: Int): T {
    @Suppress("UNCHECKED_CAST")
    return findPreference(getString(titleId)) as T
}

/**
 * Finds a {@link Preference} based on its key.
 *
 * @param titleId Title resource id of the preference
 * @return The {@link Preference} with the key, or null.
 * @see PreferenceGroup#findPreference(CharSequence)
 */
fun PreferenceFragmentCompat.findPreference(@StringRes titleId: Int): Preference {
    @Suppress("UNCHECKED_CAST")
    return findPreference(getString(titleId))
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

/**
 * Utility method to get integer from SharedPreferences which has default values saved as String resource
 */
fun SharedPreferences.getInt(context: Context, @StringRes key: Int, @StringRes defaultResource: Int): Int {
    val resources = context.resources
    return getInt(resources.getString(key), resources.getString(defaultResource).toInt())
}

/**
 * Utility method to get string from SharedPreferences which has default values saved as String resource
 */
fun SharedPreferences.getString(context: Context, @StringRes key: Int, @StringRes defaultResource: Int): String {
    val resources = context.resources
    return getString(resources.getString(key), resources.getString(defaultResource))
}

/**
 * Utility method to get color from SharedPreferences which has default values saved as Integer resource
 */
@ColorInt
fun SharedPreferences.getColor(context: Context, @StringRes key: Int, @ColorRes defaultResource: Int): Int {
    val defaultColor = ContextCompat.getColor(context, defaultResource)
    return getInt(context.getString(key), defaultColor)
}