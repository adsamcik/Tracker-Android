package com.adsamcik.tracker.shared.utils.language

import android.content.Context
import android.content.ContextWrapper
import com.adsamcik.tracker.shared.base.assist.LocaleAssist
import java.util.*


class LocaleContextWrapper(base: Context?) : ContextWrapper(base) {
	companion object {
		fun wrap(context: Context): ContextWrapper {
			val language: String = LocaleManager.getLocale(context)
			val config = LocaleAssist.getConfig(context)
			val localeSplit = language.split('-', '_')
			val locale = if (localeSplit.size == 1) {
				Locale(language)
			} else {
				Locale(localeSplit[0], localeSplit[1])
			}
			Locale.setDefault(locale)
			LocaleAssist.setSystemLocale(config, locale)
			return LocaleContextWrapper(context.createConfigurationContext(config))
		}
	}
}
