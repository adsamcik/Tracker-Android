package com.adsamcik.tracker.common.language

import android.content.Context
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.preference.Preferences
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import java.util.*


object LocaleManager {
	fun getLocaleList(context: Context): Set<String> {
		return SplitInstallManagerFactory.create(context).installedLanguages
	}

	fun getLocale(context: Context): String {
		return Preferences.getPref(context).getStringRes(R.string.settings_language_key)
				?: Locale.getDefault().toLanguageTag()
	}
}
