package com.adsamcik.tracker.preference.pages

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.R
import com.adsamcik.tracker.activity.ui.SessionActivityActivity
import com.adsamcik.tracker.license.LicenseActivity
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.module.Module
import com.adsamcik.tracker.module.activity.ModuleActivity
import com.adsamcik.tracker.preference.component.DialogListPreference
import com.adsamcik.tracker.preference.findPreference
import com.adsamcik.tracker.preference.findPreferenceTyped
import com.adsamcik.tracker.preference.setOnClickListener
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.preferences.ModuleSettings
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.introduction.Introduction
import com.adsamcik.tracker.shared.utils.language.LocaleManager
import java.util.*

/**
 * Root preference page
 */
class RootPage(private val modules: Map<Module, ModuleSettings>) : PreferencePage {
	private var clickCount = 0

	private lateinit var snackMaker: SnackMaker

	override fun onEnter(caller: PreferenceFragmentCompat) {
		snackMaker = SnackMaker(caller.listView)

		caller.setOnClickListener(R.string.settings_module_enable_key) {
			it.context.startActivity<ModuleActivity> {}
		}

		caller.setOnClickListener(R.string.settings_licenses_key) {
			it.context.startActivity<LicenseActivity> {}
		}

		caller.setOnClickListener(R.string.settings_activity_key) {
			it.context.startActivity<SessionActivityActivity> { }
		}

		caller.findPreference(R.string.show_tips_key)
				.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
			if (newValue as Boolean) {
				Preferences.getPref(preference.context).edit {
					removeKeyByPrefix(Introduction.prefix)
				}
			}
			true
		}

		initializeVersion(caller)
		createModuleScreens(caller)
		initializeLanguage(caller)
	}

	override fun onExit(caller: PreferenceFragmentCompat): Unit = Unit

	private fun initializeVersion(caller: PreferenceFragmentCompat) {
		val version = caller.findPreference(R.string.settings_app_version_key)
		version.title = String.format(
				"%1\$s - %2\$s",
				BuildConfig.VERSION_CODE,
				BuildConfig.VERSION_NAME
		)

		val devEnabledKeyRes = R.string.settings_debug_enabled_key
		val devEnabledDefaultRes = R.string.settings_debug_enabled_default

		val debugPreference = caller.findPreference(R.string.settings_debug_key).apply {
			isVisible = Preferences.getPref(context)
					.getBooleanRes(devEnabledKeyRes, devEnabledDefaultRes)
		}

		caller.findPreference(R.string.settings_debug_enabled_key).apply {
			onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
				debugPreference.isVisible = newValue as Boolean
				true
			}
		}
	}

	private fun initializeLanguage(caller: PreferenceFragmentCompat) {
		caller.findPreferenceTyped<DialogListPreference>(R.string.settings_language_key).apply {
			val languages = LocaleManager.getLocaleList()

			val localeList = languages.map { Locale(it) }
			val entries = localeList.map { it.getDisplayName(it) }

			setValues(entries, languages)

			val currentLocale = LocaleManager.getLocale(context)
			var indexOf = languages.indexOf(currentLocale)

			if (indexOf < 0) {
				indexOf = languages.indexOfFirst {
					currentLocale.substringBefore('-') == it.substringBefore('-')
				}
			}

			if (indexOf >= 0) {
				setIndex(indexOf)
			} else {
				Reporter.report("Could not find index for language $currentLocale")
			}

			setOnPreferenceChangeListener { _, _ ->
				caller.requireActivity().recreate()
				true
			}
		}
	}


	private fun createModuleScreens(caller: PreferenceFragmentCompat) {
		val preferenceManager = caller.preferenceManager
		val preferenceParent = caller.findPreferenceTyped<PreferenceGroup>(R.string.settings_module_group_key)
		val context = preferenceParent.context

		if (modules.isEmpty()) {
			preferenceParent.isVisible = false
		} else {
			val locale = Locale.getDefault()
			modules.forEach { module ->
				val preferenceScreen = preferenceManager.createPreferenceScreen(context)
				preferenceScreen.title = context.getString(module.key.titleRes)
					.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
				preferenceScreen.key = "module-${module.key.moduleName}"
				preferenceScreen.setIcon(module.value.iconRes)
				module.value.onCreatePreferenceScreen(preferenceScreen)
				preferenceParent.addPreference(preferenceScreen)
			}
		}
	}

	companion object {
		private const val REQUIRED_DEV_TAP_COUNT = 7
		private const val REQUIRED_DEV_TAP_SNACK_COUNT = 4
	}
}

