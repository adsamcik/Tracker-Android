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
import com.adsamcik.tracker.map.presentation.MapViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.adsamcik.tracker.map.ui.MapScreen
import kotlinx.coroutines.launch
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.tracker.map.presentation.sensors.LocationAndSensorsManager
// Legacy sheet controller will be decommissioned; Compose sheet used in Phase 1
import com.adsamcik.tracker.map.R

import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.transaction
import com.adsamcik.tracker.shared.map.ColorMap
import com.adsamcik.tracker.shared.utils.fragment.CorePermissionFragment
// import removed: StyleManager not used after cleanup
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import com.google.android.gms.maps.SupportMapFragment
import com.adsamcik.tracker.map.MapConstants

/**
 * Fragment containing primary map with overlays, user location and more.
 */
@Suppress("unused")
class FragmentMap : CorePermissionFragment(), IOnDemandView {
	// Legacy MapController removed in Phase 2; state owned by MapStore
	// private var mapSheetController: MapSheetController? = null

	private var mapFragment: SupportMapFragment? = null
	// Phase 5: MapEventListener and MapOwner removed; Compose handles all map interactions
	private var fActivity: FragmentActivity? = null

	override fun onPermissionResponse(requestCode: Int, success: Boolean): Unit = Unit

	override fun onLeave(activity: FragmentActivity) {
		// No-op; map lifecycle managed by Compose
	}

	override fun onPause() {
		super.onPause()
		// No-op; map lifecycle managed by Compose
	}

	override fun onResume() {
		super.onResume()
		// No-op; map lifecycle managed by Compose
	}

	override fun onEnter(activity: FragmentActivity) {
		// This will prevent a crash, but can cause side effects, investigation needed
		if (isStateSaved) return

		this.fActivity = activity

		if (Assist.isPlayServicesAvailable(activity)) {
			// Phase 5: Direct SupportMapFragment creation; MapOwner removed
			val mapFragment = SupportMapFragment.newInstance()
			mapFragment.getMapAsync(this::onMapReady)
			childFragmentManager.transaction {
				replace(R.id.container_map, mapFragment)
			}
			this.mapFragment = mapFragment
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val context = requireContext()

		// Phase 5: Removed MapOwner/MapSensorController lifecycle wiring; Compose manages map
		MapsInitializer.initialize(context)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// Phase 5: v2 scaffolding removed; Compose is the primary UI
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
		mapFragment = null
		// Phase 5: Legacy listeners removed
	}

		override fun onLowMemory() {
			super.onLowMemory()
			// Tile caches trimmed via providers; nothing to do here in Phase 2
		}

	private fun onMapReady(map: GoogleMap) {
		val activity = activity ?: return

		val showComposeMap = resources.getBoolean(com.adsamcik.tracker.map.R.bool.feature_flag_compose_map)
		// Phase 5: MapEventListener removed; Compose handles all interactions
		val inProgressTileTextView = activity.findViewById<TextView>(R.id.tile_generation_count_textview)
		// Phase 4: Flow-based sensors manager (no UI references)
		val sensors = LocationAndSensorsManager(activity.applicationContext)

		// Phase 2: Configure map UI settings here (until Maps Compose swap in Phase 3)
		map.uiSettings.apply {
			isMapToolbarEnabled = false
			isIndoorLevelPickerEnabled = false
			isCompassEnabled = false
			isMyLocationButtonEnabled = false
		}
		map.setMaxZoomPreference(MapConstants.MAX_ZOOM)

		val mapUiParent = activity.findViewById<ViewGroup>(R.id.map_ui_parent)
		// Build registry/manager and a single MapStore owned by this Fragment
		val registry = com.adsamcik.tracker.map.layers.registry.DefaultLayerRegistry()
		val layerManager = com.adsamcik.tracker.map.presentation.bridge.LayerManager(activity, map, registry)
		val storeFactory = object : androidx.lifecycle.ViewModelProvider.Factory {
			override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return com.adsamcik.tracker.map.presentation.MapStore(layerManager) as T
			}
		}
		val store = ViewModelProvider(this, storeFactory)[com.adsamcik.tracker.map.presentation.MapStore::class.java]

		// Forward user location updates into declarative overlays from new manager
		viewLifecycleOwner.lifecycleScope.launch {
			sensors.locationUpdates().collect { (lat, lng, acc) ->
				store.dispatch(
					com.adsamcik.tracker.map.presentation.udf.MapEvent.SetUserLocation(
						com.adsamcik.tracker.map.presentation.udf.LatLngModel(lat, lng),
						acc
					)
				)
			}
		}

		// Forward bearing updates to store; used to rotate camera while following
		viewLifecycleOwner.lifecycleScope.launch {
			sensors.bearingUpdates().collect { bearing ->
				store.dispatch(
					com.adsamcik.tracker.map.presentation.udf.MapEvent.SetBearing(bearing)
				)
			}
		}

		if (mapUiParent is androidx.compose.ui.platform.ComposeView) {
			// Phase 1/2/3: Compose UI layer over legacy map. Bottom sheet + optional MapScreen overlay.
			// Legacy user overlays removed; Compose renders user position
			mapUiParent.setContent {
				com.adsamcik.tracker.shared.utils.style.compose.TrackerTheme {
					androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier) {
						// Optional Maps Compose rendering; when enabled, let it be interactive
						if (showComposeMap) { MapScreen(store, overlayMode = false) }
						// Bottom sheet on top
						com.adsamcik.tracker.map.ui.MapSheet(
							registry = registry,
							store = store,
						)
					}
				}
			}
		}

		// Initialize quality from preferences to match legacy defaults
		val pref = com.adsamcik.tracker.shared.preferences.Preferences.getPref(activity)
		val res = activity.resources
		val quality = pref.getFloat(
			res.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_key),
			res.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_default).toFloat()
		)
		store.setQuality(quality)

		// Observe state to apply UI settings and tile progress
		viewLifecycleOwner.lifecycleScope.launch {
			store.state.collect { s ->
				map.uiSettings.apply {
					isMapToolbarEnabled = s.uiSettings.isMapToolbarEnabled
					isIndoorLevelPickerEnabled = s.uiSettings.isIndoorLevelPickerEnabled
					isCompassEnabled = s.uiSettings.isCompassEnabled
					isMyLocationButtonEnabled = s.uiSettings.isMyLocationButtonEnabled
				}
				// Follow state is handled by Compose map gestures/effects when Compose is active.
				if (s.tileGenerationInProgress > 0) {
					inProgressTileTextView.text = activity.resources.getQuantityString(
						com.adsamcik.tracker.map.R.plurals.generating_tile_count,
						s.tileGenerationInProgress,
						s.tileGenerationInProgress
					)
					inProgressTileTextView.visibility = View.VISIBLE
				} else {
					inProgressTileTextView.visibility = View.GONE
				}
			}
		}

		ColorMap.addListener(activity, map)

		// Note: Introduction removed as UI moved to Compose
	}

	companion object {
		private const val PERMISSION_LOCATION_CODE = 200
	}

}
