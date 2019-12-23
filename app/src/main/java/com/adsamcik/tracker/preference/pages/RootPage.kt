package com.adsamcik.tracker.preference.pages

import android.content.Context
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.R
import com.adsamcik.tracker.activity.ui.SessionActivityActivity
import com.adsamcik.tracker.shared.utils.debug.Reporter
import com.adsamcik.tracker.common.extension.startActivity
import com.adsamcik.tracker.shared.utils.introduction.Introduction
import com.adsamcik.tracker.shared.utils.language.LocaleManager
import com.adsamcik.tracker.common.misc.SnackMaker
import com.adsamcik.tracker.license.LicenseActivity
import com.adsamcik.tracker.module.Module
import com.adsamcik.tracker.module.activity.ModuleActivity
import com.adsamcik.tracker.preference.findPreference
import com.adsamcik.tracker.preference.findPreferenceTyped
import com.adsamcik.tracker.preference.setOnClickListener
import com.adsamcik.tracker.shared.preferences.ModuleSettings
import com.adsamcik.tracker.shared.preferences.Preferences
import java.util.*

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

	override fun onExit(caller: PreferenceFragmentCompat) = Unit

	private fun initializeVersion(caller: PreferenceFragmentCompat) {
		val version = caller.findPreference(R.string.settings_app_version_key)
		version.title = String.format(
				"%1\$s - %2\$s",
				BuildConfig.VERSION_CODE,
				BuildConfig.VERSION_NAME
		)

		val resources = caller.resources

		val devEnabledKeyRes = R.string.settings_debug_enabled_key
		val devEnabledDefaultRes = R.string.settings_debug_enabled_default

		val debugPreference = caller.findPreference(R.string.settings_debug_key).apply {
			isVisible = Preferences.getPref(context)
					.getBooleanRes(devEnabledKeyRes, devEnabledDefaultRes)
		}

		version.setOnPreferenceClickListener {
			val context = it.context
			val preferences = Preferences.getPref(context)

			if (preferences.getBooleanRes(devEnabledKeyRes, devEnabledDefaultRes)) {
				showToast(context, resources.getString(R.string.settings_debug_already_available))
				return@setOnPreferenceClickListener false
			}

			clickCount++
			if (clickCount >= REQUIRED_DEV_TAP_COUNT) {
				preferences.edit {
					setBoolean(devEnabledKeyRes, true)
				}
				showToast(context, resources.getString(R.string.settings_debug_available))
				debugPreference.isVisible = true
				caller.findPreferenceTyped<SwitchPreferenceCompat>(devEnabledKeyRes)
						.isChecked = true
			} else if (clickCount >= REQUIRED_DEV_TAP_SNACK_COUNT) {
				val remainingClickCount = REQUIRED_DEV_TAP_COUNT - clickCount
				showToast(
						context,
						resources.getQuantityString(
								R.plurals.settings_debug_available_in, remainingClickCount,
								remainingClickCount
						)
				)
			}
			true
		}
	}

	private fun showToast(context: Context, string: String) = Toast.makeText(
			context,
			string,
			Toast.LENGTH_SHORT
	).show()

	private fun initializeLanguage(caller: PreferenceFragmentCompat) {
		caller.findPreferenceTyped<ListPreference>(R.string.settings_language_key).apply {
			val languages = LocaleManager.getLocaleList()
			entryValues = languages.toTypedArray()

			val localeList = languages.map { Locale(it) }
			entries = localeList.map { it.getDisplayName(it) }.toTypedArray()

			val currentLocale = LocaleManager.getLocale(context)
			var indexOf = languages.indexOf(currentLocale)

			if (indexOf < 0) {
				indexOf = languages.indexOfFirst {
					currentLocale.substringBefore('-') == it.substringBefore('-')
				}
			}

			if (indexOf >= 0) {
				setValueIndex(indexOf)
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
			modules.forEach {
				val preferenceScreen = preferenceManager.createPreferenceScreen(context)
				preferenceScreen.title = context.getString(it.key.titleRes).capitalize()
				preferenceScreen.key = "module-${it.key.moduleName}"
				preferenceScreen.setIcon(it.value.iconRes)
				it.value.onCreatePreferenceScreen(preferenceScreen)
				preferenceParent.addPreference(preferenceScreen)
			}
		}
	}

	companion object {
		private const val REQUIRED_DEV_TAP_COUNT = 7
		private const val REQUIRED_DEV_TAP_SNACK_COUNT = 4
	}
}

