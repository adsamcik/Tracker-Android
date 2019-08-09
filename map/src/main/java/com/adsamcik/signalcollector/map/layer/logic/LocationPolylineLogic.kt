package com.adsamcik.signalcollector.map.layer.logic

import android.content.Context
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.LocationDataDao
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

	private var map: GoogleMap? = null
	private var context: Context? = null
	private var dao: LocationDataDao? = null
	private var activePolyline: Polyline? = null

	override fun onEnable(context: Context, map: GoogleMap) {
		this.map = map
		this.dao = AppDatabase.getDatabase(context).locationDao()
		this.context = context
		update(context)
	}

	override fun onDisable(map: GoogleMap) {
		this.map = null
		this.dao = null
		this.context = null
		activePolyline?.remove()
	}

	//todo color based on activity
	override fun update(context: Context) {
		launch {
			val locationDao = requireNotNull(dao)
			val data = if (dateRange == LongRange.EMPTY) {
				locationDao.getAll()
			} else {
				locationDao.getAllBetween(dateRange.first, dateRange.last)
			}

			val options = PolylineOptions().apply {
				geodesic(true)
				addAll(data.map { LatLng(it.latitude, it.longitude) })
			}

			launch(Dispatchers.Main) {
				activePolyline?.remove()
				requireNotNull(map).apply {
					activePolyline = addPolyline(options)
				}
			}
		}
	}

}