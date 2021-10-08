package com.adsamcik.tracker.map

import android.content.Context
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.view.isGone
import com.adsamcik.tracker.map.layer.logic.NoMapLayerLogic
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.map.ColorMap
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.preferences.Preferences
import com.google.android.gms.maps.GoogleMap

internal class MapController(
		val context: Context,
		val map: GoogleMap,
		mapOwner: MapOwner,
		private val inProgressTileTextView: TextView
) {
	private var activeLayer: MapLayerLogic = NoMapLayerLogic()
	private var quality: Float = 1f

	val availableDateRange: LongRange
		get() = activeLayer.availableRange

	val defaultDateRange: LongRange
		get() = LongRange(
				Time.today.minusMonths(1).toEpochMillis(),
				Long.MAX_VALUE
		)

	var dateRange: LongRange = defaultDateRange
		set(value) {
			field = value
			activeLayer.dateRange = value
			lastDateChange = Time.nowMillis
		}

	var lastDateChange: Long = 0L
		private set

	@MainThread
	fun setLayer(context: Context, logic: MapLayerLogic) {
		if (this.activeLayer::class != logic::class) {
			this.activeLayer.onDisable(map)
			this.activeLayer.tileCountInGeneration.removeObserver(this::generatingTileCountObserver)

			logic.onEnable(context, map, quality)
			logic.dateRange = dateRange
			this.activeLayer = logic

			Preferences.getPref(context).edit {
				setString(R.string.settings_map_last_layer_key, logic.layerInfo.type.name)
			}

			logic.tileCountInGeneration.observeForever(this::generatingTileCountObserver)
		}
	}

	private fun generatingTileCountObserver(count: Int) {
		if (count > 0) {
			inProgressTileTextView.apply {
				text = context.resources.getQuantityString(
						R.plurals.generating_tile_count,
						count,
						count
				)
				isGone = false
			}
		} else {
			inProgressTileTextView.isGone = true
		}
	}

	private fun update() {
		//activeLayer.update()
	}

	init {
		mapOwner.addOnEnableListener { onEnable() }
		mapOwner.addOnDisableListener { onDisable() }
	}


	//initialize UI
	init {
		val uiSettings = map.uiSettings
		uiSettings.isMapToolbarEnabled = false
		uiSettings.isIndoorLevelPickerEnabled = false
		uiSettings.isCompassEnabled = false
		uiSettings.isMyLocationButtonEnabled = false

		map.setMaxZoomPreference(MAX_ZOOM)
	}

	private fun onEnable() {
		ColorMap.addListener(context, map)

		val pref = Preferences.getPref(context)
		val resources = context.resources

		val quality = pref.getFloat(
				resources.getString(R.string.settings_map_quality_key),
				resources.getString(R.string.settings_map_quality_default).toFloat()
		)

		this.quality = quality
		activeLayer.quality = quality
		activeLayer.tileCountInGeneration.observeForever(this::generatingTileCountObserver)
		activeLayer.onEnable(context, map, quality)

		if (lastDateChange != 0L && Time.nowMillis - lastDateChange > Time.QUARTER_DAY_IN_HOURS * Time.HOUR_IN_MILLISECONDS) {
			dateRange = defaultDateRange
		}
	}

	private fun onDisable() {
		ColorMap.removeListener(map)
		activeLayer.tileCountInGeneration.removeObserver(this::generatingTileCountObserver)
		activeLayer.onDisable(map)
	}

	companion object {
		const val MAX_ZOOM = 17f
	}
}

