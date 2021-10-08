package com.adsamcik.tracker.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.Geocoder
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.map.adapter.MapFilterableAdapter
import com.adsamcik.tracker.map.introduction.MapSheetHiddenIntroduction
import com.adsamcik.tracker.map.layer.logic.CellHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.LocationHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.LocationPolylineLogic
import com.adsamcik.tracker.map.layer.logic.NoMapLayerLogic
import com.adsamcik.tracker.map.layer.logic.WifiCountHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.WifiHeatmapLogic
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.extension.coerceIn
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.marginBottom
import com.adsamcik.tracker.shared.base.extension.requireParent
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.misc.Int2
import com.adsamcik.tracker.shared.base.misc.NavBarPosition
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.dialog.createDateTimeDialog
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.adsamcik.tracker.shared.utils.fragment.CorePermissionFragment
import com.adsamcik.tracker.shared.utils.introduction.IntroductionManager
import com.adsamcik.tracker.shared.utils.keyboard.KeyboardListener
import com.adsamcik.tracker.shared.utils.keyboard.KeyboardManager
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

internal class MapSheetController(
		private val activity: FragmentActivity,
		fragment: CorePermissionFragment,
		private val map: GoogleMap,
		mapOwner: MapOwner,
		private val rootLayout: ViewGroup,
		private val mapController: MapController,
		private val locationListener: MapSensorController,
		private val mapEventListener: MapEventListener
) : CoroutineScope, GoogleMap.OnMapClickListener {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private val navbarDim: Int2
	private val navbarPosition: NavBarPosition

	private val geocoder: Geocoder? = if (Geocoder.isPresent()) {
		Geocoder(activity, Locale.getDefault())
	} else {
		null
	}

	private val styleController = StyleManager.createController().also { styleController ->
		styleController.watchView(StyleView(rootLayout, layer = 0))
		styleController.watchRecyclerView(
				RecyclerStyleView(rootLayout.findViewById(R.id.map_legend_recycler), layer = 1)
		)
		styleController.watchRecyclerView(
				RecyclerStyleView(rootLayout.findViewById(R.id.map_layers_recycler), layer = 0)
		)
	}

	private val peekNavbarSpace = rootLayout.findViewById<Space>(R.id.peek_navbar_space)
	private val contentNavbarSpace = rootLayout.findViewById<Space>(R.id.content_navbar_space)

	private val tileGenerationCountTextView =
			(rootLayout.parent as ViewGroup).findViewById<TextView>(R.id.tile_generation_count_textview)

	private val legendController = MapLegendController(rootLayout)

	private val scrollView = rootLayout.findViewById<NestedScrollView>(R.id.map_sheet_scroll_view)

	init {
		mapOwner.addOnEnableListener { onEnable() }
		mapOwner.addOnDisableListener { onDisable() }

		//mapController.setLayer(context, )
	}

	init {
		val (position, navbarHeight) = DisplayAssist.getNavigationBarSize(activity)
		this.navbarDim = navbarHeight
		this.navbarPosition = position

		mapEventListener += this
	}

	init {
		if (navbarPosition == NavBarPosition.BOTTOM) {
			peekNavbarSpace.updateLayoutParams<LinearLayoutCompat.LayoutParams> {
				height = navbarDim.y
			}
			contentNavbarSpace.updateLayoutParams {
				height = navbarDim.y
			}
		}
	}

	private val sheetBehavior = BottomSheetBehavior.from(rootLayout).apply {
		peekHeight = PEEK_CONTENT_HEIGHT_DP.dp +
				peekNavbarSpace.layoutParams.height +
				rootLayout.findViewById<LinearLayoutCompat>(R.id.layout_map_controls).marginBottom

		val navbarHeightInverseRatio = -1f + navbarDim.y / peekHeight.toFloat()
		isHideable = true
		isFitToContents = false
		val expandedOffset = EXPANDED_TOP_OFFSET_DP.dp
		setExpandedOffset(expandedOffset)
		state = BottomSheetBehavior.STATE_COLLAPSED
		addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
			private var lastOffset = Float.MIN_VALUE

			override fun onSlide(bottomSheet: View, slideOffset: Float) {
				val updatedOffset = slideOffset.coerceIn(
						navbarHeightInverseRatio,
						halfExpandedRatio
				)

				if (updatedOffset != lastOffset) {
					lastOffset = updatedOffset

					if (updatedOffset >= 0) {
						val parentHeight = (bottomSheet.parent as View).height
						val maxHeightDifference = parentHeight - expandedOffset - peekHeight
						val offset = (peekHeight + updatedOffset * maxHeightDifference).roundToInt()

						setSheetOffset(offset)

						val progress = updatedOffset / halfExpandedRatio
						peekNavbarSpace.updateLayoutParams {
							height = ((1 - progress) * navbarDim.y).roundToInt()
						}
					} else {
						val offset = ((1 + updatedOffset) * peekHeight).roundToInt()
						setSheetOffset(offset)
					}
				}

				scrollView.updateLayoutParams {
					height = bottomSheet.height - bottomSheet.top - peekHeight + navbarDim.y
				}
			}

			@SuppressLint("SwitchIntDef")
			override fun onStateChanged(bottomSheet: View, newState: Int) {
				if (newState != BottomSheetBehavior.STATE_EXPANDED && newState != BottomSheetBehavior.STATE_SETTLING) {
					keyboardManager.hideKeyboard()
					rootLayout.findViewById<AppCompatEditText>(R.id.edittext_map_search)
							.clearFocus()
				}

				if (newState == BottomSheetBehavior.STATE_HIDDEN) {
					bottomSheet.post {
						IntroductionManager.showIntroduction(activity, MapSheetHiddenIntroduction())
					}
				}
			}
		})

		rootLayout.alpha = 0f
		rootLayout.visibility = View.VISIBLE
		rootLayout.animate().alpha(1f).start()
	}

	private fun setSheetOffset(offset: Int) {
		map.setPadding(0, 0, 0, offset)
		tileGenerationCountTextView.marginBottom = offset
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
	private var isKeyboardOpen: Boolean = false

	/**
	 * Keyboard listener
	 * Is object variable so it can be unsubscribed when map is closed
	 */
	private val keyboardListener: KeyboardListener = { isOpen, _ ->
		// map_menu_button is null in some rare cases. I am not entirely sure when it happens,
		// but it seems to be quite rare so checking for null is probably OK atm
		// check payloads
		updateIconList(isOpen)
		when {
			isOpen && !isKeyboardOpen -> {
				stateBeforeKeyboard = when (val state = sheetBehavior.state) {
					BottomSheetBehavior.STATE_COLLAPSED,
					BottomSheetBehavior.STATE_HALF_EXPANDED,
					BottomSheetBehavior.STATE_EXPANDED -> state
					else -> BottomSheetBehavior.STATE_COLLAPSED
				}

				isKeyboardOpen = true

				sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
			}
			!isOpen && isKeyboardOpen -> {
				sheetBehavior.state = stateBeforeKeyboard
				isKeyboardOpen = false
				rootLayout.findViewById<AppCompatEditText>(R.id.edittext_map_search).clearFocus()
			}
		}
	}

	private val keyboardManager = KeyboardManager(rootLayout).apply {
		addKeyboardListener(keyboardListener)
	}

	override fun onMapClick(p0: LatLng?) {
		sheetBehavior.state = when (sheetBehavior.state) {
			BottomSheetBehavior.STATE_COLLAPSED -> BottomSheetBehavior.STATE_HIDDEN
			BottomSheetBehavior.STATE_HIDDEN -> BottomSheetBehavior.STATE_COLLAPSED
			else -> BottomSheetBehavior.STATE_COLLAPSED
		}
	}

	init {
		val isSearchDisabled = geocoder == null
		if (isSearchDisabled) {
			rootLayout.findViewById<EditText>(R.id.edittext_map_search).apply {
				isEnabled = false
				setHint(R.string.map_search_no_geocoder)
			}
			rootLayout.findViewById<View>(R.id.button_map_search).isGone = true
		} else {
			rootLayout.findViewById<AppCompatEditText>(R.id.edittext_map_search)
					.setOnEditorActionListener { textView, _, _ ->
						search(textView.text.toString())
						true
					}

			rootLayout
					.findViewById<AppCompatEditText>(R.id.edittext_map_search)
					.setOnFocusChangeListener { _, isFocused ->
						when (isFocused) {
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
								if (sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
									sheetBehavior.state = stateBeforeKeyboard
								}
								rootLayout.findViewById<AppCompatEditText>(R.id.edittext_map_search)
										.clearFocus()
							}
						}
					}

			rootLayout.findViewById<View>(R.id.button_map_search).setOnClickListener {
				search(rootLayout.findViewById<AppCompatEditText>(R.id.edittext_map_search).text.toString())
			}
		}
	}

	private fun makeChip(context: Context) = Chip(context).apply {
		setTextColor(Color.WHITE)
	}

	@Suppress("UNUSED_PARAMETER")
	private fun showRangeSelectionDialog(view: View) {
		launch(Dispatchers.Default) {
			val availableRange = mapController.availableDateRange

			if (availableRange.isEmpty()) {
				SnackMaker(rootLayout).addMessage(R.string.map_layer_no_data)
				return@launch
			}

			val selectedRange = mapController.dateRange.coerceIn(availableRange)

			launch(Dispatchers.Main) {
				val context = activity
				MaterialDialog(context).show {
					customView(R.layout.layout_map_date_range_selection)
					val chipGroup = this.view.contentLayout.findViewById<ChipGroup>(R.id.map_date_range_chip_group)

					@Suppress("MagicNumber")
					val chipDays = listOf(1, 3, 7, 14, 30, 60, 90, 180, 365)
					chipDays.forEach { days ->
						val daysText = context.resources.getQuantityString(
								R.plurals.date_range_selector_x_days,
								days
						)
						val chip = makeChip(context).apply {
							text = daysText.format(Locale.getDefault(), days)
							setOnClickListener {
								// Add day and remove day to make the intent clear.
								val tomorrow = Time.tomorrow
								mapController.dateRange = LongRange(
										tomorrow.minusDays((days - 1).toLong()).toEpochMillis(),
										tomorrow.toEpochMillis()
								)
								this@show.dismiss()
							}
						}
						chipGroup.addView(chip)
					}

					chipGroup.addView(makeChip(context).apply {
						text = context.getString(R.string.date_range_selector_all_days)
						setOnClickListener {
							mapController.dateRange = LongRange(0, Long.MAX_VALUE)
							this@show.dismiss()
						}
					})

					chipGroup.addView(makeChip(context).apply {
						text = context.getString(R.string.date_range_selector_custom_days)
						setOnClickListener {
							activity.createDateTimeDialog(
									styleController,
									availableRange,
									selectedRange
							) {
								mapController.dateRange = selectedRange
							}
							this@show.dismiss()
						}
					})

					dynamicStyle()

				}
			}
		}
	}

	/**
	 * Initializes UI elements and colors
	 */
	init {
		rootLayout.findViewById<View>(R.id.button_map_date_range)
				.setOnClickListener(this::showRangeSelectionDialog)
		rootLayout.findViewById<AppCompatImageButton>(R.id.button_map_my_location)
				.setOnClickListener {
					fun onPositionClick(button: AppCompatImageButton) {
						locationListener.onMyPositionButtonClick(button)
					}

					it as AppCompatImageButton
					if (it.context.hasLocationPermission) {
						onPositionClick(it)
					} else {
						fragment.requestPermissions(
								PermissionRequest
										.with(it.context)
										.permissions(listOf(
												PermissionData(
														Manifest.permission.ACCESS_FINE_LOCATION
												) { context -> context.getString(R.string.permission_rationale_location_map) }
										))
										.onResult { result ->
											if (result.isSuccess) {
												onPositionClick(it)
											}
										}
										.build()
						)
					}
				}
		// styleController.watchView(StyleView(layout_map_controls, MAP_CONTROLS_LAYER))
	}


	init {
		val mapLayerList = listOf(
				NoMapLayerLogic(),
				LocationHeatmapLogic(),
				CellHeatmapLogic(),
				WifiHeatmapLogic(),
				WifiCountHeatmapLogic(),
				LocationPolylineLogic()
		)

		rootLayout.findViewById<RecyclerView>(R.id.map_layers_recycler).apply {
			layoutManager = GridLayoutManager(context, 2, GridLayoutManager.HORIZONTAL, false)
			addItemDecoration(MarginDecoration(0, 4.dp, 0, 0))
			adapter = MapFilterableAdapter(context, R.layout.layout_layer_icon) {
				context.getString(it.layerInfo.nameRes)
			}.apply {
				addAll(mapLayerList)
				onItemClickListener = this@MapSheetController::onItemClicked

				initializeLastLayer(context, mapLayerList)
			}
		}
	}

	private fun initializeLastLayer(context: Context, list: List<MapLayerLogic>) {
		launch(Dispatchers.Default) {
			val default = Preferences.getPref(context)
					.getStringRes(R.string.settings_map_last_layer_key) ?: return@launch

			val lastIndex = list.indexOfFirst { it.layerInfo.type.name == default }
			if (lastIndex >= 0) {
				onItemClicked(lastIndex, list[lastIndex])
			} else {
				onItemClicked(0, list[0])
			}
		}
	}

	private fun setLayer(layer: MapLayerLogic) {
		launch(Dispatchers.Main.immediate) {
			mapController.setLayer(rootLayout.context, layer)
			legendController.setLayer(layer.layerData())
		}
	}

	@Suppress("unused_parameter")
	private fun onItemClicked(position: Int, item: MapLayerLogic) {
		setLayer(item)

		if (sheetBehavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
			sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
		}
	}

	init {
		setSheetOffset(sheetBehavior.peekHeight)
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
				locationListener.stopUsingUserPosition(rootLayout.findViewById(R.id.button_map_my_location))
				locationListener.animateToPositionZoom(
						LatLng(address.latitude, address.longitude),
						ANIMATE_TO_ZOOM
				)
				sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
			}
		} catch (e: IOException) {
			SnackMaker(view).addMessage(R.string.map_search_no_geocoder)
		}
	}

	private fun onEnable() {
		styleController.isSuspended = false
		keyboardManager.onEnable()
	}


	private fun onDisable() {
		keyboardManager.run {
			hideKeyboard()
			onDisable()
		}

		styleController.isSuspended = true
	}

	private fun updateIconList(isKeyboardOpen: Boolean) {
		val shouldBeVisible = !isKeyboardOpen

		rootLayout.findViewById<AppCompatImageButton>(R.id.button_map_date_range).isVisible = shouldBeVisible
		rootLayout.findViewById<AppCompatImageButton>(R.id.button_map_my_location).isVisible = shouldBeVisible
	}

	companion object {
		private const val ANIMATE_TO_ZOOM = 13f

		private const val EXPANDED_TOP_OFFSET_DP = 120
		private const val PEEK_CONTENT_HEIGHT_DP = 80
	}
}

