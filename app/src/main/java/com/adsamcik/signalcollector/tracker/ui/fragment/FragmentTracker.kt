package com.adsamcik.signalcollector.tracker.ui.fragment

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.style.RecyclerStyleView
import com.adsamcik.signalcollector.common.style.StyleManager
import com.adsamcik.signalcollector.common.style.StyleView
import com.adsamcik.signalcollector.common.data.*
import com.adsamcik.signalcollector.common.extension.*
import com.adsamcik.signalcollector.common.fragment.CoreUIFragment
import com.adsamcik.signalcollector.common.misc.SnackMaker
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.common.useMock
import com.adsamcik.signalcollector.preference.activity.SettingsActivity
import com.adsamcik.signalcollector.tracker.data.collection.CollectionDataEcho
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.TrackerService
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter
import com.google.android.gms.location.DetectedActivity
import kotlinx.android.synthetic.main.activity_ui.*
import kotlinx.android.synthetic.main.fragment_tracker.*
import kotlinx.android.synthetic.main.fragment_tracker.view.*

class FragmentTracker : CoreUIFragment(), LifecycleObserver {
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

		button_settings.setOnClickListener { startActivity<SettingsActivity> { } }

		button_tracking.setOnClickListener {
			val activity = activity!!
			if (TrackerService.sessionInfo.value?.isInitiatedByUser == false) {
				TrackerLocker.lockTimeLock(activity, Time.MINUTE_IN_MILLISECONDS * LOCK_WHEN_CANCELLED)
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
		StyleManager.recycleController(styleController)
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

		val session = TrackerSession(0, Time.nowMillis - 5 * Time.MINUTE_IN_MILLISECONDS, Time.nowMillis, true, 56, 5410f, 15f, 5000f, 154)

		updateData(CollectionDataEcho(location, collectionData, session))
	}

	private fun initializeColorElements() {
		styleController.apply {
			watchView(StyleView(top_panel_root, layer = 1))
			watchRecyclerView(RecyclerStyleView(tracker_recycler, layer = 0, childrenLayer = 1))
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
		adapter.update(dataEcho.collectionData, dataEcho.session)
	}

	companion object {
		const val LOCK_WHEN_CANCELLED = 60
	}
}