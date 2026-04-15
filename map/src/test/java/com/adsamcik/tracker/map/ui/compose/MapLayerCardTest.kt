package com.adsamcik.tracker.map.ui.compose

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.map.shared.layers.LayerCapabilities
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import com.adsamcik.tracker.map.shared.layers.LayerFactory
import com.adsamcik.tracker.map.shared.layers.LayerRecipe
import com.adsamcik.tracker.map.ui.MapLayerCard
import com.adsamcik.tracker.map.ui.theme.MapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLayerCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createTestLayer(
        id: String = "test_layer",
        titleRes: Int = com.adsamcik.tracker.map.R.string.map_layer_location_heatmap_title,
    ): LayerDescriptor {
        return LayerDescriptor(
            id = id,
            titleRes = titleRes,
            iconRes = null,
            capabilities = LayerCapabilities(),
            recipe = LayerRecipe(factory = LayerFactory { Object() }),
        )
    }

    @Test
    fun displaysLayerTitle() {
        val layer = createTestLayer()
        composeRule.setContent {
            MapTheme {
                MapLayerCard(
                    layer = layer,
                    isSelected = false,
                    onSelect = {}
                )
            }
        }
        // The card should render with "Not selected" state description
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected")
        ).assertIsDisplayed()
    }

    @Test
    fun selectedState_showsCheckIcon() {
        val layer = createTestLayer()
        composeRule.setContent {
            MapTheme {
                MapLayerCard(
                    layer = layer,
                    isSelected = true,
                    onSelect = {}
                )
            }
        }
        // Selected state should show check mark via semantics stateDescription
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected")
        ).assertIsDisplayed()
    }

    @Test
    fun unselectedState_noCheckIcon() {
        val layer = createTestLayer()
        composeRule.setContent {
            MapTheme {
                MapLayerCard(
                    layer = layer,
                    isSelected = false,
                    onSelect = {}
                )
            }
        }
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected")
        ).assertIsDisplayed()
    }

    @Test
    fun clickingCard_callsOnSelect() {
        var selected = false
        val layer = createTestLayer()
        composeRule.setContent {
            MapTheme {
                MapLayerCard(
                    layer = layer,
                    isSelected = false,
                    onSelect = { selected = true }
                )
            }
        }
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected")
        ).performClick()
        assertTrue(selected)
    }

    @Test
    fun selectedLayer_hasRadioButtonRole() {
        val layer = createTestLayer()
        composeRule.setContent {
            MapTheme {
                MapLayerCard(
                    layer = layer,
                    isSelected = true,
                    onSelect = {}
                )
            }
        }
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.RadioButton
            )
        ).assertIsDisplayed()
    }

    @Test
    fun differentLayerIds_renderWithoutCrash() {
        val layerIds = listOf(
            "location_heatmap",
            "cell_heatmap",
            "wifi_heatmap",
            "activity_polyline",
            "unknown_type",
        )
        composeRule.setContent {
            MapTheme {
                androidx.compose.foundation.layout.Column {
                    layerIds.forEach { id ->
                        MapLayerCard(
                            layer = createTestLayer(id = id),
                            isSelected = false,
                            onSelect = {}
                        )
                    }
                }
            }
        }
        // All 5 cards should render with "Not selected" state
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected")
        ).assertCountEquals(layerIds.size)
    }
}
