package com.adsamcik.signalcollector.map.heatmap

import android.content.Context
import android.graphics.Color
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseMapMaxHeat
import com.adsamcik.signalcollector.map.CoordinateBounds
import com.adsamcik.signalcollector.map.LayerType
import com.adsamcik.signalcollector.map.MapFunctions
import com.adsamcik.signalcollector.map.heatmap.creators.CellHeatmapTileCreator
import com.adsamcik.signalcollector.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.signalcollector.map.heatmap.creators.LocationHeatmapTileCreator
import com.adsamcik.signalcollector.map.heatmap.creators.WifiHeatmapTileCreator
import com.adsamcik.signalcollector.misc.ConditionVariableInt
import com.adsamcik.signalcollector.misc.Int2
import com.adsamcik.signalcollector.misc.extension.toDate
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt


//todo refactor
class HeatmapTileProvider(context: Context) : TileProvider {
	private var heatmapTileCreator: HeatmapTileCreator? = null
		set(value) {
			heatmapCache.clear()
			field = value
		}

	private val heatmapCache = mutableMapOf<Int2, HeatmapTile>()

	private val heatDao = AppDatabase.getDatabase(context).mapHeatDao()

	private lateinit var maxHeat: DatabaseMapMaxHeat
	private val heatLock = ReentrantLock()
	private val heatUpdateScheduled = AtomicBoolean(false)

	private val tileRequestCount: ConditionVariableInt = ConditionVariableInt(0)

	var heatChange: Float = 0f
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
				value.start.toDate()..value.endInclusive.toDate().apply {
					add(Calendar.DAY_OF_MONTH, 1)
				}
			} else
				null
			heatmapCache.clear()
			initMaxHeat(maxHeat.layerName, maxHeat.zoom, value == null)
		}

	private val colorScheme = HeatmapColorScheme.fromArray(listOf(Pair(0.1, Color.TRANSPARENT), Pair(0.3, Color.BLUE), Pair(0.7, Color.YELLOW), Pair(1.0, Color.RED)), 100)

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
		heatLock.withLock {
			heatChange = 0f
			if (useDatabase) {
				val dbMaxHeat = heatDao.getSingle(layerName, zoom)
				if (dbMaxHeat != null) {
					maxHeat = dbMaxHeat
					return
				}
			}

			maxHeat = DatabaseMapMaxHeat(layerName, zoom, MIN_HEAT)
		}
	}

	fun setHeatmapLayer(context: Context, layerType: LayerType) {
		heatmapTileCreator = when (layerType) {
			LayerType.Location -> LocationHeatmapTileCreator(context)
			LayerType.Cell -> CellHeatmapTileCreator(context)
			LayerType.WiFi -> WifiHeatmapTileCreator(context)
		}
		initMaxHeat(layerType.name, lastZoom, range == null)
	}

	override fun getTile(x: Int, y: Int, zoom: Int): Tile {
		tileRequestCount.incrementAndGet()
		//Ensure that everything is up to date. It's fine to lock every time, since it is called only handful of times at once.
		heatLock.withLock {
			if (lastZoom != zoom) {
				heatmapCache.clear()
				lastZoom = zoom

				initMaxHeat(maxHeat.layerName, zoom, range == null)
			}
		}

		val heatmapProvider = heatmapTileCreator!!


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
				heatmapProvider.getHeatmap(heatmapSize, stamp, colorScheme, x, y, zoom, area, maxHeat.maxHeat)
			else
				heatmapProvider.getHeatmap(heatmapSize, stamp, colorScheme, range.start.timeInMillis, range.endInclusive.timeInMillis, x, y, zoom, area, maxHeat.maxHeat)
			heatmapCache[key] = heatmap
		}

		heatLock.withLock {
			if (maxHeat.maxHeat < heatmap.maxHeat && zoom == lastZoom) {
				//round to next whole number to avoid frequent calls
				val newHeat = ceil(heatmap.maxHeat)
				heatChange += newHeat - maxHeat.maxHeat
				maxHeat.maxHeat = newHeat

				if (range == null)
					heatDao.insert(maxHeat)

				if (!heatUpdateScheduled.get()) {
					heatUpdateScheduled.set(true)
					tileRequestCount.addWaiter({ it == 0 }) {
						if (heatChange > 0) {
							onHeatChange?.invoke(maxHeat.maxHeat, heatChange)
							heatUpdateScheduled.set(false)
						}
					}
				}
				return@withLock
			}
		}

		val tile = Tile(heatmapSize, heatmapSize, heatmap.toByteArray(max(MIN_TILE_SIZE, heatmapSize)))
		tileRequestCount.decrementAndGet()

		return tile
	}

	companion object {
		const val MIN_HEAT: Float = 1f
		const val MIN_TILE_SIZE: Int = 256
	}
}