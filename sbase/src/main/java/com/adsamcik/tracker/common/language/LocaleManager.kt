package com.adsamcik.tracker.common.language

import android.content.Context
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.preferences.Preferences
import java.util.*


object LocaleManager {
	private const val CZECH = "cs"
	private const val ENGLISH = "en"

	fun getLocaleList(): List<String> {
		return listOf(ENGLISH, CZECH)
	}

	fun getLocale(context: Context): String {
		return com.adsamcik.tracker.common.preferences.Preferences.getPref(context).getStringRes(R.string.settings_language_key)
				?: Locale.getDefault().toLanguageTag()
	}
}
