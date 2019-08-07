package com.adsamcik.signalcollector.map.layer.logic

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.map.R
import com.adsamcik.signalcollector.map.heatmap.HeatmapTileProvider
import com.adsamcik.signalcollector.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.signalcollector.map.layer.MapLayerLogic
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

internal abstract class HeatmapLayerLogic : MapLayerLogic, CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

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

	override fun onEnable(context: Context, map: GoogleMap) {
		val tileCreator = getTileCreator(context)
		val maxHeat = Preferences.getPref(context).getIntResString(
				R.string.settings_map_max_heat_key,
				R.string.settings_map_max_heat_default).toFloat()
		val tileProvider = HeatmapTileProvider(tileCreator, maxHeat)
		provider = tileProvider

		val tileOverlayOptions = TileOverlayOptions().tileProvider(tileProvider)

		overlay = map.addTileOverlay(tileOverlayOptions)
	}

	override fun onDisable(map: GoogleMap) {
		overlay.remove()
	}

	override fun update() {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}
}