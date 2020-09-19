package com.adsamcik.tracker.shared.base.assist

import android.annotation.TargetApi
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.*

@Suppress("unused")
object LocaleAssist {
	fun getConfig(context: Context): Configuration = context.resources.configuration

	@Suppress("deprecation")
	private fun getSystemLocaleLegacy(config: Configuration): Locale {
		return config.locale
	}

	@TargetApi(Build.VERSION_CODES.N)
	private fun getSystemLocale2(config: Configuration): Locale {
		return config.locales.get(0)
	}

	fun getSystemLocale(context: Context): Locale = getSystemLocale(getConfig(context))

	@Suppress("WeakerAccess")
	fun getSystemLocale(config: Configuration): Locale {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			getSystemLocale2(config)
		} else {
			getSystemLocaleLegacy(config)
		}
	}

	@Suppress("deprecation")
	private fun setSystemLocaleLegacy(config: Configuration, locale: Locale) {
		config.locale = locale
	}

	@TargetApi(Build.VERSION_CODES.N)
	private fun setSystemLocale2(config: Configuration, locale: Locale) {
		config.setLocale(locale)
	}

	fun setSystemLocale(context: Context, locale: Locale) =
			setSystemLocale(getConfig(context), locale)

	fun setSystemLocale(config: Configuration, locale: Locale) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			setSystemLocale2(config, locale)
		} else {
			setSystemLocaleLegacy(config, locale)
		}
	}
}
