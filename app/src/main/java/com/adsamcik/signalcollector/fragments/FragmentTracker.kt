package com.adsamcik.signalcollector.fragments

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.content.edit
import androidx.core.view.children
import androidx.fragment.app.FragmentActivity
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.SettingsActivity
import com.adsamcik.signalcollector.activities.UserActivity
import com.adsamcik.signalcollector.components.InfoComponent
import com.adsamcik.signalcollector.data.CellData
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.data.WifiData
import com.adsamcik.signalcollector.enums.ActionSource
import com.adsamcik.signalcollector.enums.CloudStatuses
import com.adsamcik.signalcollector.enums.ResolvedActivities
import com.adsamcik.signalcollector.extensions.*
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.*
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.android.synthetic.main.fragment_tracker.*
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.android.Main
import kotlinx.coroutines.experimental.launch
import java.util.*

class FragmentTracker : androidx.fragment.app.Fragment() {
    private lateinit var colorManager: ColorManager

    private var wifiInfo: InfoComponent? = null
    private var cellInfo: InfoComponent? = null

    private var lastWifiTime: Long = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return if (view != null)
            view
        else
            inflater.inflate(R.layout.fragment_tracker, container, false)
    }

    override fun onStart() {
        super.onStart()

        icon_activity.visibility = GONE
        altitude.visibility = GONE
        accuracy.visibility = GONE


        button_settings.setOnClickListener { startActivity<SettingsActivity> { } }
        updateUploadButton()

        button_tracking.setOnClickListener { _ ->
            val activity = activity!!
            if (TrackerService.isServiceRunning.value && TrackerService.isBackgroundActivated) {
                val lockedForMinutes = 30
                TrackingLocker.lockTimeLock(activity, Constants.MINUTE_IN_MILLISECONDS * lockedForMinutes)
                SnackMaker(activity.findViewById(R.id.root) as View).showSnackbar(activity.resources.getQuantityString(R.plurals.notification_auto_tracking_lock, lockedForMinutes, lockedForMinutes))
            } else
                toggleCollecting(activity, !TrackerService.isServiceRunning.value)
        }

        button_tracking_lock.setOnClickListener {
            val context = context!!
            TrackingLocker.unlockTimeLock(context)
            TrackingLocker.unlockRechargeLock(context)
        }

        TrackingLocker.isLocked.observe(this) {
            button_tracking_lock.visibility = if (it) VISIBLE else GONE
        }

        initializeColorElements()

        TrackerService.isServiceRunning.observe(this) {
            updateTrackerButton(it)
        }

        TrackerService.rawDataEcho.observe(this) {
            if (it != null && it.time > 0) {
                updateData(it)
            }
        }
    }


    override fun onStop() {
        ColorSupervisor.recycleColorManager(colorManager)
        DataStore.setOnDataChanged(null)
        DataStore.setOnUploadProgress(null)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val context = context!!

        val orientation = Assist.orientation(context)
        if (orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270) {
            include.setPadding(72.dpAsPx, 0, 72.dpAsPx, 0)
        }



        setCollected(DataStore.sizeOfData(context), DataStore.collectionCount(context))

        updateUploadButton()
        Signin.signIn(context) {
            if (it != null)
                updateUploadButton()
        }

        DataStore.setOnDataChanged { GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT, null, { setCollected(DataStore.sizeOfData(activity!!), DataStore.collectionCount(activity!!)) }) }
        DataStore.setOnUploadProgress { GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT, null, { updateUploadButton() }) }

        if (useMock)
            mock()
    }

    /**
     * Enables or disables collecting service
     *
     * @param enable ensures intended action
     */
    private fun toggleCollecting(activity: FragmentActivity, enable: Boolean) {
        if (TrackerService.isServiceRunning.value == enable)
            return

        val requiredPermissions = Assist.checkTrackingPermissions(activity)
        val view = view

        if (requiredPermissions == null && view != null) {
            if (!TrackerService.isServiceRunning.value) {
                if (!Assist.isGNSSEnabled(activity)) {
                    SnackMaker(activity.findViewById(R.id.root)).showSnackbar(R.string.error_gnss_not_enabled, R.string.enable, View.OnClickListener { _ ->
                        val locationOptionsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        startActivity(locationOptionsIntent)
                    })
                } else if (!Assist.canTrack(activity)) {
                    SnackMaker(activity.findViewById(R.id.root)).showSnackbar(R.string.error_nothing_to_track)
                } else {
                    Preferences.getPref(activity).edit {
                        putBoolean(Preferences.PREF_STOP_UNTIL_RECHARGE, false)
                    }

                    activity.startForegroundService<TrackerService> {
                        putExtra("backTrack", false)
                    }

                    updateTrackerButton(true)
                }
            } else {
                activity.stopService<TrackerService>()
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            activity.requestPermissions(requiredPermissions!!, 0)
        }
    }

    private fun mock() {
        val rawData = RawData(System.currentTimeMillis())
        rawData.activity = ResolvedActivities.ON_FOOT
        rawData.wifi = arrayOf(WifiData(), WifiData(), WifiData())
        rawData.accuracy = 6f
        rawData.cellCount = 8
        rawData.registeredCells = arrayOf(CellData("MOCK", 2, 0, "123", "456", -30, 90, 0))
        rawData.latitude = 15.0
        rawData.longitude = 15.0
        rawData.altitude = 123.0
        rawData.wifiTime = System.currentTimeMillis()
        updateData(rawData)
    }

    private fun initializeColorElements() {
        colorManager = ColorSupervisor.createColorManager(context!!)
        colorManager.watchView(ColorView(top_panel, 1, true, false))
        colorManager.watchView(ColorView(bar_info_top, 1, true, false))

        cellInfo?.setColorManager(colorManager)
        wifiInfo?.setColorManager(colorManager)
    }

    private fun setUploadButtonClickable() {
        button_upload.setOnClickListener { _ ->
            val activity = activity!!
            GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT, null, {
                if (Assist.privacyPolicy(activity)) {
                    val success = UploadJobService.requestUpload(activity, ActionSource.USER)
                    FirebaseAnalytics.getInstance(activity).logEvent(FirebaseAssist.MANUAL_UPLOAD_EVENT, Bundle())
                    if (success)
                        updateUploadButton()
                }
            })
        }
    }

    /**
     * Updates collected data text
     *
     * @param collected amount of collected data
     */
    private fun setCollected(collected: Long, count: Int) {
        val resources = context!!.resources
        data_size!!.text = resources.getString(R.string.main_collected, Assist.humanReadableByteCount(collected, true))
        collection_count!!.text = resources.getQuantityString(R.plurals.main_collections, count, count)
    }

    private fun updateTrackerButton(state: Boolean) {
        if (state) {
            button_tracking.setImageResource(R.drawable.ic_pause_circle_filled_black_24dp)
            button_tracking.contentDescription = getString(R.string.description_tracking_stop)
        } else {
            button_tracking.setImageResource(R.drawable.ic_play_circle_filled_black_24dp)
            button_tracking.contentDescription = getString(R.string.description_tracking_start)
        }
    }

    private fun updateUploadButton() {
        button_upload?.post {
            if (!Signin.isSignedIn) {
                button_upload.setImageResource(R.drawable.ic_account_circle_black_24dp)
                button_upload.setOnClickListener {
                    startActivity<UserActivity> { }
                }
                button_upload.visibility = View.VISIBLE
                return@post
            }

            when (Network.cloudStatus) {
                CloudStatuses.NO_SYNC_REQUIRED, CloudStatuses.UNKNOWN -> {
                    button_upload.setOnClickListener(null)
                    button_upload.visibility = View.GONE
                }
                CloudStatuses.SYNC_AVAILABLE -> {
                    button_upload.setImageResource(R.drawable.ic_cloud_upload_24dp)
                    setUploadButtonClickable()
                    button_upload.visibility = View.VISIBLE
                }
                CloudStatuses.SYNC_SCHEDULED -> {
                    button_upload.setImageResource(R.drawable.ic_cloud_queue_black_24dp)
                    setUploadButtonClickable()
                    button_upload.visibility = View.VISIBLE
                }
                CloudStatuses.SYNC_IN_PROGRESS -> {
                    button_upload.setImageResource(R.drawable.ic_sync_black_24dp)
                    button_upload.setOnClickListener(null)
                    button_upload.visibility = View.VISIBLE
                }
                CloudStatuses.ERROR -> {
                    button_upload.setImageResource(R.drawable.ic_cloud_off_24dp)
                    button_upload.setOnClickListener(null)
                    button_upload.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun initializeWifiInfo(): InfoComponent {
        if (wifiInfo != null)
            return wifiInfo!!

        val drawable = getDrawable(context!!, R.drawable.ic_network_wifi_24dp)!!
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)

        val component = (layoutInflater.inflate(R.layout.template_component_info, content) as ViewGroup).children.last() as InfoComponent
        component.setTitle(drawable, getString(R.string.wifi))
        component.addPrimaryText(WIFI_COMPONENT_COUNT, "")
        component.addSecondaryText(WIFI_COMPONENT_DISTANCE, "")
        component.setColorManager(colorManager)
        wifiInfo = component
        return component
    }

    private fun initializeCellInfo(): InfoComponent {
        if (cellInfo != null)
            return cellInfo!!
        val drawable = getDrawable(context!!, R.drawable.ic_network_cell_black_24dp)!!
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)

        val component = (layoutInflater.inflate(R.layout.template_component_info, content) as ViewGroup).children.last() as InfoComponent
        component.setTitle(drawable, getString(R.string.cell_title))
        component.addPrimaryText(CELL_COMPONENT_CURRENT, "")
        component.addSecondaryText(CELL_COMPONENT_COUNT, "")
        component.setColorManager(colorManager)
        cellInfo = component
        return component
    }

    private fun updateData(d: RawData) {
        val context = context!!
        val res = context.resources
        setCollected(DataStore.sizeOfData(context), DataStore.collectionCount(context))

        if (DataStore.sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE && Network.cloudStatus == CloudStatuses.NO_SYNC_REQUIRED) {
            Network.cloudStatus = CloudStatuses.SYNC_AVAILABLE
            updateUploadButton()
        }

        textview_time.text = res.getString(R.string.main_last_update, DateFormat.getTimeFormat(context).format(Date(d.time)))

        if (d.accuracy != null) {
            accuracy.visibility = VISIBLE
            accuracy.text = getString(R.string.info_accuracy, d.accuracy!!.toInt())
        } else
            accuracy.visibility = GONE

        altitude.text = getString(R.string.info_altitude, d.altitude!!.toInt())
        altitude.visibility = VISIBLE

        when {
            d.wifi != null -> {
                val component = initializeWifiInfo()
                component.setText(WIFI_COMPONENT_COUNT, res.getString(R.string.main_wifi_count, d.wifi!!.size))
                component.setText(WIFI_COMPONENT_DISTANCE, res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi))
                lastWifiTime = d.time
            }
            lastWifiTime - d.time < Constants.MINUTE_IN_MILLISECONDS && wifiInfo != null ->
                wifiInfo!!.setText(WIFI_COMPONENT_DISTANCE, res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi))
            else -> {
                wifiInfo?.detach()
                wifiInfo = null
            }
        }

        if (d.cellCount != null) {
            val component = initializeCellInfo()
            val registered = d.registeredCells
            if (registered != null && registered.isNotEmpty()) {
                component.setText(CELL_COMPONENT_CURRENT, res.getString(R.string.main_cell_current, registered[0].getType(), registered[0].dbm, registered[0].asu))
            } else
                component.setVisibility(CELL_COMPONENT_CURRENT, GONE)
            component.setText(CELL_COMPONENT_COUNT, res.getString(R.string.main_cell_count, d.cellCount))
        } else {
            cellInfo?.detach()
            cellInfo = null
        }

        when (d.activity) {
            ResolvedActivities.STILL -> {
                icon_activity.setImageResource(R.drawable.ic_accessibility_white_24dp)
                icon_activity.contentDescription = getString(R.string.activity_idle)
                icon_activity.visibility = VISIBLE
            }
            ResolvedActivities.ON_FOOT -> {
                icon_activity.setImageResource(R.drawable.ic_directions_walk_white_24dp)
                icon_activity.contentDescription = getString(R.string.activity_on_foot)
                icon_activity.visibility = VISIBLE
            }
            ResolvedActivities.IN_VEHICLE -> {
                icon_activity.setImageResource(R.drawable.ic_directions_car_white_24dp)
                icon_activity.contentDescription = getString(R.string.activity_in_vehicle)
                icon_activity.visibility = VISIBLE
            }
            ResolvedActivities.UNKNOWN -> {
                icon_activity.setImageResource(R.drawable.ic_help_white_24dp)
                icon_activity.contentDescription = getString(R.string.activity_unknown)
                icon_activity.visibility = VISIBLE
            }
            else -> icon_activity.visibility = GONE
        }
    }

    companion object {
        private const val WIFI_COMPONENT_COUNT = "WifiCount"
        private const val WIFI_COMPONENT_DISTANCE = "WifiDistance"
        private const val CELL_COMPONENT_COUNT = "CellCount"
        private const val CELL_COMPONENT_CURRENT = "CellCurrent"
    }

}