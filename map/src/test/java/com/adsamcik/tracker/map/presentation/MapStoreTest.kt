package com.adsamcik.tracker.map.presentation

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
 

@OptIn(ExperimentalCoroutinesApi::class)
class MapStoreTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @Mock
    private lateinit var mockLayerEngine: LayerEngine

    private lateinit var mapStore: MapStore
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
    // Default stubs to avoid nulls during applyLayer state updates
    whenever(mockLayerEngine.activeLegend()).thenReturn(null)
    whenever(mockLayerEngine.activeLayerConfig()).thenReturn(null)
    whenever(mockLayerEngine.overlays()).thenReturn(persistentListOf())
        mapStore = MapStore()
        mapStore.setLayerEngine(mockLayerEngine)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val initialState = mapStore.state.first()
        
        assertEquals(persistentSetOf<String>(), initialState.activeLayerIds)
        assertFalse(initialState.isFollowing)
    assertEquals(SheetVisibility.Peek, initialState.sheet.visibility)
        assertEquals(persistentListOf<com.adsamcik.tracker.map.presentation.udf.MapOverlayState>(), initialState.overlays)
        assertEquals(persistentListOf<MapLayerData>(), initialState.legend)
        assertEquals(1f, initialState.quality)
        assertEquals(0L..Long.MAX_VALUE, initialState.dateRange)
        assertEquals(0, initialState.layerLoadingProgress)
        assertNull(initialState.layerConfig)
    }

    @Test
    fun `sheet show and hide events work correctly`() = runTest {
        // Hide sheet
        mapStore.dispatch(MapEvent.HideSheet)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val hiddenState = mapStore.state.first()
    assertEquals(SheetVisibility.Hidden, hiddenState.sheet.visibility)

        // Show sheet
        mapStore.dispatch(MapEvent.ShowSheet)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val visibleState = mapStore.state.first()
    assertEquals(SheetVisibility.Expanded, visibleState.sheet.visibility)
    }

    @Test
    fun `toggle follow changes state correctly`() = runTest {
        // Initially not following
        assertFalse(mapStore.state.first().isFollowing)

        // Toggle on
        mapStore.dispatch(MapEvent.ToggleFollow)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertTrue(mapStore.state.first().isFollowing)

        // Toggle off
        mapStore.dispatch(MapEvent.ToggleFollow)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertFalse(mapStore.state.first().isFollowing)
    }

    @Test
    fun `follow canceled sets following to false`() = runTest {
        // First set following to true
        mapStore.dispatch(MapEvent.ToggleFollow)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(mapStore.state.first().isFollowing)

        // Cancel follow
        mapStore.dispatch(MapEvent.FollowCanceled)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertFalse(mapStore.state.first().isFollowing)
    }

    @Test
    fun `layer selection updates active layer ids`() = runTest {
        val layerId = "test_layer"
        
        mapStore.dispatch(MapEvent.SelectLayer(layerId))
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        assertTrue(state.activeLayerIds.contains(layerId))
    verify(mockLayerEngine).selectSingleLayer(eq(layerId), eq(1f), any())
    }

    @Test
    fun `quality update triggers layer reapplication`() = runTest {
        val newQuality = 0.5f
        
        mapStore.dispatch(MapEvent.SetQuality(newQuality))
        testDispatcher.scheduler.advanceUntilIdle()
        // Wait a bit for withContext(Dispatchers.Default) work to complete (race condition workaround)
        kotlinx.coroutines.delay(50)
        
    val state = mapStore.state.first()
    assertEquals(newQuality, state.quality)
    verify(mockLayerEngine).selectSingleLayer(eq(null), eq(newQuality), any())
    }

    @Test
    fun `date range update triggers layer reapplication`() = runTest {
        val newRange = 1000L..2000L
        
        mapStore.dispatch(MapEvent.SetDateRange(newRange))
        testDispatcher.scheduler.advanceUntilIdle()
        
    val state = mapStore.state.first()
    assertEquals(newRange, state.dateRange)
    val rangeCaptor = argumentCaptor<LongRange>()
    verify(mockLayerEngine).selectSingleLayer(eq(null), eq(1f), rangeCaptor.capture())
    assertEquals(newRange, rangeCaptor.firstValue)
    }

    @Test
    fun `user location update adds markers and accuracy circle`() = runTest {
        val latLng = LatLngModel(37.7749, -122.4194) // San Francisco
        val accuracy = 10.0
        
        mapStore.dispatch(MapEvent.SetUserLocation(latLng, accuracy))
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        assertEquals(2, state.overlays.size) // UserMarker + AccuracyCircle
        
        val userMarker = state.overlays.find { it is com.adsamcik.tracker.map.presentation.udf.MapOverlayState.UserMarker }
        val accuracyCircle = state.overlays.find { it is com.adsamcik.tracker.map.presentation.udf.MapOverlayState.AccuracyCircle }
        
        assertNotNull(userMarker)
        assertNotNull(accuracyCircle)
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
        
        assertNull(userMarker?.bearing) // Should be null when not following
        
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
        
        assertEquals(bearing, followingUserMarker?.bearing) // Should match when following
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
        assertEquals(cameraModel, state.camera)
    }

    @Test
    fun `setQuality helper method works`() = runTest {
        val quality = 0.75f
        
        mapStore.setQuality(quality)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        assertEquals(quality, state.quality)
    }

    @Test
    fun `setDateRange helper method works`() = runTest {
        val range = 500L..1500L
        
        mapStore.setDateRange(range)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = mapStore.state.first()
        assertEquals(range, state.dateRange)
    }

    @Test
    fun `update search query updates state`() = runTest {
        mapStore.dispatch(MapEvent.UpdateSearchQuery("Berlin"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = mapStore.state.first()
        assertEquals("Berlin", state.search.query)
    }

    @Test
    fun `submit search emits PerformGeocode effect`() = runTest {
        mapStore.dispatch(MapEvent.UpdateSearchQuery("Prague"))
        testDispatcher.scheduler.advanceUntilIdle()

        mapStore.effects.test {
            mapStore.dispatch(MapEvent.SubmitSearch)
            val effect = awaitItem()
            assertTrue(effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.PerformGeocode)
            val pg = effect as com.adsamcik.tracker.map.presentation.udf.MapEffect.PerformGeocode
            assertEquals("Prague", pg.query)
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
            assertTrue(effect is com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera)
            val cc = effect as com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera
            assertEquals(bounds, cc.bounds)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
