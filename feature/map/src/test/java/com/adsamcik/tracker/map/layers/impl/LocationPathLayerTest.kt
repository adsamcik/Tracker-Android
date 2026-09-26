package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationPathLayerTest {
    @Test fun isolatedFixesRemainPointsWithoutFabricatedConnectingLine() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layer = LocationPathLayer(segmentsProvider = { _, _ -> listOf(
            listOf(LatLngModel(50.0, 14.0)), listOf(LatLngModel(51.0, 15.0))) })
        layer.enable(context, 1f).join()
        val config = layer.lastConfig as MapLibreLayerConfig.Composite
        val line = config.layers.filterIsInstance<MapLibreLayerConfig.Line>().single()
        val points = config.layers.filterIsInstance<MapLibreLayerConfig.Circle>().single()
        assertTrue(!line.geoJson.contains("LineString"))
        assertEquals(2, Regex("\"type\":\"Point\"").findAll(points.geoJson).count())
        layer.disable()
    }
}
