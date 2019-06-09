package com.adsamcik.signalcollector.preference.activity

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.activity.DetailActivity
import com.adsamcik.signalcollector.common.misc.extension.dp
import com.adsamcik.signalcollector.common.misc.extension.transaction
import com.adsamcik.signalcollector.common.preference.ModuleSettings
import com.adsamcik.signalcollector.module.Module
import com.adsamcik.signalcollector.preference.fragment.FragmentSettings
import com.adsamcik.signalcollector.preference.pages.*

/**
 * Settings Activity contains local settings and hosts debugging features
 * It is based upon Android's [Preference].
 */
class SettingsActivity : DetailActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
	lateinit var fragment: FragmentSettings

	private val backstack = mutableListOf<PreferenceScreen>()

	private val moduleSettingsList = mutableMapOf<Module, ModuleSettings>()

	private var activePage: PreferencePage? = null

	override fun onConfigure(configuration: Configuration) {
		configuration.elevation = 4.dp
		configuration.titleBarLayer = 2
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		createLinearContentParent(false)
		fragment = FragmentSettings()

		initializeModuleSettingsList()

		supportFragmentManager.transaction {
			replace(CONTENT_ID, fragment, FragmentSettings.TAG)
			runOnCommit { setPage(fragment, RootPage(moduleSettingsList)) }
		}

		title = getString(R.string.settings_title)
	}

	private fun initializeModuleSettingsList() {
		val modules = Module.getActiveModuleInfo(this)
		modules.forEach {
			try {
				val tClass = it.module.loadClass<ModuleSettings>("preference.${it.module.moduleName.capitalize()}Settings")
				val instance = tClass.newInstance()
				moduleSettingsList[it.module] = instance
			} catch (e: ClassNotFoundException) {
				e.printStackTrace()
				//this exception is ok, just don't add anything
			} catch (e: InstantiationException) {
				Reporter.report(e)
				e.printStackTrace()
			} catch (e: IllegalAccessException) {
				Reporter.report(e)
				e.printStackTrace()
			} catch (e: ClassCastException) {
				Reporter.report(e)
				e.printStackTrace()
			}
		}
	}

	override fun onBackPressed() {
		if (!pop()) super.onBackPressed()
	}

	private fun pop(): Boolean {
		return when {
			backstack.isEmpty() -> false
			backstack.size == 1 -> {
				fragment.setPreferencesFromResource(R.xml.app_preferences, null)
				backstack.clear()
				title = getString(R.string.settings_title)
				setPage(fragment, RootPage(moduleSettingsList))
				true
			}
			else -> {
				onPreferenceStartScreen(fragment, backstack[backstack.size - 2])
			}
		}
	}

	private fun setPage(caller: PreferenceFragmentCompat, page: PreferencePage) {
		activePage?.onExit(caller)
		activePage = page
		page.onEnter(caller)
	}


	private fun initializeStartScreen(caller: PreferenceFragmentCompat, key: String) {
		val r = resources
		val page = when (key) {
			r.getString(R.string.settings_debug_title) -> DebugPage()
			r.getString(R.string.settings_style_title) -> StylePage()
			r.getString(R.string.settings_tracking_title) -> TrackerPreferencePage()
			r.getString(R.string.settings_export_title) -> DataPage()
			else -> return
		}
		setPage(caller, page)
	}


	override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
		caller.preferenceScreen = pref
		val index = backstack.indexOf(pref)
		if (index >= 0) {
			for (i in backstack.size - 1 downTo index + 1) {
				backstack.removeAt(i)
			}
		} else {
			backstack.add(pref)
		}

		title = pref.title

		initializeStartScreen(caller, pref.title.toString())
		return true
	}
}
