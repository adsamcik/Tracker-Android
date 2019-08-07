package com.adsamcik.signalcollector.map

import android.content.Context
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.map.layer.MapLayerLogic
import com.adsamcik.signalcollector.map.layer.logic.NoMapLayerLogic
import com.google.android.gms.maps.GoogleMap
import java.util.*

internal class MapController(context: Context, val map: GoogleMap) {
	private var activeLayer: MapLayerLogic = NoMapLayerLogic()
	private var quality: Float = 1f

	fun setLayer(context: Context, logic: MapLayerLogic) {
		if (this.activeLayer::class != logic::class) {
			this.activeLayer.onDisable(map)

			this.activeLayer = logic.also {
				it.onEnable(context, map)
				it.quality = quality
			}
		}
	}

	fun setDateRange(range: ClosedRange<Calendar>?) {
		activeLayer.dateRange = if (range == null) {
			LongRange.EMPTY
		} else {
			LongRange(range.start.timeInMillis, range.endInclusive.timeInMillis)
		}
	}

	private fun update() {
		activeLayer.update()
	}

	fun onEnable(context: Context) {
		val pref = Preferences.getPref(context)
		val resources = context.resources

		val quality = pref.getFloat(resources.getString(R.string.settings_map_quality_key), resources.getString(R.string.settings_map_quality_default).toFloat())

		this.quality = quality
		activeLayer.quality = quality
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

	//initialize layerType
	init {
		onEnable(context)
	}

	companion object {
		const val MAX_ZOOM = 17f
	}
}