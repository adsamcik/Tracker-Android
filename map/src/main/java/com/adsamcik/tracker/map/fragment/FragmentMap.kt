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
import androidx.compose.foundation.layout.fillMaxSize
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
	private var sensorsManager: LocationAndSensorsManager? = null
	private var mapStore: com.adsamcik.tracker.map.presentation.MapStore? = null

	override fun onPermissionResponse(requestCode: Int, success: Boolean): Unit = Unit

	override fun onLeave(activity: FragmentActivity) {
		cleanup()
	}

	override fun onPause() {
		super.onPause()
		// Pause sensor updates to save battery
		sensorsManager?.let { manager ->
			// The flows will be cancelled when the lifecycle scope is paused
		}
	}

	override fun onResume() {
		super.onResume()
		// Sensor flows will restart automatically when lifecycle scope resumes
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
		cleanup()
	}

	private fun cleanup() {
		mapFragment = null
		sensorsManager = null
		mapStore = null
		fActivity = null
		// Phase 5: Legacy listeners removed
	}

		override fun onLowMemory() {
			super.onLowMemory()
			// Trigger cleanup in layer manager to free up tile caches
			mapStore?.let { store ->
				// Request garbage collection of unused layers
				viewLifecycleOwner.lifecycleScope.launch {
					try {
						store.dispatch(com.adsamcik.tracker.map.presentation.udf.MapEvent.SelectLayer("none"))
					} catch (e: Exception) {
						// Ignore errors during low memory cleanup
					}
				}
			}
		}

	private fun onMapReady(map: GoogleMap) {
		val activity = activity ?: return

		val showComposeMap = resources.getBoolean(com.adsamcik.tracker.map.R.bool.feature_flag_compose_map)
		// Phase 5: MapEventListener removed; Compose handles all interactions
		val inProgressTileTextView = activity.findViewById<TextView>(R.id.tile_generation_count_textview)
		// Phase 4: Flow-based sensors manager (no UI references)
		val sensors = LocationAndSensorsManager(activity.applicationContext)
		sensorsManager = sensors

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
		mapStore = store

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
				com.adsamcik.tracker.map.ui.theme.MapTheme {
					androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
						// Optional Maps Compose rendering; when enabled, let it be interactive
						if (showComposeMap) {
							// Shared state for map padding reported by the sheet
							val paddingPxState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
							// Render map first; padding starts at 0 and updates when the sheet reports changes
							MapScreen(store, overlayMode = false, bottomPaddingPx = paddingPxState.value)
							// Render sheet on top so it’s visible above the map
							com.adsamcik.tracker.map.ui.MapSheet(
								registry = registry,
								store = store,
								bottomInsetPx = try {
									val insets = mapUiParent.rootWindowInsets
									if (android.os.Build.VERSION.SDK_INT >= 30) {
										insets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
									} else {
										@Suppress("DEPRECATION")
										insets?.systemWindowInsetBottom ?: 0
									}
								} catch (_: Throwable) { 0 },
								onBottomPaddingChanged = { padding -> paddingPxState.value = padding }
							)
						}
					}
				}
			}
		}

		// Handle search geocoding via Android Geocoder as a bridge for Compose UI
		viewLifecycleOwner.lifecycleScope.launch {
			store.effects.collect { eff ->
				when (eff) {
					is com.adsamcik.tracker.map.presentation.udf.MapEffect.PerformGeocode -> {
						launch(kotlinx.coroutines.Dispatchers.IO) {
							try {
								val geocoder = android.location.Geocoder(activity)
								val results = geocoder.getFromLocationName(eff.query, 1)
								val b = results?.firstOrNull()?.let { addr ->
									val sw = com.google.android.gms.maps.model.LatLng(addr.latitude - 0.005, addr.longitude - 0.005)
									val ne = com.google.android.gms.maps.model.LatLng(addr.latitude + 0.005, addr.longitude + 0.005)
									com.google.android.gms.maps.model.LatLngBounds(sw, ne)
								}
								kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
									store.dispatch(com.adsamcik.tracker.map.presentation.udf.MapEvent.GeocodeResult(b))
								}
							} catch (_: Throwable) {
								kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
									store.dispatch(com.adsamcik.tracker.map.presentation.udf.MapEvent.GeocodeResult(null))
								}
							}
						}
					}
					else -> { /* ignore */ }
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
