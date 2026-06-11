package com.adsamcik.tracker.map.layers.registry

import com.adsamcik.tracker.map.shared.layers.LayerCapabilities
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import com.adsamcik.tracker.map.shared.layers.LayerFactory
import com.adsamcik.tracker.map.shared.layers.LayerRecipe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LayerRegistry")
class LayerRegistryTest {

	private val testFactory = LayerFactory { Object() }

	private fun descriptor(id: String, title: Int = 0) = LayerDescriptor(
		id = id,
		titleRes = title,
		iconRes = null,
		capabilities = LayerCapabilities(),
		recipe = LayerRecipe(factory = testFactory)
	)

	private class TestRegistry(private val layers: List<LayerDescriptor>) : LayerRegistry {
		override fun getAllLayers(): List<LayerDescriptor> = layers
	}

	@Nested
	@DisplayName("getAllLayers")
	inner class GetAllLayersTests {

		@Test
		fun `returns all registered layers`() {
			val layers = listOf(descriptor("a"), descriptor("b"), descriptor("c"))
			val registry = TestRegistry(layers)
			registry.getAllLayers() shouldBe layers
		}

		@Test
		fun `returns empty list when no layers registered`() {
			val registry = TestRegistry(emptyList())
			registry.getAllLayers() shouldBe emptyList()
		}
	}

	@Nested
	@DisplayName("findById default implementation")
	inner class FindByIdTests {

		@Test
		fun `finds existing layer by id`() {
			val target = descriptor("target", title = 42)
			val registry = TestRegistry(listOf(descriptor("other"), target))
			registry.findById("target") shouldBe target
		}

		@Test
		fun `returns null for non-existent id`() {
			val registry = TestRegistry(listOf(descriptor("a"), descriptor("b")))
			registry.findById("missing").shouldBeNull()
		}

		@Test
		fun `returns first match when duplicate ids exist`() {
			val first = descriptor("dup", title = 1)
			val second = descriptor("dup", title = 2)
			val registry = TestRegistry(listOf(first, second))
			registry.findById("dup") shouldBe first
		}

		@Test
		fun `returns null from empty registry`() {
			val registry = TestRegistry(emptyList())
			registry.findById("anything").shouldBeNull()
		}
	}
}
