package com.adsamcik.signalcollector.map.fragment


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.drawable.AnimatedVectorDrawable
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.*
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.adsamcik.draggable.*
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.app.Assist.navbarSize
import com.adsamcik.signalcollector.app.Tips
import com.adsamcik.signalcollector.app.color.ColorManager
import com.adsamcik.signalcollector.app.color.ColorSupervisor
import com.adsamcik.signalcollector.app.color.ColorView
import com.adsamcik.signalcollector.app.dialog.DateTimeRangeDialog
import com.adsamcik.signalcollector.map.*
import com.adsamcik.signalcollector.misc.SnackMaker
import com.adsamcik.signalcollector.misc.extension.dpAsPx
import com.adsamcik.signalcollector.misc.extension.marginBottom
import com.adsamcik.signalcollector.misc.extension.transaction
import com.adsamcik.signalcollector.misc.extension.transactionStateLoss
import com.adsamcik.signalcollector.misc.keyboard.KeyboardListener
import com.adsamcik.signalcollector.misc.keyboard.KeyboardManager
import com.adsamcik.signalcollector.misc.keyboard.NavBarPosition
import com.appeaser.sublimepickerlibrary.helpers.SublimeOptions
import com.appeaser.sublimepickerlibrary.helpers.SublimeOptions.ACTIVATE_DATE_PICKER
import com.crashlytics.android.Crashlytics
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import kotlinx.android.synthetic.main.fragment_map.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FragmentMap : Fragment(), GoogleMap.OnCameraIdleListener, OnMapReadyCallback, IOnDemandView {
	private var locationListener: UpdateLocationListener? = null
	private var mapController: MapController? = null

	private var map: GoogleMap? = null
	private var mapFragment: SupportMapFragment? = null
	private var mapEventListener: MapEventListener? = null

	private var fActivity: FragmentActivity? = null

	private var mapLayerFilterRule = CoordinateBounds()

	private var hasPermissions = false
	private var initialized = false

	private var keyboardManager: KeyboardManager? = null
	private var searchOriginalMargin = 0
	private var keyboardInitialized = AtomicBoolean(false)

	private var colorManager: ColorManager? = null

	private var fragmentMapMenu: AtomicReference<FragmentMapMenu?> = AtomicReference(null)

	private val isMapLight = AtomicBoolean()

	private var dateRange: ClosedRange<Calendar>? = null

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {
		val activity = fActivity
		if (requestCode == PERMISSION_LOCATION_CODE && success && activity != null) {
			val newFrag = FragmentMap()
			activity.supportFragmentManager.transactionStateLoss {
				replace(R.id.container, newFrag)
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
		if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			return true
		else if (request && Build.VERSION.SDK_INT >= 23)
			activity!!.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_LOCATION_CODE)
		return false
	}

	override fun onLeave(activity: FragmentActivity) {
		if (hasPermissions) {
			locationListener?.unsubscribeFromLocationUpdates(activity)
		}

		if (keyboardManager != null) {
			val keyboardManager = keyboardManager!!
			keyboardManager.hideKeyboard()
			keyboardManager.removeKeyboardListener(keyboardListener)
			keyboardInitialized.set(false)
		}
	}

	override fun onEnter(activity: FragmentActivity) {
		this.fActivity = activity

		if (this.mapFragment == null) {
			val mapFragment = SupportMapFragment.newInstance()
			mapFragment.getMapAsync(this)
			fragmentManager!!.transaction {
				replace(R.id.container_map, mapFragment)
			}
			this.mapFragment = mapFragment
		}

		mapController?.let {
			it.setDateRange(dateRange)
			it.onEnable(activity)
		}

		locationListener?.subscribeToLocationUpdates(activity)

		Tips.showTips(activity, Tips.MAP_TIPS, null)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		MapsInitializer.initialize(context)
		retainInstance = false
	}

	override fun onStart() {
		super.onStart()
		if (!initialized) {
			initialized = true
			initializeUserElements()
			loadMapLayers()
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = activity!!
		hasPermissions = checkLocationPermission(activity, true)
		val fragmentView: View
		if (Assist.checkPlayServices(activity) && container != null && hasPermissions) {
			fragmentView = view ?: inflater.inflate(R.layout.fragment_map, container, false)
		} else {
			fragmentView = inflater.inflate(R.layout.layout_error, container, false)
			(fragmentView.findViewById<View>(R.id.activity_error_text) as TextView).setText(if (hasPermissions) R.string.error_play_services_not_available else R.string.error_missing_permission)
			return fragmentView
		}

		colorManager = ColorSupervisor.createColorManager(activity)

		return fragmentView
	}

	override fun onDestroyView() {
		super.onDestroyView()
		mapFragment = null
		if (colorManager != null)
			ColorSupervisor.recycleColorManager(colorManager!!)
	}

	/**
	 * Keyboard listener
	 * Is object variable so it can be unsubscribed when map is closed
	 */
	private val keyboardListener: KeyboardListener = { opened, keyboardHeight ->
		val activity = activity
		//map_menu_button is null in some rare cases. I am not entirely sure when it happens, but it seems to be quite rare so checking for null is probably OK atm
		if (activity != null && map_menu_button != null) {
			val (position, navbarHeight) = navbarSize(activity)
			//check payloads
			when (opened) {
				true -> {
					if (position == NavBarPosition.BOTTOM) {
						val top = searchOriginalMargin +
								keyboardHeight +
								map_menu_button.height +
								edittext_map_search.paddingBottom +
								edittext_map_search.paddingTop + edittext_map_search.height

						map_ui_parent.marginBottom = searchOriginalMargin + keyboardHeight
						map?.setPadding(map_ui_parent.paddingLeft, 0, 0, top)
					}
				}
				false -> {
					if (position == NavBarPosition.BOTTOM) {
						map_ui_parent.marginBottom = searchOriginalMargin + navbarHeight.y + 32.dpAsPx
						map?.setPadding(0, 0, 0, navbarHeight.y)
					} else {
						map_ui_parent.marginBottom = searchOriginalMargin + 32.dpAsPx
						map?.setPadding(0, 0, 0, 0)
					}
				}
			}

			//Update map_menu_button position after UI has been redrawn
			map_menu_button.post {
				if (map_menu_button != null)
					map_menu_button.moveToState(map_menu_button.state, false)
			}
		}
	}


	/**
	 * Initializes keyboard detection so margins are properly set when keyboard is open
	 * Cannot be 100% reliable because Android does not provide any keyboard api whatsoever
	 * Relies on detecting bigger layout size changes.
	 */
	private fun initializeKeyboardDetection() {
		if (keyboardInitialized.get())
			keyboardManager!!.onDisplaySizeChanged()
		else {
			if (keyboardManager == null) {
				searchOriginalMargin = (map_ui_parent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).bottomMargin
				keyboardManager = KeyboardManager(view!!.rootView)
			}

			keyboardManager!!.addKeyboardListener(keyboardListener)
			keyboardInitialized.set(true)
		}
	}

	/**
	 * Initializes UI elements and colors
	 */
	private fun initializeUserElements() {
		initializeKeyboardDetection()
		edittext_map_search.setOnEditorActionListener { v, _, _ ->
			search(v.text.toString())
			true
		}

		button_map_search.setOnClickListener {
			search(edittext_map_search.text.toString())
		}

		edittext_map_search.setOnFocusChangeListener { _, hasFocus ->
			if (hasFocus) {
				button_map_my_location.visibility = VISIBLE
				button_map_date_range.visibility = VISIBLE
			} else {
				button_map_my_location.visibility = INVISIBLE
				button_map_date_range.visibility = INVISIBLE
			}
		}

		button_map_date_range.setOnClickListener {
			DateTimeRangeDialog().apply {
				arguments = Bundle().apply {
					putParcelable(DateTimeRangeDialog.ARG_OPTIONS, SublimeOptions().apply {
						val dateRange = this@FragmentMap.dateRange
						if (dateRange != null)
							setDateParams(dateRange.start, dateRange.endInclusive)

						setDisplayOptions(ACTIVATE_DATE_PICKER)
						setCanPickDateRange(true)
					})
				}
				successCallback = { range ->
					this@FragmentMap.dateRange = range
					mapController?.setDateRange(range)
				}
			}.show(fragmentManager!!, "Map date range dialog")
		}

		button_map_my_location.setOnClickListener {
			locationListener?.onMyPositionButtonClick(it as AppCompatImageButton)
		}

		colorManager!!.watchView(ColorView(map_menu_button, 2, recursive = false, rootIsBackground = false))
		colorManager!!.watchView(ColorView(layout_map_controls, 3, recursive = true, rootIsBackground = false))
	}

	/**
	 * Uses search field and Geocoder to find given location
	 * Does not rely on Google Maps search API because this way it does not have to deal with API call restrictions
	 */
	private fun search(searchText: String) {
		if (searchText.isBlank()) {
			SnackMaker(view!!).showSnackbar(R.string.map_search_no_text)
			return
		} else if (!Geocoder.isPresent()) {
			SnackMaker(view!!).showSnackbar(R.string.map_search_no_geocoder)
			return
		}

		try {
			val geocoder = Geocoder(context)
			val addresses = geocoder.getFromLocationName(searchText, 1)
			val locationListener = locationListener
			if (addresses?.isNotEmpty() == true && map != null && locationListener != null) {
				val address = addresses[0]
				locationListener.stopUsingUserPosition(button_map_my_location, true)
				locationListener.animateToPositionZoom(LatLng(address.latitude, address.longitude), 13f)
			}
		} catch (e: IOException) {
			Crashlytics.logException(e)
			val view = view
			if (view != null)
				SnackMaker(view).showSnackbar(R.string.map_search_no_geocoder)
		}
	}

	/**
	 * Sets menu drawable to given drawable and starts its animation
	 */
	private fun animateMenuDrawable(@DrawableRes drawableRes: Int) {
		val context = context
		if (context != null) {
			val drawable = context.getDrawable(drawableRes) as AnimatedVectorDrawable
			map_menu_button.setImageDrawable(drawable)
			drawable.start()
		}
	}

	/**
	 * Called when map is ready and initializes everything that needs to be initialized after maps loading
	 */
//todo refactor
	override fun onMapReady(map: GoogleMap) {
		val context = context ?: return

		val mapEventListener = MapEventListener(map)
		this.mapEventListener = mapEventListener

		mapController = MapController(context, map)
		locationListener = UpdateLocationListener(context, map, mapEventListener)

		this.map = map

		colorManager!!.addListener { luminance, _ ->
			//-32
			if (luminance >= 0) {
				if (isMapLight.get())
					return@addListener

				isMapLight.set(true)

				GlobalScope.launch(Dispatchers.Main) { map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style)) }
			} else {
				if (!isMapLight.get())
					return@addListener

				isMapLight.set(false)
				GlobalScope.launch(Dispatchers.Main) { map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)) }
			}
		}

		//does not work well with bearing. Known bug in Google maps api since 2014.
		//Unfortunately had to be implemented anyway under new UI because Google requires Google logo to be visible at all times.
		//val padding = navbarHeight(c)
		//map.setPadding(0, 0, 0, padding)

		map.setOnMapClickListener {
			map_ui_parent.visibility = if (map_ui_parent.visibility == VISIBLE) GONE else VISIBLE
		}
		map.setOnCameraIdleListener(this)


		initializeKeyboardDetection()
	}

	/**
	 * Initializes map layers and menu button. If map layers are already initialized only initializes menu button.
	 */
	private fun loadMapLayers() {
		val resources = resources
		val mapLayers = mutableListOf(
				MapLayer(resources.getString(R.string.location)),
				MapLayer(resources.getString(R.string.wifi)),
				MapLayer(resources.getString(R.string.cell)))
		initializeMenuButton(mapLayers)
	}

	/**
	 * Initialized draggable menu button
	 */
	private fun initializeMenuButton(mapLayers: List<MapLayer>) {
		//uses post to make sure heights and widths are available
		map_menu_parent.post {
			val activity = activity!!
			val payload = DraggablePayload(activity, FragmentMapMenu::class.java, map_menu_parent, map_menu_button)
			payload.initialTranslation = Point(0, map_menu_parent.height)
			payload.stickToTarget = true
			payload.anchor = DragTargetAnchor.LeftTop
			payload.offsets = Offset(0, map_menu_button.height)
			payload.width = map_menu_parent.width
			payload.height = map_menu_parent.height
			payload.onInitialized = {
				fragmentMapMenu.set(it)
				colorManager!!.watchAdapterView(ColorView(it.view!!, 2))
				if (mapLayers.isNotEmpty()) {
					val adapter = it.adapter
					adapter.clear()
					adapter.addAll(mapLayers)
					it.onClickListener = { layer, _ ->
						mapController?.setLayer(activity, LayerType.valueOfCaseInsensitive(layer.name))
						map_menu_button.moveToState(DraggableImageButton.State.INITIAL, true)
					}
					it.filter(mapLayerFilterRule)
				}
			}
			payload.onBeforeDestroyed = {
				fragmentMapMenu.set(null)
				colorManager!!.stopWatchingAdapterView(R.id.recycler)
			}

			map_menu_button.onEnterStateListener = { _, state, _, hasStateChanged ->
				if (hasStateChanged) {
					if (state == DraggableImageButton.State.TARGET)
						animateMenuDrawable(R.drawable.up_to_down)
					else if (state == DraggableImageButton.State.INITIAL)
						animateMenuDrawable(R.drawable.down_to_up)
				}
			}
			//payload.initialTranslation = Point(map_menu_parent.x.toInt(), map_menu_parent.y.toInt() + map_menu_parent.height)
			//payload.setOffsetsDp(Offset(0, 24))
			map_menu_button.addPayload(payload)
			if (mapLayers.isNotEmpty()) {
				map_menu_button.visibility = View.VISIBLE
				colorManager!!.notifyChangeOn(map_menu_button)
			}
		}

		map_menu_button.extendTouchAreaBy(0, 12.dpAsPx, 0, 0)
	}

	override fun onCameraIdle() {
		val bounds = map!!.projection.visibleRegion.latLngBounds
		mapLayerFilterRule.updateBounds(bounds.northeast.latitude, bounds.northeast.longitude, bounds.southwest.latitude, bounds.southwest.longitude)
		fragmentMapMenu.get()?.filter(mapLayerFilterRule)
	}

	companion object {
		private const val PERMISSION_LOCATION_CODE = 200

		private const val TAG = "SignalsMap"
	}

}
