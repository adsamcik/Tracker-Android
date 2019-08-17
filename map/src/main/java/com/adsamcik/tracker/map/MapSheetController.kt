package com.adsamcik.tracker.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Scroller
import android.widget.Space
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.dialog.dateTimeRangePicker
import com.adsamcik.tracker.common.extension.coerceIn
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.extension.marginBottom
import com.adsamcik.tracker.common.extension.requireParent
import com.adsamcik.tracker.common.keyboard.KeyboardListener
import com.adsamcik.tracker.common.keyboard.KeyboardManager
import com.adsamcik.tracker.common.keyboard.NavBarPosition
import com.adsamcik.tracker.common.misc.Int2
import com.adsamcik.tracker.common.misc.SnackMaker
import com.adsamcik.tracker.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.common.style.StyleView
import com.adsamcik.tracker.commonmap.CoordinateBounds
import com.adsamcik.tracker.map.adapter.MapFilterableAdapter
import com.adsamcik.tracker.map.layer.MapLayerLogic
import com.adsamcik.tracker.map.layer.logic.CellHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.LocationHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.LocationPolylineLogic
import com.adsamcik.tracker.map.layer.logic.NoMapLayerLogic
import com.adsamcik.tracker.map.layer.logic.WifiHeatmapLogic
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

internal class MapSheetController(
		context: Context,
		private val map: GoogleMap,
		private val rootLayout: ViewGroup,
		private val mapController: MapController,
		private val locationListener: UpdateLocationListener,
		private val mapEventListener: MapEventListener
) : CoroutineScope, GoogleMap.OnCameraIdleListener {
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
		peekHeight = PEEK_CONTENT_HEIGHT_DP.dp + navbarSpace.layoutParams.height + rootLayout.layout_map_controls.marginBottom
		isFitToContents = false
		val expandedOffset = EXPANDED_TOP_OFFSET_DP.dp
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
				else -> BottomSheetBehavior.STATE_COLLAPSED
			}
		}
	}

	private var stateBeforeKeyboard: Int = sheetBehavior.state

	/**
	 * Keyboard listener
	 * Is object variable so it can be unsubscribed when map is closed
	 */
	private val keyboardListener: KeyboardListener = { isOpen, _ ->
		// map_menu_button is null in some rare cases. I am not entirely sure when it happens,
		// but it seems to be quite rare so checking for null is probably OK atm
		// check payloads
		updateIconList(isOpen)
		when (isOpen) {
			true -> {
				stateBeforeKeyboard = when (val state = sheetBehavior.state) {
					BottomSheetBehavior.STATE_COLLAPSED,
					BottomSheetBehavior.STATE_HALF_EXPANDED,
					BottomSheetBehavior.STATE_EXPANDED -> state
					else -> BottomSheetBehavior.STATE_COLLAPSED
				}

				sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
			}
			false -> {
				sheetBehavior.state = stateBeforeKeyboard
				rootLayout.edittext_map_search.clearFocus()
			}
		}
	}

	private val keyboardManager = KeyboardManager(rootLayout).apply {
		// searchOriginalMargin = (map_ui_parent.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin
		onDisplaySizeChanged()
		addKeyboardListener(keyboardListener)
	}

	override fun onCameraIdle() {
		val bounds = map.projection.visibleRegion.latLngBounds
		mapLayerFilterRule.updateBounds(bounds.northeast.latitude,
				bounds.northeast.longitude,
				bounds.southwest.latitude,
				bounds.southwest.longitude)
		// fragmentMapMenu.get()?.filter(mapLayerFilterRule)
	}

	init {
		val isSearchDisabled = geocoder == null || true
		if (isSearchDisabled) {
			rootLayout.findViewById<EditText>(R.id.edittext_map_search).apply {
				isEnabled = false
				setHint(R.string.map_search_no_geocoder)
			}
			rootLayout.findViewById<View>(R.id.button_map_search).isGone = true
		} else {
			rootLayout.edittext_map_search.setOnEditorActionListener { textView, _, _ ->
				search(textView.text.toString())
				true
			}

			rootLayout.findViewById<View>(R.id.button_map_search).setOnClickListener {
				search(rootLayout.edittext_map_search.text.toString())
			}
		}
	}

	/**
	 * Initializes UI elements and colors
	 */
	init {
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
								.setTheme(com.adsamcik.tracker.common.R.style.CalendarPicker)
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
		// styleController.watchView(StyleView(layout_map_controls, MAP_CONTROLS_LAYER))
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
			adapter = MapFilterableAdapter(context, R.layout.layout_layer_icon) {
				context.getString(it.data.nameRes)
			}.apply {
				addAll(mapLayerList)
				onItemClickListener = this@MapSheetController::onItemClicked
			}
		}
	}

	private fun onItemClicked(@Suppress("UNUSED") position: Int, item: MapLayerLogic) {
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


	fun onDestroy() {
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

		private const val EXPANDED_TOP_OFFSET_DP = 120
		private const val PEEK_CONTENT_HEIGHT_DP = 80
	}
}

