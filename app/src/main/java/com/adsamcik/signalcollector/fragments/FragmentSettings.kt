package com.adsamcik.signalcollector.fragments

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.*
import com.adsamcik.signalcollector.data.MapLayer
import com.adsamcik.signalcollector.file.CacheStore
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.interfaces.*
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.network.NetworkLoader
import com.adsamcik.signalcollector.network.Prices
import com.adsamcik.signalcollector.services.ActivityService
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.signin.User
import com.adsamcik.signalcollector.utility.*
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MINUTES
import com.adsamcik.slider.IntSlider
import com.adsamcik.slider.Slider
import com.google.android.gms.common.SignInButton
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crash.FirebaseCrash
import com.google.gson.Gson
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.*

class FragmentSettings : Fragment(), ITabFragment {
    private val TAG = "SignalsSettings"
    private val REQUEST_CODE_PERMISSIONS_MICROPHONE = 401

    private var trackingString: Array<String>? = null
    private var autoupString: Array<String>? = null
    private var trackingNone: ImageView? = null
    private var trackingOnFoot: ImageView? = null
    private var trackingAlways: ImageView? = null
    private var autoupDisabled: ImageView? = null
    private var autoupWifi: ImageView? = null
    private var autoupAlways: ImageView? = null
    private var autoupDesc: TextView? = null
    private var trackDesc: TextView? = null
    private var signInNoConnection: TextView? = null

    private var devView: View? = null

    private var mTrackingSelected: ImageView? = null
    private var mAutoupSelected: ImageView? = null

    private var signInButton: SignInButton? = null
    private var signedInMenu: LinearLayout? = null
    private var signin: Signin? = null

    private var switchNoise: Switch? = null

    private var mSelectedState: ColorStateList? = null
    private var mDefaultState: ColorStateList? = null

    private var rootView: View? = null

    private var dummyNotificationIndex = 1972

    private val userSignedCallback: IValueCallback<User> = IValueCallback { u ->
        val activity = activity
        if (activity != null) {
            if (u != null) {
                if (Signin.isMock) {
                    u.addServerDataCallback(INonNullValueCallback { user -> resolveUserMenuOnLogin(user, Prices()) })
                } else
                    NetworkLoader.request(Network.URL_USER_PRICES, DAY_IN_MINUTES, activity, Preferences.PREF_USER_PRICES, Prices::class.java, IStateValueCallback { s, p ->
                        //todo check when server data are available
                        if (s.isSuccess) {
                            if (p == null)
                                SnackMaker(activity).showSnackbar(R.string.error_invalid_data)
                            else
                                u.addServerDataCallback(INonNullValueCallback { user -> resolveUserMenuOnLogin(user, p) })
                        } else
                            SnackMaker(activity).showSnackbar(R.string.error_connection_failed)
                    })
            } else
                SnackMaker(activity).showSnackbar(R.string.error_failed_signin)
        }
    }


    private fun updateTracking(select: Int) {
        val selected: ImageView? = when (select) {
            0 -> trackingNone
            1 -> trackingOnFoot
            2 -> trackingAlways
            else -> return
        }

        FirebaseAssist.updateValue(context!!, FirebaseAssist.autoUploadString, trackingString!![select])
        trackDesc!!.text = trackingString!![select]
        updateState(mTrackingSelected, selected!!, Preferences.PREF_AUTO_TRACKING, select)

        if (mTrackingSelected != null)
            if (select == 0)
                ActivityService.removeAutoTracking(context!!, MainActivity::class.java)
            else
                ActivityService.requestAutoTracking(context!!, MainActivity::class.java)

        mTrackingSelected = selected
    }

    private fun updateAutoup(select: Int) {
        val selected: ImageView? = when (select) {
            0 -> autoupDisabled
            1 -> autoupWifi
            2 -> autoupAlways
            else -> return
        }
        FirebaseAssist.updateValue(context!!, FirebaseAssist.autoUploadString, autoupString!![select])

        autoupDesc!!.text = autoupString!![select]
        updateState(mAutoupSelected, selected!!, Preferences.PREF_AUTO_UPLOAD, select)
        mAutoupSelected = selected
    }

