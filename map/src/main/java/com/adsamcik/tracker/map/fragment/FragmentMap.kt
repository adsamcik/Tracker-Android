package com.adsamcik.tracker.map.fragment


import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.FragmentActivity
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.extension.hasLocationPermission
import com.adsamcik.tracker.common.extension.transactionStateLoss
import com.adsamcik.tracker.common.fragment.CoreUIFragment
import com.adsamcik.tracker.common.introduction.IntroductionManager
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.commonmap.ColorMap
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.MapEventListener
import com.adsamcik.tracker.map.MapOwner
import com.adsamcik.tracker.map.MapSheetController
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.UpdateLocationListener
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.SupportMapFragment
import kotlinx.android.synthetic.main.fragment_map.*

@Suppress("unused")
class FragmentMap : CoreUIFragment(), IOnDemandView {
	private var locationListener: UpdateLocationListener? = null
	private var mapController: MapController? = null
	private var mapSheetController: MapSheetController? = null

	private var mapFragment: SupportMapFragment? = null
	private var mapEventListener: MapEventListener? = null
	private var mapOwner = MapOwner()

	private var fActivity: FragmentActivity? = null

	private var hasPermissions = false
	private var initialized = false

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {
		val activity = fActivity
		if (requestCode == PERMISSION_LOCATION_CODE && success && activity != null) {
			val newFrag = FragmentMap()
			activity.supportFragmentManager.transactionStateLoss {
				replace(R.id.container_map, newFrag)
			}
			newFrag.onEnter(activity)
		}
	}

	/**
	 * Check if permission to access fine location is granted
	 * If not and is android 6 or newer, than it prompts you to enable it
	 *
	 * @return i
	 * s permission available atm
	 */
	private fun checkLocationPermission(context: Context): Boolean {
		if (context.hasLocationPermission) {
			return true
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			activity?.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_LOCATION_CODE)
		}
		return false
	}

	override fun onLeave(activity: FragmentActivity) {
		if (hasPermissions) {
			locationListener?.unsubscribeFromLocationUpdates(activity)
		}

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

		mapOwner.createMap(fragmentManager)

		mapOwner.onEnable()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		MapsInitializer.initialize(context)
		retainInstance = false

		mapOwner.addOnCreateListener(this::onMapReady)
		mapOwner.addOnEnableListener {
			requireNotNull(locationListener).subscribeToLocationUpdates(requireContext())
		}
		mapOwner.addOnDisableListener {
			requireNotNull(locationListener).unsubscribeFromLocationUpdates(requireContext())
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = requireActivity()
		hasPermissions = checkLocationPermission(activity)
		val fragmentView: View
		if (Assist.checkPlayServices(activity) && container != null && hasPermissions) {
			fragmentView = view ?: inflater.inflate(R.layout.fragment_map, container, false)
		} else {
			fragmentView = inflater.inflate(com.adsamcik.tracker.common.R.layout.layout_error, container, false)

			val textRes = if (hasPermissions) {
				com.adsamcik.tracker.common.R.string.error_play_services_not_available
			} else {
				com.adsamcik.tracker.common.R.string.error_missing_permission
			}

			fragmentView.findViewById<AppCompatTextView>(com.adsamcik.tracker.common.R.id.activity_error_text)
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
		val context = context ?: return

		val mapEventListener = MapEventListener(map)
		this.mapEventListener = mapEventListener

		val mapController = MapController(context, map, mapOwner)
		val locationListener = UpdateLocationListener(context, map, mapEventListener)

		this.mapController = mapController
		this.locationListener = locationListener

		mapSheetController = MapSheetController(context, map, mapOwner, map_ui_parent, mapController, locationListener,
				mapEventListener)

		ColorMap.addListener(context, map)

		map.setOnMapClickListener {
			map_ui_parent.visibility = if (map_ui_parent.visibility == VISIBLE) GONE else VISIBLE
		}

		map_ui_parent.post {
			IntroductionManager.showIntroduction(requireActivity(), MapIntroduction())
		}
	}

	companion object {
		private const val PERMISSION_LOCATION_CODE = 200
	}

}

