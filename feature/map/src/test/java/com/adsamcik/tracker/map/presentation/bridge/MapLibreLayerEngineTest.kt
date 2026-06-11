package com.adsamcik.tracker.map.presentation.bridge

import android.content.Context
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.ui.LayerController
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MapLibreLayerEngine")
class MapLibreLayerEngineTest {

    private lateinit var context: Context
    private lateinit var registry: LayerRegistry
    private lateinit var engine: MapLibreLayerEngine

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        registry = mockk()
        engine = MapLibreLayerEngine(context, registry)
    }

    @Nested
    @DisplayName("selectSingleLayer")
    inner class SelectSingleLayer {

        @Test
        fun `delegates to controller when descriptor is found`() = runTest {
            val descriptor = mockk<LayerDescriptor>(relaxed = true)
            every { registry.findById("location_heatmap") } returns descriptor

            engine.selectSingleLayer("location_heatmap", 1.0f, 0L..Long.MAX_VALUE)

            verify { registry.findById("location_heatmap") }
        }

        @Test
        fun `passes null descriptor when id is null`() = runTest {
            engine.selectSingleLayer(null, 1.0f, 0L..Long.MAX_VALUE)

            verify(exactly = 0) { registry.findById(any()) }
        }

        @Test
        fun `passes null descriptor when id not found in registry`() = runTest {
            every { registry.findById("nonexistent") } returns null

            engine.selectSingleLayer("nonexistent", 0.8f, 100L..200L)

            verify { registry.findById("nonexistent") }
        }
    }

    @Nested
    @DisplayName("clear")
    inner class Clear {

        @Test
        fun `clears active legend after clear`() = runTest {
            engine.clear()

            engine.activeLegend().shouldBeNull()
        }

        @Test
        fun `clears active layer config after clear`() = runTest {
            engine.clear()

            engine.activeLayerConfig().shouldBeNull()
        }
    }

    @Nested
    @DisplayName("overlays")
    inner class Overlays {

        @Test
        fun `returns empty list`() {
            engine.overlays().shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("activeLegend and activeLayerConfig")
    inner class ActiveState {

        @Test
        fun `activeLegend returns null when no layer set`() {
            engine.activeLegend().shouldBeNull()
        }

        @Test
        fun `activeLayerConfig returns null when no layer set`() {
            engine.activeLayerConfig().shouldBeNull()
        }
    }

    @Nested
    @DisplayName("destroy")
    inner class Destroy {

        @Test
        fun `destroy clears legend and config`() {
            engine.destroy()

            engine.activeLegend().shouldBeNull()
            engine.activeLayerConfig().shouldBeNull()
        }

        @Test
        fun `destroy can be called multiple times without error`() {
            engine.destroy()
            engine.destroy()

            engine.activeLegend().shouldBeNull()
        }
    }
}
