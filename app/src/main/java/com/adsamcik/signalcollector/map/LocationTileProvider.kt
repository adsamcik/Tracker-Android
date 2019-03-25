package com.adsamcik.signalcollector.map

import android.content.Context
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseMapMaxHeat
import com.adsamcik.signalcollector.extensions.lock
import com.adsamcik.signalcollector.utility.CoordinateBounds
import com.adsamcik.signalcollector.utility.Int2
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil


class LocationTileProvider(context: Context) : TileProvider {
	var colorProvider: MapTileColorProvider? = null

	private val map = mutableMapOf<Int2, HeatmapTile>()

	private val heatDao = AppDatabase.getAppDatabase(context).mapHeatDao()

	private var heat = DatabaseMapMaxHeat(Int.MIN_VALUE, 0f)
	private val heatMutex = ReentrantLock()

	var heatChange = 0f
		private set

	private var lastZoom = Int.MIN_VALUE

	init {
		GlobalScope.launch {
			heatDao.clear()
		}
	}

	fun synchronizeMaxHeat() {
		heatMutex.lock {
			map.forEach {
				it.value.heatmap.maxHeat = heat.maxHeat
			}
			heatChange = 0f
		}
	}

	override fun getTile(x: Int, y: Int, zoom: Int): Tile {
		//Ensure that everything is up to date. It's fine to lock every time, since it is called only handful of times at once.
		heatMutex.lock {
			if (lastZoom != zoom) {
				map.clear()
				lastZoom = zoom

				heat = heatDao.getSingle(zoom) ?: DatabaseMapMaxHeat(zoom, MIN_HEAT)
			}
		}

		val colorProvider = colorProvider!!


		val leftX = MapFunctions.toLon(x.toDouble(), zoom)
		val topY = MapFunctions.toLat(y.toDouble(), zoom)

		val rightX = MapFunctions.toLon((x + 1).toDouble(), zoom)
		val bottomY = MapFunctions.toLat((y + 1).toDouble(), zoom)

		val area = CoordinateBounds(topY, rightX, bottomY, leftX)

		val key = Int2(x, y)
		val heatmap: HeatmapTile
		if (map.containsKey(key)) {
			heatmap = map[key]!!
		} else {
			heatmap = colorProvider.getHeatmap(x, y, zoom, area, heat.maxHeat)
			map[key] = heatmap
		}

		heatMutex.lock {
			if (heat.maxHeat < heatmap.maxHeat) {
				//round to next whole number to avoid frequent calls
				val newHeat = ceil(heatmap.maxHeat)
				heatChange += newHeat - heat.maxHeat
				heat.maxHeat = heatmap.maxHeat
				heatDao.insert(heat)
			}
		}

		return Tile(IMAGE_SIZE, IMAGE_SIZE, heatmap.toByteArray(IMAGE_SIZE))
	}

	companion object {
		const val IMAGE_SIZE: Int = 256
		const val MIN_HEAT: Float = 5f
	}
}