    private fun updateState(selected: ImageView?, select: ImageView, preference: String, index: Int) {
        val context = context!!
        Preferences.getPref(context).edit().putInt(preference, index).apply()

        if (selected != null)
            setInactive(selected)
        select.imageTintList = mSelectedState
        select.imageAlpha = Color.alpha(mSelectedState!!.defaultColor)
    }

    private fun setInactive(item: ImageView) {
        item.imageTintList = mDefaultState
        item.imageAlpha = Color.alpha(mDefaultState!!.defaultColor)
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        rootView = inflater.inflate(R.layout.fragment_settings, container, false)
        val activity = activity!!
        val sharedPreferences = Preferences.getPref(activity)

        initializeClassVariables(activity)

        findViews(rootView!!)
        initializeVersionLicense(rootView!!, devView!!)

        updateTracking(sharedPreferences.getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING))

        updateAutoup(sharedPreferences.getInt(Preferences.PREF_AUTO_UPLOAD, Preferences.DEFAULT_AUTO_UPLOAD))

        initializeSignIn(activity)

        initializeAutoTrackingSection(activity, rootView!!)

        initializeAutoUploadSection(activity, rootView!!)

        initializeTrackingOptionsSection(activity, rootView!!)

        initializeExportSection(rootView!!)

        initializeOtherSection(activity, rootView!!)

        initializeDevSection(activity, rootView!!)

