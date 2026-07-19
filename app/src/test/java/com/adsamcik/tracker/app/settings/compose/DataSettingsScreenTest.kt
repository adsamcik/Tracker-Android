package com.adsamcik.tracker.app.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.MigrationBackupUiInfo
import com.adsamcik.tracker.app.settings.data.CollectedDataDeletionDialog
import com.adsamcik.tracker.app.settings.data.MigrationBackupExportAvailability
import com.adsamcik.tracker.app.settings.data.MigrationBackupExportSetting
import com.adsamcik.tracker.app.settings.data.MigrationBackupWarningDialog
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the data settings screen layout.
 * Since DataSettingsScreen uses hiltViewModel(), we test with a reconstructed layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun DataSettingsTestLayout(
        autoCleanupEnabled: Boolean = false,
        dataRetentionYears: Int = 1,
        incrementalBackupsEnabled: Boolean = true,
        smartGoalNotificationsEnabled: Boolean = true,
        onExportClick: () -> Unit = {},
        onImportClick: () -> Unit = {},
        onAutoCleanupChanged: (Boolean) -> Unit = {},
        onIncrementalBackupsChanged: (Boolean) -> Unit = {},
        onSmartGoalNotificationsChanged: (Boolean) -> Unit = {},
        onDeleteAllClick: () -> Unit = {},
        onResetWatermarksClick: () -> Unit = {},
    ) {
        var showDeleteDialog by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("dataSettingsList"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            // Export section
            item { SectionHeader("Export") }
            item {
                SettingsItem(
                    title = "Export data",
                    subtitle = "Export tracking data",
                    icon = Icons.Default.FileUpload,
                    onClick = onExportClick,
                )
            }
            item {
                SwitchSettingsItem(
                    title = "Incremental backups",
                    subtitle = "Only export new data since last export",
                    checked = incrementalBackupsEnabled,
                    onCheckedChange = onIncrementalBackupsChanged,
                )
            }
            item {
                SettingsItem(
                    title = "Reset export watermarks",
                    subtitle = "Next export will include all data",
                    icon = Icons.Default.Refresh,
                    onClick = onResetWatermarksClick,
                )
            }

            // Import section
            item {
                SettingsItem(
                    title = "Import",
                    subtitle = "Import from GPX, KML, ZIP",
                    icon = Icons.Default.FileDownload,
                    onClick = onImportClick,
                )
            }

            // Data management section
            item { SectionHeader("Data Management") }
            item {
                val autoCleanupSummary = if (dataRetentionYears == 0) {
                    "Keep data forever; auto-cleanup will not delete old data"
                } else {
                    "Automatically remove data older than $dataRetentionYears " +
                        if (dataRetentionYears == 1) "year" else "years"
                }
                SwitchSettingsItem(
                    title = "Auto cleanup old data",
                    subtitle = autoCleanupSummary,
                    checked = autoCleanupEnabled,
                    onCheckedChange = onAutoCleanupChanged,
                )
            }
            item {
                SwitchSettingsItem(
                    title = "Smart goal notifications",
                    checked = smartGoalNotificationsEnabled,
                    onCheckedChange = onSmartGoalNotificationsChanged,
                )
            }

            // Danger zone
            item { SectionHeader("Danger Zone") }
            item {
                SettingsItem(
                    title = "Delete all collected data",
                    subtitle = "This action cannot be undone",
                    icon = Icons.Default.DeleteForever,
                    onClick = {
                        showDeleteDialog = true
                    },
                )
            }
        }

        if (showDeleteDialog) {
            CollectedDataDeletionDialog(
                onDismiss = { showDeleteDialog = false },
                onConfirm = {
                    showDeleteDialog = false
                    onDeleteAllClick()
                },
            )
        }
    }

    @Test
    fun displaysExportSection() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Export data").assertIsDisplayed()
    }

    @Test
    fun displaysImportSetting() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Import", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysDataManagementSection() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        // "Auto cleanup old data" sits below several settings rows in a LazyColumn
        // — scroll to it before asserting visibility so the test viewport doesn't
        // silently hide it off-screen.
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Auto cleanup old data"))
        composeTestRule.onNodeWithText("Data Management").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto cleanup old data").assertIsDisplayed()
    }

    @Test
    fun autoCleanupSummaryReflectsSelectedYears() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(dataRetentionYears = 3) }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Automatically remove data older than 3 years"))
        composeTestRule.onNodeWithText("Automatically remove data older than 3 years").assertIsDisplayed()
    }

    @Test
    fun autoCleanupSummaryReflectsKeepForever() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(dataRetentionYears = 0) }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Keep data forever", substring = true))
        composeTestRule.onNodeWithText("Keep data forever", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysDangerZone() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Delete all collected data"))
        composeTestRule.onNodeWithText("Delete all collected data").assertIsDisplayed()
    }

    @Test
    fun exportClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(onExportClick = { clicked = true }) }
        }
        composeTestRule.onNodeWithText("Export data").performClick()
        clicked shouldBe true
    }

    @Test
    fun migrationBackupExportAppearsOnlyWhenAvailable() {
        composeTestRule.setContent {
            AppTheme {
                MigrationBackupExportAvailability(
                    backup = migrationBackup(),
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Export pre-upgrade safety backup").assertIsDisplayed()
    }

    @Test
    fun migrationBackupExportIsAbsentWhenUnavailable() {
        composeTestRule.setContent {
            AppTheme {
                MigrationBackupExportAvailability(
                    backup = null,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Export pre-upgrade safety backup").assertDoesNotExist()
    }

    @Test
    fun migrationBackupExportCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme {
                MigrationBackupExportSetting(
                    backup = migrationBackup(),
                    onClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Export pre-upgrade safety backup").performClick()
        clicked shouldBe true
    }

    @Test
    fun migrationBackupWarningExportsExpectedFile() {
        var exportedFile: String? = null
        composeTestRule.setContent {
            AppTheme {
                MigrationBackupWarningDialog(
                    backup = migrationBackup(),
                    onDismiss = {},
                    onExport = { exportedFile = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Export backup").performClick()
        exportedFile shouldBe "main_database-v10-pre-v35.db"
    }

    @Test
    fun importClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(onImportClick = { clicked = true }) }
        }
        composeTestRule.onNodeWithText("Import", substring = true).performClick()
        clicked shouldBe true
    }

    @Test
    fun autoCleanupToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onAutoCleanupChanged = { newValue = it })
            }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Auto cleanup old data"))
        composeTestRule.onNodeWithText("Auto cleanup old data").performClick()
        newValue shouldBe true // Was false
    }

    @Test
    fun incrementalBackupsToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onIncrementalBackupsChanged = { newValue = it })
            }
        }
        composeTestRule.onNodeWithText("Incremental backups").performClick()
        newValue shouldBe false // Was true
    }

    @Test
    fun smartGoalNotificationsToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onSmartGoalNotificationsChanged = { newValue = it })
            }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Smart goal notifications"))
        composeTestRule.onNodeWithText("Smart goal notifications").performClick()
        newValue shouldBe false // Was true
    }

    @Test
    fun deleteAllShowsConfirmationDialog() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Delete all collected data"))
        composeTestRule.onNodeWithText("Delete all collected data").performClick()
        composeTestRule.onNodeWithText("Are you sure", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun deleteDialogDismissesOnCancel() {
        var deleted = false
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(onDeleteAllClick = { deleted = true }) }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Delete all collected data"))
        composeTestRule.onNodeWithText("Delete all collected data").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Are you sure", substring = true).assertDoesNotExist()
        deleted shouldBe false
    }

    @Test
    fun deleteRunsOnlyAfterConfirmation() {
        var deleted = false
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(onDeleteAllClick = { deleted = true }) }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Delete all collected data"))

        composeTestRule.onNodeWithText("Delete all collected data").performClick()
        deleted shouldBe false
        composeTestRule.onNodeWithText("Delete").performClick()

        deleted shouldBe true
    }

    @Test
    fun resetWatermarksClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onResetWatermarksClick = { clicked = true })
            }
        }
        composeTestRule.onNodeWithText("Reset export watermarks").performClick()
        clicked shouldBe true
    }

    private fun migrationBackup() = MigrationBackupUiInfo(
        fileName = "main_database-v10-pre-v35.db",
        sourceVersion = 10,
        targetVersion = 35,
        createdAtMs = 1_700_000_000_000L,
    )
}
