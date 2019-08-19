package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.HeatmapTileProvider
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.commonmap.MapLayerLogic
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

internal abstract class HeatmapLayerLogic : MapLayerLogic, CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	override var availableRange: LongRange = LongRange.EMPTY
		protected set

	override var dateRange: LongRange
		get() = provider.range
		set(value) {
			provider.range = value
		}

	override var quality: Float
		get() = provider.quality
		set(value) {
			provider.updateQuality(value)
		}

	protected lateinit var overlay: TileOverlay

	protected lateinit var provider: HeatmapTileProvider


	override val supportsAutoUpdate: Boolean
		get() = false

	@WorkerThread
	protected abstract fun getTileCreator(context: Context): HeatmapTileCreator

	override fun onEnable(context: Context,
	                      map: GoogleMap,
	                      quality: Float) {
		val tileCreator = getTileCreator(context)

		launch {
			availableRange = tileCreator.availableRange
		}

		val maxHeat = Preferences.getPref(context).getIntResString(
				R.string.settings_map_max_heat_key,
				R.string.settings_map_max_heat_default).toFloat()
		val tileProvider = HeatmapTileProvider(tileCreator, maxHeat, quality)
		provider = tileProvider

		val tileOverlayOptions = TileOverlayOptions().tileProvider(tileProvider)

		overlay = map.addTileOverlay(tileOverlayOptions)

		tileProvider.onHeatChange = { heat, heatChange ->
			if (heatChange / (heat - heatChange) > HEAT_CHANGE_THRESHOLD_PERCENTAGE) {
				tileProvider.synchronizeMaxHeat()
				overlay.clearTileCache()
			}
		}
	}

	override fun onDisable(map: GoogleMap) {
		overlay.remove()
	}

	override fun update(context: Context) {
		overlay.clearTileCache()
	}

	companion object {
		private const val HEAT_CHANGE_THRESHOLD_PERCENTAGE = 0.05f
	}
}
