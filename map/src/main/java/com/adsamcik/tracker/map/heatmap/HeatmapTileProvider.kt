package com.adsamcik.tracker.map.heatmap

import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileCreator
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.shared.base.extension.LocationExtensions
import com.adsamcik.tracker.shared.base.misc.ConditionVariableInt
import com.adsamcik.tracker.shared.base.misc.Int2
import com.adsamcik.tracker.shared.map.CoordinateBounds
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.lang.Throwable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt


//todo refactor
internal class HeatmapTileProvider(
		private val tileCreator: HeatmapTileCreator,
		private var dataUser: UserHeatmapData
) : TileProvider {
	private val heatmapCache = mutableMapOf<Int2, HeatmapTile>()

	private val heatLock = ReentrantLock()
	private val heatUpdateScheduled = AtomicBoolean(false)

	private val tileRequestCount: ConditionVariableInt = ConditionVariableInt(0)

	var tileRequestCountListener: ((Int) -> Unit)? = null

	private var heatChange: Float = 0f

	var onHeatChange: ((currentHeat: Float, heatChange: Float) -> Unit)? = null

	private var lastZoom = Int.MIN_VALUE

	var quality: Float = Float.MIN_VALUE
		private set

	private var heatmapSize: Int = 0

	private var maxHeat: Float = dataUser.maxHeat

	private var config: HeatmapConfig? = null
	private var stamp: HeatmapStamp? = null

	var range: LongRange = LongRange.EMPTY
		set(value) {
			field = value
			heatmapCache.clear()
			resetMaxHeat()
		}

	init {
		updateQuality(dataUser.quality)
		resetMaxHeat()
	}

	fun updateQuality(quality: Float) {
		if (this.quality == quality) return

		this.quality = quality
		heatmapSize = (quality * HeatmapTile.BASE_HEATMAP_SIZE).roundToInt()

		config = tileCreator.createHeatmapConfig(dataUser)

		if (lastZoom > 0) {
			reinitializeHeatmapData(lastZoom)
		}

		heatmapCache.clear()
	}

	fun synchronizeMaxHeat() {
		heatLock.withLock {
			heatmapCache.forEach {
				it.value.maxHeat = maxHeat
			}
			heatChange = 0f
		}
	}

	private fun resetMaxHeat() {
		heatLock.withLock {
			maxHeat = dataUser.maxHeat

			// todo make this smarter so it actually takes nearest neighbour
			maxHeat *= max(1f, 2f.pow(MAX_HEAT_ZOOM - lastZoom))
		}
	}

	private fun reinitializeHeatmapData(zoom: Int) {
		val pixelSize = LocationExtensions.EARTH_CIRCUMFERENCE.toDouble() /
				MapFunctions.getTileCount(zoom).toDouble() /
				heatmapSize.toDouble()

		stamp = tileCreator.generateStamp(heatmapSize, zoom, pixelSize.toFloat())
	}

	private fun onZoomChanged(zoom: Int) {
		heatLock.withLock {
			if (lastZoom != zoom) {
				heatmapCache.clear()

				reinitializeHeatmapData(zoom)
				resetMaxHeat()

				lastZoom = zoom
			}
		}
	}

	private fun updateHeat(heatmap: HeatmapTile, zoom: Int) {
		heatLock.withLock {
			if (maxHeat < heatmap.maxHeat && zoom == lastZoom) {
				// round to next whole number to avoid frequent calls
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
			}
		}
	}

	override fun getTile(x: Int, y: Int, zoom: Int): Tile {
		val beforeCount = tileRequestCount.incrementAndGet()
		tileRequestCountListener?.invoke(beforeCount)
		// Ensure that everything is up to date. It's fine to lock every time,
		// since it is called only handful of times at once.

		try {
			if (lastZoom != zoom) {
				onZoomChanged(zoom)
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
				val config = requireNotNull(config)
				val stamp = requireNotNull(stamp)
				val tileData = HeatmapTileData(config, stamp, heatmapSize, x, y, zoom, area)

				var genHeatmap: HeatmapTile? = null
				var lastException: kotlin.Throwable? = null
				for (i in 1..3) {
					try {
						genHeatmap = if (range == LongRange.EMPTY) {
							tileCreator.getHeatmap(tileData)
						} else {
							tileCreator.getHeatmap(tileData, range.first, range.last)
						}
						break;
					} catch (e: OutOfMemoryError) {
						lastException = e
						System.gc()
						Thread.sleep(1000)
					}
				}

				if(genHeatmap == null) {
					throw lastException!!
				}

				heatmapCache[key] = genHeatmap
				heatmap = genHeatmap
			}

			updateHeat(heatmap, zoom)

			return Tile(
					heatmapSize, heatmapSize,
					heatmap.toByteArray(max(MIN_TILE_SIZE, heatmapSize))
			)
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			Log.e(TAG, e.localizedMessage, e)
			Reporter.report(e)
			return Tile(0, 0, byteArrayOf())
		} finally {
			val afterCount = tileRequestCount.decrementAndGet()
			tileRequestCountListener?.invoke(afterCount)
		}
	}

	companion object {
		private const val MAX_HEAT_ZOOM = MapController.MAX_ZOOM

		private const val MIN_TILE_SIZE: Int = 256

		private const val TAG: String = "AdventionTile"
	}
}

