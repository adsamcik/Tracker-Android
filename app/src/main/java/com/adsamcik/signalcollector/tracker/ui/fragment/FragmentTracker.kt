package com.adsamcik.signalcollector.tracker.ui.fragment

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
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleObserver
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.*
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.data.*
import com.adsamcik.signalcollector.common.misc.SnackMaker
import com.adsamcik.signalcollector.common.misc.extension.*
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.preference.activity.SettingsActivity
import com.adsamcik.signalcollector.tracker.data.collection.CollectionDataEcho
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.TrackerService
import com.adsamcik.signalcollector.tracker.ui.InfoComponent
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter
import com.google.android.gms.location.DetectedActivity
import kotlinx.android.synthetic.main.activity_ui.*
import kotlinx.android.synthetic.main.fragment_tracker.*
import kotlinx.android.synthetic.main.fragment_tracker.view.*
import java.util.*

class FragmentTracker : androidx.fragment.app.Fragment(), LifecycleObserver {
	private lateinit var colorController: ColorController

	private var wifiInfo: InfoComponent? = null
	private var cellInfo: InfoComponent? = null

	private lateinit var adapter: TrackerInfoAdapter

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		if (container == null) return null

		val view = inflater.inflate(R.layout.fragment_tracker, container, false)
		view.top_panel_root.updateLayoutParams<LinearLayoutCompat.LayoutParams> {
			height += Assist.getStatusBarHeight(container.context)
		}

		view.tracker_recycler.apply {
			val adapter = TrackerInfoAdapter()
			this@FragmentTracker.adapter = adapter
			this.adapter = adapter

			val itemAnimator = itemAnimator
			if (itemAnimator != null && itemAnimator is DefaultItemAnimator) {
				itemAnimator.supportsChangeAnimations = false
			} else {
				Reporter.report(RuntimeException("Item animator was null or invalid type"))
			}

			post {
				val computedWidth = measuredWidth - paddingStart - paddingEnd
				val oneSideHorizontalMargin = 8.dp
				val totalHorizontalMargin = oneSideHorizontalMargin * 2
				val maxWidth = 220.dp + totalHorizontalMargin
				val minWidth = 125.dp + totalHorizontalMargin
				val minColumnCount = kotlin.math.max(computedWidth / maxWidth, 1)
				val columnPlusOneWidth = computedWidth / (minColumnCount + 1)
				val columnCount = if (columnPlusOneWidth < minWidth) minColumnCount else minColumnCount + 1
				layoutManager = StaggeredGridLayoutManager(columnCount, LinearLayoutManager.VERTICAL)
				addItemDecoration(SimpleMarginDecoration(horizontalMargin = oneSideHorizontalMargin))
			}
		}

