package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.graphics.PolylineOptimizer
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/** Polyline layer drawing user paths with decimation. */
class LocationPathLayer(
    private val pointsProvider: suspend (LongRange) -> List<LatLng>,
    private val perf: PerformanceManager = PerformanceManager()
) : BaseMapLayer<LocationPathLayer.Input, LocationPathLayer.Prepared>(), SupportsDateRange {

    data class Input(val points: List<LatLng>)
    data class Prepared(val options: List<PolylineOptions>)

    private val polylines = mutableListOf<Polyline>()
    override var dateRange: LongRange = LongRange(0, Long.MAX_VALUE)

    override fun beforeEnable(context: Context, map: GoogleMap) {}

    override fun loadData(context: Context): Input {
        // Blocking fetch; adapters can supply a provider using DAOs.
        val points = kotlinx.coroutines.runBlocking { pointsProvider(dateRange) }
        return Input(points)
    }

    override fun processData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared {
        if (input.points.isEmpty()) return Prepared(emptyList())
        val simplified = PolylineOptimizer.optimize(
            input.points,
            toleranceMeters = budgets.decimationThreshold.toDouble(),
            maxPoints = budgets.maxPolylinePoints
        )
        val options = listOf(PolylineOptions().addAll(simplified).geodesic(true))
        return Prepared(options)
    }

    override fun render(map: GoogleMap, processed: Prepared) {
        polylines.forEach { it.remove() }
        polylines.clear()
        processed.options.forEach { opt -> polylines += map.addPolyline(opt) }
    }

    override fun onDisable(map: GoogleMap) {
        polylines.forEach { it.remove() }
        polylines.clear()
    }
}
