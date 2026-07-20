package com.adsamcik.tracker.map.layers.registry

import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layers.impl.HeatmapColorRamps
import com.adsamcik.tracker.map.ui.LayerEntry
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
		fun `finds observed_presence layer`() {
			val result = registry.findById("observed_presence")

			result.shouldNotBeNull()
			result.id shouldBe "observed_presence"
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
        fun `finds vehicle_compliance layer`() {
            val result = registry.findById("vehicle_compliance")

            result.shouldNotBeNull()
            result.id shouldBe "vehicle_compliance"
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
				"observed_presence",
                "location_heatmap",
                "cell_heatmap",
                "signal_coverage",
                "signal_aurora",
                "wifi_heatmap",
                "wifi_count_heatmap",
                "speed_heatmap",
                "vehicle_compliance",
                "location_polyline",
                "legacy_heatmap",
                "seasonal_palimpsest",
                "frequent_places",
                "life_terrain",
                "speed_ribbon",
                "altitude_ribbon",
                "activity_ribbon",
                "ski_xray",
                "trip_constellations",
                "fog_of_wonder",
                "first_contact"
            )
        }

        @Test
        fun `heatmap layers have isHeatmap capability`() {
            val heatmapIds = listOf(
				"observed_presence",
                "location_heatmap",
                "cell_heatmap",
                "signal_coverage",
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

        @Test
        fun `location heatmap legend matches density color ramp`() {
            val entry = registry.findById("location_heatmap")!!.recipe.factory.create() as LayerEntry

            entry.legend.colorList shouldBe HeatmapColorRamps.LocationDensity.drop(1).map { it.second }
            entry.legend.legend.valueList.map { it.color } shouldBe HeatmapColorRamps.LocationDensity.drop(1).map { it.second }
            entry.legend.legend.valueList.map { it.nameRes } shouldBe listOf(
                R.string.map_layer_location_heatmap_low,
                R.string.map_layer_location_heatmap_low_medium,
                R.string.map_layer_location_heatmap_medium,
                R.string.map_layer_location_heatmap_high,
                R.string.map_layer_location_heatmap_peak,
            )
        }

		@Test
		fun `observed presence legend uses the absolute time ramp`() {
			val entry = registry.findById("observed_presence")!!.recipe.factory.create() as LayerEntry

			entry.legend.colorList shouldBe HeatmapColorRamps.ObservedPresenceTime.map { it.second }
			entry.legend.legend.valueList.map { it.color } shouldBe
				HeatmapColorRamps.ObservedPresenceTime.map { it.second }
			entry.legend.legend.valueList.map { it.nameRes } shouldBe listOf(
				R.string.map_layer_observed_presence_seconds,
				R.string.map_layer_observed_presence_one_minute,
				R.string.map_layer_observed_presence_eight_minutes,
				R.string.map_layer_observed_presence_one_hour,
				R.string.map_layer_observed_presence_eight_hours,
			)
		}

        @Test
        fun `speed heatmap legend matches speed color ramp`() {
            val entry = registry.findById("speed_heatmap")!!.recipe.factory.create() as LayerEntry

            entry.legend.colorList shouldBe HeatmapColorRamps.Speed.map { it.second }
            entry.legend.legend.valueList.map { it.color } shouldBe HeatmapColorRamps.Speed.map { it.second }
        }

        @Test
        fun `cell heatmap legend identifies radio technologies`() {
            val entry = registry.findById("cell_heatmap")!!.recipe.factory.create() as LayerEntry

            entry.legend.colorList shouldBe listOf(
                com.adsamcik.tracker.map.viz.CELL_RADIO_COLORS.getValue(com.adsamcik.tracker.map.viz.RadioVisualGroup.CELL_GSM),
                com.adsamcik.tracker.map.viz.CELL_RADIO_COLORS.getValue(com.adsamcik.tracker.map.viz.RadioVisualGroup.CELL_WCDMA),
                com.adsamcik.tracker.map.viz.CELL_RADIO_COLORS.getValue(com.adsamcik.tracker.map.viz.RadioVisualGroup.CELL_LTE),
                com.adsamcik.tracker.map.viz.CELL_RADIO_COLORS.getValue(com.adsamcik.tracker.map.viz.RadioVisualGroup.CELL_NR),
            )
            entry.legend.legend.description shouldBe R.string.map_layer_cell_heatmap_description
            entry.legend.legend.valueList.map { it.nameRes } shouldBe listOf(
                R.string.map_layer_cell_radio_gsm,
                R.string.map_layer_cell_radio_wcdma,
                R.string.map_layer_cell_radio_lte,
                R.string.map_layer_cell_radio_nr,
            )
        }
    }
}
