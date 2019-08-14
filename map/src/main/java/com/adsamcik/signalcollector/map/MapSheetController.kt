package com.adsamcik.signalcollector.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.view.View
import android.view.ViewGroup
import android.widget.Space
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.dialog.dateTimeRangePicker
import com.adsamcik.signalcollector.common.extension.coerceIn
import com.adsamcik.signalcollector.common.extension.dp
import com.adsamcik.signalcollector.common.extension.marginBottom
import com.adsamcik.signalcollector.common.extension.requireParent
import com.adsamcik.signalcollector.common.misc.Int2
import com.adsamcik.signalcollector.common.misc.SnackMaker
import com.adsamcik.signalcollector.common.misc.keyboard.KeyboardListener
import com.adsamcik.signalcollector.common.misc.keyboard.KeyboardManager
import com.adsamcik.signalcollector.common.misc.keyboard.NavBarPosition
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.common.style.StyleManager
import com.adsamcik.signalcollector.common.style.StyleView
import com.adsamcik.signalcollector.commonmap.CoordinateBounds
import com.adsamcik.signalcollector.map.adapter.MapFilterableAdapter
import com.adsamcik.signalcollector.map.layer.MapLayerLogic
import com.adsamcik.signalcollector.map.layer.logic.*
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.android.synthetic.main.layout_map_bottom_sheet_peek.view.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

