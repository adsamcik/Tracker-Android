package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import com.adsamcik.signalcollector.preference.MutablePreferences
import com.adsamcik.signalcollector.preference.listener.OnPreferenceChanged
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.component.data.DataTrackerComponent

abstract class PreferenceDataTrackerComponent : DataTrackerComponent {
	protected abstract val enabledKeyRes: Int
	protected abstract val enabledDefaultRes: Int

	private var enabledKey: String = ""
	private var enabledDefault: Boolean = false

	fun initialize(context: Context) {
		val resources = context.resources
		enabledKey = resources.getString(enabledKeyRes)
		enabledDefault = resources.getString(enabledDefaultRes).toBoolean()
		MutablePreferences.addListener(enabledKey, object : OnPreferenceChanged<Boolean> {
			override fun onChange(value: Boolean) {

			}
		})
	}

	fun isEnabled(context: Context): Boolean = Preferences.getPref(context).getBoolean(enabledKey, enabledDefault)
	abstract fun onDestroy(context: Context)
}