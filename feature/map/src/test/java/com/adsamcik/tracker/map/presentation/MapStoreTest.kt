package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.bridge.SpeedSummary
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.SearchResultStatus
import com.adsamcik.tracker.geocoder.GeocodedPlace
import com.adsamcik.tracker.geocoder.PlaceSearchResult
import com.adsamcik.tracker.map.presentation.udf.SheetStateModel
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.map.shared.MapLegend
import com.adsamcik.tracker.map.shared.MapLegendValue
import com.adsamcik.tracker.map.shared.MapLayerInfo
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.geocoder.ReverseGeocoder
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.testing.fake.FakeMapSettingsRepository
import com.adsamcik.tracker.testing.fake.FakeOnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsState
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import app.cash.turbine.test
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class MapStoreTest {

    private val mockLayerEngine: LayerEngine = mockk(relaxed = true)
    private val mockTrackerController: TrackerStateReader = mockk(relaxed = true)
    private val mockReverseGeocoder: ReverseGeocoder = mockk(relaxed = true)
    private val mockMapImageShareHelper: com.adsamcik.tracker.map.export.MapImageShareHelper = mockk(relaxed = true)
    private val mockMapDataChangeObserver: MapDataChangeObserver = mockk()

    private lateinit var mapStore: MapStore
    private val testDispatcher = StandardTestDispatcher()
    private val serviceRunningFlow = MutableStateFlow(false)
    private val mapDataChanges = MutableSharedFlow<MapDataChange>(extraBufferCapacity = 16)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { mockLayerEngine.activeLegend() } returns null
        every { mockLayerEngine.activeLayerConfig() } returns null
        every { mockLayerEngine.overlays() } returns persistentListOf()
        every { mockTrackerController.isServiceRunningFlow } returns serviceRunningFlow
        every { mockMapDataChangeObserver.changes() } returns flow {
            emit(MapDataChange.Ready)
            emitAll(mapDataChanges)
        }
        mapStore = MapStore(
            SavedStateHandle(),
            mockTrackerController,
            TestDispatchersProvider(testDispatcher),
            FakeOnlineMapTilesRepository(),
            FakeMapSettingsRepository(),
            mockReverseGeocoder,
            mockMapImageShareHelper,
            mockMapDataChangeObserver,
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
            FakeOnlineMapTilesRepository(),
            FakeMapSettingsRepository(),
            mockReverseGeocoder,
            mockMapImageShareHelper,
            mockMapDataChangeObserver,
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
            FakeOnlineMapTilesRepository(),
            FakeMapSettingsRepository(),
            mockReverseGeocoder,
            mockMapImageShareHelper,
            mockMapDataChangeObserver,
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
            FakeOnlineMapTilesRepository(),
            FakeMapSettingsRepository(),
            mockReverseGeocoder,
            mockMapImageShareHelper,
            mockMapDataChangeObserver,
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "location_heatmap",
            "cell_heatmap",
            "signal_coverage",
            "signal_aurora",
            "wifi_heatmap",
            "wifi_count_heatmap",
            "speed_heatmap",
            "legacy_heatmap",
            "life_terrain",
            "ski_xray",
            "trip_constellations",
            "seasonal_palimpsest",
            "fog_of_wonder",
            "first_contact",
        ],
    )
    fun `camera moved for bounds-sensitive layer refreshes in place`(layerId: String) = runTest {
        mapStore.dispatch(MapEvent.SelectLayer(layerId))
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(mockLayerEngine, answers = false)
        val cameraModel = com.adsamcik.tracker.map.presentation.udf.CameraModel(
            lat = 37.7749,
            lng = -122.4194,
            zoom = 15f,
            tilt = 0f,
            bearing = 0f
        )

        mapStore.dispatch(MapEvent.CameraMoved(cameraModel, byGesture = true))
        testDispatcher.scheduler.advanceTimeBy(499)
        coVerify(exactly = 0) { mockLayerEngine.refreshLayersInPlace(any(), any(), any(), any()) }

        testDispatcher.scheduler.advanceTimeBy(1)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockLayerEngine.selectLayers(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { mockLayerEngine.refreshLayersInPlace(any(), eq(15f), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cell_heatmap", "signal_coverage", "signal_aurora"])
    fun `cell sample layers participate in live reactive refreshes`(layerId: String) {
        (layerId in MapStore.LIVE_REACTIVE_LAYER_IDS) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["wifi_heatmap", "wifi_count_heatmap"])
    fun `wifi sample layers participate in live reactive refreshes`(layerId: String) {
        (layerId in MapStore.LIVE_REACTIVE_LAYER_IDS) shouldBe true
    }

    @Test
    fun `database observer registration does not trigger a redundant refresh`() = runTest {
        testDispatcher.scheduler.advanceTimeBy(1_500)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            mockLayerEngine.refreshLayersInPlace(any(), any(), any(), any())
        }
    }

    @Test
    fun `committed stationary location sample refreshes the active location layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(mockLayerEngine, answers = false)

        mapDataChanges.emit(MapDataChange.Committed(setOf(MapDataSource.Location)))
        testDispatcher.scheduler.advanceTimeBy(1_499)
        coVerify(exactly = 0) { mockLayerEngine.refreshLayersInPlace(any(), any(), any(), any()) }

        testDispatcher.scheduler.advanceTimeBy(1)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockLayerEngine.refreshLayersInPlace(any(), any(), any(), eq(true))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cell_heatmap", "signal_coverage", "signal_aurora"])
    fun `committed cell sample refreshes the active cell layer`(layerId: String) = runTest {
        mapStore.dispatch(MapEvent.SelectLayer(layerId))
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(mockLayerEngine, answers = false)

        mapDataChanges.emit(MapDataChange.Committed(setOf(MapDataSource.Cell)))
        testDispatcher.scheduler.advanceTimeBy(1_500)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockLayerEngine.refreshLayersInPlace(any(), any(), any(), eq(true))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["wifi_heatmap", "wifi_count_heatmap"])
    fun `committed wifi sample refreshes the active wifi layer`(layerId: String) = runTest {
        mapStore.dispatch(MapEvent.SelectLayer(layerId))
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(mockLayerEngine, answers = false)

        mapDataChanges.emit(MapDataChange.Committed(setOf(MapDataSource.Wifi)))
        testDispatcher.scheduler.advanceTimeBy(1_500)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockLayerEngine.refreshLayersInPlace(any(), any(), any(), eq(true))
        }
    }

    @Test
    fun `cell-only commit does not refresh the active location layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(mockLayerEngine, answers = false)

        mapDataChanges.emit(MapDataChange.Committed(setOf(MapDataSource.Cell)))
        testDispatcher.scheduler.advanceTimeBy(1_500)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            mockLayerEngine.refreshLayersInPlace(any(), any(), any(), any())
        }
    }

    @Test
    fun `rapid committed samples are debounced into one live refresh`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(mockLayerEngine, answers = false)

        repeat(3) {
            mapDataChanges.emit(MapDataChange.Committed(setOf(MapDataSource.Location)))
            testDispatcher.scheduler.advanceTimeBy(400)
        }
        testDispatcher.scheduler.advanceTimeBy(1_500)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockLayerEngine.refreshLayersInPlace(any(), any(), any(), eq(true))
        }
    }

    @Test
    fun `pending live refresh cannot cancel a newer layer selection`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(mockLayerEngine, answers = false)

        mapDataChanges.emit(MapDataChange.Committed(setOf(MapDataSource.Location)))
        testDispatcher.scheduler.advanceTimeBy(1_000)
        mapStore.dispatch(MapEvent.SelectLayer("cell_heatmap"))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockLayerEngine.selectLayers(eq(setOf("cell_heatmap")), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            mockLayerEngine.refreshLayersInPlace(any(), any(), any(), any())
        }
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
    fun `quality from map settings repository drives state`() = runTest {
        val settingsRepo = FakeMapSettingsRepository(MapSettingsState(quality = 2f))
        val store = MapStore(
            SavedStateHandle(),
            mockTrackerController,
            TestDispatchersProvider(testDispatcher),
            FakeOnlineMapTilesRepository(),
            settingsRepo,
            mockReverseGeocoder,
            mockMapImageShareHelper,
            mockMapDataChangeObserver,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        store.state.first().quality shouldBe 2f

        settingsRepo.setQuality(0.5f)
        testDispatcher.scheduler.advanceUntilIdle()

        store.state.first().quality shouldBe 0.5f
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

    @Test
    fun `zoom in emits positive ZoomBy effect`() = runTest {
        mapStore.effects.test {
            mapStore.dispatch(MapEvent.ZoomIn)
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.ZoomBy) shouldBe true
            (effect as com.adsamcik.tracker.map.presentation.udf.MapEffect.ZoomBy).delta shouldBe 1f
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `zoom out emits negative ZoomBy effect`() = runTest {
        mapStore.effects.test {
            mapStore.dispatch(MapEvent.ZoomOut)
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.ZoomBy) shouldBe true
            (effect as com.adsamcik.tracker.map.presentation.udf.MapEffect.ZoomBy).delta shouldBe -1f
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reverse geocode tap shows place callout with resolved name`() = runTest {
        coEvery { mockReverseGeocoder.reverseGeocode(50.0, 14.0) } returns GeocodedPlace(
            displayName = "Vinohradská, Prague",
            street = "Vinohradská",
            locality = "Prague",
            countryCode = "CZ",
            latitude = 50.0,
            longitude = 14.0,
        )

        mapStore.dispatch(MapEvent.ReverseGeocodeAt(50.0, 14.0))
        testDispatcher.scheduler.advanceUntilIdle()

        val callout = mapStore.state.first().placeCallout
        callout.shouldNotBeNull()
        callout!!.isLoading shouldBe false
        callout.hasResult shouldBe true
        callout.title shouldBe "Vinohradská, Prague"
        callout.subtitle shouldBe "CZ"
    }

    @Test
    fun `reverse geocode tap with no place shows empty callout`() = runTest {
        coEvery { mockReverseGeocoder.reverseGeocode(any(), any()) } returns null

        mapStore.dispatch(MapEvent.ReverseGeocodeAt(0.0, 0.0))
        testDispatcher.scheduler.advanceUntilIdle()

        val callout = mapStore.state.first().placeCallout
        callout.shouldNotBeNull()
        callout!!.isLoading shouldBe false
        callout.hasResult shouldBe false
    }

    @Test
    fun `dismiss place callout clears it`() = runTest {
        coEvery { mockReverseGeocoder.reverseGeocode(any(), any()) } returns null
        mapStore.dispatch(MapEvent.ReverseGeocodeAt(0.0, 0.0))
        testDispatcher.scheduler.advanceUntilIdle()
        mapStore.state.first().placeCallout.shouldNotBeNull()

        mapStore.dispatch(MapEvent.DismissPlaceCallout)
        mapStore.state.first().placeCallout.shouldBeNull()
    }

    @Test
    fun `submit place-name search forward-geocodes and centers camera`() = runTest {
        coEvery {
            mockReverseGeocoder.searchPlaces("Prague", any(), any(), any())
        } returns listOf(
            PlaceSearchResult(
                displayName = "Prague",
                locality = "Prague",
                countryCode = "CZ",
                latitude = 50.0875,
                longitude = 14.4213,
                population = 1_165_581,
            )
        )
        mapStore.dispatch(MapEvent.UpdateSearchQuery("Prague"))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.effects.test {
            mapStore.dispatch(MapEvent.SubmitSearch)
            testDispatcher.scheduler.advanceUntilIdle()
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera) shouldBe true
            cancelAndIgnoreRemainingEvents()
        }

        val state = mapStore.state.first()
        state.search.resultStatus shouldBe SearchResultStatus.Found
        val marker = state.overlays
            .filterIsInstance<com.adsamcik.tracker.map.presentation.udf.MapOverlayState.SearchMarker>()
            .single()
        marker.latLng.lat shouldBe (50.0875 plusOrMinus 0.000001)
        marker.latLng.lng shouldBe (14.4213 plusOrMinus 0.000001)
    }

    @Test
    fun `submit place-name search with no match emits format hint and marks NotFound`() = runTest {
        coEvery { mockReverseGeocoder.searchPlaces(any(), any(), any(), any()) } returns emptyList()
        mapStore.dispatch(MapEvent.UpdateSearchQuery("Nowhereville"))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.effects.test {
            mapStore.dispatch(MapEvent.SubmitSearch)
            testDispatcher.scheduler.advanceUntilIdle()
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.ShowSearchFormatHint) shouldBe true
            cancelAndIgnoreRemainingEvents()
        }

        mapStore.state.first().search.resultStatus shouldBe SearchResultStatus.NotFound
    }

    @Test
    fun `zoom buttons setting drives state`() = runTest {
        val settingsRepo = FakeMapSettingsRepository(MapSettingsState(zoomButtonsEnabled = true))
        val store = MapStore(
            SavedStateHandle(),
            mockTrackerController,
            TestDispatchersProvider(testDispatcher),
            FakeOnlineMapTilesRepository(),
            settingsRepo,
            mockReverseGeocoder,
            mockMapImageShareHelper,
            mockMapDataChangeObserver,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        store.zoomButtonsEnabled.value shouldBe true

        settingsRepo.setZoomButtonsEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        store.zoomButtonsEnabled.value shouldBe false
    }

    @Test
    fun `probe speed at populates speed probe when speed heatmap active`() = runTest {
        mapStore.dispatch(MapEvent.SelectLayer("speed_heatmap"))
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { mockLayerEngine.querySpeedSummaryAt(any(), any(), any(), any()) } returns
            SpeedSummary(avgSpeedMps = 10.0, maxSpeedMps = 25.0, sampleCount = 7)
        mapStore.dispatch(MapEvent.ProbeSpeedAt(lat = 50.0, lng = 14.0, radiusMeters = 50.0))
        testDispatcher.scheduler.advanceUntilIdle()

        val probe = mapStore.state.first().speedProbe
        probe.shouldNotBeNull()
        probe.latLng shouldBe LatLngModel(50.0, 14.0)
        probe.avgSpeedMps shouldBe 10.0
        probe.maxSpeedMps shouldBe 25.0
        probe.sampleCount shouldBe 7
        probe.hasData shouldBe true
        coVerify { mockLayerEngine.querySpeedSummaryAt(eq(50.0), eq(14.0), eq(50.0), any()) }
    }

    @Test
    fun `probe speed at is ignored when speed heatmap is not active`() = runTest {
        // Default layer is location_polyline.
        mapStore.dispatch(MapEvent.ProbeSpeedAt(lat = 50.0, lng = 14.0, radiusMeters = 50.0))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.state.first().speedProbe.shouldBeNull()
        coVerify(exactly = 0) { mockLayerEngine.querySpeedSummaryAt(any(), any(), any(), any()) }
    }

    @Test
    fun `probe speed at with no nearby data publishes empty probe`() = runTest {
        mapStore.dispatch(MapEvent.SelectLayer("speed_heatmap"))
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { mockLayerEngine.querySpeedSummaryAt(any(), any(), any(), any()) } returns null

        mapStore.dispatch(MapEvent.ProbeSpeedAt(lat = 50.0, lng = 14.0, radiusMeters = 50.0))
        testDispatcher.scheduler.advanceUntilIdle()

        val probe = mapStore.state.first().speedProbe
        probe.shouldNotBeNull()
        probe.sampleCount shouldBe 0
        probe.hasData shouldBe false
    }

    @Test
    fun `dismiss speed probe clears it`() = runTest {
        mapStore.dispatch(MapEvent.SelectLayer("speed_heatmap"))
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { mockLayerEngine.querySpeedSummaryAt(any(), any(), any(), any()) } returns
            SpeedSummary(5.0, 5.0, 1)
        mapStore.dispatch(MapEvent.ProbeSpeedAt(50.0, 14.0, 50.0))
        testDispatcher.scheduler.advanceUntilIdle()
        mapStore.state.first().speedProbe.shouldNotBeNull()

        mapStore.dispatch(MapEvent.DismissSpeedProbe)
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.state.first().speedProbe.shouldBeNull()
    }

    @Test
    fun `selecting another layer clears speed probe`() = runTest {
        mapStore.dispatch(MapEvent.SelectLayer("speed_heatmap"))
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { mockLayerEngine.querySpeedSummaryAt(any(), any(), any(), any()) } returns
            SpeedSummary(5.0, 5.0, 1)
        mapStore.dispatch(MapEvent.ProbeSpeedAt(50.0, 14.0, 50.0))
        testDispatcher.scheduler.advanceUntilIdle()
        mapStore.state.first().speedProbe.shouldNotBeNull()

        mapStore.dispatch(MapEvent.SelectLayer("location_heatmap"))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.state.first().speedProbe.shouldBeNull()
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