internal class MapSheetController(context: Context,
                                  private val map: GoogleMap,
                                  private val rootLayout: ViewGroup,
                                  private val mapController: MapController,
                                  private val locationListener: UpdateLocationListener,
                                  private val mapEventListener: MapEventListener) : CoroutineScope, GoogleMap.OnCameraIdleListener {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job


	private val navbarDim: Int2
	private val navbarPosition: NavBarPosition

	private val mapLayerFilterRule = CoordinateBounds()

	private val geocoder: Geocoder? = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

	private val styleController = StyleManager.createController().also { styleController ->
		styleController.watchView(StyleView(rootLayout, layer = 0))
	}

	private val navbarSpace = rootLayout.findViewById<Space>(R.id.navbar_space)

	init {
		val (position, navbarHeight) = Assist.getNavigationBarSize(context)
		this.navbarDim = navbarHeight
		this.navbarPosition = position

		mapEventListener += this
	}

	init {
		if (navbarPosition == NavBarPosition.BOTTOM) {
			navbarSpace.updateLayoutParams<LinearLayoutCompat.LayoutParams> {
				height = navbarDim.y
			}
		}
	}

	private val sheetBehavior = BottomSheetBehavior.from(rootLayout).apply {
		//todo add dynamic navbar height
		peekHeight = 80.dp + navbarSpace.layoutParams.height + rootLayout.layout_map_controls.marginBottom
		isFitToContents = false
		val expandedOffset = 120.dp
		setExpandedOffset(expandedOffset)
		state = BottomSheetBehavior.STATE_COLLAPSED
		setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
			override fun onSlide(bottomSheet: View, slideOffset: Float) {
				if (slideOffset in 0f..halfExpandedRatio) {
					val parentHeight = (bottomSheet.parent as View).height
					val maxHeightDifference = parentHeight - expandedOffset - peekHeight
					map.setPadding(0, 0, 0, (peekHeight + slideOffset * maxHeightDifference).roundToInt())

					val progress = slideOffset / halfExpandedRatio
					navbarSpace.updateLayoutParams {
						height = ((1 - progress) * navbarDim.y).roundToInt()
					}
				}
			}

			@SuppressLint("SwitchIntDef")
			override fun onStateChanged(bottomSheet: View, newState: Int) {
				when (newState) {
					BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.STATE_HALF_EXPANDED -> keyboardManager.hideKeyboard()
				}
			}

		})

		rootLayout.alpha = 0f
		rootLayout.visibility = View.VISIBLE
		rootLayout.animate().alpha(1f).start()
	}

	init {
		rootLayout.findViewById<View>(R.id.map_sheet_drag_area).setOnClickListener {
			sheetBehavior.state = when (sheetBehavior.state) {
				BottomSheetBehavior.STATE_COLLAPSED -> BottomSheetBehavior.STATE_HALF_EXPANDED
				BottomSheetBehavior.STATE_HALF_EXPANDED -> BottomSheetBehavior.STATE_EXPANDED
				BottomSheetBehavior.STATE_EXPANDED -> BottomSheetBehavior.STATE_COLLAPSED
				else -> throw IllegalStateException()
			}
		}
	}

	private var stateBeforeKeyboard: Int = sheetBehavior.state

	/**
	 * Keyboard listener
	 * Is object variable so it can be unsubscribed when map is closed
	 */
	private val keyboardListener: KeyboardListener = { isOpen, keyboardHeight ->
		//map_menu_button is null in some rare cases. I am not entirely sure when it happens, but it seems to be quite rare so checking for null is probably OK atm
		//check payloads
		updateIconList(isOpen)
		when (isOpen) {
			true -> {
				/*if (position == NavBarPosition.BOTTOM) {
					val top = searchOriginalMargin +
							keyboardHeight +
							map_menu_draggable.height +
							edittext_map_search.paddingBottom +
							edittext_map_search.paddingTop + edittext_map_search.height

					//map_ui_parent.marginBottom = searchOriginalMargin + keyboardHeight
					//map?.setPadding(map_ui_parent.paddingLeft, 0, 0, top)
				}*/
				stateBeforeKeyboard = sheetBehavior.state
				sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
			}
			false -> {
				val baseBottomMarginPx = 32.dp
				if (navbarPosition == NavBarPosition.BOTTOM) {
					//map_ui_parent.marginBottom = searchOriginalMargin + navbarHeight.y + baseBottomMarginPx
					map.setPadding(0, 0, 0, navbarDim.y)
				} else {
					//map_ui_parent.marginBottom = searchOriginalMargin + baseBottomMarginPx
					map.setPadding(0, 0, 0, 0)
				}
				sheetBehavior.state = stateBeforeKeyboard
			}

			//Update map_menu_button position after UI has been redrawn
			/*map_menu_button.post {
				if (map_menu_button != null) {
					map_menu_button.moveToState(map_menu_button.state, false)
				}
			}*/
		}
	}

	private val keyboardManager = KeyboardManager(rootLayout).apply {
		//searchOriginalMargin = (map_ui_parent.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin
		onDisplaySizeChanged()
		addKeyboardListener(keyboardListener)
	}

	override fun onCameraIdle() {
		val bounds = map.projection.visibleRegion.latLngBounds
		mapLayerFilterRule.updateBounds(bounds.northeast.latitude, bounds.northeast.longitude, bounds.southwest.latitude, bounds.southwest.longitude)
		//fragmentMapMenu.get()?.filter(mapLayerFilterRule)
	}

	/**
	 * Initializes UI elements and colors
	 */
	init {
		rootLayout.edittext_map_search.setOnEditorActionListener { textView, _, _ ->
			search(textView.text.toString())
			true
		}

		rootLayout.findViewById<View>(R.id.button_map_search).setOnClickListener {
			search(rootLayout.edittext_map_search.text.toString())
		}

		rootLayout.findViewById<View>(R.id.button_map_date_range).setOnClickListener {
			launch(Dispatchers.Default) {
				val availableRange = mapController.availableDateRange

				if (availableRange.isEmpty()) {
					SnackMaker(rootLayout).addMessage(R.string.map_layer_no_data)
					return@launch
				}

				val selectedRange = mapController.dateRange.coerceIn(availableRange)
				/*val rangeFrom = createCalendarWithTime(availableRange.first)
				val rangeTo = createCalendarWithTime(availableRange.last)*/

				launch(Dispatchers.Main) {
					if (availableRange.last <= availableRange.first) {
						SnackMaker(it.requireParent<CoordinatorLayout>()).addMessage(R.string.map_layer_no_data)
					} else {
						MaterialDialog(it.context).dateTimeRangePicker(availableRange, selectedRange) {
							mapController.dateRange = it.first..it.last + Time.DAY_IN_MILLISECONDS - Time.SECOND_IN_MILLISECONDS
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
			
		}
		rootLayout.button_map_my_location.setOnClickListener {
			locationListener.onMyPositionButtonClick(it as AppCompatImageButton)
		}
		//styleController.watchView(StyleView(layout_map_controls, MAP_CONTROLS_LAYER))
	}

	init {
		val mapLayerList = listOf(
				NoMapLayerLogic(),
				LocationHeatmapLogic(),
				CellHeatmapLogic(),
				WifiHeatmapLogic(),
				LocationPolylineLogic())

		rootLayout.findViewById<RecyclerView>(R.id.recycler_layers).apply {
			layoutManager = GridLayoutManager(context, 2, GridLayoutManager.HORIZONTAL, false)
			addItemDecoration(SimpleMarginDecoration(0, 4.dp, 0, 0))
			adapter = MapFilterableAdapter(context, R.layout.layout_layer_icon) { context.getString(it.data.nameRes) }.apply {
				addAll(mapLayerList)
				onItemClickListener = this@MapSheetController::onItemClicked
			}
		}
	}

	private fun onItemClicked(position: Int, item: MapLayerLogic) {
		mapController.setLayer(rootLayout.context, item)

		if (sheetBehavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
			sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
		}
	}

	init {
		map.setPadding(0, 0, 0, sheetBehavior.peekHeight)
	}

	/**
	 * Uses search field and Geocoder to find given location
	 * Does not rely on Google Maps search API because this way it does not have to deal with API call restrictions
	 */
	private fun search(searchText: String) {
		val view = rootLayout.requireParent<CoordinatorLayout>()
		if (searchText.isBlank()) {
			SnackMaker(view).addMessage(R.string.map_search_no_text)
			return
		} else if (geocoder == null) {
			SnackMaker(view).addMessage(R.string.map_search_no_geocoder)
			return
		}

		try {
			val addresses = geocoder.getFromLocationName(searchText, 1)
			val locationListener = locationListener
			if (addresses?.isNotEmpty() == true) {
				val address = addresses.first()
				locationListener.stopUsingUserPosition(rootLayout.button_map_my_location, true)
				locationListener.animateToPositionZoom(LatLng(address.latitude, address.longitude), ANIMATE_TO_ZOOM)
			}
		} catch (e: IOException) {
			SnackMaker(view).addMessage(R.string.map_search_no_geocoder)
		}
	}


	private fun onLeave() {
		keyboardManager.run {
			hideKeyboard()
			removeKeyboardListener(keyboardListener)
		}

		StyleManager.recycleController(styleController)
	}

	private fun updateIconList(isKeyboardOpen: Boolean) {
		val shouldBeVisible = !isKeyboardOpen

		rootLayout.button_map_date_range.isVisible = shouldBeVisible
		rootLayout.button_map_my_location.isVisible = shouldBeVisible
	}

	companion object {
		private const val ANIMATE_TO_ZOOM = 13f
	}
}