package com.adsamcik.signalcollector.activities

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.support.v7.preference.*
import android.util.Log
import android.widget.Toast
import androidx.content.edit
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.file.CacheStore
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.fragments.FragmentNewSettings
import com.adsamcik.signalcollector.jobs.DisableTillRechargeJobService
import com.adsamcik.signalcollector.services.ActivityService
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.startActivity
import com.adsamcik.signalcollector.utility.transaction
import java.io.File
import java.util.*


class SettingsActivity : DetailActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    lateinit var fragment: FragmentNewSettings

    private val backstack = ArrayList<PreferenceScreen>()

    private var dummyIndex = 0

    private var clickCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createContentParent(false)
        fragment = FragmentNewSettings()
        supportFragmentManager.transaction {
            replace(CONTENT_ID, fragment, FragmentNewSettings.TAG)
            runOnCommit { initializeRoot(fragment) }
        }
        
        title = "Settings"
    }

    private fun initializeTracking(caller: PreferenceFragmentCompat) {
        caller.findPreference(getString(R.string.settings_activity_watcher_key)).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            ActivityWakerService.poke(this@SettingsActivity, newValue as Boolean)
            return@OnPreferenceChangeListener true
        }

        caller.findPreference(getString(R.string.settings_activity_freq_key)).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            ActivityService.requestActivity(this, NewUIActivity::class.java, newValue as Int)
            ActivityWakerService.poke(this)
            return@OnPreferenceChangeListener true
        }

        caller.findPreference(getString(R.string.settings_disabled_recharge_key)).onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                return@OnPreferenceChangeListener DisableTillRechargeJobService.stopTillRecharge(this)
            } else
                DisableTillRechargeJobService.enableTracking(this)

            return@OnPreferenceChangeListener true
        }
    }

    private fun initializeRoot(caller: PreferenceFragmentCompat) {
        if (Signin.isSignedIn)
            setOnClickListener(R.string.settings_feedback_key) {
                startActivity<FeedbackActivity> { }
            }
        else
            caller.findPreference(getString(R.string.settings_feedback_key)).isEnabled = false

        setOnClickListener(R.string.settings_account_key) {
            startActivity<UserActivity> { }
        }


        setOnClickListener(R.string.settings_export_key) {
            startActivity<FileSharingActivity> {}
        }

        setOnClickListener(R.string.settings_licenses_key) {
            startActivity<LicenseActivity> { }
        }

        val devKey = getString(R.string.settings_debug_key)

        caller.findPreference("debug_key_screen").isVisible = Preferences.getPref(this).getBoolean(devKey, false)

        val version = caller.findPreference(getString(R.string.settings_app_version_key))
        try {
            version.title = String.format("%1\$s - %2\$s", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        } catch (e: Exception) {
            Log.d("SignalsSettings", "Failed to set version")
        }


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
                caller.findPreference("debug_key").isVisible = true
                (caller.findPreference(devKey) as SwitchPreferenceCompat).isChecked = true
            } else if (clickCount >= 4) {
                showToast(getString(R.string.settings_debug_available_in, 7 - clickCount))
            }
            true
        }
    }

    private fun showToast(string: String) {
        Toast.makeText(this, string, Toast.LENGTH_SHORT).show()
    }

    private fun setOnClickListener(@StringRes key: Int, listener: () -> Unit) {
        fragment.findPreference(getString(key)).setOnPreferenceClickListener {
            listener.invoke()
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

    private fun createClearDialog(clearFunction: (Context) -> Unit, @StringRes snackBarString: Int) {
        val alertDialogBuilder = AlertDialog.Builder(this)
        alertDialogBuilder
                .setPositiveButton(resources.getText(R.string.yes)) { _, _ ->
                    clearFunction.invoke(this)
                }
                .setNegativeButton(resources.getText(R.string.no)) { _, _ -> }
                .setMessage(resources.getText(R.string.alert_confirm_generic))

        alertDialogBuilder.show()
    }

    private fun createFileAlertDialog(directory: File, verifyFunction: ((File) -> Boolean)?) {
        val files = directory.listFiles()
        val temp = files
                .filter { verifyFunction == null || verifyFunction.invoke(it) }
                .map { it.name + "|  " + Assist.humanReadableByteCount(it.length(), true) }

        Collections.sort(temp) { obj, s -> obj.compareTo(s) }

        val alertDialogBuilder = AlertDialog.Builder(this)
        val fileNames = temp.toTypedArray()
        alertDialogBuilder
                .setTitle(getString(R.string.dev_browse_files))
                .setItems(fileNames) { _, which ->
                    val intent = Intent(this, DebugFileActivity::class.java)
                    intent.putExtra("directory", directory.path)
                    intent.putExtra("fileName", fileNames[which].substring(0, fileNames[which].lastIndexOf('|')))
                    startActivity(intent)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }

        alertDialogBuilder.show()
    }


    private fun initializeDebug(caller: PreferenceFragmentCompat) {
        //val isDevEnabled = Preferences.getPref(activity).getBoolean(Preferences.PREF_SHOW_DEV_SETTINGS, false)
        //devView!!.visibility = if (isDevEnabled) View.VISIBLE else View.GONE

        caller.findPreference(getString(R.string.settings_clear_cache_key)).setOnPreferenceClickListener { _ ->
            createClearDialog({ CacheStore.clearAll(it) }, R.string.settings_cleared_all_cache_files)
            false
        }
        caller.findPreference(getString(R.string.settings_clear_data_key)).setOnPreferenceClickListener { _ ->
            createClearDialog({ DataStore.clearAll(it) }, R.string.settings_cleared_all_data_files)
            false
        }
        caller.findPreference(getString(R.string.settings_clear_reports_key)).setOnPreferenceClickListener { _ ->
            createClearDialog({ _ ->
                DataStore.delete(this, DataStore.RECENT_UPLOADS_FILE)
                Preferences.getPref(this).edit().remove(Preferences.PREF_OLDEST_RECENT_UPLOAD).apply()
            }, R.string.settings_cleared_all_upload_reports)
            false
        }

        caller.findPreference(getString(R.string.settings_browse_files_key)).setOnPreferenceClickListener { _ ->
            createFileAlertDialog(filesDir, { file ->
                val name = file.name
                !name.startsWith("DATA") && !name.startsWith("firebase") && !name.startsWith("com.") && !name.startsWith("event_store") && !name.startsWith("_m_t") && name != "ZoomTables.data"
            })
            false
        }

        caller.findPreference(getString(R.string.settings_browse_cache_key)).setOnPreferenceClickListener { _ ->
            createFileAlertDialog(cacheDir, { file -> !file.name.startsWith("com.") && !file.isDirectory })
            false
        }

        caller.findPreference(getString(R.string.settings_hello_world_key)).setOnPreferenceClickListener { _ ->
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
            notificationManager.notify(dummyIndex++, notiBuilder.build())
            false
        }

        caller.findPreference(getString(R.string.settings_activity_debug_key)).setOnPreferenceClickListener { _ ->
            startActivity(Intent(this, ActivityRecognitionActivity::class.java))
            false
        }

    }

    private fun initializeStyle(caller: PreferenceFragmentCompat) {
        val onPreferenceChange = android.support.v7.preference.Preference.OnPreferenceChangeListener { _, newValue ->
            val morning = caller.findPreference(getString(R.string.settings_color_morning_key))
            val evening = caller.findPreference(getString(R.string.settings_color_evening_key))
            val night = caller.findPreference(getString(R.string.settings_color_night_key))

            val newValueInt = (newValue as String).toInt()
            night.isVisible = newValueInt >= 1

            evening.isVisible = newValueInt >= 2
            morning.isVisible = newValueInt >= 2

            true
        }

        val stylePreference = caller.findPreference(getString(R.string.settings_style_mode_key)) as ListPreference
        stylePreference.onPreferenceChangeListener = onPreferenceChange
        onPreferenceChange.onPreferenceChange(stylePreference, stylePreference.value)

        caller.findPreference(getString(R.string.settings_color_default_key)).setOnPreferenceClickListener {
            caller.preferenceManager.sharedPreferences.edit {
                remove(getString(R.string.settings_color_morning_key))
                remove(getString(R.string.settings_color_evening_key))
                remove(getString(R.string.settings_color_day_key))
                remove(getString(R.string.settings_color_night_key))
            }
            true
        }
    }


    private fun initializeStartScreen(caller: PreferenceFragmentCompat, key: String) {
        val r = resources
        when (key) {
            r.getString(R.string.settings_debug_title) -> initializeDebug(caller)
            r.getString(R.string.settings_style_title) -> initializeStyle(caller)
            r.getString(R.string.settings_tracking_title) -> initializeTracking(caller)
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
