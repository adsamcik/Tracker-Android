package com.adsamcik.tracker.app.activity

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDatabaseRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `blocked recovery offers Retry but cannot export preserved files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var retries = 0
        compose.setContent {
            AppTheme(useDynamicColor = false) {
                LegacyDatabaseRecoveryScreen(
                    message = null, sizeBytes = 4096L, canDelete = false,
                    onRetry = { retries++ }, onDelete = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.legacy_database_export)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.legacy_database_import_failed_message)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.legacy_database_delete)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.legacy_database_retry)).assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test fun `removing Export preserves confirmed eligible legacy deletion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var deleted = false
        compose.setContent {
            AppTheme(useDynamicColor = false) {
                LegacyDatabaseRecoveryScreen(
                    message = null, sizeBytes = 4096L, canDelete = true,
                    onRetry = {}, onDelete = { deleted = true },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.legacy_database_export)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.legacy_database_delete)).performClick()
        assertEquals(false, deleted)
        compose.onNodeWithText(context.getString(R.string.legacy_database_delete_confirm_title)).assertIsDisplayed()
    }
}
