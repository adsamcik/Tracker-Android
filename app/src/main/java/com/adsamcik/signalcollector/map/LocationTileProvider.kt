package com.adsamcik.signalcollector.map

import android.content.Context
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseMapMaxHeat
import com.adsamcik.signalcollector.map.heatmap.HeatmapStamp
import com.adsamcik.signalcollector.map.heatmap.HeatmapTile
import com.adsamcik.signalcollector.map.heatmap.providers.CellTileHeatmapProvider
import com.adsamcik.signalcollector.map.heatmap.providers.LocationTileHeatmapProvider
import com.adsamcik.signalcollector.map.heatmap.providers.MapTileHeatmapProvider
import com.adsamcik.signalcollector.map.heatmap.providers.WifiTileHeatmapProvider
import com.adsamcik.signalcollector.misc.Int2
import com.adsamcik.signalcollector.misc.extension.date
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt


//todo refactor
class LocationTileProvider(context: Context) : TileProvider {
	private var heatmapProvider: MapTileHeatmapProvider? = null
		set(value) {
			heatmapCache.clear()
			field = value
		}

	private val heatmapCache = mutableMapOf<Int2, HeatmapTile>()

	private val heatDao = AppDatabase.getAppDatabase(context).mapHeatDao()

	private lateinit var maxHeat: DatabaseMapMaxHeat
	private val heatLock = ReentrantLock()

	var heatChange = 0f
		private set

	var onHeatChange: ((currentHeat: Float, heatChange: Float) -> Unit)? = null

	private var lastZoom = Int.MIN_VALUE

	var quality: Float = 0f
		private set

	private var heatmapSize: Int = 0
	private lateinit var stamp: HeatmapStamp

	var range: ClosedRange<Calendar>? = null
		set(value) {
			field = if (value != null) {
				value.start.date()..value.endInclusive.date().apply {
					add(Calendar.DAY_OF_MONTH, 1)
				}
			} else
				null
			heatmapCache.clear()
			initMaxHeat(maxHeat.layerName, maxHeat.zoom, value == null)
		}

	fun updateQuality(quality: Float) {
		this.quality = quality
		heatmapSize = (quality * HeatmapTile.BASE_HEATMAP_SIZE).roundToInt()
		val stampRadius = HeatmapStamp.calculateOptimalRadius(heatmapSize)
		stamp = HeatmapStamp.generateNonlinear(stampRadius) { it.pow(2f) }
		heatmapCache.clear()
	}

	fun synchronizeMaxHeat() {
		heatLock.withLock {
			heatmapCache.forEach {
				it.value.heatmap.maxHeat = maxHeat.maxHeat
			}
			heatChange = 0f
		}
	}

	fun initMaxHeat(layerName: String, zoom: Int, useDatabase: Boolean) {
		maxHeat = DatabaseMapMaxHeat(layerName, zoom, MIN_HEAT)

		if (useDatabase) {
			GlobalScope.launch {
				val dbMaxHeat = heatDao.getSingle(layerName, zoom)
				if (dbMaxHeat != null) {
					heatLock.withLock {
						if (maxHeat.zoom != dbMaxHeat.zoom || maxHeat.layerName != dbMaxHeat.layerName)
							return@launch

						if (maxHeat.maxHeat < dbMaxHeat.maxHeat)
							maxHeat = dbMaxHeat
					}
				}
			}
		}
	}

	fun setHeatmapLayer(context: Context, layerType: LayerType) {
		heatmapProvider = when (layerType) {
			LayerType.Location -> LocationTileHeatmapProvider(context)
			LayerType.Cell -> CellTileHeatmapProvider(context)
			LayerType.WiFi -> WifiTileHeatmapProvider(context)
		}
		initMaxHeat(layerType.name, lastZoom, range == null)
	}

	override fun getTile(x: Int, y: Int, zoom: Int): Tile {
		//Ensure that everything is up to date. It's fine to lock every time, since it is called only handful of times at once.
		heatLock.withLock {
			if (lastZoom != zoom) {
				heatmapCache.clear()
				lastZoom = zoom

				initMaxHeat(maxHeat.layerName, zoom, range == null)
			}
		}

		val heatmapProvider = heatmapProvider!!


		val leftX = MapFunctions.toLon(x.toDouble(), zoom)
		val topY = MapFunctions.toLat(y.toDouble(), zoom)

		val rightX = MapFunctions.toLon((x + 1).toDouble(), zoom)
		val bottomY = MapFunctions.toLat((y + 1).toDouble(), zoom)

		val area = CoordinateBounds(topY, rightX, bottomY, leftX)

		val key = Int2(x, y)
		val heatmap: HeatmapTile
		if (heatmapCache.containsKey(key)) {
			heatmap = heatmapCache[key]!!
		} else {
			val range = range
			heatmap = if (range == null)
				heatmapProvider.getHeatmap(heatmapSize, stamp, x, y, zoom, area, maxHeat.maxHeat)
			else
				heatmapProvider.getHeatmap(heatmapSize, stamp, range.start.timeInMillis, range.endInclusive.timeInMillis, x, y, zoom, area, maxHeat.maxHeat)
			heatmapCache[key] = heatmap
		}

		heatLock.withLock {
			if (maxHeat.maxHeat < heatmap.maxHeat) {
				//round to next whole number to avoid frequent calls
				val newHeat = ceil(heatmap.maxHeat)
				heatChange += newHeat - maxHeat.maxHeat
				maxHeat.maxHeat = heatmap.maxHeat

				if (range == null)
					heatDao.insert(maxHeat)

				onHeatChange?.invoke(maxHeat.maxHeat, heatChange)
			}
		}

		return Tile(heatmapSize, heatmapSize, heatmap.toByteArray(max(MIN_TILE_SIZE, heatmapSize)))
	}

	companion object {
		const val MIN_HEAT: Float = 1f
		const val MIN_TILE_SIZE = 256
	}
}