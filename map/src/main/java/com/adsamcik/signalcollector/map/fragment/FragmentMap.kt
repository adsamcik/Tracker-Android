package com.adsamcik.signalcollector.map.fragment


import android.Manifest
import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.Assist.getNavigationBarSize
import com.adsamcik.signalcollector.common.dialog.dateTimeRangePicker
import com.adsamcik.signalcollector.common.extension.*
import com.adsamcik.signalcollector.common.fragment.CoreUIFragment
import com.adsamcik.signalcollector.common.introduction.IntroductionManager
import com.adsamcik.signalcollector.common.misc.SnackMaker
import com.adsamcik.signalcollector.common.misc.keyboard.KeyboardListener
import com.adsamcik.signalcollector.common.misc.keyboard.KeyboardManager
import com.adsamcik.signalcollector.common.misc.keyboard.NavBarPosition
import com.adsamcik.signalcollector.common.style.StyleManager
import com.adsamcik.signalcollector.common.style.StyleView
import com.adsamcik.signalcollector.commonmap.ColorMap
import com.adsamcik.signalcollector.commonmap.CoordinateBounds
import com.adsamcik.signalcollector.map.MapController
import com.adsamcik.signalcollector.map.MapEventListener
import com.adsamcik.signalcollector.map.R
import com.adsamcik.signalcollector.map.UpdateLocationListener
import com.adsamcik.signalcollector.map.layer.MapLayerLogic
import com.adsamcik.signalcollector.map.layer.logic.*
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.android.synthetic.main.fragment_map.*
import kotlinx.android.synthetic.main.layout_map_bottom_sheet_peek.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Suppress("unused")
class FragmentMap : CoreUIFragment(), GoogleMap.OnCameraIdleListener, OnMapReadyCallback, IOnDemandView {
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

	private var fragmentMapMenu: AtomicReference<FragmentMapMenu?> = AtomicReference(null)

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

		keyboardManager?.run {
			hideKeyboard()
			removeKeyboardListener(keyboardListener)
		}
		keyboardInitialized.set(false)
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
		initializeKeyboardDetection()
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

		fragmentView.setOnTouchListener { _, _ ->
			edittext_map_search.clearFocus()
			true
		}

