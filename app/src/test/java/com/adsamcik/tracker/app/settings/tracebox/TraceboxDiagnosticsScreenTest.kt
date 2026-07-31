package com.adsamcik.tracker.app.settings.tracebox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.adsamcik.tracker.app.tracebox.TraceboxDiagnosticsState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import dev.tracebox.api.Readiness
import dev.tracebox.api.TraceboxHealth
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraceboxDiagnosticsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `status and package controls expose honest runtime state`() {
        setContent(
            state = state(
                readiness = Readiness.DURABLE,
                health = TraceboxHealth.READY,
                packageReady = false,
            ),
        )

        composeTestRule.onNodeWithText("Durable capture is ready").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ready").assertIsDisplayed()
        scrollTo("Review and approve package")
        composeTestRule.onNodeWithText("Review and approve package").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save approved package").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Share approved package").assertIsNotEnabled()
    }

    @Test
    fun `capture package and deletion actions delegate independently`() {
        var enabled: Boolean? = null
        var reviewed = 0
        var saved = 0
        var shared = 0
        var deleted = 0
        setContent(
            state = state(packageReady = true),
            onEnabledChange = { enabled = it },
            onReviewPackage = { reviewed += 1 },
            onSavePackage = { saved += 1 },
            onSharePackage = { shared += 1 },
            onDeleteAll = { deleted += 1 },
        )

        composeTestRule.onNodeWithText("Record crash diagnostics").performClick()
        enabled shouldBe false

        scrollTo("Review and approve package")
        composeTestRule.onNodeWithText("Review and approve package").performClick()
        scrollTo("Save approved package")
        composeTestRule.onNodeWithText("Save approved package").performClick()
        scrollTo("Share approved package")
        composeTestRule.onNodeWithText("Share approved package").performClick()
        reviewed shouldBe 1
        saved shouldBe 1
        shared shouldBe 1

        scrollTo("Delete all Tracebox data")
        composeTestRule.onNodeWithText("Delete all Tracebox data").performClick()
        deleted shouldBe 1
    }

    @Test
    fun `partial save result does not claim complete delivery`() {
        setContent(
            state = state(packageReady = true),
            message = TraceboxUiMessage.SavePartial(bytes = 512, cancelled = true),
        )

        scrollTo("Saving was cancelled after 512 bytes")
        composeTestRule.onNodeWithText(
            "Saving was cancelled after 512 bytes",
            substring = true,
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "incomplete package",
            substring = true,
        ).assertIsDisplayed()
    }

    private fun setContent(
        state: TraceboxDiagnosticsState,
        message: TraceboxUiMessage? = null,
        onEnabledChange: (Boolean) -> Unit = {},
        onReviewPackage: () -> Unit = {},
        onSavePackage: () -> Unit = {},
        onSharePackage: () -> Unit = {},
        onDeleteAll: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                TraceboxDiagnosticsContent(
                    state = state,
                    message = message,
                    onEnabledChange = onEnabledChange,
                    onReviewPackage = onReviewPackage,
                    onSavePackage = onSavePackage,
                    onSharePackage = onSharePackage,
                    onDeleteAll = onDeleteAll,
                )
            }
        }
    }

    private fun state(
        readiness: Readiness = Readiness.DURABLE,
        health: TraceboxHealth = TraceboxHealth.READY,
        packageReady: Boolean = false,
    ) = TraceboxDiagnosticsState(
        readiness = readiness,
        health = health,
        operationInProgress = false,
        packageReady = packageReady,
    )

    private fun scrollTo(text: String) {
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
    }
}
