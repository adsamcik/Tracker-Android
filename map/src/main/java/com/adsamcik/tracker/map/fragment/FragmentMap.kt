package com.adsamcik.tracker.map.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.adsamcik.tracker.map.v2.presentation.MapViewModel
import com.adsamcik.tracker.map.v2.ui.MapHost
import kotlinx.coroutines.launch
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.MapEventListener
import com.adsamcik.tracker.map.MapOwner
import com.adsamcik.tracker.map.MapSensorController
import com.adsamcik.tracker.map.MapSheetController
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.introduction.MapIntroduction
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.map.ColorMap
import com.adsamcik.tracker.shared.utils.fragment.CorePermissionFragment
import com.adsamcik.tracker.shared.utils.introduction.IntroductionManager
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import com.google.android.gms.maps.SupportMapFragment

/**
 * Fragment containing primary map with overlays, user location and more.
 */
@Suppress("unused")
class FragmentMap : CorePermissionFragment(), IOnDemandView {
	private var locationListener: MapSensorController? = null
	private var mapController: MapController? = null
	private var mapSheetController: MapSheetController? = null

	private var mapFragment: SupportMapFragment? = null
	private var mapEventListener: MapEventListener? = null
	private var mapOwner = MapOwner()

	// --- v2 scaffolding (task 2.4) ---
	private val enableV2Scaffold = false // keep false to guarantee no behavior change by default
	private var v2MapHost: MapHost? = null
	private var v2ViewModel: MapViewModel? = null
	// --- end v2 scaffolding ---

	private var fActivity: FragmentActivity? = null

	override fun onPermissionResponse(requestCode: Int, success: Boolean): Unit = Unit

	override fun onLeave(activity: FragmentActivity) {
		mapOwner.onDisable()
	}

	override fun onPause() {
		super.onPause()
		mapOwner.onDisable()
	}

	override fun onResume() {
		super.onResume()
		mapOwner.onEnable()
	}

	override fun onEnter(activity: FragmentActivity) {
		// This will prevent a crash, but can cause side effects, investigation needed
		if (isStateSaved) return

		this.fActivity = activity

		if (Assist.isPlayServicesAvailable(activity)) {
			mapOwner.createMap(childFragmentManager)
		}

		mapOwner.onEnable()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val context = requireContext()

		mapOwner.addOnCreateListener(this::onMapReady)
	// Removed direct MapOwner enable/disable wiring for MapSensorController; lifecycle observer now controls it.

		MapsInitializer.initialize(context)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		if (!enableV2Scaffold) return

		// Initialize v2 ViewModel & MapHost (wrapping existing mapOwner) with no-op state collection
		v2ViewModel = ViewModelProvider(this)[MapViewModel::class.java]
		v2MapHost = MapHost(mapOwner)

		viewLifecycleOwner.lifecycleScope.launch {
			v2ViewModel?.state?.collect { /* no-op: placeholder for future UI binding */ }
		}
	}

	override fun onCreateView(
	    inflater: LayoutInflater,
	    container: ViewGroup?,
	    savedInstanceState: Bundle?
	): View {
		val activity = requireActivity()
		val fragmentView: View
		if (Assist.isPlayServicesAvailable(activity) && container != null) {
			fragmentView = view ?: inflater.inflate(R.layout.fragment_map, container, false)
		} else {
			fragmentView = inflater.inflate(
					R.layout.layout_error,
					container,
					false
			)

			val textRes = com.adsamcik.tracker.shared.base.R.string.error_play_services_not_available

			fragmentView.findViewById<AppCompatTextView>(R.id.activity_error_text)
					.setText(textRes)
		}

		/*fragmentView.setOnTouchListener { _, _ ->
			edittext_map_search.clearFocus()
			true
		}*/

		return fragmentView
	}

	override fun onDestroyView() {
		super.onDestroyView()

	override fun onLowMemory() {
		super.onLowMemory()
		mapController?.onLowMemory()
	}

	override fun onTrimMemory(level: Int) {
		super.onTrimMemory(level)
		if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
			mapController?.onLowMemory()
		}
	}
		mapFragment = null

		styleController.let { StyleManager.recycleController(it) }

		mapController = null
		mapEventListener = null
		mapSheetController = null
	}

	private fun onMapReady(map: GoogleMap) {
		val activity = activity ?: return

		val mapEventListener = MapEventListener(map)
		this.mapEventListener = mapEventListener

		val inProgressTileTextView = activity.findViewById<TextView>(R.id.tile_generation_count_textview)
		val mapController = MapController(activity, map, mapOwner, inProgressTileTextView)
		val locationListener = MapSensorController(activity, map, mapEventListener)
		// Attach sensor controller to fragment view lifecycle for automatic start/stop via internal observer
		locationListener.attachToLifecycle(viewLifecycleOwner, activity)

		this.mapController = mapController
		this.locationListener = locationListener

		val mapUiParent = activity.findViewById<ViewGroup>(R.id.map_ui_parent)

		mapSheetController = MapSheetController(
				activity,
				this,
				map,
				mapOwner,
				mapUiParent,
				mapController,
				locationListener,
				mapEventListener
		)

		ColorMap.addListener(activity, map)

		mapUiParent.post {
			IntroductionManager.showIntroduction(requireActivity(), MapIntroduction())
		}
	}

	companion object {
		private const val PERMISSION_LOCATION_CODE = 200
	}

}
