package com.adsamcik.tracker.map.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.LegendItem
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.SheetStateModel
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.testing.AccessibilityTestExtensions.assertMinTouchTargetSize
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * UI tests for MapSheet component.
 *
 * Tests cover:
 * - Bottom sheet visibility states (Hidden, Peek, Expanded)
 * - Layer selection in the expanded sheet
 * - Search field functionality
 * - Date range controls
 * - Quality slider
 * - Legend display
 * - Tile progress indicator
 * - Accessibility compliance
 */
@RunWith(AndroidJUnit4::class)
class MapSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var mockStore: MapStore
    private lateinit var mockRegistry: LayerRegistry
    private lateinit var stateFlow: MutableStateFlow<MapState>

    @Before
    fun setUp() {
        mockStore = mock()
        mockRegistry = mock()
        stateFlow = MutableStateFlow(MapState())
        
        whenever(mockStore.state).thenReturn(stateFlow as StateFlow<MapState>)
        whenever(mockRegistry.getAllLayers()).thenReturn(emptyList())
    }

    // ========================================
    // Sheet Visibility Tests
    // ========================================

    @Test
    fun sheetHidden_displaysRevealHandle() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Hidden)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        // When hidden, a reveal handle with "Search" text should be visible
        composeTestRule.onNodeWithText("Search").assertIsDisplayed()
    }

    @Test
    fun sheetPeek_displaysSearchField() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        // Search placeholder should be visible in peek state
        composeTestRule.onNodeWithText(
            context.getString(R.string.map_search_placeholder),
            useUnmergedTree = true
        ).assertIsDisplayed()
    }

    @Test
    fun sheetExpanded_displaysMapControlsHeader() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        // Wait for idle and check for header text
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Map Controls").assertIsDisplayed()
    }

    @Test
    fun sheetExpanded_displaysFiltersSection() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Filters").assertIsDisplayed()
    }

    @Test
    fun sheetExpanded_displaysMapLayersSection() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Map Layers").assertIsDisplayed()
    }

    // ========================================
    // Search Functionality Tests
    // ========================================

    @Test
    fun searchField_dispatchesQueryUpdateEvent() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        // Enter text in search field
        composeTestRule.onNode(
            hasText(context.getString(R.string.map_search_placeholder), substring = true)
        ).performTextInput("Test Query")

        // Verify event was dispatched
        verify(mockStore).dispatch(any<MapEvent.UpdateSearchQuery>())
    }

    @Test
    fun searchButton_dispatchesSubmitEvent() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        // Click search icon button
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        // Verify submit event was dispatched
        verify(mockStore).dispatch(MapEvent.SubmitSearch)
    }

    // ========================================
    // Location Button Tests
    // ========================================

    @Test
    fun myLocationButton_dispatchesToggleFollowEvent() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        // Click my location button
        composeTestRule.onNodeWithContentDescription("My location").performClick()

        // Verify toggle follow event was dispatched
        verify(mockStore).dispatch(MapEvent.ToggleFollow)
    }

    // ========================================
    // Quality Slider Tests
    // ========================================

    @Test
    fun qualitySlider_isDisplayed_whenExpanded() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Quality").assertIsDisplayed()
    }

    // ========================================
    // Legend Display Tests
    // ========================================

    @Test
    fun legendSection_displaysWhenLegendItems() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded),
            legend = persistentListOf(
                LegendItem(color = 0xFFFF0000.toInt(), label = "High Activity"),
                LegendItem(color = 0xFF00FF00.toInt(), label = "Low Activity")
            )
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Legend").assertIsDisplayed()
        composeTestRule.onNodeWithText("High Activity").assertIsDisplayed()
        composeTestRule.onNodeWithText("Low Activity").assertIsDisplayed()
    }

    @Test
    fun legendSection_hiddenWhenNoItems() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded),
            legend = persistentListOf()
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Legend").assertDoesNotExist()
    }

    // ========================================
    // Tile Progress Tests
    // ========================================

    @Test
    fun tileProgress_displaysWhenGenerating() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded),
            tileGenerationInProgress = 5
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Generating 5 tiles...", substring = true).assertIsDisplayed()
    }

    @Test
    fun tileProgress_hiddenWhenNotGenerating() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded),
            tileGenerationInProgress = 0
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Generating", substring = true).assertDoesNotExist()
    }

    // ========================================
    // Accessibility Tests
    // ========================================

    @Test
    fun searchButton_meetsMinTouchTargetSize() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.onNodeWithContentDescription("Search")
            .assertMinTouchTargetSize()
    }

    @Test
    fun myLocationButton_meetsMinTouchTargetSize() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.onNodeWithContentDescription("My location")
            .assertMinTouchTargetSize()
    }

    @Test
    fun dateRangeButton_meetsMinTouchTargetSize() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.onNodeWithContentDescription("Date range")
            .assertMinTouchTargetSize()
    }

    @Test
    fun hideButton_meetsMinTouchTargetSize_whenExpanded() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Hide")
            .assertMinTouchTargetSize()
    }

    @Test
    fun dragHandle_meetsMinTouchTargetSize() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Peek)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.onNodeWithTag(MAP_SHEET_DRAG_HANDLE_TAG)
            .assertMinTouchTargetSize()
    }

    // ========================================
    // Hide Button Tests
    // ========================================

    @Test
    fun hideButton_dispatchesHideSheetEvent() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Hide").performClick()

        verify(mockStore).dispatch(MapEvent.HideSheet)
    }

    // ========================================
    // Date Range Tests
    // ========================================

    @Test
    fun dateRangeButton_opensDatePickerDialog() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        
        // Click the date range button in the expanded view
        composeTestRule.onNodeWithText(
            context.getString(R.string.map_date_range_button)
        ).performClick()

        // Verify date picker dialog appears with OK button
        composeTestRule.onNodeWithText(
            context.getString(R.string.map_date_range_dialog_ok)
        ).assertIsDisplayed()
    }

    @Test
    fun dateRangeDialog_hasCancelButton() {
        stateFlow.value = MapState(
            sheet = SheetStateModel(visibility = SheetVisibility.Expanded)
        )

        composeTestRule.setContent {
            MapSheet(
                registry = mockRegistry,
                store = mockStore
            )
        }

        composeTestRule.waitForIdle()
        
        // Click the date range button
        composeTestRule.onNodeWithText(
            context.getString(R.string.map_date_range_button)
        ).performClick()

        // Verify cancel button is displayed
        composeTestRule.onNodeWithText(
            context.getString(R.string.map_date_range_dialog_cancel)
        ).assertIsDisplayed()
    }
}
