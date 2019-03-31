package com.adsamcik.signalcollector.map

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.preference.Preferences
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.TileOverlayOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class MapController(context: Context, val map: GoogleMap) {
	private var layerType: LayerType
	private val tileProvider: HeatmapTileProvider = HeatmapTileProvider(context)

	private val tileOverlayOptions = TileOverlayOptions().tileProvider(tileProvider)
	private val activeOverlay = map.addTileOverlay(tileOverlayOptions)

	fun setLayer(context: Context, layerType: LayerType, force: Boolean = false) {
		if (force || this.layerType != layerType) {
			this.layerType = layerType
			tileProvider.setHeatmapLayer(context, layerType)
		}
	}

	fun setDateRange(range: ClosedRange<Calendar>?) {
		tileProvider.range = range
		activeOverlay.clearTileCache()
	}

	fun onEnable(context: Context) {
		val pref = Preferences.getPref(context)
		val resources = context.resources

		val quality = pref.getFloat(resources.getString(R.string.settings_map_quality_key), resources.getString(R.string.settings_map_quality_default).toFloat())

		tileProvider.let {
			if (it.quality != quality) {
				it.updateQuality(quality)
				activeOverlay.clearTileCache()
			}
		}
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
		val resources = context.resources
		layerType = LayerType.fromPreference(context, resources.getString(R.string.settings_map_default_layer_key), resources.getString(R.string.settings_map_default_layer_default))
		tileProvider.setHeatmapLayer(context, layerType)
		onEnable(context)
	}

	init {
		tileProvider.onHeatChange = { heat, heatChange ->
			if (heatChange / (heat - heatChange) > HEAT_CHANGE_THRESHOLD_PERCENTAGE) {
				tileProvider.synchronizeMaxHeat()
				GlobalScope.launch(Dispatchers.Main) { activeOverlay.clearTileCache() }
			}
		}

		tileProvider.initMaxHeat(layerType.name, map.cameraPosition.zoom.toInt(), true)
	}

	companion object {
		private const val MAX_ZOOM = 17f
		private const val HEAT_CHANGE_THRESHOLD_PERCENTAGE = 0.05f
	}
}