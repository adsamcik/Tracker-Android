package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.heatmap.UserHeatmapData
import com.adsamcik.tracker.map.heatmap.HeatmapTileProvider
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.preferences.Preferences
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
			overlay.clearTileCache()
		}

	override var quality: Float
		get() = provider.quality
		set(value) {
			provider.updateQuality(value)
			overlay.clearTileCache()
		}

	override val tileCountInGeneration: MutableLiveData<Int> = MutableLiveData()

	protected lateinit var overlay: TileOverlay

	protected lateinit var provider: HeatmapTileProvider


	override val supportsAutoUpdate: Boolean
		get() = false

	@WorkerThread
	protected abstract fun getTileCreator(context: Context): HeatmapTileCreator

	override fun onEnable(
			context: Context,
			map: GoogleMap,
			quality: Float
	) {
		val tileCreator = getTileCreator(context)

		launch {
			availableRange = tileCreator.availableRange
		}

		val preferences = Preferences.getPref(context)
		val maxHeat = preferences.getIntResString(
				R.string.settings_map_max_heat_key,
				R.string.settings_map_max_heat_default
		).toFloat()
		val visitThreshold = preferences.getIntResString(
				R.string.settings_map_visit_threshold_key,
				R.string.settings_map_visit_threshold_default
		)
		val tileProvider = HeatmapTileProvider(
				tileCreator,
				UserHeatmapData(
						maxHeat,
						visitThreshold,
						quality
				)
		)

		tileProvider.tileRequestCountListener = { tileCountInGeneration.postValue(it) }

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
