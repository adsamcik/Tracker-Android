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
import androidx.lifecycle.LifecycleObserver
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.SettingsActivity
import com.adsamcik.signalcollector.components.InfoComponent
import com.adsamcik.signalcollector.data.*
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.enums.ResolvedActivities
import com.adsamcik.signalcollector.extensions.*
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.*
import com.google.android.gms.location.DetectedActivity
import kotlinx.android.synthetic.main.fragment_tracker.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class FragmentTracker : androidx.fragment.app.Fragment(), LifecycleObserver {
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

		button_tracking.setOnClickListener {
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
		updateExtendedInfoBar()

		TrackerService.isServiceRunning.observe(this) {
			updateTrackerButton(it)
		}

		TrackerService.rawDataEcho.observe(this) {
			if (it != null && it.time > 0) {
				updateData(it)
			}
		}

		bar_info_top.setOnClickListener {
			bar_info_top_extended.visibility = if (bar_info_top_extended.visibility == VISIBLE) GONE else VISIBLE
			updateExtendedInfoBar()
		}
	}

	private fun updateExtendedInfoBar() {
		if (bar_info_top_extended.visibility == VISIBLE) {
			colorManager.watchView(ColorView(bar_info_top_extended, 0, recursive = true, rootIsBackground = false, ignoreRoot = true))
			initializeExtendedInfo()
		} else {
			colorManager.stopWatchingView(bar_info_top_extended)
		}
	}


	override fun onStop() {
		ColorSupervisor.recycleColorManager(colorManager)
		super.onStop()
	}

	override fun onResume() {
		super.onResume()
		val context = context!!

		val orientation = Assist.orientation(context)
		if (orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270) {
			include.setPadding(72.dpAsPx, 0, 72.dpAsPx, 0)
		}


		val appDatabase = AppDatabase.getAppDatabase(context)
		val locationDao = appDatabase.locationDao()

		val liveCount = locationDao.count()
		setCollected(liveCount.value ?: 0)

		liveCount.observe(this) { GlobalScope.launch(Dispatchers.Main) { setCollected(it!!) } }

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
					SnackMaker(activity.findViewById(R.id.root)).showSnackbar(R.string.error_gnss_not_enabled, R.string.enable, View.OnClickListener {
						val locationOptionsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
						startActivity(locationOptionsIntent)
					})
				} else if (!Assist.canTrack(activity)) {
					SnackMaker(activity.findViewById(R.id.root)).showSnackbar(R.string.error_nothing_to_track)
				} else {
					val keyDisableTillRecharge = getString(R.string.settings_disabled_recharge_key)

					Preferences.getPref(activity).edit {
						putBoolean(keyDisableTillRecharge, false)
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
		rawData.location = Location(rawData.time, 15.0, 15.0, 123.0, 6f, 3f)
		rawData.activity = ActivityInfo(DetectedActivity.RUNNING, 75)
		rawData.wifi = WifiData(System.currentTimeMillis(), arrayOf(WifiInfo(), WifiInfo(), WifiInfo()))
		rawData.cell = CellData(arrayOf(CellInfo("MOCK", CellType.LTE, 0, "123", "456", 90, -30, 0)), 8)
		updateData(rawData)
	}

	private fun initializeColorElements() {
		colorManager = ColorSupervisor.createColorManager(context!!)
		colorManager.watchView(ColorView(top_panel, 1, recursive = true, rootIsBackground = false))
		colorManager.watchView(ColorView(bar_info_top, 1, recursive = true, rootIsBackground = false))

		cellInfo?.setColorManager(colorManager)
		wifiInfo?.setColorManager(colorManager)
	}

	/**
	 * Updates collected data text
	 *
	 * @param collected amount of collected data
	 */
	private fun setCollected(count: Int) {
		val resources = context!!.resources
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
		component.setTitle(drawable, getString(R.string.cell))
		component.addPrimaryText(CELL_COMPONENT_CURRENT, "")
		component.addSecondaryText(CELL_COMPONENT_COUNT, "")
		component.setColorManager(colorManager)
		cellInfo = component
		return component
	}

	private fun initializeExtendedInfo() {
		val rawData = TrackerService.rawDataEcho.value
		if (rawData != null) {
			updateExtendedInfo(rawData)
		} else {
			longitude.visibility = GONE
			latitude.visibility = GONE
		}
		//archived_data.text = getString(R.string.main_archived_data, Assist.humanReadableByteCount(LongTermStore.sizeOfStoredFiles(context!!), true))
	}

	private fun updateExtendedInfo(rawData: RawData) {
		val location = rawData.location
		if (location != null) {
			longitude.text = getString(R.string.main_longitude, Assist.coordinateToString(location.longitude))
			latitude.text = getString(R.string.main_latitude, Assist.coordinateToString(location.latitude))

			if (longitude.visibility == GONE) {
				colorManager.notifyChangeOn(bar_info_top_extended)

				longitude.visibility = VISIBLE
				latitude.visibility = VISIBLE
			}
		} else {
			longitude.visibility = GONE
			latitude.visibility = GONE
		}
	}

	//todo refactor
	private fun updateData(rawData: RawData) {
		val context = context!!
		val res = context.resources

		textview_time.text = res.getString(R.string.main_last_update, DateFormat.getTimeFormat(context).format(Date(rawData.time)))

		val location = rawData.location
		if (location != null) {
			if (location.horizontalAccuracy != null) {
				accuracy.visibility = VISIBLE
				accuracy.text = getString(R.string.info_accuracy, location.horizontalAccuracy.toInt())
			} else
				accuracy.visibility = GONE

			//todo add vertical accuracy

			if (location.altitude != null) {
				altitude.text = getString(R.string.info_altitude, location.altitude.toInt())
				altitude.visibility = VISIBLE
			} else
				altitude.visibility = GONE
		} else {
			accuracy.visibility = GONE
			altitude.visibility = GONE
		}

		when {
			rawData.wifi != null -> {
				val component = initializeWifiInfo()
				component.setText(WIFI_COMPONENT_COUNT, res.getString(R.string.main_wifi_count, rawData.wifi!!.inRange.size))
				component.setText(WIFI_COMPONENT_DISTANCE, res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi))
				lastWifiTime = rawData.time
			}
			lastWifiTime - rawData.time < Constants.MINUTE_IN_MILLISECONDS && wifiInfo != null ->
				wifiInfo!!.setText(WIFI_COMPONENT_DISTANCE, res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi))
			else -> {
				wifiInfo?.detach()
				wifiInfo = null
			}
		}

		val cell = rawData.cell
		if (cell != null) {
			val component = initializeCellInfo()
			if (cell.registeredCells.isNotEmpty()) {
				component.setText(CELL_COMPONENT_CURRENT, res.getString(R.string.main_cell_current, cell.registeredCells[0].type.name, cell.registeredCells[0].dbm, cell.registeredCells[0].asu))
			} else
				component.setVisibility(CELL_COMPONENT_CURRENT, GONE)
			component.setText(CELL_COMPONENT_COUNT, res.getString(R.string.main_cell_count, cell.totalCount))
		} else {
			cellInfo?.detach()
			cellInfo = null
		}

		when (rawData.activity?.resolvedActivity) {
			ResolvedActivities.STILL -> {
				icon_activity.setImageResource(R.drawable.ic_outline_still_24px)
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

		if (bar_info_top_extended.visibility == VISIBLE) {
			updateExtendedInfo(rawData)
		}
	}

	companion object {
		private const val WIFI_COMPONENT_COUNT = "WifiCount"
		private const val WIFI_COMPONENT_DISTANCE = "WifiDistance"
		private const val CELL_COMPONENT_COUNT = "CellCount"
		private const val CELL_COMPONENT_CURRENT = "CellCurrent"
	}

}