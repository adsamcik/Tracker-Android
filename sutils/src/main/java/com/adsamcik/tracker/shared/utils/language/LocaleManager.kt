package com.adsamcik.tracker.shared.utils.language

import android.content.Context
import com.adsamcik.tracker.shared.preferences.Preferences
import java.util.*

/**
 * Provides information about supported locales.
 */
object LocaleManager {
	private const val CZECH = "cs"
	private const val ENGLISH = "en"

	fun getLocaleList(): List<String> {
		return listOf(ENGLISH, CZECH)
	}

	fun getLocale(context: Context): String {
		return Preferences
				.getPref(context)
				.getStringRes(com.adsamcik.tracker.shared.preferences.R.string.settings_language_key)
				?: Locale.getDefault().toLanguageTag()
	}
}
