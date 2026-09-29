package com.adsamcik.tracker.feature.tracker.notification

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSetting
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationManagementAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeFlagsAndEditUseAppResourcesAndEditInvokesItsAction() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = application.createConfigurationContext(
            Configuration(application.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag("cs-CZ")))
            },
        )
        var edits = 0
        var movesDown = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    NotificationItemRow(
                        item = TrackerNotificationSetting(
                            id = "duration",
                            titleRes = R.string.duration_title,
                            isInTitle = true,
                            isInContent = true,
                        ),
                        rowHeight = 72.dp,
                        isDragging = false,
                        onEdit = { edits += 1 },
                        canMoveUp = false,
                        canMoveDown = true,
                        onMoveUp = {},
                        onMoveDown = { movesDown += 1 },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.hint_customize_notification_show_in_title),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.hint_customize_notification_show_in_content),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(com.adsamcik.tracker.shared.base.R.string.generic_edit),
        ).performClick()
        assertEquals(1, edits)
        val actions = composeRule.onNodeWithTag("notification_item_duration")
            .fetchSemanticsNode().config[SemanticsProperties.CustomActions]
        assertEquals(listOf(context.getString(R.string.settings_notification_move_down)), actions.map { it.label })
        composeRule.runOnIdle { actions.single().action() }
        assertEquals(1, movesDown)
    }
}
