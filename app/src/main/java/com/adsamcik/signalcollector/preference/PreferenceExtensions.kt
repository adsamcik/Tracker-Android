package com.adsamcik.signalcollector.preference

import androidx.annotation.StringRes
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
fun PreferenceFragmentCompat.findDirectPreferenceByTitle(title: CharSequence): Preference {
	return preferenceScreen?.findDirectPreferenceByTitle(title)
			?: throw PreferenceNotFoundException("Preference with title $title not found")
}


/**
 * Finds a {@link Preference} based on its key.
 *
 * @param key The title of the preference to retrieve.
 * @return The {@link Preference} with the key, or null.
 * @see PreferenceGroup#findPreference(CharSequence)
 */
fun <T : Preference> PreferenceFragmentCompat.findPreferenceTyped(key: CharSequence): T {
	return findPreference(key)
			?: throw PreferenceNotFoundException("Preference with title $key not found")
}

/**
 * Finds a {@link Preference} based on its key.
 *
 * @param titleId Title resource id of the preference
 * @return The {@link Preference} with the key, or null.
 * @see PreferenceGroup#findPreference(CharSequence)
 */
fun <T : Preference> PreferenceFragmentCompat.findPreferenceTyped(@StringRes titleId: Int): T {
	return findPreference(getString(titleId))
			?: throw PreferenceNotFoundException("Preference with title id $titleId not found")
}

/**
 * Finds a {@link Preference} based on its key.
 *
 * @param titleId Title resource id of the preference
 * @return The {@link Preference} with the key, or null.
 * @see PreferenceGroup#findPreference(CharSequence)
 */
fun PreferenceFragmentCompat.findPreference(@StringRes titleId: Int): Preference {
	return findPreferenceTyped(titleId)
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

class PreferenceNotFoundException : Exception {
	constructor() : super()
	constructor(message: String?) : super(message)
	constructor(message: String?, cause: Throwable?) : super(message, cause)
	constructor(cause: Throwable?) : super(cause)
	constructor(message: String?, cause: Throwable?, enableSuppression: Boolean, writableStackTrace: Boolean) : super(message, cause, enableSuppression, writableStackTrace)
}