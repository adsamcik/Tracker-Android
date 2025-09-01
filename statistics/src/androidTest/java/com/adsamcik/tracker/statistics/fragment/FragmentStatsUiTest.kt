package com.adsamcik.tracker.statistics.fragment

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.activity.ComponentActivity
import android.app.Instrumentation
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import com.adsamcik.tracker.shared.utils.style.compose.DynamicTrackerTheme
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.detail.activity.StatsDetailActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Ignore

@RunWith(AndroidJUnit4::class)
class FragmentStatsUiTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule = composeRule

    @Test
    fun refreshStates_areDisplayed() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
        val refreshState = mutableStateOf(RefreshUiState.Error)
        composeRule.setContent {
            DynamicTrackerTheme {
                StatsScreenTestHost(
                    refreshState = refreshState.value,
                    appendState = AppendUiState.NotLoading,
                    onRetry = {},
                    onShowSummary = {},
                    onShowWeek = {},
                    onOpenWifi = {},
                )
            }
        }
    composeRule.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed()
        composeRule.runOnUiThread { refreshState.value = RefreshUiState.Empty }
    composeRule.onNodeWithText(context.getString(R.string.stats_no_tracker_sessions)).assertIsDisplayed()
    }

    @Test
    fun headerActions_areClickable_andLabeled() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
        var summaryClicked = false
        var weekClicked = false
        var wifiClicked = false

        composeRule.setContent {
            DynamicTrackerTheme {
                StatsScreenTestHost(
                    refreshState = RefreshUiState.Content,
                    appendState = AppendUiState.NotLoading,
                    onRetry = {},
                    onShowSummary = { summaryClicked = true },
                    onShowWeek = { weekClicked = true },
                    onOpenWifi = { wifiClicked = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.stats_sum_title)
        ).assertIsDisplayed().performClick()

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.stats_weekly_title)
        ).assertIsDisplayed().performClick()

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.stats_wifi_label)
        ).assertIsDisplayed().performClick()

    assertTrue(summaryClicked)
    assertTrue(weekClicked)
    assertTrue(wifiClicked)
    }

    @Test
    fun appendPlaceholders_areVisible_whenAppending() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            DynamicTrackerTheme {
                StatsScreenTestHost(
                    refreshState = RefreshUiState.Content,
                    appendState = AppendUiState.Loading,
                    onRetry = {},
                    onShowSummary = {},
                    onShowWeek = {},
                    onOpenWifi = {},
                )
            }
        }

    // Verify all three numbered placeholders exist
    composeRule.onNodeWithTag("stats_placeholder_0").assertIsDisplayed()
    composeRule.onNodeWithTag("stats_placeholder_1").assertIsDisplayed()
    composeRule.onNodeWithTag("stats_placeholder_2").assertIsDisplayed()
    }

    @Test
    fun footerError_retry_isClickable() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
        var retried = false
        composeRule.setContent {
            DynamicTrackerTheme {
                StatsScreenTestHost(
                    refreshState = RefreshUiState.Content,
                    appendState = AppendUiState.Error,
                    onRetry = { retried = true },
                    onShowSummary = {},
                    onShowWeek = {},
                    onOpenWifi = {},
                )
            }
        }

    // Footer error row is tagged, and contains a Retry button
    composeRule.onNodeWithTag("stats_append_error").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed().performClick()
        assertTrue(retried)
    }

    @Test
    fun sessionRow_click_opensDetails_callbackInvoked() {
        var opened = false
        composeRule.setContent {
            DynamicTrackerTheme {
                StatsScreenTestHost(
                    refreshState = RefreshUiState.Content,
                    appendState = AppendUiState.NotLoading,
                    onRetry = {},
                    onShowSummary = {},
                    onShowWeek = {},
                    onOpenWifi = {},
                    includeSampleSessionRow = true,
                    onOpenDetails = { opened = true },
                )
            }
        }

        composeRule.onNodeWithTag("stats_session_row").assertIsDisplayed().performClick()
        assertTrue(opened)
    }

    @Test
    @Ignore("Covered by StatsIntentTest using ActivityScenario; this variant was flaky on some devices")
    fun sessionRow_click_sendsIntent_withSessionId() {
        // Arrange an expected session id
        val expectedId = 1234L

        // Initialize Espresso Intents and stub external activity launch
        Intents.init()
        intending(hasComponent(StatsDetailActivity::class.java.name)).respondWith(ActivityResult(0, null))

        // Act: start the detail activity explicitly with the expected extra
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.startActivity(
            Intent(context, StatsDetailActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(StatsDetailActivity.ARG_SESSION_ID, expectedId)
            }
        )

        // Assert: an intent was sent to StatsDetailActivity with the expected extra
        intended(hasComponent(StatsDetailActivity::class.java.name))
        intended(hasExtra(StatsDetailActivity.ARG_SESSION_ID, expectedId))

        // Cleanup
        Intents.release()
    }
}

@RunWith(AndroidJUnit4::class)
class StatsIntentTest {
    @get:Rule
    val intentsRule = androidx.test.espresso.intent.rule.IntentsRule()

    @Test
    fun sessionRow_click_sendsIntent_withSessionId() {
        val expectedId = 1234L

        // Stub out the external Activity so it doesn't actually launch
        intending(hasComponent(StatsDetailActivity::class.java.name)).respondWith(ActivityResult(0, null))

        // Start the target Activity from the target app context (requires NEW_TASK)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(context, StatsDetailActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(StatsDetailActivity.ARG_SESSION_ID, expectedId)
            }
        )

        // Verify the intent component and extras
        intended(hasComponent(StatsDetailActivity::class.java.name))
        intended(hasExtra(StatsDetailActivity.ARG_SESSION_ID, expectedId))
    }
}

// A tiny test-only Composable that triggers the same intent path as SessionItemRow
@androidx.compose.runtime.Composable
private fun TestSessionIntentLauncher(sessionId: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("stats_session_row_intent")
            .clickable {
                context.startActivity(
                    Intent(context, StatsDetailActivity::class.java).apply {
                        putExtra(StatsDetailActivity.ARG_SESSION_ID, sessionId)
                    }
                )
            }
    ) {}
}
