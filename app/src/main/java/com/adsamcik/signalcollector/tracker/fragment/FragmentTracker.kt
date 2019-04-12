package com.adsamcik.signalcollector.tracker.fragment

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
import androidx.core.view.children
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleObserver
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.activity.GroupedActivity
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.app.color.ColorManager
import com.adsamcik.signalcollector.app.color.ColorSupervisor
import com.adsamcik.signalcollector.app.color.ColorView
import com.adsamcik.signalcollector.app.widget.InfoComponent
import com.adsamcik.signalcollector.misc.SnackMaker
import com.adsamcik.signalcollector.misc.extension.*
import com.adsamcik.signalcollector.mock.useMock
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.preference.activity.SettingsActivity
import com.adsamcik.signalcollector.tracker.data.*
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.TrackerService
import com.google.android.gms.location.DetectedActivity
import kotlinx.android.synthetic.main.activity_ui.*
import kotlinx.android.synthetic.main.fragment_tracker.*
import java.util.*
import kotlin.math.roundToInt

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
		horizontal_accuracy.visibility = GONE


		button_settings.setOnClickListener { startActivity<SettingsActivity> { } }

		button_tracking.setOnClickListener {
			val activity = activity!!
			if (TrackerService.isServiceRunning.value && TrackerService.isBackgroundActivated) {
				val lockedForMinutes = 60
				TrackerLocker.lockTimeLock(activity, Constants.MINUTE_IN_MILLISECONDS * lockedForMinutes)
				SnackMaker(activity.findViewById(R.id.root) as View).showSnackbar(activity.resources.getQuantityString(R.plurals.notification_auto_tracking_lock, lockedForMinutes, lockedForMinutes))
			} else
				toggleCollecting(activity, !TrackerService.isServiceRunning.value)
		}

		button_tracking_lock.setOnClickListener {
			val context = context!!
			TrackerLocker.unlockTimeLock(context)
			TrackerLocker.unlockRechargeLock(context)
		}

		TrackerLocker.isLocked.observeGetCurrent(this) {
			button_tracking_lock.visibility = if (it) VISIBLE else GONE
		}

		initializeColorElements()
		updateExtendedInfoBar()

		TrackerService.isServiceRunning.observeGetCurrent(this) {
			updateTrackerButton(it)
		}

		TrackerService.trackerEcho.observe(this) {
			if (it != null && it.first.start > 0) {
				updateData(it.first, it.second)
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
					SnackMaker(activity.root).showSnackbar(R.string.error_gnss_not_enabled,
							priority = SnackMaker.SnackbarPriority.IMPORTANT,
							actionRes = R.string.enable,
							onActionClick = View.OnClickListener {
								val locationOptionsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
								startActivity(locationOptionsIntent)
							})
				} else if (!Assist.canTrack(activity)) {
					SnackMaker(activity.findViewById(R.id.root)).showSnackbar(R.string.error_nothing_to_track)
				} else {
					Preferences.getPref(activity).edit {
						setBoolean(R.string.settings_disabled_recharge_key, false)
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
		val rawData = MutableCollectionData(System.currentTimeMillis())
		val location = Location(rawData.time, 15.0, 15.0, 123.0, 6f, 3f, 10f, 15f)
		rawData.location = location
		rawData.activity = ActivityInfo(DetectedActivity.RUNNING, 75)
		rawData.wifi = WifiData(android.location.Location("").apply {
			latitude = location.latitude
			longitude = location.longitude
			altitude = location.altitude!!
			if (Build.VERSION.SDK_INT >= 26)
				verticalAccuracyMeters = location.verticalAccuracy!!
			accuracy = location.horizontalAccuracy!!
		}, System.currentTimeMillis(), arrayOf(WifiInfo(), WifiInfo(), WifiInfo()))
		rawData.cell = CellData(arrayOf(CellInfo("MOCK", CellType.LTE, 0, "123", "456", 90, -30, 0)), 8)

		val session = TrackerSession(System.currentTimeMillis() - 5 * Constants.MINUTE_IN_MILLISECONDS, System.currentTimeMillis(), 56, 5410f, 15f, 5000f, 154)

		updateData(session, rawData)
	}

	private fun initializeColorElements() {
		colorManager = ColorSupervisor.createColorManager(context!!)
		colorManager.watchView(ColorView(top_panel, 1, recursive = true, rootIsBackground = false))
		colorManager.watchView(ColorView(bar_info_top, 1, recursive = true, rootIsBackground = false))

		cellInfo?.setColorManager(colorManager)
		wifiInfo?.setColorManager(colorManager)
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
		val rawData = TrackerService.trackerEcho.value
		if (rawData != null) {
			updateExtendedInfo(rawData.second)
		} else {
			longitude.visibility = GONE
			latitude.visibility = GONE
		}
		//archived_data.text = getStringRes(R.string.main_archived_data, Assist.humanReadableByteCount(LongTermStore.sizeOfStoredFiles(context!!), true))
	}

	private fun updateExtendedInfo(collectionData: MutableCollectionData) {
		val location = collectionData.location
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

	private fun updateData(session: TrackerSession, collectionData: MutableCollectionData) {
		val context = getNonNullContext()
		val res = context.resources

		textview_time.text = res.getString(R.string.main_last_update, DateFormat.getTimeFormat(context).format(Date(collectionData.time)))

		updateActivityUI(collectionData.activity)
		updateLocationUI(collectionData.location)
		updateSessionUI(session)
		updateCellUI(collectionData.cell)
		updateWifiUI(collectionData.time, collectionData.wifi)


		if (bar_info_top_extended.visibility == VISIBLE) {
			updateExtendedInfo(collectionData)
		}
	}

	private fun updateActivityUI(activityInfo: ActivityInfo?) {
		when (activityInfo?.groupedActivity) {
			GroupedActivity.STILL -> {
				icon_activity.setImageResource(R.drawable.ic_outline_still_24px)
				icon_activity.contentDescription = getString(R.string.activity_idle)
				icon_activity.visibility = VISIBLE
			}
			GroupedActivity.ON_FOOT -> {
				icon_activity.setImageResource(R.drawable.ic_directions_walk_white_24dp)
				icon_activity.contentDescription = getString(R.string.activity_on_foot)
				icon_activity.visibility = VISIBLE
			}
			GroupedActivity.IN_VEHICLE -> {
				icon_activity.setImageResource(R.drawable.ic_directions_car_white_24dp)
				icon_activity.contentDescription = getString(R.string.activity_in_vehicle)
				icon_activity.visibility = VISIBLE
			}
			GroupedActivity.UNKNOWN -> {
				icon_activity.setImageResource(R.drawable.ic_help_white_24dp)
				icon_activity.contentDescription = getString(R.string.activity_unknown)
				icon_activity.visibility = VISIBLE
			}
			else -> icon_activity.visibility = GONE
		}
	}

	private fun updateLocationUI(location: Location?) {
		if (location != null) {
			val context = getNonNullContext()
			val resources = context.resources
			val lengthSystem = Preferences.getLengthSystem(context)
			if (location.horizontalAccuracy != null) {
				horizontal_accuracy.visibility = VISIBLE
				horizontal_accuracy.text = getString(R.string.info_accuracy, resources.formatDistance(location.horizontalAccuracy, 0, lengthSystem))
			} else {
				horizontal_accuracy.visibility = GONE
			}

			//todo add vertical accuracy

			if (location.altitude != null) {
				altitude.text = getString(R.string.info_altitude, resources.formatDistance(location.altitude, 2, lengthSystem))
				altitude.visibility = VISIBLE
			} else {
				altitude.visibility = GONE
			}
		} else {
			horizontal_accuracy.visibility = GONE
			altitude.visibility = GONE
		}
	}

	private fun updateCellUI(cellData: CellData?) {
		val res = resources
		if (cellData != null) {
			val component = initializeCellInfo()
			if (cellData.registeredCells.isNotEmpty()) {
				val firstCell = cellData.registeredCells.first()
				component.setText(CELL_COMPONENT_CURRENT, res.getString(R.string.main_cell_current, firstCell.type.name, firstCell.dbm, firstCell.asu))
			} else
				component.setVisibility(CELL_COMPONENT_CURRENT, GONE)
			component.setText(CELL_COMPONENT_COUNT, res.getString(R.string.main_cell_count, cellData.totalCount))
		} else {
			cellInfo?.detach()
			cellInfo = null
		}
	}

	private fun updateWifiUI(time: Long, wifiData: WifiData?) {
		val res = resources
		val wifiInfo = wifiInfo

		if (wifiData != null) {
			val component = initializeWifiInfo()
			component.setText(WIFI_COMPONENT_COUNT, res.getString(R.string.main_wifi_count, wifiData.inRange.size))
			component.setText(WIFI_COMPONENT_DISTANCE, res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi.roundToInt()))
			lastWifiTime = time
		} else if (wifiInfo != null) {
			if (lastWifiTime - time < Constants.MINUTE_IN_MILLISECONDS) {
				wifiInfo.setText(WIFI_COMPONENT_DISTANCE, res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi.roundToInt()))
			} else {
				wifiInfo.detach()
				this.wifiInfo = null
			}
		}
	}

	private fun updateSessionUI(session: TrackerSession) {
		val resources = resources
		session_collections.text = resources.getString(R.string.info_session_collections, session.collections)

		val lengthSystem = Preferences.getLengthSystem(getNonNullContext())

		session_distance.text = resources.getString(R.string.info_session_distance, resources.formatDistance(session.distanceInM, 1, lengthSystem))

	}

	companion object {
		private const val WIFI_COMPONENT_COUNT = "WifiCount"
		private const val WIFI_COMPONENT_DISTANCE = "WifiDistance"
		private const val CELL_COMPONENT_COUNT = "CellCount"
		private const val CELL_COMPONENT_CURRENT = "CellCurrent"
	}

}