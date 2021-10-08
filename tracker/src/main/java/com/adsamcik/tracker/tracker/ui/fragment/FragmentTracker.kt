package com.adsamcik.tracker.tracker.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.annotation.MainThread
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.CellData
import com.adsamcik.tracker.shared.base.data.CellInfo
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.data.WifiData
import com.adsamcik.tracker.shared.base.data.WifiInfo
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.requireParent
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.base.useMock
import com.adsamcik.tracker.shared.preferences.PreferencesAssist
import com.adsamcik.tracker.shared.utils.fragment.CorePermissionFragment
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.service.TrackerService
import com.adsamcik.tracker.tracker.ui.TrackerViewModel
import com.adsamcik.tracker.tracker.ui.receiver.SessionUpdateReceiver
import com.adsamcik.tracker.tracker.ui.recycler.TrackerInfoAdapter
import com.google.android.gms.location.DetectedActivity

/**
 * Fragment that displays current tracking information
 */
class FragmentTracker : CorePermissionFragment(), LifecycleObserver {
	private lateinit var adapter: TrackerInfoAdapter

	private lateinit var viewModel: TrackerViewModel

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel = ViewModelProvider(this).get(TrackerViewModel::class.java)
	}

	override fun onCreateView(
			inflater: LayoutInflater,
			container: ViewGroup?,
			savedInstanceState: Bundle?
	): View? {
		if (container == null) return null

		val view = inflater.inflate(R.layout.fragment_tracker, container, false)
		view.findViewById<LinearLayoutCompat>(R.id.top_panel_root)
				.updateLayoutParams<LinearLayoutCompat.LayoutParams> {
					height += DisplayAssist.getStatusBarHeight(container.context)
				}

		view.findViewById<RecyclerView>(R.id.tracker_recycler).apply {
			val adapter = TrackerInfoAdapter()
			this@FragmentTracker.adapter = adapter
			this.adapter = adapter

			val itemAnimator = itemAnimator
			if (itemAnimator is DefaultItemAnimator) {
				itemAnimator.supportsChangeAnimations = false
			} else {
				Reporter.report(RuntimeException("Item animator was null or invalid type"))
			}

			post { initializeTrackerRecycler() }
		}

		return view
	}

	private fun RecyclerView.initializeTrackerRecycler() {
		val computedWidth = measuredWidth - paddingStart - paddingEnd
		val oneSideHorizontalMargin = RECYCLER_HORIZONTAL_MARGIN.dp
		val totalHorizontalMargin = oneSideHorizontalMargin * 2
		val maxWidth = MAX_RECYCLER_COLUMN_WIDTH.dp + totalHorizontalMargin
		val minWidth = MIN_RECYCLER_COLUMN_WIDTH.dp + totalHorizontalMargin
		val minColumnCount = kotlin.math.max(computedWidth / maxWidth, 1)
		val columnPlusOneWidth = computedWidth / (minColumnCount + 1)
		val columnCount = if (columnPlusOneWidth < minWidth) minColumnCount else minColumnCount + 1
		layoutManager = StaggeredGridLayoutManager(columnCount, LinearLayoutManager.VERTICAL)
		addItemDecoration(MarginDecoration(horizontalMargin = oneSideHorizontalMargin))
	}

	override fun onStart() {
		super.onStart()

		val view = requireView()
		view.findViewById<View>(R.id.button_settings).setOnClickListener {
			it.context.startActivity("com.adsamcik.tracker.preference.activity.SettingsActivity") {
				flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
			}
		}

		view.findViewById<View>(R.id.button_tracking).setOnClickListener {
			val activity = requireActivity()
			if (TrackerService.sessionInfo.value?.isInitiatedByUser == false) {
				TrackerLocker.lockTimeLock(
						activity,
						Time.MINUTE_IN_MILLISECONDS * LOCK_WHEN_CANCELLED
				)
				SnackMaker(rootCoordinatorLayout).addMessage(
						activity.resources.getQuantityString(
								R.plurals.notification_auto_tracking_lock,
								LOCK_WHEN_CANCELLED, LOCK_WHEN_CANCELLED
						)
				)
			} else {
				toggleCollecting(activity, !TrackerService.isServiceRunning.value)
			}
		}

		val buttonTrackingLock = view.findViewById<View>(R.id.button_tracking_lock)
		buttonTrackingLock.setOnClickListener {
			val context = requireContext()
			TrackerLocker.unlockTimeLock(context)
			TrackerLocker.unlockRechargeLock(context)
		}

		TrackerLocker.isLocked.observeGetCurrent(this) {
			buttonTrackingLock.visibility = if (it) VISIBLE else GONE
		}

		initializeColorElements()

		TrackerService.isServiceRunning.observeGetCurrent(this) {
			updateTrackerButton(it)
		}

		SessionUpdateReceiver.sessionData.observe(this) {
			if (it != null && it.start > 0) {
				updateSessionData(it)
			}
		}

		SessionUpdateReceiver.collectionData.observe(this) {
			if (it != null) {
				updateCollectionData(it)
			}
		}

	}

	override fun onResume() {
		super.onResume()
		val context = requireContext()

		val orientation = DisplayAssist.getOrientation(context)
		if (orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270) {
			requireView().findViewById<View>(R.id.tracker_recycler).setPadding(
					RECYCLER_HORIZONTAL_PADDING.dp,
					0,
					RECYCLER_HORIZONTAL_PADDING.dp,
					0
			)
		}

		if (useMock) mock()
	}

	private val rootCoordinatorLayout: CoordinatorLayout
		get() {
			val fragmentRoot = requireActivity().findViewById<View>(R.id.fragment_tracker_root)
			return fragmentRoot.requireParent()
		}

	private fun startTracking(activity: FragmentActivity) {
		if (!Assist.isGNSSEnabled(activity)) {
			SnackMaker(rootCoordinatorLayout)
					.addMessage(
							messageRes = R.string.error_gnss_not_enabled,
							priority = SnackMaker.SnackbarPriority.IMPORTANT,
							actionRes = R.string.generic_enable,
							onActionClick = {
								val locationOptionsIntent = Intent(
										Settings.ACTION_LOCATION_SOURCE_SETTINGS
								)
								startActivity(locationOptionsIntent)
							}
					)
		} else if (!PreferencesAssist.hasAnythingToTrack(activity)) {
			SnackMaker(rootCoordinatorLayout).addMessage(R.string.error_nothing_to_track)
		} else {
			TrackerServiceApi.startService(activity, isUserInitiated = true)
			updateTrackerButton(true)
		}
	}

	private fun stopTracking(activity: FragmentActivity) {
		TrackerServiceApi.stopService(activity)
	}

	/**
	 * Enables or disables collecting service
	 *
	 * @param enable ensures intended action
	 */
	private fun toggleCollecting(activity: FragmentActivity, enable: Boolean) {
		val isActive = TrackerServiceApi.isActive
		if (isActive == enable) return

		TrackerTimerManager.checkTimerPermissions(activity) {
			if (it.isSuccess) {
				if (!isActive) {
					startTracking(activity)
				} else {
					stopTracking(activity)
				}
			}
		}
	}

	//todo improve this
	@Suppress("MagicNumber")
	private fun mock() {
		val collectionData = MutableCollectionData(Time.nowMillis)
		val location = Location(collectionData.time, 15.0, 15.0, 123.0, 6f, 3f, 10f, 15f)
		collectionData.location = location
		collectionData.activity = ActivityInfo(DetectedActivity.RUNNING, 75)
		collectionData.wifi = WifiData(
				location,
				Time.nowMillis,
				listOf(WifiInfo(), WifiInfo(), WifiInfo())
		)
		collectionData.cell = CellData(
				listOf(
						CellInfo(
								NetworkOperator("123", "321", "MOCK"),
								123456,
								CellType.LTE,
								90,
								-30,
								0
						)
				), 8
		)

		val session = TrackerSession(
				0,
				Time.nowMillis - 5 * Time.MINUTE_IN_MILLISECONDS,
				Time.nowMillis,
				true,
				56,
				5410f,
				15f,
				5000f,
				154
		)

		updateCollectionData(collectionData)
		updateSessionData(session)
	}

	private fun initializeColorElements() {
		val view = requireView()
		styleController.apply {
			watchView(StyleView(view.findViewById(R.id.top_panel_root), layer = 1))
			watchRecyclerView(
					RecyclerStyleView(
							view.findViewById(R.id.tracker_recycler),
							layer = 0,
							childrenLayer = 1
					)
			)
		}
	}

	private fun updateTrackerButton(state: Boolean) {
		val buttonTracking = requireView().findViewById<ImageButton>(R.id.button_tracking)
		if (state) {
			buttonTracking.setImageResource(R.drawable.ic_pause_circle_filled_black_24dp)
			buttonTracking.contentDescription = getString(R.string.description_tracking_stop)
		} else {
			buttonTracking.setImageResource(R.drawable.ic_play_circle_filled_black_24dp)
			buttonTracking.contentDescription = getString(R.string.description_tracking_start)
		}
	}

	@MainThread
	private fun updateCollectionData(collectionData: CollectionData) {
		adapter.updateCollection(collectionData)
	}

	@MainThread
	private fun updateSessionData(session: TrackerSession) {
		adapter.updateSession(session)
	}

	companion object {
		private const val LOCK_WHEN_CANCELLED = 60
		private const val MIN_RECYCLER_COLUMN_WIDTH = 125
		private const val MAX_RECYCLER_COLUMN_WIDTH = 220
		private const val RECYCLER_HORIZONTAL_MARGIN = 8

		private const val RECYCLER_HORIZONTAL_PADDING = 72
	}
}

