package com.adsamcik.signalcollector.map

import android.content.Context
import android.os.Looper
import com.adsamcik.signalcollector.map.R
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.map.heatmap.HeatmapTileProvider
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
	private var activeOverlay = map.addTileOverlay(tileOverlayOptions)

	fun setLayer(context: Context, layerType: LayerType, force: Boolean = false) {
		if (force || this.layerType != layerType) {
			this.layerType = layerType

			GlobalScope.launch {
				tileProvider.setHeatmapLayer(context, layerType)
				clearTileCache()
			}
		}
	}

	fun setDateRange(range: ClosedRange<Calendar>?) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			GlobalScope.launch {
				tileProvider.range = range
				clearTileCache()
			}
		} else {
			tileProvider.range = range
			clearTileCache()
		}
	}

	private fun clearTileCache() {
		if (Looper.myLooper() != Looper.getMainLooper())
			GlobalScope.launch(Dispatchers.Main) { activeOverlay.clearTileCache() }
		else
			activeOverlay.clearTileCache()
	}

	fun onEnable(context: Context) {
		val pref = Preferences.getPref(context)
		val resources = context.resources

		val quality = pref.getFloat(resources.getString(R.string.settings_map_quality_key), resources.getString(R.string.settings_map_quality_default).toFloat())

		tileProvider.let {
			if (it.quality != quality) {
				it.updateQuality(quality)
				clearTileCache()
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
		GlobalScope.launch { tileProvider.setHeatmapLayer(context, layerType) }
		onEnable(context)
	}

	init {
		tileProvider.onHeatChange = { heat, heatChange ->
			if (heatChange / (heat - heatChange) > HEAT_CHANGE_THRESHOLD_PERCENTAGE) {
				tileProvider.synchronizeMaxHeat()
				clearTileCache()
			}
		}
	}

	companion object {
		private const val MAX_ZOOM = 17f
		private const val HEAT_CHANGE_THRESHOLD_PERCENTAGE = 0.05f
	}
}