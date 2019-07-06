package com.adsamcik.signalcollector.map.heatmap

import android.content.Context
import android.graphics.Color
import com.adsamcik.signalcollector.common.extension.toDate
import com.adsamcik.signalcollector.common.misc.ConditionVariableInt
import com.adsamcik.signalcollector.common.misc.Int2
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.commonmap.CoordinateBounds
import com.adsamcik.signalcollector.map.LayerType
import com.adsamcik.signalcollector.map.MapController
import com.adsamcik.signalcollector.map.MapFunctions
import com.adsamcik.signalcollector.map.R
import com.adsamcik.signalcollector.map.heatmap.creators.CellHeatmapTileCreator
import com.adsamcik.signalcollector.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.signalcollector.map.heatmap.creators.LocationHeatmapTileCreator
import com.adsamcik.signalcollector.map.heatmap.creators.WifiHeatmapTileCreator
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

	private val preferences = Preferences.getPref(context)

	private val heatmapCache = mutableMapOf<Int2, HeatmapTile>()

	private val heatLock = ReentrantLock()
	private val heatUpdateScheduled = AtomicBoolean(false)

	private val tileRequestCount: ConditionVariableInt = ConditionVariableInt(0)

	private var heatChange: Float = 0f

	var onHeatChange: ((currentHeat: Float, heatChange: Float) -> Unit)? = null

	private var lastZoom = Int.MIN_VALUE

	var quality: Float = 0f
		private set

	private var heatmapSize: Int = 0
	private lateinit var stamp: HeatmapStamp

	private var maxHeat: Float = 0f

	var range: ClosedRange<Calendar>? = null
		set(value) {
			field = if (value != null) {
				value.start.toDate()..value.endInclusive.toDate().apply {
					add(Calendar.DAY_OF_MONTH, 1)
				}
			} else
				null
			heatmapCache.clear()
			resetMaxHeat()
		}

	private val colorScheme = HeatmapColorScheme.fromArray(listOf(Pair(0.1, Color.TRANSPARENT), Pair(0.3, Color.BLUE), Pair(0.7, Color.YELLOW), Pair(1.0, Color.RED)), 100)

	init {
		resetMaxHeat()
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
				it.value.heatmap.maxHeat = maxHeat
			}
			heatChange = 0f
		}
	}

	private fun resetMaxHeat() {
		heatLock.withLock {
			maxHeat = preferences.getIntResString(
					R.string.settings_map_max_heat_key,
					R.string.settings_map_max_heat_default).toFloat()

			//todo make this smarter so it actually takes nearest neighbour
			maxHeat *= max(1f, 2f.pow(MAX_HEAT_ZOOM - lastZoom))
		}
	}

	fun setHeatmapLayer(context: Context, layerType: LayerType) {
		heatmapTileCreator = when (layerType) {
			LayerType.Location -> LocationHeatmapTileCreator(context)
			LayerType.Cell -> CellHeatmapTileCreator(context)
			LayerType.WiFi -> WifiHeatmapTileCreator(context)
		}
		resetMaxHeat()
	}

	override fun getTile(x: Int, y: Int, zoom: Int): Tile {
		tileRequestCount.incrementAndGet()
		//Ensure that everything is up to date. It's fine to lock every time, since it is called only handful of times at once.
		heatLock.withLock {
			if (lastZoom != zoom) {
				heatmapCache.clear()
				lastZoom = zoom

				resetMaxHeat()
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
			heatmap = if (range == null) {
				heatmapProvider.getHeatmap(heatmapSize, stamp, colorScheme, x, y, zoom, area, maxHeat)
			} else {
				heatmapProvider.getHeatmap(heatmapSize, stamp, colorScheme, range.start.timeInMillis, range.endInclusive.timeInMillis, x, y, zoom, area, maxHeat)
			}
			heatmapCache[key] = heatmap
		}

		heatLock.withLock {
			if (maxHeat < heatmap.maxHeat && zoom == lastZoom) {
				//round to next whole number to avoid frequent calls
				val newHeat = ceil(heatmap.maxHeat)
				heatChange += newHeat - maxHeat
				maxHeat = newHeat

				if (!heatUpdateScheduled.get()) {
					heatUpdateScheduled.set(true)
					tileRequestCount.addWaiter({ it == 0 }) {
						if (heatChange > 0) {
							onHeatChange?.invoke(maxHeat, heatChange)
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
		const val MIN_TILE_SIZE: Int = 256
		const val MAX_HEAT_ZOOM = MapController.MAX_ZOOM
	}
}