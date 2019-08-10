package com.adsamcik.signalcollector.map.layer.logic

import android.content.Context
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.LocationDataDao
import com.adsamcik.signalcollector.common.database.dao.SessionDataDao
import com.adsamcik.signalcollector.common.style.ColorGenerator
import com.adsamcik.signalcollector.map.R
import com.adsamcik.signalcollector.map.layer.MapLayerData
import com.adsamcik.signalcollector.map.layer.MapLayerLogic
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

internal class LocationPolylineLogic : MapLayerLogic, CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	@Suppress("UNCHECKED_CAST")
	override val data: MapLayerData
		get() = MapLayerData(this::class as KClass<MapLayerLogic>, R.string.map_layer_location_polyline)
	override val supportsAutoUpdate: Boolean
		get() = false

	override var dateRange: LongRange = LongRange.EMPTY
		set(value) {
			field = value
			context?.let { update(it) }
		}

	override var quality: Float = 1f
		set(value) {
			field = value
			context?.let { update(it) }
		}

	override val availableRange: LongRange
		get() {
			val range = locationDao?.range()
			return if (range == null) {
				LongRange.EMPTY
			} else {
				LongRange(range.start, range.endInclusive)
			}
		}

	private var map: GoogleMap? = null
	private var context: Context? = null
	private var locationDao: LocationDataDao? = null
	private var sessionDao: SessionDataDao? = null
	private var activePolylines: MutableList<Polyline> = mutableListOf()

	override fun onEnable(context: Context, map: GoogleMap) {
		this.map = map
		AppDatabase.getDatabase(context).let { db ->
			this.locationDao = db.locationDao()
			this.sessionDao = db.sessionDao()
		}

		this.context = context
		update(context)
	}

	private fun clearActivePolylines() {
		activePolylines.forEach { it.remove() }
		activePolylines.clear()
	}

	override fun onDisable(map: GoogleMap) {
		clearActivePolylines()
		this.map = null
		this.locationDao = null
		this.sessionDao = null
		this.context = null
	}

	//todo color based on activity
	override fun update(context: Context) {
		clearActivePolylines()

		launch {
			val sessionDao = requireNotNull(sessionDao)
			val locationDao = requireNotNull(locationDao)

			val sessions = if (dateRange == LongRange.EMPTY) {
				sessionDao.getAll()
			} else {
				sessionDao.getAllBetween(dateRange.first, dateRange.last)
			}

			val colors = ColorGenerator.generateWithGolden(sessions.size)

			sessions.forEachIndexed { index, session ->
				val locations = locationDao.getAllBetween(session.start, session.end)
				val polylineOptions = PolylineOptions().apply {
					geodesic(true)
					addAll(locations.map { LatLng(it.latitude, it.longitude) })
					color(colors[index])
				}

				launch(Dispatchers.Main) {
					requireNotNull(map).let { map ->
						val polyline = map.addPolyline(polylineOptions)
						activePolylines.add(polyline)
					}
				}
			}
		}
	}
}