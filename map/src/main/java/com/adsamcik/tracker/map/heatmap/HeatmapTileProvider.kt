package com.adsamcik.tracker.map.heatmap

import android.graphics.Color
import com.adsamcik.tracker.common.misc.ConditionVariableInt
import com.adsamcik.tracker.common.misc.Int2
import com.adsamcik.tracker.commonmap.CoordinateBounds
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapData
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt


//todo refactor
internal class HeatmapTileProvider(private val tileCreator: HeatmapTileCreator,
                                   private var initMaxHeat: Float
) : TileProvider {
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

	private var maxHeat: Float = initMaxHeat

	var range: LongRange = LongRange.EMPTY
		set(value) {
			field = value
			heatmapCache.clear()
			resetMaxHeat()
		}

	private val colorScheme = HeatmapColorScheme.fromArray(
			listOf(Pair(0.1, Color.TRANSPARENT), Pair(0.3, Color.BLUE), Pair(0.7, Color.YELLOW), Pair(1.0, Color.RED)),
			100)

	init {
		resetMaxHeat()
	}

	fun updateQuality(quality: Float) {
		if (this.quality == quality) return

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
			maxHeat = initMaxHeat

			//todo make this smarter so it actually takes nearest neighbour
			maxHeat *= max(1f, 2f.pow(MAX_HEAT_ZOOM - lastZoom))
		}
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


		val leftX = MapFunctions.toLon(x.toDouble(), zoom)
		val topY = MapFunctions.toLat(y.toDouble(), zoom)

		val rightX = MapFunctions.toLon((x + 1).toDouble(), zoom)
		val bottomY = MapFunctions.toLat((y + 1).toDouble(), zoom)

		val area = CoordinateBounds(topY, rightX, bottomY, leftX)

		val key = Int2(x, y)
		val heatmap: HeatmapTile
		if (heatmapCache.containsKey(key)) {
			heatmap = requireNotNull(heatmapCache[key])
		} else {
			val range = range
			try {
				val config = tileCreator.createHeatmapConfig(heatmapSize, maxHeat)
				val data = HeatmapData(config, heatmapSize, x, y, zoom, area)

				heatmap = if (range == LongRange.EMPTY) {
					tileCreator.getHeatmap(data)
				} else {
					tileCreator.getHeatmap(data, range.first, range.last)
				}
			} catch (e: Exception) {
				e.printStackTrace()
				throw e
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
		private const val MIN_TILE_SIZE: Int = 256
		private const val MAX_HEAT_ZOOM = MapController.MAX_ZOOM
	}
}

