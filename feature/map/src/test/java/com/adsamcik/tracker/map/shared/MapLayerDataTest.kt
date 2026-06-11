package com.adsamcik.tracker.map.shared

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MapLayerData")
class MapLayerDataTest {

    @Nested
    @DisplayName("MapLayerInfo")
    inner class MapLayerInfoTests {

        @Test
        fun `stores class name and name resource`() {
            val layerClass = "com.example.MyLayer"
            val nameRes = 123

            val info = MapLayerInfo(layerClass = layerClass, nameRes = nameRes)

            info.layerClass shouldBe layerClass
            info.nameRes shouldBe nameRes
        }

        @Test
        fun `equality works`() {
            val info1 = MapLayerInfo(layerClass = "TestLayer", nameRes = 100)
            val info2 = MapLayerInfo(layerClass = "TestLayer", nameRes = 100)

            info1 shouldBe info2
            info1.hashCode() shouldBe info2.hashCode()
        }

        @Test
        fun `inequality for different class`() {
            val info1 = MapLayerInfo(layerClass = "Layer1", nameRes = 100)
            val info2 = MapLayerInfo(layerClass = "Layer2", nameRes = 100)

            info1 shouldNotBe info2
        }

        @Test
        fun `destructuring works`() {
            val info = MapLayerInfo(layerClass = "MyClass", nameRes = 999)

            val (layerClass, nameRes) = info

            layerClass shouldBe "MyClass"
            nameRes shouldBe 999
        }
    }

    @Nested
    @DisplayName("MapLayerData")
    inner class MapLayerDataTests {

        @Test
        fun `stores info, colors, and legend`() {
            val info = MapLayerInfo(layerClass = "TestLayer", nameRes = 1)
            val colorList = listOf(0xFF0000, 0x00FF00, 0x0000FF)
            val legend = MapLegend(description = 10)

            val data = MapLayerData(info = info, colorList = colorList, legend = legend)

            data.info shouldBe info
            data.colorList shouldBe colorList
            data.legend shouldBe legend
        }

        @Test
        fun `equality works`() {
            val info = MapLayerInfo(layerClass = "TestLayer", nameRes = 1)
            val colorList = listOf(0xFF0000)
            val legend = MapLegend()

            val data1 = MapLayerData(info = info, colorList = colorList, legend = legend)
            val data2 = MapLayerData(info = info, colorList = colorList, legend = legend)

            data1 shouldBe data2
        }

        @Test
        fun `preserves color list order`() {
            val colorList = listOf(0x111111, 0x222222, 0x333333, 0x444444, 0x555555)

            val data = MapLayerData(
                info = MapLayerInfo(layerClass = "Test", nameRes = 1),
                colorList = colorList,
                legend = MapLegend()
            )

            data.colorList[0] shouldBe colorList[0]
            data.colorList[2] shouldBe colorList[2]
            data.colorList[4] shouldBe colorList[4]
        }

        @Test
        fun `copy works`() {
            val original = MapLayerData(
                info = MapLayerInfo(layerClass = "Original", nameRes = 1),
                colorList = listOf(0xFF0000),
                legend = MapLegend()
            )

            val newInfo = MapLayerInfo(layerClass = "Modified", nameRes = 2)
            val modified = original.copy(info = newInfo)

            modified.info.layerClass shouldBe "Modified"
            modified.colorList shouldBe original.colorList
            modified.legend shouldBe original.legend
        }
    }
}
