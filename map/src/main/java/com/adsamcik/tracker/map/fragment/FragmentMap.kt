package com.adsamcik.tracker.map.fragment


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.FragmentActivity
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.utils.introduction.IntroductionManager
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.commonmap.ColorMap
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.MapEventListener
import com.adsamcik.tracker.map.MapOwner
import com.adsamcik.tracker.map.MapSensorController
import com.adsamcik.tracker.map.MapSheetController
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.introduction.MapIntroduction
import com.adsamcik.tracker.shared.utils.fragment.CorePermissionFragment
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
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

	private var fActivity: FragmentActivity? = null

	override fun onPermissionResponse(requestCode: Int, success: Boolean) = Unit

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
		//This will prevent a crash, but can cause side effects, investigation needed
		if (isStateSaved) return

		this.fActivity = activity

		if (Assist.isPlayServicesAvailable(activity)) {
			mapOwner.createMap(childFragmentManager)
		}

		mapOwner.onEnable()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		MapsInitializer.initialize(context)
		retainInstance = false

		mapOwner.addOnCreateListener(this::onMapReady)
		mapOwner.addOnEnableListener {
			locationListener?.onEnable(requireContext())
		}
		mapOwner.addOnDisableListener {
			locationListener?.onDisable(requireContext())
		}
	}

	override fun onCreateView(
			inflater: LayoutInflater,
			container: ViewGroup?,
			savedInstanceState: Bundle?
	): View? {
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