		return view
	}

	override fun onStart() {
		super.onStart()

		icon_activity.visibility = GONE
		textview_altitude.visibility = GONE
		textview_horizontal_accuracy.visibility = GONE


		button_settings.setOnClickListener { startActivity<SettingsActivity> { } }

		button_tracking.setOnClickListener {
			val activity = activity!!
			if (TrackerService.sessionInfo.value?.isInitiatedByUser == false) {
				TrackerLocker.lockTimeLock(activity, Constants.MINUTE_IN_MILLISECONDS * LOCK_WHEN_CANCELLED)
				SnackMaker(activity.findViewById(R.id.root) as View).addMessage(activity.resources.getQuantityString(R.plurals.notification_auto_tracking_lock, LOCK_WHEN_CANCELLED, LOCK_WHEN_CANCELLED))
			} else {
				toggleCollecting(activity, !TrackerService.isServiceRunning.value)
			}
		}

		button_tracking_lock.setOnClickListener {
			val context = requireContext()
			TrackerLocker.unlockTimeLock(context)
			TrackerLocker.unlockRechargeLock(context)
		}

		TrackerLocker.isLocked.observeGetCurrent(this) {
			button_tracking_lock.visibility = if (it) VISIBLE else GONE
		}

		initializeColorElements()

		TrackerService.isServiceRunning.observeGetCurrent(this) {
			updateTrackerButton(it)
		}

		TrackerService.lastCollectionData.observe(this) {
			if (it != null && it.session.start > 0) {
				updateData(it)
			}
		}

	}

	override fun onStop() {
		ColorManager.recycleController(colorController)
		super.onStop()
	}

	override fun onResume() {
		super.onResume()
		val context = requireContext()

		val orientation = Assist.orientation(context)
		if (orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270) {
			tracker_recycler.setPadding(72.dp, 0, 72.dp, 0)
		}

		if (useMock) mock()
	}

	/**
	 * Enables or disables collecting service
	 *
	 * @param enable ensures intended action
	 */
	private fun toggleCollecting(activity: FragmentActivity, enable: Boolean) {
		if (TrackerService.isServiceRunning.value == enable) return

		val requiredPermissions = Assist.checkTrackingPermissions(activity)
		val view = view

		if (requiredPermissions == null && view != null) {
			if (!TrackerService.isServiceRunning.value) {
				if (!Assist.isGNSSEnabled(activity)) {
					SnackMaker(activity.root).addMessage(R.string.error_gnss_not_enabled,
							priority = SnackMaker.SnackbarPriority.IMPORTANT,
							actionRes = R.string.enable,
							onActionClick = View.OnClickListener {
								val locationOptionsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
								startActivity(locationOptionsIntent)
							})
				} else if (!Assist.canTrack(activity)) {
					SnackMaker(activity.findViewById(R.id.root)).addMessage(R.string.error_nothing_to_track)
				} else {
					Preferences.getPref(activity).edit {
						setBoolean(R.string.settings_disabled_recharge_key, false)
					}

					activity.startForegroundService<TrackerService> {
						putExtra(TrackerService.ARG_IS_USER_INITIATED, true)
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

	//todo improve this
	private fun mock() {
		val collectionData = MutableCollectionData(Time.nowMillis)
		val location = Location(collectionData.time, 15.0, 15.0, 123.0, 6f, 3f, 10f, 15f)
		collectionData.location = location
		collectionData.activity = ActivityInfo(DetectedActivity.RUNNING, 75)
		collectionData.wifi = WifiData(location, Time.nowMillis, listOf(WifiInfo(), WifiInfo(), WifiInfo()))
		collectionData.cell = CellData(arrayOf(CellInfo("MOCK", CellType.LTE, 0, "123", "456", 90, -30, 0)), 8)

		val session = TrackerSession(0, Time.nowMillis - 5 * Constants.MINUTE_IN_MILLISECONDS, Time.nowMillis, true, 56, 5410f, 15f, 5000f, 154)

		updateData(CollectionDataEcho(location, collectionData, session))
	}

	private fun initializeColorElements() {
		colorController = ColorManager.createController().apply {
			watchView(ColorView(top_panel_root, 1))
			watchView(ColorView(bar_info_top, 1))

			cellInfo?.setColorManager(this)
			wifiInfo?.setColorManager(this)

			watchRecyclerView(ColorView(tracker_recycler, 1))
		}
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

	private fun updateData(dataEcho: CollectionDataEcho) {
		val context = requireContext()
		val collectionData = dataEcho.collectionData

		textview_time.text = DateFormat.getTimeFormat(context).format(Date(collectionData.time))

		updateActivityUI(collectionData.activity)
		updateLocationUI(collectionData.location)
		updateSessionUI(dataEcho.session)

		adapter.update(collectionData)
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
			val context = requireContext()
			val resources = context.resources
			val lengthSystem = Preferences.getLengthSystem(context)
			val horizontalAccuracy = location.horizontalAccuracy
			if (horizontalAccuracy != null) {
				textview_horizontal_accuracy.visibility = VISIBLE
				textview_horizontal_accuracy.text = getString(R.string.info_accuracy, resources.formatDistance(horizontalAccuracy, 0, lengthSystem))
			} else {
				textview_horizontal_accuracy.visibility = GONE
			}

			//todo add vertical accuracy

			val altitude = location.altitude
			if (altitude != null) {
				textview_altitude.text = getString(R.string.info_altitude, resources.formatDistance(altitude, 2, lengthSystem))
				textview_altitude.visibility = VISIBLE
			} else {
				textview_altitude.visibility = GONE
			}
		} else {
			textview_horizontal_accuracy.visibility = GONE
			textview_altitude.visibility = GONE
		}
	}

	private fun updateSessionUI(session: TrackerSession) {
		val resources = resources
		session_collections.text = resources.getQuantityString(R.plurals.info_session_collections, session.collections, session.collections)

		val lengthSystem = Preferences.getLengthSystem(requireContext())

		session_distance.text = resources.getString(R.string.info_session_distance, resources.formatDistance(session.distanceInM, 1, lengthSystem))
	}

	companion object {
		const val LOCK_WHEN_CANCELLED = 60
	}
}