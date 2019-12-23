package com.adsamcik.tracker.map.layer.logic

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.utils.style.utility.ColorGenerator
import com.adsamcik.tracker.commonmap.MapLayerData
import com.adsamcik.tracker.commonmap.MapLayerInfo
import com.adsamcik.tracker.commonmap.MapLayerLogic
import com.adsamcik.tracker.commonmap.MapLegend
import com.adsamcik.tracker.map.R
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

internal class LocationPolylineLogic : MapLayerLogic, CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	override val supportsAutoUpdate: Boolean
		get() = false

	override val tileCountInGeneration: LiveData<Int> = MutableLiveData()

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

	override val layerInfo: MapLayerInfo = MapLayerInfo(
			this::class.java,
			R.string.map_layer_location_polyline
	)

	override fun colorList(): List<Int> = emptyList()

	override fun layerData(): MapLayerData {
		return MapLayerData(
				info = layerInfo,
				colorList = colorList(),
				legend = MapLegend(R.string.map_layer_location_heatmap_description)
		)
	}


	override fun onEnable(
			context: Context,
			map: GoogleMap,
			quality: Float
	) {
		this.map = map
		AppDatabase.database(context).let { db ->
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

	override fun update(context: Context) {
		clearActivePolylines()

		launch {
			val sessionDao = sessionDao ?: return@launch
			val locationDao = locationDao ?: return@launch

			val sessions = if (dateRange == LongRange.EMPTY) {
				sessionDao.getAll()
			} else {
				sessionDao.getAllBetween(dateRange.first, dateRange.last)
			}.sortedBy { it.start }

			val colors = ColorGenerator.generateWithGolden(sessions.size)

			sessions.forEachIndexed { index, session ->
				val locations = locationDao.getAllBetween(session.start, session.end)
				val polylineOptions = PolylineOptions().apply {
					geodesic(true)
					addAll(locations.map { LatLng(it.latitude, it.longitude) })
					color(colors[index])
				}

				launch(Dispatchers.Main) {
					map?.let { map ->
						val polyline = map.addPolyline(polylineOptions)
						activePolylines.add(polyline)
					}
				}
			}
		}
	}
}
