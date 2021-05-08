package com.adsamcik.tracker.preference.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.R
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.preference.component.DialogListPreference
import com.adsamcik.tracker.preference.component.IndicesDialogListPreference
import com.adsamcik.tracker.preference.findPreference
import com.adsamcik.tracker.preference.findPreferenceTyped
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.notification.NotificationManagementActivity
import com.adsamcik.tracker.tracker.service.ActivityWatcherService

/**
 * Tracker preference page.
 * Contains configuration for tracker options.
 */
class TrackerPreferencePage : PreferencePage {

	private lateinit var snackMaker: SnackMaker


	override fun onExit(caller: PreferenceFragmentCompat): Unit = Unit


	private fun initializeEnableTrackingPreferences(caller: PreferenceFragmentCompat) {
		val locationPreference =
			caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_location_enabled_key)
		val wifiPreference =
			caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_wifi_enabled_key)
		val cellPreference =
			caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_cell_enabled_key)
		val activityPreference =
			caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_activity_enabled_key)
		val stepCountPreference =
			caller.findPreferenceTyped<CheckBoxPreference>(R.string.settings_steps_enabled_key)

		val locationWarning = caller.findPreference(R.string.settings_location_warning_key)

		val trackerPreference =
			caller.findPreferenceTyped<DialogListPreference>(R.string.settings_tracker_timer_key)

		val autoTracking =
			caller.findPreferenceTyped<IndicesDialogListPreference>(R.string.settings_tracking_activity_key)

		val context = caller.requireContext()
		val packageManager = context.packageManager
		if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
			wifiPreference.isEnabled = false
		}

		if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
			cellPreference.isEnabled = false
		}

		fun updateLocationWarning(
			isAutoTracking: Boolean = autoTracking.selectedValueIndex > 0,
			tracksLocation: Boolean = locationPreference.isChecked,
			usesLocationUpdater: Boolean = TrackerTimerManager.currentTimerRequiresLocation(context)
		) {
			if (isAutoTracking && (tracksLocation || usesLocationUpdater)) {
				locationWarning.isVisible = !context.hasBackgroundLocationPermission
			} else {
				locationWarning.isVisible = false
			}
		}

		locationWarning.setOnPreferenceClickListener {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				PermissionManager.checkPermissions(
					PermissionRequest
						.with(context)
						.permissions(
							listOf(
								PermissionData(
									Manifest.permission.ACCESS_BACKGROUND_LOCATION
								) { "BlaBla" }
							)
						)
						//.onRationale { token, _ -> token.continuePermissionRequest() }
						.onResult { updateLocationWarning() }
						.build()
				)
			}
			true
		}

		autoTracking.setOnPreferenceChangeListener { _, newValue ->
			updateLocationWarning(isAutoTracking = newValue as Int > 0)
			true
		}

		trackerPreference.setOnPreferenceChangeListener { _, newValue ->
			updateLocationWarning(
				usesLocationUpdater = TrackerTimerManager.timerWithKeyRequiredLocation(
					newValue as String
				)
			)
			true
		}


		fun validateEnablePreference(
			locationEnabled: Boolean = locationPreference.isChecked,
			wifiEnabled: Boolean = wifiPreference.isChecked,
			cellEnabled: Boolean = cellPreference.isChecked,
			activity: Boolean = activityPreference.isChecked,
			stepCount: Boolean = stepCountPreference.isChecked,
		): Boolean {
			return locationEnabled.or(wifiEnabled).or(cellEnabled).or(activity).or(stepCount)
		}

		fun onValidated(isValid: Boolean): Boolean {
			if (!isValid) {
				snackMaker.addMessage(
					R.string.error_nothing_to_track,
					priority = SnackMaker.SnackbarPriority.IMPORTANT
				)
			}
			return isValid
		}

		locationPreference.setOnPreferenceChangeListener { _, newValue ->
			updateLocationWarning(tracksLocation = newValue as Boolean)
			onValidated(validateEnablePreference(locationEnabled = newValue))
		}

		wifiPreference.setOnPreferenceChangeListener { _, newValue ->
			onValidated(validateEnablePreference(wifiEnabled = newValue as Boolean))
		}

		cellPreference.setOnPreferenceChangeListener { _, newValue ->
			onValidated(validateEnablePreference(cellEnabled = newValue as Boolean))
		}

		activityPreference.setOnPreferenceChangeListener { _, newValue ->
			onValidated(validateEnablePreference(cellEnabled = newValue as Boolean))
		}

		stepCountPreference.setOnPreferenceChangeListener { _, newValue ->
			onValidated(validateEnablePreference(cellEnabled = newValue as Boolean))
		}
	}

	private fun initializeTrackingTickerPreference(caller: PreferenceFragmentCompat) {
		caller.findPreferenceTyped<DialogListPreference>(R.string.settings_tracker_timer_key)
			.apply {
				val values = TrackerTimerManager.availableTimerData
				val resources = context.resources
				val entries = values.map { resources.getString(it.second) }
				val entryValues = values.map { it.first }
				setValues(entries, entryValues)
				val selectedKey = TrackerTimerManager.getSelectedKey(context)
				val selectedIndex = values.indexOfFirst { it.first == selectedKey }
				if (selectedIndex >= 0) {
					setIndex(selectedIndex)
				} else {
					Reporter.report("Key $selectedKey was not found in ${entries.joinToString { it }}")
				}
			}
	}

	private fun initializeAutoTrackingPreferences(caller: PreferenceFragmentCompat) {
		caller.findPreference(R.string.settings_tracking_activity_key)
			.onPreferenceChangeListener =
			Preference.OnPreferenceChangeListener { preference, newValue ->
				ActivityWatcherService.onAutoTrackingPreferenceChange(
					preference.context,
					newValue as Int
				)
				val context = caller.requireContext()
				if (newValue > 0) {
					PermissionManager.checkActivityPermissions(context) {
						if (!it.isSuccess) {
							Preferences
								.getPref(context)
								.edit { setInt(R.string.settings_tracking_activity_key, 0) }
						} else {
							ActivityWatcherService.poke(context)
						}
					}
				}
				return@OnPreferenceChangeListener true
			}

		caller.findPreference(R.string.settings_activity_watcher_key)
			.onPreferenceChangeListener =
			Preference.OnPreferenceChangeListener { preference, newValue ->
				ActivityWatcherService.onWatcherPreferenceChange(
					preference.context,
					newValue as Boolean
				)
				return@OnPreferenceChangeListener true
			}

		caller.findPreference(R.string.settings_activity_freq_key)
			.onPreferenceChangeListener =
			Preference.OnPreferenceChangeListener { preference, newValue ->
				ActivityWatcherService.onActivityIntervalPreferenceChange(
					preference.context,
					newValue as Int
				)
				return@OnPreferenceChangeListener true
			}

		caller.findPreference(R.string.settings_disabled_recharge_key)
			.onPreferenceChangeListener =
			Preference.OnPreferenceChangeListener { preference, newValue ->
				if (newValue as Boolean) {
					TrackerLocker.lockUntilRecharge(preference.context)
				} else {
					TrackerLocker.unlockRechargeLock(preference.context)
				}

				return@OnPreferenceChangeListener true
			}
	}

	private fun initializeNotificationPreference(caller: PreferenceFragmentCompat) {
		caller.findPreference(R.string.settings_notification_customize_key)
			.setOnPreferenceClickListener {
				it.context.startActivity<NotificationManagementActivity> { }
				false
			}
	}

	override fun onEnter(caller: PreferenceFragmentCompat) {
		snackMaker = SnackMaker(caller.requireView())

		initializeAutoTrackingPreferences(caller)
		initializeEnableTrackingPreferences(caller)
		initializeNotificationPreference(caller)
		initializeTrackingTickerPreference(caller)
	}
}