        return rootView
    }


    private fun initializeVersionLicense(rootView: View, devView: View) {
        rootView.findViewById<View>(R.id.open_source_licenses).setOnClickListener { _ -> startActivity(Intent(activity, LicenseActivity::class.java)) }

        val versionView = rootView.findViewById<TextView>(R.id.versionNum)
        try {
            versionView.text = String.format("%1\$s - %2\$s", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to set version")
        }

        versionView.setOnLongClickListener { _ ->
            val setVisible = devView.visibility == View.GONE
            devView.visibility = if (setVisible) View.VISIBLE else View.GONE
            Preferences.getPref(rootView.context).edit().putBoolean(Preferences.PREF_SHOW_DEV_SETTINGS, setVisible).apply()
            SnackMaker(activity!!).showSnackbar(getString(if (setVisible) R.string.dev_join else R.string.dev_leave))
            true
        }
    }

    private fun findViews(rootView: View) {
        autoupDesc = rootView.findViewById(R.id.autoupload_description)
        trackDesc = rootView.findViewById(R.id.tracking_description)

        trackingNone = rootView.findViewById(R.id.tracking_none)
        trackingNone!!.setOnClickListener { _ -> updateTracking(0) }
        setInactive(trackingNone!!)
        trackingOnFoot = rootView.findViewById(R.id.tracking_onfoot)
        trackingOnFoot!!.setOnClickListener { _ -> updateTracking(1) }
        setInactive(trackingOnFoot!!)
        trackingAlways = rootView.findViewById(R.id.tracking_always)
        trackingAlways!!.setOnClickListener { _ -> updateTracking(2) }
        setInactive(trackingAlways!!)

        autoupDisabled = rootView.findViewById(R.id.autoupload_disabled)
        autoupDisabled!!.setOnClickListener { _ -> updateAutoup(0) }
        setInactive(autoupDisabled!!)
        autoupWifi = rootView.findViewById(R.id.autoupload_wifi)
        autoupWifi!!.setOnClickListener { _ -> updateAutoup(1) }
        setInactive(autoupWifi!!)
        autoupAlways = rootView.findViewById(R.id.autoupload_always)
        autoupAlways!!.setOnClickListener { _ -> updateAutoup(2) }
        setInactive(autoupAlways!!)

        devView = rootView.findViewById(R.id.dev_corner_layout)

        signInButton = rootView.findViewById(R.id.sign_in_button)
        signedInMenu = rootView.findViewById(R.id.signed_in_menu)
        signInNoConnection = rootView.findViewById(R.id.sign_in_message)
    }

    private fun initializeClassVariables(activity: Activity) {
        val resources = resources
        val csl = Assist.getSelectionStateLists(resources, activity.theme)
        mSelectedState = csl[1]
        mDefaultState = csl[0]

        trackingString = resources.getStringArray(R.array.background_tracking_options)
        autoupString = resources.getStringArray(R.array.automatic_upload_options)
    }

    private fun initializeSignIn(activity: Activity) {
        if (Assist.hasNetwork(activity)) {
            signin = Signin.signin(activity, userSignedCallback, true)
            signin!!.setButtons(signInButton!!, signedInMenu!!, activity)
        } else
            signInNoConnection!!.visibility = View.VISIBLE
    }

    private fun initializeAutoTrackingSection(activity: Activity, rootView: View) {
        setSwitchChangeListener(activity,
                Preferences.PREF_ACTIVITY_WATCHER_ENABLED,
                rootView.findViewById(R.id.switch_activity_watcher),
                Preferences.DEFAULT_ACTIVITY_WATCHER_ENABLED,
                INonNullValueCallback { _ -> ActivityWakerService.poke(activity) })

        val activityFrequencySlider = rootView.findViewById<IntSlider>(R.id.settings_seekbar_watcher_frequency)
        //todo update to not set useless values because of setItems below
        setSeekbar(activity,
                activityFrequencySlider,
                rootView.findViewById(R.id.settings_text_activity_frequency),
                0,
                300,
                30,
                Preferences.PREF_ACTIVITY_UPDATE_RATE,
                Preferences.DEFAULT_ACTIVITY_UPDATE_RATE,
                Slider.IStringify setSeekbar@ { progress ->
                    when {
                        progress == 0 -> return@setSeekbar getString(R.string.frequency_asap)
                        progress < 60 -> return@setSeekbar getString(R.string.frequency_seconds, progress)
                        progress!! % 60 == 0 -> return@setSeekbar getString(R.string.frequency_minute, progress / 60)
                        else -> {
                            val minutes = progress / 60
                            return@setSeekbar getString(R.string.frequency_minute_second, minutes, progress - minutes * 60)
                        }
                    }
                },
                INonNullValueCallback { value ->
                    ActivityService.requestActivity(activity, MainActivity::class.java, value)
                    ActivityWakerService.poke(activity)
                })

        activityFrequencySlider.items = arrayOf(0, 5, 10, 30, 60, 120, 240, 300, 600)

        setSwitchChangeListener(activity, Preferences.PREF_STOP_TILL_RECHARGE, rootView.findViewById(R.id.switchDisableTrackingTillRecharge), false, INonNullValueCallback { b ->
            if (b) {
                val bundle = Bundle()
                bundle.putString(FirebaseAssist.PARAM_SOURCE, "settings")
                FirebaseAnalytics.getInstance(activity).logEvent(FirebaseAssist.STOP_TILL_RECHARGE_EVENT, bundle)
                if (TrackerService.isRunning)
                    activity.stopService(Intent(activity, TrackerService::class.java))
            }
        })
    }

    private fun initializeAutoUploadSection(activity: Activity, rootView: View) {
        val valueAutoUploadAt = rootView.findViewById<TextView>(R.id.settings_autoupload_at_value)
        val seekAutoUploadAt = rootView.findViewById<IntSlider>(R.id.settings_autoupload_at_seekbar)

        setSeekbar(activity,
                seekAutoUploadAt,
                valueAutoUploadAt,
                1,
                10,
                1,
                Preferences.PREF_AUTO_UPLOAD_AT_MB,
                Preferences.DEFAULT_AUTO_UPLOAD_AT_MB,
                Slider.IStringify { progress -> getString(R.string.settings_autoupload_at_value, progress) }, null)

        setSwitchChangeListener(activity, Preferences.PREF_AUTO_UPLOAD_SMART, rootView.findViewById(R.id.switchAutoUploadSmart), Preferences.DEFAULT_AUTO_UPLOAD_SMART, INonNullValueCallback { value -> (seekAutoUploadAt.parent as ViewGroup).visibility = if (value) View.GONE else View.VISIBLE })

        if (Preferences.getPref(activity).getBoolean(Preferences.PREF_AUTO_UPLOAD_SMART, Preferences.DEFAULT_AUTO_UPLOAD_SMART)) {
            (seekAutoUploadAt.parent as ViewGroup).visibility = View.GONE
        }
    }

    private fun initializeTrackingOptionsSection(activity: Activity, rootView: View) {
        setSwitchChangeListener(activity, Preferences.PREF_TRACKING_WIFI_ENABLED, rootView.findViewById(R.id.switchTrackWifi), Preferences.DEFAULT_TRACKING_WIFI_ENABLED, null)
        setSwitchChangeListener(activity, Preferences.PREF_TRACKING_CELL_ENABLED, rootView.findViewById(R.id.switchTrackCell), Preferences.DEFAULT_TRACKING_CELL_ENABLED, null)
        val switchTrackLocation = rootView.findViewById<Switch>(R.id.switchTrackLocation)
        setSwitchChangeListener(activity, Preferences.PREF_TRACKING_LOCATION_ENABLED, switchTrackLocation, Preferences.DEFAULT_TRACKING_LOCATION_ENABLED, INonNullValueCallback { s ->
            if (!s) {
                val alertDialogBuilder = AlertDialog.Builder(activity, R.style.AlertDialog)
                alertDialogBuilder
                        .setPositiveButton(getText(R.string.yes), null)
                        .setNegativeButton(getText(R.string.cancel)) { _, _ -> switchTrackLocation.isChecked = true }
                        .setMessage(getText(R.string.alert_disable_location_tracking_description))
                        .setTitle(R.string.alert_disable_location_tracking_title)

                alertDialogBuilder.create().show()
            }

        })

        switchNoise = rootView.findViewById(R.id.switchTrackNoise)
        switchNoise!!.isChecked = Preferences.getPref(activity).getBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, false)
        switchNoise!!.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            if (b && Build.VERSION.SDK_INT > 22 && ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CODE_PERMISSIONS_MICROPHONE)
            else
                Preferences.getPref(activity).edit().putBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, b).apply()
        }
    }

    private fun initializeExportSection(rootView: View) {
        rootView.findViewById<View>(R.id.export_share_button).setOnClickListener { _ -> startActivity(Intent(activity, FileSharingActivity::class.java)) }
    }

    private fun initializeOtherSection(activity: Activity, rootView: View) {
        val darkThemeSwitch = rootView.findViewById<Switch>(R.id.switchDarkTheme)
        darkThemeSwitch.isChecked = Preferences.getTheme(activity) == R.style.AppThemeDark
        darkThemeSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            val theme = if (b) R.style.AppThemeDark else R.style.AppThemeLight
            Preferences.setTheme(activity, theme)

            //If activity is first started than finished it will finish the new activity
            activity.finish()
            startActivity(activity.intent)
        }

        setSwitchChangeListener(activity,
                Preferences.PREF_UPLOAD_NOTIFICATIONS_ENABLED,
                rootView.findViewById(R.id.switchNotificationsUpload),
                true,
                INonNullValueCallback { b -> FirebaseAssist.updateValue(activity, FirebaseAssist.uploadNotificationString, java.lang.Boolean.toString(b)) })

        rootView.findViewById<View>(R.id.other_clear_data).setOnClickListener { _ ->
            val alertDialogBuilder = AlertDialog.Builder(activity)
            alertDialogBuilder
                    .setPositiveButton(resources.getText(R.string.yes)) { _, _ -> DataStore.clearAllData(activity) }
                    .setNegativeButton(resources.getText(R.string.no)) { _, _ -> }
                    .setMessage(resources.getText(R.string.alert_clear_text))

            alertDialogBuilder.show()
        }

        rootView.findViewById<View>(R.id.other_reopen_tutorial).setOnClickListener { _ ->
            startActivity(Intent(activity, IntroActivity::class.java))
            activity.finish()
        }

        rootView.findViewById<View>(R.id.other_feedback).setOnClickListener { _ ->
            if (Signin.isSignedIn)
                startActivity(Intent(getActivity(), FeedbackActivity::class.java))
            else
                SnackMaker(getActivity()).showSnackbar(R.string.feedback_error_not_signed_in)
        }
    }

    private fun initializeDevSection(activity: Activity, rootView: View) {
        val isDevEnabled = Preferences.getPref(activity).getBoolean(Preferences.PREF_SHOW_DEV_SETTINGS, false)
        devView!!.visibility = if (isDevEnabled) View.VISIBLE else View.GONE

        rootView.findViewById<View>(R.id.dev_button_cache_clear).setOnClickListener { _ -> createClearDialog(activity, IValueCallback { CacheStore.clearAll(it!!) }, R.string.settings_cleared_all_cache_files) }
        rootView.findViewById<View>(R.id.dev_button_data_clear).setOnClickListener { _ -> createClearDialog(activity, IValueCallback { DataStore.clearAll(it!!) }, R.string.settings_cleared_all_data_files) }
        rootView.findViewById<View>(R.id.dev_button_upload_reports_clear).setOnClickListener { _ ->
            createClearDialog(activity, IValueCallback { _ ->
                DataStore.delete(activity, DataStore.RECENT_UPLOADS_FILE)
                Preferences.getPref(activity).edit().remove(Preferences.PREF_OLDEST_RECENT_UPLOAD).apply()
            }, R.string.settings_cleared_all_upload_reports)
        }

        rootView.findViewById<View>(R.id.dev_button_browse_files).setOnClickListener { _ ->
            createFileAlertDialog(activity, activity.filesDir, IVerify { file ->
                val name = file.name
                !name.startsWith("DATA") && !name.startsWith("firebase") && !name.startsWith("com.") && !name.startsWith("event_store") && !name.startsWith("_m_t") && name != "ZoomTables.data"
            })
        }

        rootView.findViewById<View>(R.id.dev_button_browse_cache_files).setOnClickListener { _ -> createFileAlertDialog(activity, activity.cacheDir, IVerify { file -> !file.name.startsWith("com.") && !file.isDirectory }) }


        rootView.findViewById<View>(R.id.dev_button_noise_tracking).setOnClickListener { _ -> startActivity(Intent(getActivity(), NoiseTestingActivity::class.java)) }

        rootView.findViewById<View>(R.id.dev_button_notification_dummy).setOnClickListener { _ ->
            val helloWorld = getString(R.string.dev_notification_dummy)
            val color = ContextCompat.getColor(activity, R.color.color_primary)
            val rng = Random(System.currentTimeMillis())
            val facts = resources.getStringArray(R.array.lorem_ipsum_facts)
            val notiBuilder = NotificationCompat.Builder(activity, getString(R.string.channel_other_id))
                    .setSmallIcon(R.drawable.ic_signals)
                    .setTicker(helloWorld)
                    .setColor(color)
                    .setLights(color, 2000, 5000)
                    .setContentTitle(getString(R.string.did_you_know))
                    .setContentText(facts[rng.nextInt(facts.size)])
                    .setWhen(System.currentTimeMillis())
            val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(dummyNotificationIndex++, notiBuilder.build())
        }

        rootView.findViewById<View>(R.id.dev_button_activity_recognition).setOnClickListener { _ -> startActivity(Intent(getActivity(), ActivityRecognitionActivity::class.java)) }
    }

    private fun createClearDialog(context: Context, clearFunction: IValueCallback<Context>, @StringRes snackBarString: Int) {
        val alertDialogBuilder = AlertDialog.Builder(context)
        alertDialogBuilder
                .setPositiveButton(resources.getText(R.string.yes)) { _, _ ->
                    SnackMaker(activity).showSnackbar(snackBarString)
                    clearFunction.callback(context)
                }
                .setNegativeButton(resources.getText(R.string.no)) { _, _ -> }
                .setMessage(resources.getText(R.string.alert_confirm_generic))

        alertDialogBuilder.show()
    }

    private fun createFileAlertDialog(context: Context, directory: File, verifyFunction: IVerify<File>?) {
        val files = directory.listFiles()
        val temp = files
                .filter { verifyFunction == null || verifyFunction.verify(it) }
                .map { it.name + "|  " + Assist.humanReadableByteCount(it.length(), true) }

        Collections.sort(temp) { obj, s -> obj.compareTo(s) }

        val alertDialogBuilder = AlertDialog.Builder(context)
        val fileNames = temp.toTypedArray()
        alertDialogBuilder
                .setTitle(getString(R.string.dev_browse_files))
                .setItems(fileNames) { _, which ->
                    val intent = Intent(activity, DebugFileActivity::class.java)
                    intent.putExtra("directory", directory.path)
                    intent.putExtra("fileName", fileNames[which].substring(0, fileNames[which].lastIndexOf('|')))
                    startActivity(intent)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }

        alertDialogBuilder.show()
    }

    private fun setSeekbar(context: Context,
                           slider: IntSlider,
                           title: TextView,
                           minValue: Int,
                           maxValue: Int,
                           step: Int,
                           preference: String?,
                           defaultValue: Int,
                           textGenerationFuncton: Slider.IStringify<Int>,
                           valueCallback: INonNullValueCallback<Int>?) {
        slider.maxValue = maxValue
        val previousProgress = Preferences.getPref(context).getInt(preference, defaultValue) - minValue
        slider.setProgressValue(previousProgress)
        slider.step = step
        slider.minValue = minValue
        slider.setTextView(title, textGenerationFuncton)
        if (valueCallback != null)
            slider.setOnValueChangeListener { _, _ -> valueCallback.callback(slider.value!!) }
    }

    private fun setSwitchChangeListener(context: Context, name: String, s: Switch, defaultState: Boolean, callback: INonNullValueCallback<Boolean>?) {
        s.isChecked = Preferences.getPref(context).getBoolean(name, defaultState)
        s.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            Preferences.getPref(context).edit().putBoolean(name, b).apply()
            callback?.callback(b)
        }
    }

    private fun resolveUserMenuOnLogin(u: User, prices: Prices) {
        val activity = activity!!

        if (!u.isServerDataAvailable) {
            SnackMaker(activity).showSnackbar(R.string.error_connection_failed)
            return
        }

        launch(UI) {
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
            val userInfoLayout = signedInMenu!!.getChildAt(0) as LinearLayout
            userInfoLayout.visibility = View.VISIBLE

            val wPointsTextView = userInfoLayout.getChildAt(0) as TextView
            wPointsTextView.text = String.format(activity.getString(R.string.user_have_wireless_points), Assist.formatNumber(u.wirelessPoints))

            val mapAccessLayout = userInfoLayout.getChildAt(1) as LinearLayout
            val mapAccessSwitch = mapAccessLayout.getChildAt(0) as Switch
            val mapAccessTimeTextView = mapAccessLayout.getChildAt(1) as TextView

            mapAccessSwitch.text = activity.getString(R.string.user_renew_map)
            mapAccessSwitch.isChecked = u.networkPreferences!!.renewMap
            mapAccessSwitch.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                compoundButton.isEnabled = false
                val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", java.lang.Boolean.toString(b)).build()
                Network.client(activity, u.token).newCall(Network.requestPOST(Network.URL_USER_UPDATE_MAP_PREFERENCE, body)).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        activity.runOnUiThread {
                            compoundButton.isEnabled = true
                            compoundButton.isChecked = !b
                        }
                    }

                    @Throws(IOException::class)
                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            val networkInfo = u.networkInfo!!
                            u.networkPreferences!!.renewMap = b
                            if (b) {
                                val rBody = response.body()
                                if (rBody != null) {
                                    val temp = networkInfo.mapAccessUntil
                                    networkInfo.mapAccessUntil = java.lang.Long.parseLong(rBody.string())
                                    if (temp != networkInfo.mapAccessUntil) {
                                        u.addWirelessPoints((-prices.PRICE_30DAY_MAP).toLong())
                                        activity.runOnUiThread {
                                            wPointsTextView.text = activity.getString(R.string.user_have_wireless_points, Assist.formatNumber(u.wirelessPoints))
                                            mapAccessTimeTextView.text = String.format(activity.getString(R.string.user_access_date), dateFormat.format(Date(networkInfo.mapAccessUntil)))
                                            mapAccessTimeTextView.visibility = View.VISIBLE
                                        }
                                    }

                                } else
                                    FirebaseCrash.report(Throwable("Body is null"))
                            }
                            DataStore.saveString(activity, Preferences.PREF_USER_DATA, Gson().toJson(u), false)
                        } else {
                            activity.runOnUiThread { compoundButton.isChecked = !b }
                            SnackMaker(activity).showSnackbar(R.string.user_not_enough_wp)
                        }
                        activity.runOnUiThread { compoundButton.isEnabled = true }
                        response.close()
                    }
                })
            }

            if (u.networkInfo!!.mapAccessUntil > System.currentTimeMillis())
                mapAccessTimeTextView.text = String.format(activity.getString(R.string.user_access_date), dateFormat.format(Date(u.networkInfo!!.mapAccessUntil)))
            else
                mapAccessTimeTextView.visibility = View.GONE
            (mapAccessLayout.getChildAt(2) as TextView).text = String.format(activity.getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_MAP))

            val userMapAccessLayout = userInfoLayout.getChildAt(2) as LinearLayout
            val userMapAccessSwitch = userMapAccessLayout.getChildAt(0) as Switch
            val personalMapAccessTimeTextView = userMapAccessLayout.getChildAt(1) as TextView

            userMapAccessSwitch.text = activity.getString(R.string.user_renew_personal_map)
            userMapAccessSwitch.isChecked = u.networkPreferences!!.renewPersonalMap
                    userMapAccessSwitch.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                        compoundButton.isEnabled = false
                        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", java.lang.Boolean.toString(b)).build()
                        Network.client(activity, u.token).newCall(Network.requestPOST(Network.URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE, body)).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                activity.runOnUiThread {
                                    compoundButton.isEnabled = true
                                    compoundButton.isChecked = !b
                                }
                            }

                            @Throws(IOException::class)
                            override fun onResponse(call: Call, response: Response) {
                                if (response.isSuccessful) {
                                    val networkInfo = u.networkInfo
                                    u.networkPreferences!!.renewPersonalMap = b
                                    if (b) {
                                        val rBody = response.body()
                                        if (rBody != null) {
                                            val temp = networkInfo!!.personalMapAccessUntil
                                            networkInfo.personalMapAccessUntil = java.lang.Long.parseLong(rBody.string())
                                            if (temp != networkInfo.personalMapAccessUntil) {
                                                u.addWirelessPoints((-prices.PRICE_30DAY_PERSONAL_MAP).toLong())
                                                activity.runOnUiThread {
                                                    wPointsTextView.text = activity.getString(R.string.user_have_wireless_points, Assist.formatNumber(u.wirelessPoints))
                                                    personalMapAccessTimeTextView.text = String.format(activity.getString(R.string.user_access_date), dateFormat.format(Date(networkInfo.personalMapAccessUntil)))
                                                    personalMapAccessTimeTextView.visibility = View.VISIBLE
                                                }
                                            }

                                        } else
                                            FirebaseCrash.report(Throwable("Body is null"))
                                    }
                                    DataStore.saveString(activity, Preferences.PREF_USER_DATA, Gson().toJson(u), false)
                                } else {
                                    activity.runOnUiThread { compoundButton.isChecked = !b }
                                    SnackMaker(activity).showSnackbar(R.string.user_not_enough_wp)
                                }
                                activity.runOnUiThread { compoundButton.isEnabled = true }
                                response.close()
                            }
                        })
                    }

            if (u.networkInfo!!.personalMapAccessUntil > System.currentTimeMillis())
                personalMapAccessTimeTextView.text = String.format(activity.getString(R.string.user_access_date), dateFormat.format(Date()))
            else
                personalMapAccessTimeTextView.visibility = View.GONE
            (userMapAccessLayout.getChildAt(2) as TextView).text = String.format(activity.getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_PERSONAL_MAP))
        }

        if (u.networkInfo!!.hasMapAccess())
            NetworkLoader.request(Network.URL_MAPS_AVAILABLE, DAY_IN_MINUTES, activity, Preferences.PREF_AVAILABLE_MAPS, Array<MapLayer>::class.java, IStateValueCallback { _, layerArray ->
                if (layerArray != null && layerArray.isNotEmpty()) {
                    val sp = Preferences.getPref(activity)
                    val defaultOverlay = sp.getString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].name)
                    val index = MapLayer.indexOf(layerArray, defaultOverlay)
                    val selectIndex = if (index == -1) 0 else index
                    if (index == -1)
                        sp.edit().putString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].name).apply()

                    val items = arrayOfNulls<CharSequence>(layerArray.size)
                    for (i in layerArray.indices)
                        items[i] = layerArray[i].name

                    activity.runOnUiThread {
                        val mapOverlayButton = rootView!!.findViewById<Button>(R.id.setting_map_overlay_button)

                        val adapter = ArrayAdapter(activity, R.layout.spinner_item, MapLayer.toStringArray(layerArray))
                        adapter.setDropDownViewResource(R.layout.spinner_item)
                        mapOverlayButton.text = items[selectIndex]
                        mapOverlayButton.setOnClickListener { _ ->
                            val ov = sp.getString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].name)
                            val `in` = MapLayer.indexOf(layerArray, ov)
                            val selectIn = if (`in` == -1) 0 else `in`

                            val alertDialogBuilder = AlertDialog.Builder(activity, R.style.AlertDialog)
                            alertDialogBuilder
                                    .setTitle(getString(R.string.settings_default_map_overlay))
                                    .setSingleChoiceItems(items, selectIn) { dialog, which ->
                                        Preferences.getPref(activity).edit().putString(Preferences.PREF_DEFAULT_MAP_OVERLAY, adapter.getItem(which)).apply()
                                        mapOverlayButton.text = items[which]
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton(R.string.cancel) { _, _ -> }

                            alertDialogBuilder.create().show()
                        }

                        val mDOLayout = rootView!!.findViewById<LinearLayout>(R.id.settings_map_overlay_layout)
                        mDOLayout.visibility = View.VISIBLE
                    }
                }
            })
    }

    override fun onEnter(activity: FragmentActivity, fabOne: FloatingActionButton, fabTwo: FloatingActionButton): Failure<String> =
            Failure()

    override fun onLeave(activity: FragmentActivity) {
        if (signin != null) {
            signin = null
        }
    }

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {
        when (requestCode) {
            REQUEST_CODE_PERMISSIONS_MICROPHONE -> if (success)
                Preferences.getPref(context!!).edit().putBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, true).apply()
            else
                switchNoise!!.isChecked = false
            else -> throw UnsupportedOperationException("Permissions with requestPOST code $requestCode has no defined behavior")
        }
    }

    override fun onHomeAction() {
        val v = view
        if (v != null) {
            Assist.verticalSmoothScrollTo(v.findViewById(R.id.settings_scrollbar), 0, 500)
        }
    }

}
