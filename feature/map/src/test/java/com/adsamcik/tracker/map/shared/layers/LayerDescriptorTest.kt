package com.adsamcik.tracker.map.shared.layers

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Layer Descriptor Components")
class LayerDescriptorTest {

    @Nested
    @DisplayName("LayerCapabilities")
    inner class LayerCapabilitiesTests {

        @Test
        fun `default values are correct`() {
            val capabilities = LayerCapabilities()

            capabilities.supportsDateRange.shouldBeTrue()
            capabilities.supportsQuality.shouldBeTrue()
            capabilities.supportsFollowInteraction.shouldBeFalse()
            capabilities.isHeatmap.shouldBeFalse()
            capabilities.isPolyline.shouldBeFalse()
        }

        @Test
        fun `custom values are stored`() {
            val capabilities = LayerCapabilities(
                supportsDateRange = false,
                supportsQuality = false,
                supportsFollowInteraction = true,
                isHeatmap = true,
                isPolyline = true
            )

            capabilities.supportsDateRange.shouldBeFalse()
            capabilities.supportsQuality.shouldBeFalse()
            capabilities.supportsFollowInteraction.shouldBeTrue()
            capabilities.isHeatmap.shouldBeTrue()
            capabilities.isPolyline.shouldBeTrue()
        }

        @Test
        fun `equality works`() {
            val cap1 = LayerCapabilities(isHeatmap = true)
            val cap2 = LayerCapabilities(isHeatmap = true)

            cap1 shouldBe cap2
            cap1.hashCode() shouldBe cap2.hashCode()
        }

        @Test
        fun `inequality works`() {
            val cap1 = LayerCapabilities(isHeatmap = true)
            val cap2 = LayerCapabilities(isHeatmap = false)

            cap1 shouldNotBe cap2
        }

        @Test
        fun `copy works`() {
            val original = LayerCapabilities(isHeatmap = true, isPolyline = false)

            val modified = original.copy(isPolyline = true)

            modified.isPolyline.shouldBeTrue()
            original.isPolyline.shouldBeFalse()
            modified.isHeatmap.shouldBeTrue() // Unchanged field preserved
        }
    }

    @Nested
    @DisplayName("LayerParameter")
    inner class LayerParameterTests {

        @Test
        fun `stores key and default value`() {
            val param = LayerParameter(key = "opacity", defaultValue = 0.8)

            param.key shouldBe "opacity"
            param.defaultValue shouldBe 0.8
        }

        @Test
        fun `works with different types`() {
            val intParam = LayerParameter(key = "zoom", defaultValue = 10)
            val stringParam = LayerParameter(key = "name", defaultValue = "default")
            val boolParam = LayerParameter(key = "visible", defaultValue = true)

            intParam.defaultValue shouldBe 10
            stringParam.defaultValue shouldBe "default"
            boolParam.defaultValue shouldBe true
        }

        @Test
        fun `equality works`() {
            val param1 = LayerParameter("key", 100)
            val param2 = LayerParameter("key", 100)

            param1 shouldBe param2
            param1.hashCode() shouldBe param2.hashCode()
        }

        @Test
        fun `destructuring works`() {
            val param = LayerParameter(key = "testKey", defaultValue = 42)

            val (key, defaultValue) = param

            key shouldBe "testKey"
            defaultValue shouldBe 42
        }
    }

    @Nested
    @DisplayName("LayerRecipe")
    inner class LayerRecipeTests {

        @Test
        fun `stores factory and parameters`() {
            val factory = LayerFactory { "test-layer" }
            val params = mapOf(
                "opacity" to LayerParameter("opacity", 1.0),
                "color" to LayerParameter("color", 0xFF0000)
            )

            val recipe = LayerRecipe(factory = factory, defaultParams = params)

            recipe.defaultParams shouldHaveSize 2
            recipe.defaultParams.shouldContainKey("opacity")
            recipe.defaultParams.shouldContainKey("color")
        }

        @Test
        fun `default params is empty by default`() {
            val factory = LayerFactory { Any() }
            val recipe = LayerRecipe(factory = factory)

            recipe.defaultParams.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("LayerFactory")
    inner class LayerFactoryTests {

        @Test
        fun `creates instance`() {
            var createCalled = false
            val factory = LayerFactory {
                createCalled = true
                "created-layer"
            }

            val result = factory.create()

            createCalled.shouldBeTrue()
            result shouldBe "created-layer"
        }
    }

    @Nested
    @DisplayName("LayerDescriptor")
    inner class LayerDescriptorTests {

        @Test
        fun `stores all fields`() {
            val capabilities = LayerCapabilities(isHeatmap = true)
            val factory = LayerFactory { "heatmap" }
            val recipe = LayerRecipe(factory = factory)

            val descriptor = LayerDescriptor(
                id = "heatmap-layer",
                titleRes = 123,
                iconRes = 456,
                capabilities = capabilities,
                recipe = recipe
            )

            descriptor.id shouldBe "heatmap-layer"
            descriptor.titleRes shouldBe 123
            descriptor.iconRes shouldBe 456
            descriptor.capabilities shouldBe capabilities
            descriptor.recipe shouldBe recipe
        }

        @Test
        fun `iconRes can be null`() {
            val descriptor = LayerDescriptor(
                id = "simple-layer",
                titleRes = 100,
                iconRes = null,
                capabilities = LayerCapabilities(),
                recipe = LayerRecipe(factory = LayerFactory { Unit })
            )

            descriptor.iconRes.shouldBeNull()
        }

        @Test
        fun `equality works`() {
            val capabilities = LayerCapabilities()
            val recipe = LayerRecipe(factory = LayerFactory { Unit })

            val desc1 = LayerDescriptor(
                id = "test",
                titleRes = 1,
                iconRes = 2,
                capabilities = capabilities,
                recipe = recipe
            )
            val desc2 = LayerDescriptor(
                id = "test",
                titleRes = 1,
                iconRes = 2,
                capabilities = capabilities,
                recipe = recipe
            )

            desc1 shouldBe desc2
        }

        @Test
        fun `inequality for different ids`() {
            val capabilities = LayerCapabilities()
            val recipe = LayerRecipe(factory = LayerFactory { Unit })

            val desc1 = LayerDescriptor("id1", 1, iconRes = null, capabilities = capabilities, recipe = recipe)
            val desc2 = LayerDescriptor("id2", 1, iconRes = null, capabilities = capabilities, recipe = recipe)

            desc1 shouldNotBe desc2
        }
    }
}
