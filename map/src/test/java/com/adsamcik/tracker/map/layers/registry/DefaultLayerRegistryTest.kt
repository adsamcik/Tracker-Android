package com.adsamcik.tracker.map.layers.registry

import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DefaultLayerRegistry")
class DefaultLayerRegistryTest {

    private lateinit var registry: DefaultLayerRegistry

    @BeforeEach
    fun setup() {
        registry = DefaultLayerRegistry()
    }

    @Nested
    @DisplayName("getAllLayers")
    inner class GetAllLayers {

        @Test
        fun `returns non-empty list of layers`() {
            val layers = registry.getAllLayers()

            layers shouldHaveAtLeastSize 1
        }

        @Test
        fun `each layer has a non-blank id`() {
            val layers = registry.getAllLayers()

            layers.forEach { it.id.shouldNotBeBlank() }
        }

        @Test
        fun `all layer ids are unique`() {
            val layers = registry.getAllLayers()
            val ids = layers.map { it.id }

            ids.distinct() shouldHaveSize ids.size
        }

        @Test
        fun `returns same list on repeated calls`() {
            val first = registry.getAllLayers()
            val second = registry.getAllLayers()

            first shouldBe second
        }
    }

    @Nested
    @DisplayName("findById")
    inner class FindById {

        @Test
        fun `finds none layer`() {
            val result = registry.findById("none")

            result.shouldNotBeNull()
            result.id shouldBe "none"
        }

        @Test
        fun `finds location_heatmap layer`() {
            val result = registry.findById("location_heatmap")

            result.shouldNotBeNull()
            result.id shouldBe "location_heatmap"
        }

        @Test
        fun `finds speed_heatmap layer`() {
            val result = registry.findById("speed_heatmap")

            result.shouldNotBeNull()
            result.id shouldBe "speed_heatmap"
        }

        @Test
        fun `finds location_polyline layer`() {
            val result = registry.findById("location_polyline")

            result.shouldNotBeNull()
            result.id shouldBe "location_polyline"
        }

        @Test
        fun `returns null for unknown id`() {
            val result = registry.findById("does_not_exist")

            result.shouldBeNull()
        }

        @Test
        fun `returns null for empty id`() {
            val result = registry.findById("")

            result.shouldBeNull()
        }
    }

    @Nested
    @DisplayName("known layers")
    inner class KnownLayers {

        @Test
        fun `contains all expected layer ids`() {
            val ids = registry.getAllLayers().map { it.id }

            ids shouldBe listOf(
                "none",
                "location_heatmap",
                "cell_heatmap",
                "wifi_heatmap",
                "wifi_count_heatmap",
                "speed_heatmap",
                "location_polyline"
            )
        }

        @Test
        fun `heatmap layers have isHeatmap capability`() {
            val heatmapIds = listOf(
                "location_heatmap",
                "cell_heatmap",
                "wifi_heatmap",
                "wifi_count_heatmap",
                "speed_heatmap"
            )

            heatmapIds.forEach { id ->
                val layer = registry.findById(id)
                layer.shouldNotBeNull()
                layer.capabilities.isHeatmap shouldBe true
            }
        }

        @Test
        fun `polyline layer has isPolyline capability`() {
            val layer = registry.findById("location_polyline")

            layer.shouldNotBeNull()
            layer.capabilities.isPolyline shouldBe true
        }

        @Test
        fun `none layer has no heatmap or polyline capability`() {
            val layer = registry.findById("none")

            layer.shouldNotBeNull()
            layer.capabilities.isHeatmap shouldBe false
            layer.capabilities.isPolyline shouldBe false
        }

        @Test
        fun `each layer has a non-null recipe with factory`() {
            registry.getAllLayers().forEach { descriptor ->
                descriptor.recipe.shouldNotBeNull()
                descriptor.recipe.factory.shouldNotBeNull()
            }
        }
    }
}
