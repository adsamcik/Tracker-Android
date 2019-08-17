package com.adsamcik.tracker.map

import android.content.Context
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.commonmap.ColorMap
import com.adsamcik.tracker.map.layer.MapLayerLogic
import com.adsamcik.tracker.map.layer.logic.NoMapLayerLogic
import com.google.android.gms.maps.GoogleMap

internal class MapController(val context: Context, val map: GoogleMap, mapOwner: MapOwner) {
	private var activeLayer: MapLayerLogic = NoMapLayerLogic()
	private var quality: Float = 1f

	val availableDateRange: LongRange
		get() = activeLayer.availableRange

	var dateRange: LongRange = LongRange(Time.nowMillis - 30 * Time.DAY_IN_MILLISECONDS, Time.nowMillis)
		set(value) {
			field = value
			activeLayer.dateRange = value
		}

	fun setLayer(context: Context, logic: MapLayerLogic) {
		if (this.activeLayer::class != logic::class) {
			this.activeLayer.onDisable(map)

			this.activeLayer = logic.also {
				it.onEnable(context, map)
				it.quality = quality
			}
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

	fun onEnable() {
		ColorMap.addListener(context, map)

		val pref = Preferences.getPref(context)
		val resources = context.resources

		val quality = pref.getFloat(resources.getString(R.string.settings_map_quality_key),
				resources.getString(R.string.settings_map_quality_default).toFloat())

		this.quality = quality
		activeLayer.quality = quality
	}

	fun onDisable() {
		ColorMap.removeListener(map)
	}

	companion object {
		const val MAX_ZOOM = 17f
	}
}

