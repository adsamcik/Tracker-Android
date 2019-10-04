package com.adsamcik.tracker.map.fragment


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.FragmentActivity
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.fragment.CoreUIFragment
import com.adsamcik.tracker.common.introduction.IntroductionManager
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.commonmap.ColorMap
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.MapEventListener
import com.adsamcik.tracker.map.MapOwner
import com.adsamcik.tracker.map.MapSheetController
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.MapSensorController
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.SupportMapFragment
import kotlinx.android.synthetic.main.fragment_map.*

@Suppress("unused")
class FragmentMap : CoreUIFragment(), IOnDemandView {
	private var locationListener: MapSensorController? = null
	private var mapController: MapController? = null
	private var mapSheetController: MapSheetController? = null

	private var mapFragment: SupportMapFragment? = null
	private var mapEventListener: MapEventListener? = null
	private var mapOwner = MapOwner()

	private var fActivity: FragmentActivity? = null

	override fun onPermissionResponse(requestCode: Int, success: Boolean) = Unit

	override fun onLeave(activity: FragmentActivity) {
		locationListener?.onDestroy(activity)

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

		val fragmentManager = fragmentManager
				?: throw NullPointerException("Fragment Manager is null. This was probably called too early!")

		if (Assist.isPlayServicesAvailable(activity)) {
			mapOwner.createMap(fragmentManager)
		}

		mapOwner.onEnable()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		MapsInitializer.initialize(context)
		retainInstance = false

		mapOwner.addOnCreateListener(this::onMapReady)
		mapOwner.addOnEnableListener {
			locationListener?.subscribeToLocationUpdates(requireContext())
		}
		mapOwner.addOnDisableListener {
			locationListener?.onDestroy(requireContext())
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

			val textRes = com.adsamcik.tracker.common.R.string.error_play_services_not_available

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

		mapSheetController = MapSheetController(
				activity,
				map,
				mapOwner,
				map_ui_parent,
				mapController,
				locationListener,
				mapEventListener
		)

		ColorMap.addListener(activity, map)

		map_ui_parent.post {
			IntroductionManager.showIntroduction(requireActivity(), MapIntroduction())
		}
	}

	companion object {
		private const val PERMISSION_LOCATION_CODE = 200
	}

}

