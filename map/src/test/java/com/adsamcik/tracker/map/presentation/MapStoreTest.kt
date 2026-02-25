package com.adsamcik.tracker.map.presentation

import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.SheetStateModel
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.shared.map.MapLayerData
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.collections.shouldHaveSize
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

    private lateinit var mapStore: MapStore
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { mockLayerEngine.activeLegend() } returns null
        every { mockLayerEngine.activeLayerConfig() } returns null
        every { mockLayerEngine.overlays() } returns persistentListOf()
        mapStore = MapStore()
        mapStore.setLayerEngine(mockLayerEngine)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val initialState = mapStore.state.first()
        
        initialState.activeLayerIds shouldBe persistentSetOf<String>()
        initialState.isFollowing shouldBe false
        initialState.sheet.visibility shouldBe SheetVisibility.Peek
        initialState.overlays shouldBe persistentListOf<com.adsamcik.tracker.map.presentation.udf.MapOverlayState>()
        initialState.legend shouldBe persistentListOf<MapLayerData>()
        initialState.quality shouldBe 1f
        initialState.dateRange shouldBe 0L..Long.MAX_VALUE
        initialState.layerLoadingProgress shouldBe 0
        initialState.layerConfig.shouldBeNull()
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
        coVerify { mockLayerEngine.selectSingleLayer(eq(layerId), eq(1f), any()) }
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
        coVerify { mockLayerEngine.selectSingleLayer(isNull(), eq(newQuality), any()) }
    }

    @Test
    fun `date range update triggers layer reapplication`() = runTest {
        val newRange = 1000L..2000L
        
        mapStore.dispatch(MapEvent.SetDateRange(newRange))
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        state.dateRange shouldBe newRange
        val rangeSlot = slot<LongRange>()
        coVerify { mockLayerEngine.selectSingleLayer(isNull(), eq(1f), capture(rangeSlot)) }
        rangeSlot.captured shouldBe newRange
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
    fun `submit search emits PerformGeocode effect`() = runTest {
        mapStore.dispatch(MapEvent.UpdateSearchQuery("Prague"))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.effects.test {
            mapStore.dispatch(MapEvent.SubmitSearch)
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.PerformGeocode) shouldBe true
            val pg = effect as com.adsamcik.tracker.map.presentation.udf.MapEffect.PerformGeocode
            pg.query shouldBe "Prague"
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `geocode result emits CenterCamera effect`() = runTest {
        val bounds = com.adsamcik.tracker.shared.map.CoordinateBounds(
            topBound = 3.0,
            rightBound = 4.0,
            bottomBound = 1.0,
            leftBound = 2.0
        )

        mapStore.effects.test {
            mapStore.dispatch(MapEvent.GeocodeResult(bounds))
            val effect = awaitItem()
            (effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera) shouldBe true
            val cc = effect as com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera
            cc.bounds shouldBe bounds
            cancelAndIgnoreRemainingEvents()
        }
    }
}
