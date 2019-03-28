package com.adsamcik.signalcollector.map

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseMapMaxHeat
import com.adsamcik.signalcollector.extensions.lock
import com.adsamcik.signalcollector.extensions.toCalendar
import com.adsamcik.signalcollector.map.heatmap.HeatmapStamp
import com.adsamcik.signalcollector.map.heatmap.HeatmapTile
import com.adsamcik.signalcollector.map.heatmap.providers.MapTileHeatmapProvider
import com.adsamcik.signalcollector.utility.CoordinateBounds
import com.adsamcik.signalcollector.utility.Int2
import com.adsamcik.signalcollector.utility.Preferences
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt


class LocationTileProvider(context: Context) : TileProvider {
	var heatmapProvider: MapTileHeatmapProvider? = null
		set(value) {
			heatmapCache.clear()
			field = value
		}

	private val heatmapCache = mutableMapOf<Int2, HeatmapTile>()

	private val heatDao = AppDatabase.getAppDatabase(context).mapHeatDao()

	private var heat = DatabaseMapMaxHeat(Int.MIN_VALUE, 0f)
	private val heatMutex = ReentrantLock()

	var heatChange = 0f
		private set

	private var lastZoom = Int.MIN_VALUE

	private val heatmapSize: Int
	private val stamp: HeatmapStamp

	init {
		val resources = context.resources
		val pref = Preferences.getPref(context)
		val scale = pref.getFloat(resources.getString(R.string.settings_map_quality_key), resources.getString(R.string.settings_map_quality_default).toFloat())
		heatmapSize = (scale * HeatmapTile.BASE_HEATMAP_SIZE).roundToInt()
		val stampRadius = HeatmapStamp.calculateOptimalRadius(heatmapSize)
		stamp = HeatmapStamp.generateNonlinear(stampRadius) { it.pow(2f) }
	}

	var range: ClosedRange<Date>? = null
		set(value) {
			field = if (value != null) {
				val endCal = value.endInclusive.toCalendar()
				endCal.add(Calendar.DAY_OF_MONTH, 1)
				value.start..endCal.time
			} else
				null
			heatmapCache.clear()
		}

	fun synchronizeMaxHeat() {
		heatMutex.lock {
			heatmapCache.forEach {
				it.value.heatmap.maxHeat = heat.maxHeat
			}
			heatChange = 0f
		}
	}

	override fun getTile(x: Int, y: Int, zoom: Int): Tile {
		//Ensure that everything is up to date. It's fine to lock every time, since it is called only handful of times at once.
		heatMutex.lock {
			if (lastZoom != zoom) {
				heatmapCache.clear()
				lastZoom = zoom

				heat = heatDao.getSingle(zoom) ?: DatabaseMapMaxHeat(zoom, MIN_HEAT)
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
				heatmapProvider.getHeatmap(heatmapSize, stamp, x, y, zoom, area, heat.maxHeat)
			else
				heatmapProvider.getHeatmap(heatmapSize, stamp, range.start.time, range.endInclusive.time, x, y, zoom, area, heat.maxHeat)
			heatmapCache[key] = heatmap
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

		return Tile(heatmapSize, heatmapSize, heatmap.toByteArray())
	}

	companion object {
		const val MIN_HEAT: Float = 3f
	}
}