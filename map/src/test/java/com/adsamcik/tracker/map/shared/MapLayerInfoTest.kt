package com.adsamcik.tracker.map.shared

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MapLayerInfo")
class MapLayerInfoTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `stores layerClass and nameRes`() {
			val info = MapLayerInfo(layerClass = "com.example.Layer", nameRes = 123)
			info.layerClass shouldBe "com.example.Layer"
			info.nameRes shouldBe 123
		}
	}

	@Nested
	@DisplayName("Equality")
	inner class Equality {

		@Test
		fun `equality works`() {
			val info1 = MapLayerInfo(layerClass = "Layer", nameRes = 1)
			val info2 = MapLayerInfo(layerClass = "Layer", nameRes = 1)
			info1 shouldBe info2
			info1.hashCode() shouldBe info2.hashCode()
		}

		@Test
		fun `inequality for different layerClass`() {
			val info1 = MapLayerInfo(layerClass = "LayerA", nameRes = 1)
			val info2 = MapLayerInfo(layerClass = "LayerB", nameRes = 1)
			info1 shouldNotBe info2
		}

		@Test
		fun `inequality for different nameRes`() {
			val info1 = MapLayerInfo(layerClass = "Layer", nameRes = 1)
			val info2 = MapLayerInfo(layerClass = "Layer", nameRes = 2)
			info1 shouldNotBe info2
		}
	}

	@Nested
	@DisplayName("Copy and Destructuring")
	inner class CopyAndDestructuring {

		@Test
		fun `copy works`() {
			val original = MapLayerInfo(layerClass = "Layer", nameRes = 1)
			val copy = original.copy(nameRes = 42)
			copy.layerClass shouldBe "Layer"
			copy.nameRes shouldBe 42
		}

		@Test
		fun `destructuring works`() {
			val info = MapLayerInfo(layerClass = "com.example.MyLayer", nameRes = 99)
			val (layerClass, nameRes) = info
			layerClass shouldBe "com.example.MyLayer"
			nameRes shouldBe 99
		}
	}
}
