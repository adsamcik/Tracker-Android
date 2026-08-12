package com.adsamcik.tracker.app.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.adsamcik.tracker.R
import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackup
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseInfo
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseState
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportReport
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.coVerify
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val configFlow = MutableStateFlow(RetentionConfigState())
    private val retentionConfigStore: RetentionConfigStore = mockk()
    private val exportPlanStore: com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore = mockk()
    private val appContext: Context = mockk()
    private val contentResolver: ContentResolver = mockk()
    private val backupRepository: DatabaseMigrationBackupRepository = mockk()
    private val deletionService: CollectedDataDeletionService = mockk()
    private val legacyDatabaseRepository: LegacyDatabaseRepository = mockk()
    private val backupFlow = MutableStateFlow<DatabaseMigrationBackup?>(null)
    private val legacyStateFlow = MutableStateFlow(emptyLegacyState())
    private val preferences: Preferences = mockk()
    private val smartGoalNotificationsFlow = MutableStateFlow(true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        configFlow.value = RetentionConfigState()
        backupFlow.value = null
        legacyStateFlow.value = emptyLegacyState()

        every { retentionConfigStore.config } returns configFlow
        every { exportPlanStore.plans } returns MutableStateFlow(emptyList())
        every { appContext.contentResolver } returns contentResolver
        every { backupRepository.backups } returns backupFlow
        every { legacyDatabaseRepository.states } returns legacyStateFlow
        every { backupRepository.latestBackup() } returns null
        coEvery { deletionService.deleteAll() } just Runs
        every { appContext.getString(R.string.settings_smart_goal_notifications_key) } returns "smartGoalNotifications"
        every { preferences.observeBoolean("smartGoalNotifications", true) } returns smartGoalNotificationsFlow
        every { preferences.edit(any()) } just Runs
        coEvery { retentionConfigStore.update(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as (RetentionConfigState.() -> RetentionConfigState)
            configFlow.value = block(configFlow.value)
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = DataSettingsViewModel(
        appContext = appContext,
        retentionConfigStore = retentionConfigStore,
        exportPlanStore = exportPlanStore,
        preferences = preferences,
        backupRepository = backupRepository,
        legacyDatabaseRepository = legacyDatabaseRepository,
        dispatchers = TestDispatchersProvider(testDispatcher),
        deletionService = deletionService,
    )

    // =========================================================================
    // Initial state
    // =========================================================================

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `autoCleanupEnabled defaults to false`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem().autoCleanupEnabled shouldBe false
            }
        }

        @Test
        fun `dataRetentionYears defaults to 1`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem().dataRetentionYears shouldBe 1
            }
        }

        @Test
        fun `validated migration backup is exposed to settings`() = runTest(testDispatcher) {
            backupFlow.value = migrationBackup()

            val vm = createViewModel()

            vm.uiState.test {
                var state = awaitItem()
                if (state.migrationBackup == null) state = awaitItem()
                state.migrationBackup?.sourceVersion shouldBe 10
                state.migrationBackup?.targetVersion shouldBe 35
            }
        }

        @Test
        fun `backup state updates after creation and deletion`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem().migrationBackup shouldBe null
                backupFlow.value = migrationBackup()
                awaitItem().migrationBackup?.sourceVersion shouldBe 10
                backupFlow.value = null
                awaitItem().migrationBackup shouldBe null
            }
        }
    }

    // =========================================================================
    // Config observation
    // =========================================================================

    @Nested
    @DisplayName("Config observation")
    inner class ConfigObservation {

        @Test
        fun `autoCleanupEnabled reflects flow changes`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem().autoCleanupEnabled shouldBe false

                configFlow.value = configFlow.value.copy(autoCleanupEnabled = true)
                awaitItem().autoCleanupEnabled shouldBe true

                configFlow.value = configFlow.value.copy(autoCleanupEnabled = false)
                awaitItem().autoCleanupEnabled shouldBe false
            }
        }

        @Test
        fun `dataRetentionYears reflects flow changes`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem().dataRetentionYears shouldBe 1

                configFlow.value = configFlow.value.copy(dataRetentionYears = 3)
                awaitItem().dataRetentionYears shouldBe 3

                configFlow.value = configFlow.value.copy(dataRetentionYears = 5)
                awaitItem().dataRetentionYears shouldBe 5
            }
        }
    }

    // =========================================================================
    // Setters
    // =========================================================================

    @Nested
    @DisplayName("Setters")
    inner class Setters {

        @Test
        fun `setAutoCleanupEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem().autoCleanupEnabled shouldBe false

                vm.setAutoCleanupEnabled(true)
                awaitItem().autoCleanupEnabled shouldBe true
                configFlow.value.autoPurgeEnabled shouldBe true
            }
        }

        @Test
        fun `setAutoCleanupEnabled can disable after enabling`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem() // initial

                vm.setAutoCleanupEnabled(true)
                awaitItem().autoCleanupEnabled shouldBe true

                vm.setAutoCleanupEnabled(false)
                awaitItem().autoCleanupEnabled shouldBe false
                configFlow.value.autoPurgeEnabled shouldBe false
            }
        }

        @Test
        fun `setDataRetentionYears updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem() // initial

                vm.setDataRetentionYears(5)
                awaitItem().dataRetentionYears shouldBe 5
                configFlow.value.rawDataRetentionDays shouldBe 5 * 365
                configFlow.value.wifiCellRetentionDays shouldBe 5 * 365
                configFlow.value.tripRetentionDays shouldBe 5 * 365
            }
        }

        @Test
        fun `setDataRetentionYears accepts various values`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem() // initial

                vm.setDataRetentionYears(2)
                awaitItem().dataRetentionYears shouldBe 2
                configFlow.value.rawDataRetentionDays shouldBe 2 * 365

                vm.setDataRetentionYears(10)
                awaitItem().dataRetentionYears shouldBe 10
                configFlow.value.rawDataRetentionDays shouldBe 10 * 365
            }
        }

        @Test
        fun `setDataRetentionYears accepts zero as keep forever`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem() // initial

                vm.setDataRetentionYears(0)
                awaitItem().dataRetentionYears shouldBe 0
                configFlow.value.rawDataRetentionDays shouldBe 0
                configFlow.value.wifiCellRetentionDays shouldBe 0
                configFlow.value.tripRetentionDays shouldBe 0
                configFlow.value.dailySummaryRetentionDays shouldBe 0
                configFlow.value.explorationRetentionDays shouldBe 0
            }
        }
    }

    @Nested
    @DisplayName("Migration backup export")
    inner class MigrationBackupExport {

        @Test
        fun `exports validated backup to selected document`() = runTest(testDispatcher) {
            val backup = migrationBackup()
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            backupFlow.value = backup
            every { contentResolver.openOutputStream(uri, "rwt") } returns output
            every { backupRepository.exportLatest(output) } answers {
                output.write(byteArrayOf(1, 2, 3))
                backup
            }

            var result: MigrationBackupExportResult? = null
            val vm = createViewModel()

            vm.exportMigrationBackup(uri) { result = it }

            result shouldBe MigrationBackupExportResult.Success
            output.toByteArray().toList() shouldBe listOf<Byte>(1, 2, 3)
        }

        @Test
        fun `reports failure when selected document cannot be opened`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            backupFlow.value = migrationBackup()
            every { contentResolver.openOutputStream(uri, "rwt") } returns null
            var result: MigrationBackupExportResult? = null
            val vm = createViewModel()

            vm.exportMigrationBackup(uri) { result = it }

            result shouldBe MigrationBackupExportResult.Failure
            verify(exactly = 0) { contentResolver.delete(uri, null, null) }
        }

        @Test
        fun `removes partially written document when export fails`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            every { contentResolver.openOutputStream(uri, "rwt") } returns output
            every { contentResolver.delete(uri, null, null) } returns 1
            every { backupRepository.exportLatest(output) } answers {
                output.write(byteArrayOf(1, 2, 3))
                throw IOException("write failed")
            }
            var result: MigrationBackupExportResult? = null
            val vm = createViewModel()

            vm.exportMigrationBackup(uri) { result = it }

            result shouldBe MigrationBackupExportResult.Failure
            verify(exactly = 1) { contentResolver.delete(uri, null, null) }
        }

        @Test
        fun `reports failure when destination access is denied`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            every { contentResolver.openOutputStream(uri, "rwt") } throws SecurityException("denied")
            var result: MigrationBackupExportResult? = null
            val vm = createViewModel()

            vm.exportMigrationBackup(uri) { result = it }

            result shouldBe MigrationBackupExportResult.Failure
            verify(exactly = 0) { contentResolver.delete(uri, null, null) }
        }

        @Test
        fun `truncates partial document when provider cannot delete`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            var openCount = 0
            every { contentResolver.openOutputStream(uri, "rwt") } answers {
                if (openCount++ == 0) {
                    output
                } else {
                    output.reset()
                    ByteArrayOutputStream()
                }
            }
            every { contentResolver.delete(uri, null, null) } returns 0
            every { backupRepository.exportLatest(output) } answers {
                output.write(byteArrayOf(1, 2, 3))
                throw IOException("write failed")
            }
            var result: MigrationBackupExportResult? = null
            val vm = createViewModel()

            vm.exportMigrationBackup(uri) { result = it }

            result shouldBe MigrationBackupExportResult.Failure
            output.size() shouldBe 0
            verify(exactly = 2) { contentResolver.openOutputStream(uri, "rwt") }
        }

        @Test
        fun `requires manual cleanup when partial document cannot be removed`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            every { contentResolver.openOutputStream(uri, "rwt") } returnsMany listOf(output, null)
            every { contentResolver.delete(uri, null, null) } returns 0
            every { backupRepository.exportLatest(output) } answers {
                output.write(byteArrayOf(1, 2, 3))
                throw IOException("write failed")
            }
            var result: MigrationBackupExportResult? = null
            val vm = createViewModel()

            vm.exportMigrationBackup(uri) { result = it }

            result shouldBe MigrationBackupExportResult.CleanupRequired
        }
    }

    @Nested
    @DisplayName("Legacy database vault")
    inner class LegacyDatabaseVault {

        @Test
        fun `exposes legacy size status and aggregate report`() = runTest(testDispatcher) {
            legacyStateFlow.value = LegacyDatabaseState(
                database = legacyInfo(),
                importStatus = LegacyImportStatus.COMPLETE,
                report = LegacyImportReport(
                    sourceVersion = 26,
                    importedRows = mapOf("location_sample" to 4L, "step_interval" to 3L),
                    skippedRows = mapOf("achievement_progress" to 2L),
                    completedAtMs = 1234L,
                ),
                lastError = null,
                externallyExported = false,
            )

            createViewModel().uiState.test {
                var state = awaitItem()
                if (state.legacyDatabase == null) state = awaitItem()
                state.legacyDatabase?.sourceVersion shouldBe 26
                state.legacyDatabase?.sizeBytes shouldBe 4096L
                state.legacyDatabase?.importedRows shouldBe 7L
                state.legacyDatabase?.skippedRows shouldBe 2L
                state.legacyDatabase?.canDelete shouldBe true
            }
        }

        @Test
        fun `exports through repository and reports success`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            every { contentResolver.openOutputStream(uri, "rwt") } returns output
            every { legacyDatabaseRepository.export(output) } answers {
                output.write(byteArrayOf(4, 2))
                legacyInfo()
            }
            var result: LegacyDatabaseExportResult? = null

            createViewModel().exportLegacyDatabase(uri) { result = it }

            result shouldBe LegacyDatabaseExportResult.Success
            output.toByteArray().toList() shouldBe listOf<Byte>(4, 2)
            verify(exactly = 1) { legacyDatabaseRepository.export(output) }
        }

        @Test
        fun `deletes only through guarded repository action`() = runTest(testDispatcher) {
            every { legacyDatabaseRepository.delete() } returns 4096L
            var result: LegacyDatabaseDeleteResult? = null

            createViewModel().deleteLegacyDatabase { result = it }

            result shouldBe LegacyDatabaseDeleteResult.Success
            verify(exactly = 1) { legacyDatabaseRepository.delete() }
        }
    }

    @Nested
    @DisplayName("Collected data deletion")
    inner class CollectedDataDeletion {

        @Test
        fun `deletes all stores and resets export watermarks`() = runTest(testDispatcher) {
            var result: DataDeletionResult? = null
            val vm = createViewModel()

            vm.deleteAllCollectedData { result = it }

            result shouldBe DataDeletionResult.Success
            coVerify(exactly = 1) { deletionService.deleteAll() }
        }

        @Test
        fun `reports failure when migration backup deletion fails`() = runTest(testDispatcher) {
            coEvery { deletionService.deleteAll() } throws DatabaseMigrationBackupException("failed")
            var result: DataDeletionResult? = null
            val vm = createViewModel()

            vm.deleteAllCollectedData { result = it }

            result shouldBe DataDeletionResult.Failure
            coVerify(exactly = 1) { deletionService.deleteAll() }
        }

        @Test
        fun `reports failure when deletion encounters an ordinary filesystem error`() =
            runTest(testDispatcher) {
                coEvery { deletionService.deleteAll() } throws IOException("fsync failed")
                var result: DataDeletionResult? = null
                val vm = createViewModel()

                vm.deleteAllCollectedData { result = it }

                result shouldBe DataDeletionResult.Failure
                coVerify(exactly = 1) { deletionService.deleteAll() }
            }
	}

    private fun migrationBackup() = DatabaseMigrationBackup(
        file = File("main_database-v10-pre-v35.db"),
        sourceVersion = 10,
        targetVersion = 35,
        createdAtMs = 1_700_000_000_000L,
    )

    private fun legacyInfo() = LegacyDatabaseInfo(
        file = File("main_database"),
        sourceVersion = 26,
        sizeBytes = 4096L,
    )

    private companion object {
        fun emptyLegacyState() = LegacyDatabaseState(
            database = null,
            importStatus = LegacyImportStatus.NOT_STARTED,
            report = null,
            lastError = null,
            externallyExported = false,
        )
    }
}
