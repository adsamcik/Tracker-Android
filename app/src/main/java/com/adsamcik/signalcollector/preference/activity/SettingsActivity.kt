package com.adsamcik.signalcollector.preference.activity

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.*
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.app.Tips
import com.adsamcik.signalcollector.app.activity.DetailActivity
import com.adsamcik.signalcollector.app.activity.LaunchActivity
import com.adsamcik.signalcollector.app.activity.LicenseActivity
import com.adsamcik.signalcollector.app.color.ColorSupervisor
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.debug.activity.ActivityRecognitionActivity
import com.adsamcik.signalcollector.debug.activity.StatusActivity
import com.adsamcik.signalcollector.export.GpxExport
import com.adsamcik.signalcollector.export.KmlExport
import com.adsamcik.signalcollector.export.activity.ExportActivity
import com.adsamcik.signalcollector.misc.extension.*
import com.adsamcik.signalcollector.notification.Notifications
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.preference.fragment.FragmentSettings
import com.adsamcik.signalcollector.tracker.TrackerLocker
import com.jaredrummler.android.colorpicker.ColorPreferenceCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

/**
 * Settings Activity contains local settings and hosts debugging features
 * It is based upon Android's [Preference].
 */
class SettingsActivity : DetailActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
	lateinit var fragment: FragmentSettings

	private val backstack = ArrayList<PreferenceScreen>()

	private var clickCount = 0

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		createLinearContentParent(false)
		fragment = FragmentSettings()
		supportFragmentManager.transaction {
			replace(CONTENT_ID, fragment, FragmentSettings.TAG)
			runOnCommit { initializeRoot(fragment) }
		}

		title = getString(R.string.settings_title)
	}

	private fun initializeTracking(caller: PreferenceFragmentCompat) {
		caller.findPreference(R.string.settings_activity_watcher_key).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			if (newValue as Boolean) {
				val updateRate = caller.preferenceManager.sharedPreferences.getInt(getString(R.string.settings_activity_freq_key), getString(R.string.settings_activity_freq_default).toInt())
				ActivityService.requestActivity(this, LaunchActivity::class.java, updateRate)
			} else
				ActivityService.removeActivityRequest(this, LaunchActivity::class.java)

			ActivityWatcherService.pokeWithCheck(this@SettingsActivity, newValue)
			return@OnPreferenceChangeListener true
		}

		caller.findPreference(R.string.settings_activity_freq_key).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			ActivityService.requestActivity(this, LaunchActivity::class.java, newValue as Int)
			ActivityWatcherService.pokeWithCheck(this)
			return@OnPreferenceChangeListener true
		}

		caller.findPreference(R.string.settings_disabled_recharge_key).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			if (newValue as Boolean) {
				TrackerLocker.lockUntilRecharge(this)
			} else
				TrackerLocker.unlockRechargeLock(this)

			return@OnPreferenceChangeListener true
		}

		val packageManager = packageManager
		if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI))
			caller.findPreference(R.string.settings_wifi_enabled_key).isEnabled = false

		if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
			caller.findPreference(R.string.settings_cell_enabled_key).isEnabled = false
	}

	private fun initializeExport(caller: PreferenceFragmentCompat) {
		setOnClickListener(R.string.settings_export_gpx_key) {
			startActivity<ExportActivity> {
				putExtra(ExportActivity.EXPORTER_KEY, GpxExport::class.java)
			}
		}

		setOnClickListener(R.string.settings_export_kml_key) {
			startActivity<ExportActivity> {
				putExtra(ExportActivity.EXPORTER_KEY, KmlExport::class.java)
			}
		}
	}

	private fun initializeMap(caller: PreferenceFragmentCompat) {
		setOnClickListener(R.string.settings_map_clear_heat_cache_key) {
			createConfirmDialog(it.title.toString()) {
				GlobalScope.launch {
					AppDatabase.getAppDatabase(applicationContext).mapHeatDao().clear()
				}
			}
		}
	}

	private fun initializeRoot(caller: PreferenceFragmentCompat) {
		setOnClickListener(R.string.settings_licenses_key) {
			startActivity<LicenseActivity> { }
		}

		val devKey = getString(R.string.settings_debug_key)
		val debugTitle = getString(R.string.settings_debug_title)

		caller.findDirectPreferenceByTitle(debugTitle)!!.isVisible = Preferences.getPref(this).getBoolean(devKey, false)

		caller.findPreference(R.string.show_tips_key).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
			if (newValue as Boolean) {
				Preferences.getPref(this).edit {
					remove(Tips.getTipsPreferenceKey(Tips.HOME_TIPS))
					remove(Tips.getTipsPreferenceKey(Tips.MAP_TIPS))
				}
			}
			true
		}

		val version = caller.findPreference(R.string.settings_app_version_key)
		version.title = String.format("%1\$s - %2\$s", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)

		version.setOnPreferenceClickListener {
			val preferences = Preferences.getPref(this)

			if (preferences.getBoolean(devKey, false)) {
				showToast(getString(R.string.settings_debug_already_available))
				return@setOnPreferenceClickListener false
			}

			clickCount++
			if (clickCount >= 7) {
				preferences.edit {
					putBoolean(devKey, true)
				}
				showToast(getString(R.string.settings_debug_available))
				caller.findDirectPreferenceByTitle(debugTitle)!!.isVisible = true
				caller.findPreferenceTyped<SwitchPreferenceCompat>(devKey).isChecked = true
			} else if (clickCount >= 4) {
				val remainingClickCount = 7 - clickCount
				showToast(resources.getQuantityString(R.plurals.settings_debug_available_in, remainingClickCount, remainingClickCount))
			}
			true
		}
	}

	private fun showToast(string: String) {
		Toast.makeText(this, string, Toast.LENGTH_SHORT).show()
	}

	private fun setOnClickListener(@StringRes key: Int, listener: (Preference) -> Unit) {
		fragment.findPreference(key).setOnPreferenceClickListener {
			listener.invoke(it)
			false
		}
	}

	override fun onBackPressed() {
		if (!pop())
			super.onBackPressed()
	}

	private fun pop(): Boolean {
		return when {
			backstack.isEmpty() -> false
			backstack.size == 1 -> {
				fragment.setPreferencesFromResource(R.xml.app_preferences, null)
				backstack.clear()
				title = getString(R.string.settings_title)
				initializeRoot(fragment)
				true
			}
			else -> {
				onPreferenceStartScreen(fragment, backstack[backstack.size - 2])
			}
		}
	}

	private fun createConfirmDialog(action: String, clearFunction: (Context) -> Unit) {
		val alertDialogBuilder = AlertDialog.Builder(this)
		alertDialogBuilder
				.setPositiveButton(resources.getText(R.string.yes)) { _, _ ->
					clearFunction.invoke(this)
				}
				.setNegativeButton(resources.getText(R.string.no)) { _, _ -> }
				.setMessage(resources.getString(R.string.alert_confirm, action.toLowerCase()))

		alertDialogBuilder.show()
	}

	private fun createFileAlertDialog(directory: File, verifyFunction: ((File) -> Boolean)?) {
		val files = directory.listFiles()
		val fileNames = files
				.filter { verifyFunction == null || verifyFunction.invoke(it) }
				.map { it.name + "|  " + Assist.humanReadableByteCount(it.length(), true) }
				.sortedBy { x -> x }
				.toTypedArray()

		throw NotImplementedError()

		/*val alertDialogBuilder = AlertDialog.Builder(this)
		alertDialogBuilder
				.setTitle(getString(R.string.dev_browse_files))
				.setItems(fileNames) { _, which ->
					val intent = Intent(this, DebugFileActivity::class.java)
					intent.putExtra("directory", directory.path)
					intent.putExtra("fileName", fileNames[which].substring(0, fileNames[which].lastIndexOf('|')))
					startActivity(intent)
				}
				.setNegativeButton(R.string.cancel) { _, _ -> }

		alertDialogBuilder.show()*/
	}


	/***
	 * Initializes settings on preferences on debug screen
	 */
	private fun initializeDebug(caller: PreferenceFragmentCompat) {
		setOnClickListener(R.string.settings_debug_activity_key) {
			startActivity<ActivityRecognitionActivity> { }
		}

		setOnClickListener(R.string.settings_activity_status_key) {
			startActivity<StatusActivity> { }
		}

		caller.findPreference(R.string.settings_hello_world_key).setOnPreferenceClickListener {
			val helloWorld = getString(R.string.dev_notification_dummy)
			val color = ContextCompat.getColor(this, R.color.color_primary)
			val rng = Random(System.currentTimeMillis())
			val facts = resources.getStringArray(R.array.lorem_ipsum_facts)
			val notiBuilder = NotificationCompat.Builder(this, getString(R.string.channel_other_id))
					.setSmallIcon(R.drawable.ic_signals)
					.setTicker(helloWorld)
					.setColor(color)
					.setLights(color, 2000, 5000)
					.setContentTitle(getString(R.string.did_you_know))
					.setContentText(facts[rng.nextInt(facts.size)])
					.setWhen(System.currentTimeMillis())
			val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.notify(Notifications.uniqueNotificationId(), notiBuilder.build())
			false
		}

		caller.findPreference(R.string.settings_clear_preferences_key).setOnPreferenceClickListener { pref ->
			createConfirmDialog(pref.title.toString()) {
				Preferences.getPref(it).edit {
					clear()
				}
			}

			false
		}

	}

	private lateinit var styleChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

	private fun initializeStyle(caller: PreferenceFragmentCompat) {
		val morningKey = getString(R.string.settings_color_morning_key)
		val morning = caller.findPreferenceTyped<ColorPreferenceCompat>(morningKey)

		val eveningKey = getString(R.string.settings_color_evening_key)
		val evening = caller.findPreferenceTyped<ColorPreferenceCompat>(eveningKey)

		val nightKey = getString(R.string.settings_color_night_key)
		val night = caller.findPreferenceTyped<ColorPreferenceCompat>(nightKey)

		val dayKey = getString(R.string.settings_color_day_key)
		val day = caller.findPreferenceTyped<ColorPreferenceCompat>(dayKey)

		val onStyleChange = androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
			val newValueInt = (newValue as String).toInt()
			night.isVisible = newValueInt >= 1

			evening.isVisible = newValueInt >= 2
			morning.isVisible = newValueInt >= 2

			true
		}

		val defaultColorKey = getString(R.string.settings_color_default_key)
		val styleKey = getString(R.string.settings_style_mode_key)
		val stylePreference = caller.findPreferenceTyped<ListPreference>(styleKey)
		stylePreference.onPreferenceChangeListener = onStyleChange
		onStyleChange.onPreferenceChange(stylePreference, stylePreference.value)

		caller.findPreferenceTyped<Preference>(defaultColorKey).setOnPreferenceClickListener {
			val sp = it.sharedPreferences
			sp.edit(true) {
				remove(morningKey)
				remove(dayKey)
				remove(eveningKey)
				remove(nightKey)
			}

			morning.saveValue(sp.getInt(morningKey, ContextCompat.getColor(this, R.color.settings_color_morning_default)))
			day.saveValue(sp.getInt(dayKey, ContextCompat.getColor(this, R.color.settings_color_day_default)))
			evening.saveValue(sp.getInt(eveningKey, ContextCompat.getColor(this, R.color.settings_color_evening_default)))
			night.saveValue(sp.getInt(nightKey, ContextCompat.getColor(this, R.color.settings_color_night_default)))

			true
		}

		styleChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
			when (key) {
				styleKey, defaultColorKey -> ColorSupervisor.initializeFromPreferences(this)
				morningKey, dayKey, eveningKey, nightKey -> {
					if (preferences.contains(key)) {
						val stylePrefVal = stylePreference.value.toInt()

						//-1 indexes are meant to crash the application so an issue is found. They should never happen.

						val index = when (key) {
							morningKey -> {
								if (stylePrefVal < 2)
									return@OnSharedPreferenceChangeListener
								else
									2
							}
							dayKey -> {
								if (stylePrefVal < 2)
									0
								else
									1
							}
							eveningKey -> {
								if (stylePrefVal < 2)
									return@OnSharedPreferenceChangeListener
								else
									2
							}
							nightKey -> {
								when (stylePrefVal) {
									0 -> return@OnSharedPreferenceChangeListener
									1 -> 1
									2 -> 3
									else -> -1
								}
							}
							else -> -1
						}

						ColorSupervisor.updateColorAt(index, preferences.getInt(key, 0))
					}
				}
			}
		}

		stylePreference.sharedPreferences.registerOnSharedPreferenceChangeListener(styleChangeListener)
	}


	private fun initializeStartScreen(caller: PreferenceFragmentCompat, key: String) {
		val r = resources
		when (key) {
			r.getString(R.string.settings_debug_title) -> initializeDebug(caller)
			r.getString(R.string.settings_style_title) -> initializeStyle(caller)
			r.getString(R.string.settings_tracking_title) -> initializeTracking(caller)
			r.getString(R.string.settings_map_title) -> initializeMap(caller)
			r.getString(R.string.settings_export_title) -> initializeExport(caller)
		}
	}


	override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
		caller.preferenceScreen = pref
		val index = backstack.indexOf(pref)
		if (index >= 0) {
			for (i in (backstack.size - 1) downTo (index + 1))
				backstack.removeAt(i)
		} else
			backstack.add(pref)

		title = pref.title

		initializeStartScreen(caller, pref.title.toString())

		return true
	}
}
