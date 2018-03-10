package com.adsamcik.signalcollector.fragments

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat.getDrawable
import android.support.v4.content.ContextCompat.startForegroundService
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.view.children
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.SettingsActivity
import com.adsamcik.signalcollector.activities.UserActivity
import com.adsamcik.signalcollector.components.InfoComponent
import com.adsamcik.signalcollector.data.CellData
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.data.WifiData
import com.adsamcik.signalcollector.enums.CloudStatus
import com.adsamcik.signalcollector.enums.ResolvedActivity
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.interfaces.ITabFragment
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.uitools.dpAsPx
import com.adsamcik.signalcollector.utility.*
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.android.synthetic.main.activity_user.*
import kotlinx.android.synthetic.main.fragment_new_tracker.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.util.*

class FragmentNewTracker : Fragment(), ITabFragment {
    private lateinit var colorManager: ColorManager

    private var wifiInfo: InfoComponent? = null
    private var cellInfo: InfoComponent? = null

    private var lastWifiTime: Long = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_new_tracker, container, false)
    }

    override fun onStart() {
        super.onStart()
        retainInstance = true
        initializeColorElements()

        button_settings.setOnClickListener { startActivity<SettingsActivity> { } }
        updateUploadButton()

        if (useMock)
            mock()

        button_tracking.setOnClickListener { _ ->
            if (TrackerService.isRunning && TrackerService.isBackgroundActivated) {
                val lockedForMinutes = TrackerService.setAutoLock()
                SnackMaker(root).showSnackbar(activity!!.resources.getQuantityString(R.plurals.notification_auto_tracking_lock, lockedForMinutes, lockedForMinutes))
            } else
                toggleCollecting(activity!!, !TrackerService.isRunning)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ColorSupervisor.recycleColorManager(colorManager)
    }

    override fun onResume() {
        super.onResume()
        val orientation = Assist.orientation(context!!)
        if (orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270) {
            include.setPadding(72.dpAsPx, 0, 72.dpAsPx, 0)
        }

        TrackerService.onServiceStateChange = { launch(UI) { updateTrackerButton() } }
    }

    /**
     * Enables or disables collecting service
     *
     * @param enable ensures intended action
     */
    private fun toggleCollecting(activity: Activity, enable: Boolean) {
        if (TrackerService.isRunning == enable)
            return

        val requiredPermissions = Assist.checkTrackingPermissions(activity)

        if (requiredPermissions == null) {
            if (!TrackerService.isRunning) {
                if (!Assist.isGNSSEnabled(activity)) {
                    SnackMaker(activity).showSnackbar(R.string.error_gnss_not_enabled, R.string.enable, View.OnClickListener { _ ->
                        val gpsOptionsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        startActivity(gpsOptionsIntent)
                    })
                } else if (!Assist.canTrack(activity)) {
                    SnackMaker(activity).showSnackbar(R.string.error_nothing_to_track)
                } else {
                    Preferences.getPref(activity).edit().putBoolean(Preferences.PREF_STOP_TILL_RECHARGE, false).apply()
                    val trackerService = Intent(activity, TrackerService::class.java)
                    trackerService.putExtra("backTrack", false)
                    startForegroundService(activity, trackerService)
                }
            } else {
                activity.stopService(Intent(activity, TrackerService::class.java))
            }

        } else if (Build.VERSION.SDK_INT >= 23) {
            activity.requestPermissions(requiredPermissions, 0)
        }
    }

    private fun mock() {
        val rawData = RawData(System.currentTimeMillis())
        rawData.activity = ResolvedActivity.ON_FOOT
        rawData.wifi = arrayOf(WifiData(), WifiData(), WifiData())
        rawData.accuracy = 6f
        rawData.cellCount = 8
        rawData.registeredCells = arrayOf(CellData("MOCK", 2, 0, 123, 456, -30, 90, 0))
        rawData.latitude = 15.0
        rawData.longitude = 15.0
        rawData.altitude = 123.0
        rawData.wifiTime = System.currentTimeMillis()
        updateData(rawData)

        colorManager.notififyChangeOn(content)
    }

    private fun initializeColorElements() {
        colorManager = ColorSupervisor.createColorManager(context!!)
        colorManager.watchElement(ColorView(topPanelLayout, 1, true, false))
        colorManager.watchElement(bar_info_top)
    }

    private fun setUploadButtonClickable() {
        button_upload.setOnClickListener { _ ->
            val context = context!!
            val failure = UploadJobService.requestUpload(context, UploadJobService.UploadScheduleSource.USER)
            FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.MANUAL_UPLOAD_EVENT, Bundle())
            if (failure.hasFailed())
                SnackMaker(activity!!).showSnackbar(failure.value!!)
            else {
                updateUploadButton()
            }
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

    private fun updateTrackerButton() {
        if (TrackerService.isRunning) {
            button_tracking.setImageResource(R.drawable.ic_pause_circle_filled_black_24dp)
        } else
            button_tracking.setImageResource(R.drawable.ic_play_circle_filled_black_24dp)
    }

    private fun updateUploadButton() {
        if (!Signin.isSignedIn) {
            button_upload.setImageResource(R.drawable.ic_account_circle_black_24dp)
            button_upload.setOnClickListener {
                startActivity<UserActivity> { }
            }
            button_upload.visibility = View.VISIBLE
            return
        }

        when (Network.cloudStatus) {
            CloudStatus.NO_SYNC_REQUIRED -> {
                button_upload.setOnClickListener(null)
                button_upload.visibility = View.GONE
            }
            CloudStatus.SYNC_AVAILABLE -> {
                button_upload.setImageResource(R.drawable.ic_cloud_upload_24dp)
                setUploadButtonClickable()
                button_upload.visibility = View.VISIBLE
            }
            CloudStatus.SYNC_SCHEDULED -> {
                button_upload.setImageResource(R.drawable.ic_cloud_queue_black_24dp)
                setUploadButtonClickable()
                button_upload.visibility = View.VISIBLE
            }
            CloudStatus.SYNC_IN_PROGRESS -> {
                button_upload.setImageResource(R.drawable.ic_sync_black_24dp)
                button_upload.setOnClickListener(null)
                button_upload.visibility = View.VISIBLE
            }
            CloudStatus.ERROR -> {
                button_upload.setImageResource(R.drawable.ic_cloud_off_24dp)
                button_upload.setOnClickListener(null)
                button_upload.visibility = View.VISIBLE
            }
        }
    }

    private fun initializeWifiInfo(): InfoComponent {
        if (wifiInfo != null)
            return wifiInfo!!

        val drawable = getDrawable(context!!, R.drawable.ic_network_wifi_24dp)!!
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)

        val component = (layoutInflater.inflate(R.layout.template_component_info, content) as ViewGroup).children.last() as InfoComponent
        component.setColorManager(colorManager)
        component.setTitle(drawable, getString(R.string.wifi))
        component.addSecondaryText(WIFI_COMPONENT_DISTANCE, "")
        component.addPrimaryText(WIFI_COMPONENT_COUNT, "")
        wifiInfo = component
        return component
    }

    private fun initializeCellInfo(): InfoComponent {
        if (cellInfo != null)
            return cellInfo!!
        val drawable = getDrawable(context!!, R.drawable.ic_network_cell_black_24dp)!!
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)

        val component = (layoutInflater.inflate(R.layout.template_component_info, content) as ViewGroup).children.last() as InfoComponent
        component.setColorManager(colorManager)
        component.setTitle(drawable, getString(R.string.cell))
        component.addSecondaryText(CELL_COMPONENT_COUNT, "")
        component.addPrimaryText(CELL_COMPONENT_CURRENT, "")
        cellInfo = component
        return component
    }

    private fun updateData(d: RawData) {
        val context = context!!
        val res = context.resources
        setCollected(DataStore.sizeOfData(context), DataStore.collectionCount(context))

        if (DataStore.sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE && Network.cloudStatus == CloudStatus.NO_SYNC_REQUIRED) {
            Network.cloudStatus = CloudStatus.SYNC_AVAILABLE
            updateUploadButton()
        }

        textview_time.text = res.getString(R.string.main_last_update, DateFormat.getTimeFormat(context).format(Date(d.time)))

        if (d.accuracy != null) {
            accuracy.visibility = View.VISIBLE
            accuracy.text = getString(R.string.info_accuracy, d.accuracy!!.toInt())
        } else
            accuracy.visibility = View.GONE

        altitude.text = getString(R.string.info_altitude, d.altitude!!.toInt())

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
                component.setVisibility(CELL_COMPONENT_CURRENT, View.GONE)
            component.setText(CELL_COMPONENT_COUNT, res.getString(R.string.main_cell_count, d.cellCount))
        } else {
            cellInfo?.detach()
            cellInfo = null
        }

        when (d.activity) {
            ResolvedActivity.STILL -> icon_activity.setImageResource(R.drawable.ic_accessibility_white_24dp)
            ResolvedActivity.ON_FOOT -> icon_activity.setImageResource(R.drawable.ic_directions_walk_white_24dp)
            ResolvedActivity.IN_VEHICLE -> icon_activity.setImageResource(R.drawable.ic_directions_car_white_24dp)
            else -> icon_activity.setImageResource(R.drawable.ic_help_white_24dp)
        }
    }

    override fun onEnter(activity: FragmentActivity, fabOne: FloatingActionButton, fabTwo: FloatingActionButton) {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onLeave(activity: FragmentActivity) {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onHomeAction() {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    companion object {
        private const val WIFI_COMPONENT_COUNT = "WifiCount"
        private const val WIFI_COMPONENT_DISTANCE = "WifiDistance"
        private const val CELL_COMPONENT_COUNT = "CellCount"
        private const val CELL_COMPONENT_CURRENT = "CellCurrent"
    }

}