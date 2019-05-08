package com.adsamcik.signalcollector.preference.pages

import android.content.Context
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Tips
import com.adsamcik.signalcollector.app.activity.LicenseActivity
import com.adsamcik.signalcollector.common.misc.SnackMaker
import com.adsamcik.signalcollector.common.misc.extension.startActivity
import com.adsamcik.signalcollector.common.preference.ModuleSettings
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.module.Module
import com.adsamcik.signalcollector.module.activity.ModuleActivity
import com.adsamcik.signalcollector.preference.findDirectPreferenceByTitle
import com.adsamcik.signalcollector.preference.findPreference
import com.adsamcik.signalcollector.preference.findPreferenceTyped
import com.adsamcik.signalcollector.preference.setOnClickListener

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

		val resources = caller.resources

		val devKeyRes = R.string.settings_debug_key
		val devDefaultRes = R.string.settings_debug_default
		val debugTitle = resources.getString(R.string.settings_debug_title)

		caller.findDirectPreferenceByTitle(debugTitle).apply {
			isVisible = Preferences.getPref(context).getBooleanRes(devKeyRes, devDefaultRes)
		}

		caller.findPreference(R.string.show_tips_key).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
			if (newValue as Boolean) {
				Preferences.getPref(preference.context).edit {
					remove(Tips.getTipsPreferenceKey(Tips.HOME_TIPS))
					remove(Tips.getTipsPreferenceKey(Tips.MAP_TIPS))
				}
			}
			true
		}

		val version = caller.findPreference(R.string.settings_app_version_key)
		version.title = String.format("%1\$s - %2\$s", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)

		version.setOnPreferenceClickListener {
			val context = it.context
			val preferences = Preferences.getPref(context)

			if (preferences.getBooleanRes(devKeyRes, devDefaultRes)) {
				showToast(context, resources.getString(R.string.settings_debug_already_available))
				return@setOnPreferenceClickListener false
			}

			clickCount++
			if (clickCount >= 7) {
				preferences.edit {
					setBoolean(devKeyRes, true)
				}
				showToast(context, resources.getString(R.string.settings_debug_available))
				caller.findDirectPreferenceByTitle(debugTitle).isVisible = true
				caller.findPreferenceTyped<SwitchPreferenceCompat>(devKeyRes).isChecked = true
			} else if (clickCount >= 4) {
				val remainingClickCount = 7 - clickCount
				showToast(context, resources.getQuantityString(R.plurals.settings_debug_available_in, remainingClickCount, remainingClickCount))
			}
			true
		}

		createModuleScreens(caller)
	}

	override fun onExit(caller: PreferenceFragmentCompat) {

	}

	private fun showToast(context: Context, string: String) = Toast.makeText(context, string, Toast.LENGTH_SHORT).show()


	private fun createModuleScreens(caller: PreferenceFragmentCompat) {
		val preferenceManager = caller.preferenceManager
		val preferenceParent = caller.findPreferenceTyped<PreferenceGroup>(R.string.settings_module_group_key)
		val context = preferenceParent.context

		if (modules.isEmpty()) {
			preferenceParent.isVisible = false
		} else {
			modules.forEach {
				val preferenceScreen = preferenceManager.createPreferenceScreen(context)
				preferenceScreen.setTitle(it.key.titleRes)
				preferenceScreen.key = "module-${it.key.moduleName}"
				preferenceScreen.setIcon(it.value.iconRes)
				it.value.onCreatePreferenceScreen(preferenceScreen)
				preferenceParent.addPreference(preferenceScreen)
			}
		}
	}

}