package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.SavedStateHandle
import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.SheetStateModel
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.map.shared.MapLegend
import com.adsamcik.tracker.map.shared.MapLegendValue
import com.adsamcik.tracker.map.shared.MapLayerInfo
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.async
import app.cash.turbine.test
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapStoreTest {

    private val mockLayerEngine: LayerEngine = mockk(relaxed = true)
    private val mockTrackerController: TrackerServiceController = mockk(relaxed = true)

    private lateinit var mapStore: MapStore
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { mockLayerEngine.activeLegend() } returns null
        every { mockLayerEngine.activeLayerConfig() } returns null
        every { mockLayerEngine.overlays() } returns persistentListOf()
        mapStore = MapStore(
            SavedStateHandle(),
            mockTrackerController,
            TestDispatchersProvider(testDispatcher),
        )
        mapStore.setLayerEngine(mockLayerEngine)
        io.mockk.clearMocks(mockLayerEngine, answers = false)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val initialState = mapStore.state.first()
        
        initialState.activeLayerIds shouldBe persistentSetOf("location_polyline")
        initialState.isFollowing shouldBe false
        initialState.sheet.visibility shouldBe SheetVisibility.Peek
        initialState.overlays shouldBe persistentListOf<com.adsamcik.tracker.map.presentation.udf.MapOverlayState>()
        initialState.legend shouldBe persistentListOf<MapLayerData>()
        initialState.quality shouldBe 1f
        initialState.dateRange shouldBe 0L..Long.MAX_VALUE
        // layerLoadingProgress may be non-zero due to default layer auto-apply
        initialState.layerConfig.shouldBeNull()
    }

    @Test
    fun `initializes selected trip context from navigation saved state`() = runTest {
        val tripStore = MapStore(
            SavedStateHandle(
                mapOf(
                    "tripId" to 42L,
                    "startMs" to 1_700_000_000_000L,
                    "endMs" to 1_700_000_900_000L,
                )
            ),
            mockTrackerController,
            TestDispatchersProvider(testDispatcher),
        )

        val state = tripStore.state.first()

        state.dateRange shouldBe 1_700_000_000_000L..1_700_000_900_000L
        state.selectedTripContext.shouldNotBeNull()
        state.selectedTripContext!!.tripId shouldBe 42L
        state.activeLayerIds shouldBe persistentSetOf("location_polyline")
    }

    @Test
    fun `selected trip context opens route layer even when another layer was previously selected`() = runTest {
        val tripStore = MapStore(
            SavedStateHandle(
                mapOf(
                    "selected_layer_id" to "speed_heatmap",
                    "tripId" to 42L,
                    "startMs" to 1_700_000_000_000L,
                    "endMs" to 1_700_000_900_000L,
                )
            ),
            mockTrackerController,
            TestDispatchersProvider(testDispatcher),
        )

        tripStore.state.first().activeLayerIds shouldBe persistentSetOf("location_polyline")
    }

    @Test
    fun `sheet show and hide events work correctly`() = runTest {
        // Hide sheet
        mapStore.dispatch(MapEvent.HideSheet)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val hiddenState = mapStore.state.first()
        hiddenState.sheet.visibility shouldBe SheetVisibility.Hidden

        // Show sheet
        mapStore.dispatch(MapEvent.ShowSheet)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val visibleState = mapStore.state.first()
        visibleState.sheet.visibility shouldBe SheetVisibility.Expanded
    }

    @Test
    fun `toggle follow changes state correctly`() = runTest {
        // Initially not following
        mapStore.state.first().isFollowing shouldBe false

        // Toggle on
        mapStore.dispatch(MapEvent.ToggleFollow)
        testDispatcher.scheduler.advanceUntilIdle()
        
        mapStore.state.first().isFollowing shouldBe true

        // Toggle off
        mapStore.dispatch(MapEvent.ToggleFollow)
        testDispatcher.scheduler.advanceUntilIdle()
        
        mapStore.state.first().isFollowing shouldBe false
    }

    @Test
    fun `follow canceled sets following to false`() = runTest {
        // First set following to true
        mapStore.dispatch(MapEvent.ToggleFollow)
        testDispatcher.scheduler.advanceUntilIdle()
        mapStore.state.first().isFollowing shouldBe true

        // Cancel follow
        mapStore.dispatch(MapEvent.FollowCanceled)
        testDispatcher.scheduler.advanceUntilIdle()
        
        mapStore.state.first().isFollowing shouldBe false
    }

    @Test
    fun `layer selection updates active layer ids`() = runTest {
        val layerId = "test_layer"
        
        mapStore.dispatch(MapEvent.SelectLayer(layerId))
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        state.activeLayerIds.contains(layerId) shouldBe true
        coVerify { mockLayerEngine.selectLayers(eq(setOf(layerId)), eq(1f), any(), any(), any()) }
    }

    @Test
    fun `layer selection is single-choice and no overlay clears layers`() = runTest {
        val firstLayerId = "test_layer"
        val secondLayerId = "other_layer"

        mapStore.dispatch(MapEvent.SelectLayer(firstLayerId))
        testDispatcher.scheduler.advanceUntilIdle()
        mapStore.dispatch(MapEvent.SelectLayer(secondLayerId))
        testDispatcher.scheduler.advanceUntilIdle()

        var state = mapStore.state.first()
        state.activeLayerIds shouldBe persistentSetOf(secondLayerId)

        mapStore.dispatch(MapEvent.SelectLayer("none"))
        testDispatcher.scheduler.advanceUntilIdle()

        state = mapStore.state.first()
        state.activeLayerIds shouldBe persistentSetOf()
    }

    @Test
    fun `quality update triggers layer reapplication`() = runTest {
        val newQuality = 0.5f
        
        mapStore.dispatch(MapEvent.SetQuality(newQuality))
        testDispatcher.scheduler.advanceUntilIdle()
        // Wait a bit for withContext(Dispatchers.Default) work to complete (race condition workaround)
        kotlinx.coroutines.delay(50)
        
        val state = mapStore.state.first()
        state.quality shouldBe newQuality
        coVerify { mockLayerEngine.selectLayers(eq(setOf("location_polyline")), eq(newQuality), any(), any(), any()) }
    }

    @Test
    fun `date range update triggers layer reapplication`() = runTest {
        io.mockk.clearMocks(mockLayerEngine, answers = false)
        val newRange = 1000L..2000L
        
        mapStore.dispatch(MapEvent.SetDateRange(newRange))
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        state.dateRange shouldBe newRange
        coVerify { mockLayerEngine.selectLayers(eq(setOf("location_polyline")), eq(1f), eq(newRange), any(), any()) }
    }

    @Test
    fun `date range update clears selected trip context`() = runTest {
        val tripStore = MapStore(
            SavedStateHandle(
                mapOf(
                    "tripId" to 42L,
                    "startMs" to 1_700_000_000_000L,
                    "endMs" to 1_700_000_900_000L,
                )
            ),
            mockTrackerController,
            TestDispatchersProvider(testDispatcher),
        )
        tripStore.setLayerEngine(mockLayerEngine)

        tripStore.dispatch(MapEvent.SetDateRange(100L..200L))
        testDispatcher.scheduler.advanceUntilIdle()

        tripStore.state.first().selectedTripContext.shouldBeNull()
    }

    @Test
    fun `active legend preserves per-stop labels`() = runTest {
        every { mockLayerEngine.activeLegend() } returns MapLayerData(
            info = MapLayerInfo("CellHeatmapLayer", com.adsamcik.tracker.map.R.string.map_layer_cell_heatmap_title),
            colorList = emptyList(),
            legend = MapLegend(
                valueList = listOf(
                    MapLegendValue(com.adsamcik.tracker.map.R.string.map_layer_cell_signal_weak, 0xFF440154.toInt()),
                    MapLegendValue(com.adsamcik.tracker.map.R.string.map_layer_cell_signal_excellent, 0xFFFDE725.toInt()),
                )
            )
        )

        mapStore.dispatch(MapEvent.SelectLayer("cell_heatmap"))
        testDispatcher.scheduler.advanceUntilIdle()

        val legend = mapStore.state.first().legend
        legend shouldHaveSize 2
        legend[0].labelRes shouldBe com.adsamcik.tracker.map.R.string.map_layer_cell_signal_weak
        legend[1].labelRes shouldBe com.adsamcik.tracker.map.R.string.map_layer_cell_signal_excellent
    }

    @Test
    fun `user location update adds markers and accuracy circle`() = runTest {
        val latLng = LatLngModel(37.7749, -122.4194) // San Francisco
        val accuracy = 10.0
        
        mapStore.dispatch(MapEvent.SetUserLocation(latLng, accuracy))
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        state.overlays shouldHaveSize 2 // UserMarker + AccuracyCircle
        
        val userMarker = state.overlays.find { it is com.adsamcik.tracker.map.presentation.udf.MapOverlayState.UserMarker }
        val accuracyCircle = state.overlays.find { it is com.adsamcik.tracker.map.presentation.udf.MapOverlayState.AccuracyCircle }
        
        userMarker.shouldNotBeNull()
        accuracyCircle.shouldNotBeNull()
    }

    @Test
    fun `bearing update only affects user marker when following`() = runTest {
        val latLng = LatLngModel(37.7749, -122.4194)
        val bearing = 45f
        
        // Add user location first
        mapStore.dispatch(MapEvent.SetUserLocation(latLng, 10.0))
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Set bearing without following - should not affect marker
        mapStore.dispatch(MapEvent.SetBearing(bearing))
        testDispatcher.scheduler.advanceUntilIdle()
        
        var state = mapStore.state.first()
        val userMarker = state.overlays.find { 
            it is com.adsamcik.tracker.map.presentation.udf.MapOverlayState.UserMarker 
        } as? com.adsamcik.tracker.map.presentation.udf.MapOverlayState.UserMarker
        
        userMarker?.bearing.shouldBeNull() // Should be null when not following
        
        // Enable following
        mapStore.dispatch(MapEvent.ToggleFollow)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Set bearing again
        mapStore.dispatch(MapEvent.SetBearing(bearing))
        testDispatcher.scheduler.advanceUntilIdle()
        
        state = mapStore.state.first()
        val followingUserMarker = state.overlays.find { 
            it is com.adsamcik.tracker.map.presentation.udf.MapOverlayState.UserMarker 
        } as? com.adsamcik.tracker.map.presentation.udf.MapOverlayState.UserMarker
        
        followingUserMarker?.bearing shouldBe bearing // Should match when following
    }

    @Test
    fun `camera moved event updates state`() = runTest {
        val cameraModel = com.adsamcik.tracker.map.presentation.udf.CameraModel(
            lat = 37.7749,
            lng = -122.4194,
            zoom = 15f,
            tilt = 30f,
            bearing = 45f
        )
        
        mapStore.dispatch(MapEvent.CameraMoved(cameraModel, byGesture = true))
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        state.camera shouldBe cameraModel
    }

    @Test
    fun `setQuality helper method works`() = runTest {
        val quality = 0.75f
        
        mapStore.setQuality(quality)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        state.quality shouldBe quality
    }

    @Test
    fun `setDateRange helper method works`() = runTest {
        val range = 500L..1500L
        
        mapStore.setDateRange(range)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        state.dateRange shouldBe range
    }

    @Test
    fun `update search query updates state`() = runTest {
        mapStore.dispatch(MapEvent.UpdateSearchQuery("Berlin"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = mapStore.state.first()
        state.search.query shouldBe "Berlin"
    }

    @Test
    fun `submit search emits format hint for non-coordinate query`() = runTest {
        mapStore.dispatch(MapEvent.UpdateSearchQuery("Prague"))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.effects.test {
            mapStore.dispatch(MapEvent.SubmitSearch)
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.ShowSearchFormatHint) shouldBe true
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit coordinate search emits CenterCamera and adds search marker`() = runTest {
        mapStore.dispatch(MapEvent.UpdateSearchQuery("40.7590, -73.9855"))
        testDispatcher.scheduler.advanceUntilIdle()
        mapStore.effects.test {
            mapStore.dispatch(MapEvent.SubmitSearch)
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera) shouldBe true
            val state = mapStore.state.first()
            state.overlays.any { it is com.adsamcik.tracker.map.presentation.udf.MapOverlayState.SearchMarker } shouldBe true
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit search accepts whitespace separated decimal coordinates`() = runTest {
        assertSuccessfulSearch(
            query = "48.8566 2.3522",
            expectedLat = 48.8566,
            expectedLng = 2.3522
        )
    }

    @Test
    fun `submit search accepts hemisphere prefixed decimal coordinates`() = runTest {
        assertSuccessfulSearch(
            query = "N48.8566 E2.3522",
            expectedLat = 48.8566,
            expectedLng = 2.3522
        )
    }

    @Test
    fun `submit search accepts symbol separated dms coordinates`() = runTest {
        assertSuccessfulSearch(
            query = """48°51'24.0"N 2°21'07.9"E""",
            expectedLat = 48.8566667,
            expectedLng = 2.3521944
        )
    }

    @Test
    fun `submit search accepts space separated dms coordinates`() = runTest {
        assertSuccessfulSearch(
            query = "48 51 24.0 N 2 21 07.9 E",
            expectedLat = 48.8566667,
            expectedLng = 2.3521944
        )
    }

    @Test
    fun `submit search accepts negative whitespace separated coordinates`() = runTest {
        assertSuccessfulSearch(
            query = "-33.8688 151.2093",
            expectedLat = -33.8688,
            expectedLng = 151.2093
        )
    }

    private suspend fun assertSuccessfulSearch(
        query: String,
        expectedLat: Double,
        expectedLng: Double
    ) {
        mapStore.dispatch(MapEvent.UpdateSearchQuery(query))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.effects.test {
            mapStore.dispatch(MapEvent.SubmitSearch)
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera) shouldBe true

            val state = mapStore.state.first()
            val searchMarker = state.overlays
                .filterIsInstance<com.adsamcik.tracker.map.presentation.udf.MapOverlayState.SearchMarker>()
                .single()

            searchMarker.latLng.lat shouldBe (expectedLat plusOrMinus 0.000001)
            searchMarker.latLng.lng shouldBe (expectedLng plusOrMinus 0.000001)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
