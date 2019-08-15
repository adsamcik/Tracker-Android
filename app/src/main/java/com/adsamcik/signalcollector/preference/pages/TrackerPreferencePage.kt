package com.adsamcik.signalcollector.preference.pages

import android.content.pm.PackageManager
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.misc.SnackMaker
import com.adsamcik.signalcollector.preference.findPreference
import com.adsamcik.signalcollector.preference.findPreferenceTyped
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.ActivityWatcherService

class TrackerPreferencePage : PreferencePage {

	private lateinit var snackMaker: SnackMaker

	private fun validateEnablePreference(locationEnabled: Boolean,
	                                     wifiEnabled: Boolean,
	                                     cellEnabled: Boolean) = locationEnabled.or(wifiEnabled).or(cellEnabled)

	override fun onExit(caller: PreferenceFragmentCompat) {}

	override fun onEnter(caller: PreferenceFragmentCompat) {
		val context = caller.requireContext()
		snackMaker = SnackMaker(caller.view!!)

		caller.findPreference(R.string.settings_tracking_activity_key)
				.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			ActivityWatcherService.onAutoTrackingPreferenceChange(context, newValue as Int)
			return@OnPreferenceChangeListener true
		}

		caller.findPreference(R.string.settings_activity_watcher_key)
				.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			ActivityWatcherService.onWatcherPreferenceChange(context, newValue as Boolean)
			return@OnPreferenceChangeListener true
		}

		caller.findPreference(R.string.settings_activity_freq_key)
				.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			ActivityWatcherService.onActivityIntervalPreferenceChange(context, newValue as Int)
			return@OnPreferenceChangeListener true
		}

		caller.findPreference(R.string.settings_disabled_recharge_key)
				.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			if (newValue as Boolean) {
				TrackerLocker.lockUntilRecharge(context)
			} else {
				TrackerLocker.unlockRechargeLock(context)
			}

			return@OnPreferenceChangeListener true
		}

		val locationPreference = caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_location_enabled_key)
		val wifiPreference = caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_wifi_enabled_key)
		val cellPreference = caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_cell_enabled_key)


		val packageManager = context.packageManager
		if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
			wifiPreference.isEnabled = false
		}

		if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
			cellPreference.isEnabled = false
		}

		locationPreference.setOnPreferenceChangeListener { _, newValue ->
			if (!validateEnablePreference(locationEnabled = newValue as Boolean, wifiEnabled = wifiPreference.isChecked,
							cellEnabled = cellPreference.isChecked)) {
				snackMaker.addMessage(R.string.error_nothing_to_track, priority = SnackMaker.SnackbarPriority.IMPORTANT)
				false
			} else {
				true
			}
		}

		wifiPreference.setOnPreferenceChangeListener { _, newValue ->
			if (!validateEnablePreference(locationEnabled = locationPreference.isChecked,
							wifiEnabled = newValue as Boolean, cellEnabled = cellPreference.isChecked)) {
				snackMaker.addMessage(R.string.error_nothing_to_track, priority = SnackMaker.SnackbarPriority.IMPORTANT)
				false
			} else
				true
		}

		cellPreference.setOnPreferenceChangeListener { _, newValue ->
			if (!validateEnablePreference(locationEnabled = locationPreference.isChecked,
							wifiEnabled = wifiPreference.isChecked, cellEnabled = newValue as Boolean)) {
				snackMaker.addMessage(R.string.error_nothing_to_track, priority = SnackMaker.SnackbarPriority.IMPORTANT)
				false
			} else {
				true
			}
		}
	}

}