		return fragmentView
	}

	override fun onDestroyView() {
		super.onDestroyView()
		mapFragment = null
		map?.let { ColorMap.removeListener(it) }
		map = null

		styleController.let { StyleManager.recycleController(it) }
	}

	private fun updateIconList(isKeyboardOpen: Boolean) {
		val shouldBeVisible = !isKeyboardOpen

		button_map_date_range.isVisible = shouldBeVisible
		button_map_my_location.isVisible = shouldBeVisible
	}

	/**
	 * Keyboard listener
	 * Is object variable so it can be unsubscribed when map is closed
	 */
	private val keyboardListener: KeyboardListener = { isOpen, keyboardHeight ->
		val activity = activity
		//map_menu_button is null in some rare cases. I am not entirely sure when it happens, but it seems to be quite rare so checking for null is probably OK atm
		if (activity != null && map_menu_draggable != null) {
			val (position, navbarHeight) = getNavigationBarSize(activity)
			//check payloads

			when (isOpen) {
				true -> {
					if (position == NavBarPosition.BOTTOM) {
						val top = searchOriginalMargin +
								keyboardHeight +
								map_menu_draggable.height +
								edittext_map_search.paddingBottom +
								edittext_map_search.paddingTop + edittext_map_search.height

						//map_ui_parent.marginBottom = searchOriginalMargin + keyboardHeight
						//map?.setPadding(map_ui_parent.paddingLeft, 0, 0, top)
					}
				}
				false -> {
					val baseBottomMarginPx = 32.dp
					if (position == NavBarPosition.BOTTOM) {
						//map_ui_parent.marginBottom = searchOriginalMargin + navbarHeight.y + baseBottomMarginPx
						map?.setPadding(0, 0, 0, navbarHeight.y)
					} else {
						//map_ui_parent.marginBottom = searchOriginalMargin + baseBottomMarginPx
						map?.setPadding(0, 0, 0, 0)
					}
				}
			}

			//Update map_menu_button position after UI has been redrawn
			/*map_menu_button.post {
				if (map_menu_button != null) {
					map_menu_button.moveToState(map_menu_button.state, false)
				}
			}*/
		}
	}


	/**
	 * Initializes keyboard detection so margins are properly set when keyboard is open
	 * Cannot be 100% reliable because Android does not provide any keyboard api whatsoever
	 * Relies on detecting bigger layout size changes.
	 */
	private fun initializeKeyboardDetection() {
		/*if (keyboardInitialized.get()) {
			val keyboardManager = keyboardManager
					?: throw NullPointerException("KeyboardManager should never be null when keyboardInitialized is true")
			keyboardManager.onDisplaySizeChanged()
		} else {
			val keyboardManager = keyboardManager ?: KeyboardManager(requireView().rootView).also {
				searchOriginalMargin = (map_ui_parent.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
				keyboardManager = it
			}

			keyboardManager.addKeyboardListener(keyboardListener)
			keyboardInitialized.set(true)
		}*/
	}

	/**
	 * Initializes UI elements and colors
	 */
	private fun initializeUserElements() {
		initializeNavbarOffset()

		edittext_map_search.setOnEditorActionListener { textView, _, _ ->
			search(textView.text.toString())
			true
		}

		button_map_search.setOnClickListener {
			search(edittext_map_search.text.toString())
		}

		edittext_map_search.setOnFocusChangeListener { _, hasFocus ->
			updateIconList(hasFocus)
		}

		button_map_date_range.setOnClickListener {
			launch(Dispatchers.Default) {
				val mapController = requireNotNull(mapController)
				val availableRange = mapController.availableDateRange
				val selectedRange = mapController.dateRange.coerceIn(availableRange)
				/*val rangeFrom = createCalendarWithTime(availableRange.first)
				val rangeTo = createCalendarWithTime(availableRange.last)*/

				launch(Dispatchers.Main
				) {
					if (availableRange.last <= availableRange.first) {
						SnackMaker(requireNotNull(it.firstParent<ConstraintLayout>())).addMessage(R.string.map_layer_no_data)
					} else {
						MaterialDialog(it.context).dateTimeRangePicker(availableRange, selectedRange) {
							mapController.dateRange = it
						}.show()

						/*val constraints = CalendarConstraints.Builder()
								.setStart(Month.create(rangeFrom.year, rangeFrom.month))
								.setEnd(Month.create(rangeTo.year, rangeTo.month))
						MaterialDatePicker.Builder.dateRangePicker()
								.setCalendarConstraints(constraints.build())
								.setTheme(com.adsamcik.signalcollector.common.R.style.CalendarPicker)
								.build(

								).apply {
									addOnPositiveButtonClickListener {
										val from = it.first ?: selectedRange.first
										val to = it.second ?: from
										mapController.dateRange = from..to
									}
								}.show(requireFragmentManager(), "picker")*/
					}
				}
			}


			/*DateTimeRangeDialog().apply {
				arguments = Bundle().apply {
					putParcelable(DateTimeRangeDialog.ARG_OPTIONS, SublimeOptions().apply {
						val dateRange = this@FragmentMap.dateRange
						if (dateRange != null) {
							setDateParams(dateRange.start, dateRange.endInclusive)
						}

						setDisplayOptions(ACTIVATE_DATE_PICKER)
						setCanPickDateRange(true)
					})
				}
				successCallback = { range ->
					this@FragmentMap.dateRange = range
					mapController?.setDateRange(range)
				}
			}.show(fragmentManager, "Map date range dialog")*/
		}

		val behavior = BottomSheetBehavior.from(map_ui_parent)
		//todo add dynamic navbar height
		behavior.peekHeight = 80.dp + navbar_space.layoutParams.height + layout_map_controls.marginBottom
		behavior.isFitToContents = false
		behavior.setExpandedOffset(120.dp)
		behavior.state = BottomSheetBehavior.STATE_EXPANDED

		button_map_my_location.setOnClickListener {
			locationListener?.onMyPositionButtonClick(it as AppCompatImageButton)
		}

		styleController.watchView(StyleView(map_ui_parent, MAP_MENU_BUTTON_LAYER))
		//styleController.watchView(StyleView(layout_map_controls, MAP_CONTROLS_LAYER))
	}

	private fun initializeNavbarOffset() {
		val (position, navDim) = Assist.getNavigationBarSize(requireContext())
		if (navDim.x > navDim.y) {
			navDim.x = 0
		} else {
			navDim.y = 0
		}

		if (position == NavBarPosition.BOTTOM) {
			navbar_space.updateLayoutParams<LinearLayoutCompat.LayoutParams> {
				height = navDim.y
			}
		}
	}

	/**
	 * Uses search field and Geocoder to find given location
	 * Does not rely on Google Maps search API because this way it does not have to deal with API call restrictions
	 */
	private fun search(searchText: String) {
		val view = requireView()
		if (searchText.isBlank()) {
			SnackMaker(view).addMessage(R.string.map_search_no_text)
			return
		} else if (!Geocoder.isPresent()) {
			SnackMaker(view).addMessage(R.string.map_search_no_geocoder)
			return
		}

		try {
			val geocoder = Geocoder(context)
			val addresses = geocoder.getFromLocationName(searchText, 1)
			val locationListener = locationListener
			if (addresses?.isNotEmpty() == true && map != null && locationListener != null) {
				val address = addresses.first()
				locationListener.stopUsingUserPosition(button_map_my_location, true)
				locationListener.animateToPositionZoom(LatLng(address.latitude, address.longitude), ANIMATE_TO_ZOOM)
			}
		} catch (e: IOException) {
			SnackMaker(view).addMessage(R.string.map_search_no_geocoder)
		}
	}

	/**
	 * Sets menu drawable to given drawable and starts its animation
	 */
	private fun animateMenuDrawable(@DrawableRes drawableRes: Int) {
		context?.let { context ->
			val drawable = context.getDrawable(drawableRes) as AnimatedVectorDrawable
			map_menu_draggable.setImageDrawable(drawable)
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

		ColorMap.addListener(context, map)

		//does not work well with bearing. Known bug in Google maps api since 2014.
		//Unfortunately had to be implemented anyway under new UI because Google requires Google logo to be visible at all times.
		//val padding = navbarHeight(c)
		//map.setPadding(0, 0, 0, padding)

		map.setOnMapClickListener {
			map_ui_parent.visibility = if (map_ui_parent.visibility == VISIBLE) GONE else VISIBLE
		}
		map.setOnCameraIdleListener(this)
	}

	/**
	 * Initializes map layers and menu button. If map layers are already initialized only initializes menu button.
	 */
	private fun loadMapLayers() {
		val mapLayers: List<MapLayerLogic> = mutableListOf(
				NoMapLayerLogic(),
				LocationHeatmapLogic(),
				CellHeatmapLogic(),
				WifiHeatmapLogic(),
				LocationPolylineLogic())
		initializeMenuButton(mapLayers)
	}

	/**
	 * Initialized draggable menu button
	 */
	private fun initializeMenuButton(mapLayerData: List<MapLayerLogic>) {
		/*	if (mapLayerData.isEmpty()) {
				map_menu_button.isGone = true
				return
			}

			//uses post to make sure heights and widths are available
			map_menu_parent.post {
				val activity = requireActivity()
				val payload = DraggablePayload(activity, FragmentMapMenu::class.java, map_menu_parent, map_menu_button)
				payload.initialTranslation = Point(0, map_menu_parent.height)
				payload.stickToTarget = true
				payload.anchor = DragTargetAnchor.LeftTop
				payload.offsets = Offset(0, map_menu_button.height)
				payload.width = map_menu_parent.width
				payload.height = map_menu_parent.height
				payload.destroyPayloadAfter = 0L
				payload.onInitialized = {
					fragmentMapMenu.set(it)
					styleController.watchRecyclerView(RecyclerStyleView(it.requireView() as RecyclerView, 2))
					it.adapter.addAll(mapLayerData)
					it.onClickListener = { layer, _ ->
						mapController?.setLayer(activity, layer)
						map_menu_button.moveToState(DraggableImageButton.State.INITIAL, true)
					}
					it.filter(mapLayerFilterRule)
				}
				payload.onBeforeDestroyed = {
					fragmentMapMenu.set(null)
					styleController.stopWatchingRecyclerView(R.id.recycler)
				}

				map_menu_button.onEnterStateListener = { _, state, _, hasStateChanged ->
					if (hasStateChanged) {
						if (state == DraggableImageButton.State.TARGET) {
							animateMenuDrawable(R.drawable.up_to_down)
						} else if (state == DraggableImageButton.State.INITIAL) {
							animateMenuDrawable(R.drawable.down_to_up)
						}
					}
				}
				//payload.initialTranslation = Point(map_menu_parent.x.toInt(), map_menu_parent.y.toInt() + map_menu_parent.height)
				//payload.setOffsetsDp(Offset(0, 24))
				map_menu_button.addPayload(payload)
			}

			map_menu_button.extendTouchAreaBy(0, 12.dp, 0, 0)*/
	}

	override fun onCameraIdle() {
		val map = map ?: return

		val bounds = map.projection.visibleRegion.latLngBounds
		mapLayerFilterRule.updateBounds(bounds.northeast.latitude, bounds.northeast.longitude, bounds.southwest.latitude, bounds.southwest.longitude)
		fragmentMapMenu.get()?.filter(mapLayerFilterRule)
	}

	companion object {
		private const val PERMISSION_LOCATION_CODE = 200

		private const val ANIMATE_TO_ZOOM = 13f

		private const val MAP_MENU_BUTTON_LAYER = 0
		private const val MAP_CONTROLS_LAYER = 3
	}

}
