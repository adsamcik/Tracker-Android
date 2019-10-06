package com.adsamcik.tracker.common.language

import android.annotation.TargetApi
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import java.util.*


class LocaleContextWrapper(base: Context?) : ContextWrapper(base) {
	companion object {
		@Suppress("deprecation")
		private fun getSystemLocaleLegacy(config: Configuration): Locale? {
			return config.locale
		}

		@TargetApi(Build.VERSION_CODES.N)
		private fun getSystemLocale(config: Configuration): Locale? {
			return config.locales.get(0)
		}

		@Suppress("deprecation")
		private fun setSystemLocaleLegacy(config: Configuration, locale: Locale) {
			config.locale = locale
		}

		@TargetApi(Build.VERSION_CODES.N)
		private fun setSystemLocale(config: Configuration, locale: Locale) {
			config.setLocale(locale)
		}

		fun wrap(context: Context): ContextWrapper {
			val language: String = LocaleManager.getLocale(context)
			val config: Configuration = context.resources.configuration
			val sysLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				getSystemLocale(config)
			} else {
				getSystemLocaleLegacy(config)
			}
			if (language.isNotEmpty() && sysLocale?.language != language) {
				val localeSplit = language.split('-', '_')
				val locale = Locale(localeSplit[0], localeSplit[1])
				Locale.setDefault(locale)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					setSystemLocale(config, locale)
				} else {
					setSystemLocaleLegacy(config, locale)
				}
			}
			return LocaleContextWrapper(context.createConfigurationContext(config))
		}
	}
}
