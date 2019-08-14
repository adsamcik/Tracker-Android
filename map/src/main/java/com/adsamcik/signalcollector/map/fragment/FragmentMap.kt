package com.adsamcik.signalcollector.map.fragment


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
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.extension.hasLocationPermission
import com.adsamcik.signalcollector.common.extension.transaction
import com.adsamcik.signalcollector.common.extension.transactionStateLoss
import com.adsamcik.signalcollector.common.fragment.CoreUIFragment
import com.adsamcik.signalcollector.common.introduction.IntroductionManager
import com.adsamcik.signalcollector.common.style.StyleManager
import com.adsamcik.signalcollector.commonmap.ColorMap
import com.adsamcik.signalcollector.map.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import kotlinx.android.synthetic.main.fragment_map.*

@Suppress("unused")
class FragmentMap : CoreUIFragment(), OnMapReadyCallback, IOnDemandView {
	private var locationListener: UpdateLocationListener? = null
	private var mapController: MapController? = null
	private var mapSheetController: MapSheetController? = null

	private var mapFragment: SupportMapFragment? = null
	private var mapEventListener: MapEventListener? = null

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
	private fun checkLocationPermission(context: Context, request: Boolean): Boolean {
		if (context.hasLocationPermission) {
			return true
		} else if (request && Build.VERSION.SDK_INT >= 23) {
			activity?.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_LOCATION_CODE)
		}
		return false
	}

	override fun onLeave(activity: FragmentActivity) {
		if (hasPermissions) {
			locationListener?.unsubscribeFromLocationUpdates(activity)
		}
	}

	override fun onEnter(activity: FragmentActivity) {
		//This will prevent a crash, but can cause side effects, investigation needed
		if (isStateSaved) return

		this.fActivity = activity

		if (this.mapFragment == null) {
			val mapFragment = SupportMapFragment.newInstance()
			mapFragment.getMapAsync(this)

			val fragmentManager = fragmentManager
					?: throw NullPointerException("Fragment Manager is null. This was probably called too early!")

			fragmentManager.transaction {
				replace(R.id.container_map, mapFragment)
			}
			this.mapFragment = mapFragment
		}

		mapController?.onEnable(activity)

		locationListener?.subscribeToLocationUpdates(activity)

		IntroductionManager.showIntroduction(activity, MapIntroduction())
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		MapsInitializer.initialize(context)
		retainInstance = false
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = requireActivity()
		hasPermissions = checkLocationPermission(activity, true)
		val fragmentView: View
		if (Assist.checkPlayServices(activity) && container != null && hasPermissions) {
			fragmentView = view ?: inflater.inflate(R.layout.fragment_map, container, false)
		} else {
			fragmentView = inflater.inflate(com.adsamcik.signalcollector.common.R.layout.layout_error, container, false)
			fragmentView.findViewById<AppCompatTextView>(com.adsamcik.signalcollector.common.R.id.activity_error_text)
					.setText(if (hasPermissions) com.adsamcik.signalcollector.common.R.string.error_play_services_not_available else com.adsamcik.signalcollector.common.R.string.error_missing_permission)
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
	}

	/**
	 * Called when map is ready and initializes everything that needs to be initialized after maps loading
	 */
//todo refactor
	override fun onMapReady(map: GoogleMap) {
		val context = context ?: return

		val mapEventListener = MapEventListener(map)
		this.mapEventListener = mapEventListener

		val mapController = MapController(context, map)
		val locationListener = UpdateLocationListener(context, map, mapEventListener)

		this.mapController = mapController
		this.locationListener = locationListener

		mapSheetController = MapSheetController(context, map, map_ui_parent, mapController, locationListener, mapEventListener)

		ColorMap.addListener(context, map)

		//does not work well with bearing. Known bug in Google maps api since 2014.
		//Unfortunately had to be implemented anyway under new UI because Google requires Google logo to be visible at all times.
		//val padding = navbarHeight(c)
		//map.setPadding(0, 0, 0, padding)

		map.setOnMapClickListener {
			map_ui_parent.visibility = if (map_ui_parent.visibility == VISIBLE) GONE else VISIBLE
		}
	}

	companion object {
		private const val PERMISSION_LOCATION_CODE = 200
	}

}
