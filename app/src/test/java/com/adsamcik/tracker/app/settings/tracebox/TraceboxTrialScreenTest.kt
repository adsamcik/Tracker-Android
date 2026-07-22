package com.adsamcik.tracker.app.settings.tracebox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraceboxTrialScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `disabled screen accurately describes the alpha boundaries`() {
        setContent(isEnabled = false, isAvailable = true)

        composeTestRule.onNodeWithText("Off. No structural codes are being recorded.")
            .assertIsDisplayed()
        scrollTo("This alpha does not record crashes or ANRs")
        composeTestRule.onNodeWithText(
            "This alpha does not record crashes or ANRs",
            substring = true,
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "It cannot save, export, or send diagnostics",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `opt in calls enable`() {
        var requested: Boolean? = null
        setContent(
            isEnabled = false,
            isAvailable = true,
            onEnabledChange = { requested = it },
        )

        composeTestRule.onNodeWithText("Record structural codes for this app run")
            .performClick()

        requested shouldBe true
    }

    @Test
    fun `opt out calls disable and clear path`() {
        var requested: Boolean? = null
        setContent(
            isEnabled = true,
            isAvailable = true,
            onEnabledChange = { requested = it },
        )

        composeTestRule.onNodeWithText("Record structural codes for this app run")
            .performClick()

        requested shouldBe false
    }

    @Test
    fun `unavailable build shows an accurate disabled state`() {
        setContent(
            isEnabled = false,
            isAvailable = false,
        )

        composeTestRule.onNodeWithText(
            "Available only in the Android 11+ Tracebox trial build.",
        ).assertIsDisplayed().assertIsNotEnabled()
    }

    private fun setContent(
        isEnabled: Boolean,
        isAvailable: Boolean,
        onEnabledChange: (Boolean) -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                TraceboxTrialContent(
                    isEnabled = isEnabled,
                    isAvailable = isAvailable,
                    onEnabledChange = onEnabledChange,
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
    }
}
