package com.adsamcik.tracker.app.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.adsamcik.tracker.R
import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
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
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigApplyResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.ApprovedRetentionPolicy
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigurationApprovalResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionPolicyApprovalStatus
import com.adsamcik.tracker.shared.preferences.retention.RetentionPolicyStage
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
    private val approvalStatusFlow =
        MutableStateFlow(RetentionPolicyApprovalStatus.UNAPPROVED)
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
    private val retentionAuthorityProducer: RetentionAuthorityProducer = mockk()
    private val purposeSettingsReconciler: TrackingPurposeSettingsReconciler = mockk()
    private val exportGate = ExportGate()
    private val dataBackupRestore = mockk<com.adsamcik.tracker.impexp.backup.DataBackupRestore>()
    private var configurationGeneration = 0L
    private lateinit var stagedPolicy: ApprovedRetentionPolicy

    @BeforeEach
    // Keep the shared settings fixture reset and its ordered prepare/commit/approval callbacks together.
    @Suppress("LongMethod")
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        configFlow.value = RetentionConfigState()
        approvalStatusFlow.value = RetentionPolicyApprovalStatus.UNAPPROVED
        backupFlow.value = null
        legacyStateFlow.value = emptyLegacyState()
        configurationGeneration = 0L
        exportGate.reset()

        every { retentionConfigStore.config } returns configFlow
        every { retentionConfigStore.approvalStatus } returns approvalStatusFlow
        every { exportPlanStore.plans } returns MutableStateFlow(emptyList())
        every { appContext.contentResolver } returns contentResolver
        every { backupRepository.backups } returns backupFlow
        every { legacyDatabaseRepository.states } returns legacyStateFlow
        every { backupRepository.latestBackup() } returns null
        coEvery { deletionService.deleteAll() } returns CollectedDataDeletionCompletion.Complete
        every { appContext.getString(R.string.settings_smart_goal_notifications_key) } returns "smartGoalNotifications"
        every { preferences.observeBoolean("smartGoalNotifications", true) } returns smartGoalNotificationsFlow
        every { preferences.edit(any()) } just Runs
        coEvery {
            retentionConfigStore.updateWithApproval(any(), any(), any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as
                (RetentionConfigState.() -> RetentionConfigState)
            @Suppress("UNCHECKED_CAST")
            val prepare = invocation.args[1] as
                suspend (RetentionPolicyStage) -> RetentionConfigurationApprovalResult
            @Suppress("UNCHECKED_CAST")
            val approve = invocation.args[2] as
                suspend (RetentionPolicyStage) -> RetentionConfigurationApprovalResult
            val proposed = block(configFlow.value)
            configurationGeneration++
            stagedPolicy = ApprovedRetentionPolicy(
                configurationGeneration = configurationGeneration,
                revision = configurationGeneration,
                opaquePolicyId = "policy-$configurationGeneration",
                configurationChecksum = "0".repeat(64),
                integrityChecksum = "1".repeat(64),
            )
            val stage = RetentionPolicyStage(stagedPolicy, approvalRequired = true)
            approvalStatusFlow.value = RetentionPolicyApprovalStatus.PENDING
            val prepared = prepare(stage)
            if (prepared is RetentionConfigurationApprovalResult.Unavailable) {
                return@coAnswers RetentionConfigApplyResult(stage, false, prepared)
            }
            configFlow.value = proposed
            val approved = approve(stage)
            if (approved !is RetentionConfigurationApprovalResult.Unavailable) {
                approvalStatusFlow.value = RetentionPolicyApprovalStatus.APPROVED
            }
            RetentionConfigApplyResult(stage, true, approved)
        }
        coEvery { retentionAuthorityProducer.preparePendingConfiguration(any()) } answers {
            RetentionConfigurationApprovalResult.Prepared(stagedPolicy)
        }
        coEvery { retentionAuthorityProducer.reconcilePendingConfiguration(any()) } answers {
            approvalStatusFlow.value = RetentionPolicyApprovalStatus.APPROVED
            RetentionConfigurationApprovalResult.Approved(stagedPolicy)
        }
        coEvery { purposeSettingsReconciler.reconcileCurrentSettings() } returns
            TrackingPurposeSettingsReconciliationResult.Complete(emptySet())
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
        startupGate = exportGate,
        dataBackupRestore = dataBackupRestore,
        retentionAuthorityProducer = retentionAuthorityProducer,
        purposeSettingsReconciler = purposeSettingsReconciler,
    )

    // =========================================================================
    // Initial state
    // =========================================================================

    @Test
    fun `backup inspection waits for explicit confirmation before replacing data`() = runTest(testDispatcher) {
        val uri: Uri = mockk()
        val preview = com.adsamcik.tracker.impexp.backup.DataBackupPreview("owned-token", 0L)
        coEvery { dataBackupRestore.inspect(uri) } returns
            com.adsamcik.tracker.impexp.backup.DataBackupInspection.Ready(preview)
        coEvery { dataBackupRestore.restore(preview) } returns
            com.adsamcik.tracker.impexp.backup.DataBackupRestoreResult.Restored
        val vm = createViewModel()
        vm.requestFileImport(uri)
        vm.fileImportState.value.backupChoice shouldBe preview
        coVerify(exactly = 0) { dataBackupRestore.restore(any()) }
        vm.restoreConfirmedBackup()
        coVerify(exactly = 1) { dataBackupRestore.restore(preview) }
        vm.fileImportState.value shouldBe FileImportUiState()
    }

    @Test
    fun `canceling backup confirmation removes its staged copy without restoring`() = runTest(testDispatcher) {
        val uri: Uri = mockk()
        val preview = com.adsamcik.tracker.impexp.backup.DataBackupPreview("canceled-token", 0L)
        coEvery { dataBackupRestore.inspect(uri) } returns
            com.adsamcik.tracker.impexp.backup.DataBackupInspection.Ready(preview)
        coEvery { dataBackupRestore.discardPreview(preview.token) } just Runs
        val vm = createViewModel()
        vm.requestFileImport(uri)
        vm.dismissFileImport()
        coVerify(exactly = 1) { dataBackupRestore.discardPreview(preview.token) }
        coVerify(exactly = 0) { dataBackupRestore.restore(any()) }
        vm.fileImportState.value shouldBe FileImportUiState()
    }

    @Test
    fun `canceling backup confirmation reports cleanup failure without restoring`() = runTest(testDispatcher) {
        val uri: Uri = mockk()
        val preview = com.adsamcik.tracker.impexp.backup.DataBackupPreview("failed-cleanup-token", 0L)
        coEvery { dataBackupRestore.inspect(uri) } returns
            com.adsamcik.tracker.impexp.backup.DataBackupInspection.Ready(preview)
        coEvery { dataBackupRestore.discardPreview(preview.token) } throws java.io.IOException("Storage unavailable")
        val vm = createViewModel()
        vm.requestFileImport(uri)
        vm.dismissFileImport()
        coVerify(exactly = 1) { dataBackupRestore.discardPreview(preview.token) }
        coVerify(exactly = 0) { dataBackupRestore.restore(any()) }
        vm.fileImportState.value shouldBe FileImportUiState(error = true)
    }

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
                if (state.migrationBackup == null) {
                    state = awaitItem()
                }
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
        fun `retention setting surfaces typed purpose reconciliation debt`() =
            runTest(testDispatcher) {
                val debt = TrackingPurposeSettingsReconciliationDebt(
                    listOf(
                        TrackingPurposeSettingsReconciliationFailure(
                            AmbientTrackingSource.STEPS,
                            TrackingPurposeSettingsReconciliationFailureReason
                                .OWNER_OPERATION_IN_PROGRESS,
                        ),
                    ),
                )
                coEvery { purposeSettingsReconciler.reconcileCurrentSettings() } returns
                    TrackingPurposeSettingsReconciliationResult.Debt(debt)
                val vm = createViewModel()

                vm.uiState.test {
                    awaitItem()
                    vm.setAutoCleanupEnabled(true)
                    var state = awaitItem()
                    while (state.purposeSettingsReconciliationDebt == null ||
                        state.ambientStepsLifecycleFailure == null
                    ) state = awaitItem()
                    state.purposeSettingsReconciliationDebt shouldBe debt
                    state.ambientStepsLifecycleFailure shouldBe
                        AmbientStepsSettingsReconciliationFailure.DURABLE_AUTHORITY_REJECTED
                    cancelAndIgnoreRemainingEvents()
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

        @Test
        fun `both retention setters approve and reconcile bounded lifecycle`() =
            runTest(testDispatcher) {
                val vm = createViewModel()

                vm.setAutoCleanupEnabled(true)
                vm.setDataRetentionYears(2)

                coVerify(exactly = 2) {
                    retentionAuthorityProducer.preparePendingConfiguration(any())
                }
                coVerify(exactly = 2) {
                    retentionAuthorityProducer.reconcilePendingConfiguration(any())
                }
                coVerify(exactly = 2) {
                    purposeSettingsReconciler.reconcileCurrentSettings()
                }
            }

        @Test
        fun `retention approval failure remains visible and pending`() = runTest(testDispatcher) {
            coEvery {
                retentionAuthorityProducer.reconcilePendingConfiguration(any())
            } returns RetentionConfigurationApprovalResult.Unavailable(
                RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
            )
            coEvery { purposeSettingsReconciler.reconcileCurrentSettings() } returns
                TrackingPurposeSettingsReconciliationResult.Debt(
                    TrackingPurposeSettingsReconciliationDebt(
                        listOf(
                            TrackingPurposeSettingsReconciliationFailure(
                                AmbientTrackingSource.STEPS,
                                TrackingPurposeSettingsReconciliationFailureReason
                                    .RETENTION_AUTHORITY_UNAVAILABLE,
                            ),
                        ),
                    ),
                )
            val vm = createViewModel()

            vm.uiState.test {
                awaitItem()
                vm.setDataRetentionYears(3)
                var state = awaitItem()
                while (state.retentionPolicyFailure == null ||
                    state.retentionPolicyApprovalStatus != RetentionPolicyApprovalStatus.PENDING ||
                    state.ambientStepsLifecycleFailure == null
                ) state = awaitItem()
                state.retentionPolicyApprovalStatus shouldBe
                    RetentionPolicyApprovalStatus.PENDING
                state.retentionPolicyFailure shouldBe
                    RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION
                state.ambientStepsLifecycleFailure shouldBe
                    AmbientStepsSettingsReconciliationFailure.DURABLE_AUTHORITY_REJECTED
            }
        }

        @Test
        fun `prepublication failure leaves previous cleanup setting visible`() =
            runTest(testDispatcher) {
                coEvery {
                    retentionAuthorityProducer.preparePendingConfiguration(any())
                } returns RetentionConfigurationApprovalResult.Unavailable(
                    RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
                )
                val vm = createViewModel()

                vm.uiState.test {
                    awaitItem()
                    vm.setAutoCleanupEnabled(true)
                    var state = awaitItem()
                    while (state.retentionPolicyFailure == null ||
                        state.retentionPolicyApprovalStatus != RetentionPolicyApprovalStatus.PENDING
                    ) state = awaitItem()
                    state.autoCleanupEnabled shouldBe false
                    state.retentionPolicyApprovalStatus shouldBe
                        RetentionPolicyApprovalStatus.PENDING
                    state.retentionPolicyFailure shouldBe
                        RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE
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
            every { backupRepository.exportLatest(any()) } answers {
                firstArg<java.io.OutputStream>().write(byteArrayOf(1, 2, 3))
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
            every { backupRepository.exportLatest(any()) } answers {
                firstArg<java.io.OutputStream>().write(byteArrayOf(1, 2, 3))
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
            every { backupRepository.exportLatest(any()) } answers {
                firstArg<java.io.OutputStream>().write(byteArrayOf(1, 2, 3))
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
            every { backupRepository.exportLatest(any()) } answers {
                firstArg<java.io.OutputStream>().write(byteArrayOf(1, 2, 3))
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
                if (state.legacyDatabase == null) {
                    state = awaitItem()
                }
                state.legacyDatabase?.sourceVersion shouldBe 26
                state.legacyDatabase?.sizeBytes shouldBe 4096L
                state.legacyDatabase?.importedRows shouldBe 7L
                state.legacyDatabase?.skippedRows shouldBe 2L
                state.legacyDatabase?.canDelete shouldBe false
                legacyStateFlow.value = legacyStateFlow.value.copy(externallyExported = true)
                awaitItem().legacyDatabase?.canDelete shouldBe true
            }
        }

        @Test
        fun `exports through repository and reports success`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            every { contentResolver.openOutputStream(uri, "rwt") } returns output
            every { legacyDatabaseRepository.export(any()) } answers {
                firstArg<java.io.OutputStream>().write(byteArrayOf(4, 2))
                legacyInfo()
            }
            var result: LegacyDatabaseExportResult? = null

            createViewModel().exportLegacyDatabase(uri) { result = it }

            result shouldBe LegacyDatabaseExportResult.Success
            output.toByteArray().toList() shouldBe listOf<Byte>(4, 2)
            verify(exactly = 1) { legacyDatabaseRepository.export(any()) }
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

        @Test
        fun `reports failure while deletion retains typed reconciliation debt`() =
            runTest(testDispatcher) {
                coEvery { deletionService.deleteAll() } returns
                    CollectedDataDeletionCompletion.Retryable(
						CollectedDataDeletionReconciliationFailure.PurposeSettings(
							TrackingPurposeSettingsReconciliationDebt(listOf(
								TrackingPurposeSettingsReconciliationFailure(
									AmbientTrackingSource.STEPS,
									TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED,
								),
							)),
						),
                    )
                var result: DataDeletionResult? = null
                val vm = createViewModel()

                vm.deleteAllCollectedData { result = it }

                result shouldBe DataDeletionResult.Failure
                coVerify(exactly = 1) { deletionService.deleteAll() }
            }
	}

    @Nested
    @DisplayName("Preserved-data export admission")
    inner class PreservedDataExportAdmission {
        @Test
        fun `blocked startup cannot open either recovery export destination`() = runTest(testDispatcher) {
            exportGate.startupResult = TrackingStartupResult.Blocked(TrackingStartupStage.STORAGE, "DATABASE_CLOSED")
            val uri: Uri = mockk()
            var backupResult: MigrationBackupExportResult? = null
            var legacyResult: LegacyDatabaseExportResult? = null
            val vm = createViewModel()
            vm.exportMigrationBackup(uri) { backupResult = it }
            vm.exportLegacyDatabase(uri) { legacyResult = it }
            backupResult shouldBe MigrationBackupExportResult.Failure
            legacyResult shouldBe LegacyDatabaseExportResult.Failure
            exportGate.transferCount shouldBe 0
            verify(exactly = 0) { contentResolver.openOutputStream(any(), any()) }
            verify(exactly = 0) { backupRepository.exportLatest(any()) }
            verify(exactly = 0) { legacyDatabaseRepository.export(any()) }
        }

        @Test
        fun `retryable database open also withholds both exports`() = runTest(testDispatcher) {
            exportGate.startupResult = TrackingStartupResult.RetryableFailure(TrackingStartupStage.STORAGE, "LOCKED")
            val uri: Uri = mockk()
            var backupResult: MigrationBackupExportResult? = null
            var legacyResult: LegacyDatabaseExportResult? = null
            val vm = createViewModel()
            vm.exportMigrationBackup(uri) { backupResult = it }
            vm.exportLegacyDatabase(uri) { legacyResult = it }
            backupResult shouldBe MigrationBackupExportResult.Failure
            legacyResult shouldBe LegacyDatabaseExportResult.Failure
            verify(exactly = 0) { contentResolver.openOutputStream(any(), any()) }
        }

        @Test
        fun `generation change during startup reconciliation rejects a stale export`() = runTest(testDispatcher) {
            exportGate.advanceGenerationOnReconcile = true
            val uri: Uri = mockk()
            var result: MigrationBackupExportResult? = null
            createViewModel().exportMigrationBackup(uri) { result = it }
            result shouldBe MigrationBackupExportResult.Failure
            exportGate.transferCount shouldBe 0
            verify(exactly = 0) { contentResolver.openOutputStream(any(), any()) }
        }

        @Test
        fun `ready retained history exports while collection is off`() = runTest(testDispatcher) {
            exportGate.collectionAllowed = false
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            every { contentResolver.openOutputStream(uri, "rwt") } returns output
            every { backupRepository.exportLatest(any()) } answers {
                exportGate.transferJob?.isActive shouldBe true
                firstArg<java.io.OutputStream>().write(byteArrayOf(7, 8))
                migrationBackup()
            }
            var result: MigrationBackupExportResult? = null
            createViewModel().exportMigrationBackup(uri) { result = it }
            result shouldBe MigrationBackupExportResult.Success
            exportGate.transferCount shouldBe 1
            output.toByteArray().toList() shouldBe listOf<Byte>(7, 8)
        }

        @Test
        fun `cancelled preserved export closes and cleans before leaving its transfer`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            var closed = false
            val output = object : ByteArrayOutputStream() {
                override fun close() { closed = true; super.close() }
            }
            every { contentResolver.openOutputStream(uri, "rwt") } returns output
            every { contentResolver.delete(uri, null, null) } answers {
                closed shouldBe true
                exportGate.transferJob?.isCompleted shouldBe false
                1
            }
            every { backupRepository.exportLatest(any()) } answers {
                val stream = firstArg<java.io.OutputStream>()
                stream.write(1)
                requireNotNull(exportGate.transferJob).cancel()
                stream.write(2)
                migrationBackup()
            }
            var delivered = false
            val vm = createViewModel()
            vm.exportMigrationBackup(uri) { delivered = true }
            delivered shouldBe false
            closed shouldBe true
            output.toByteArray().toList() shouldBe listOf<Byte>(1)
            verify(exactly = 1) { contentResolver.delete(uri, null, null) }
            exportGate.lastTransferJob?.isCompleted shouldBe true

            every { backupRepository.exportLatest(any()) } answers {
                firstArg<java.io.OutputStream>().write(3)
                migrationBackup()
            }
            vm.exportMigrationBackup(uri) { delivered = true }
            delivered shouldBe true
        }

        @Test
        fun `provider cleanup failures cannot replace the original export cancellation`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            var closed = false
            val output = object : ByteArrayOutputStream() {
                override fun close() { closed = true; super.close() }
            }
            val original = CancellationException("Original export cancellation")
            val cleanupFailure = CancellationException("Document provider cleanup failed")
            var opens = 0
            every { contentResolver.openOutputStream(uri, "rwt") } answers {
                if (opens++ == 0) {
                    output
                } else {
                    throw cleanupFailure
                }
            }
            every { contentResolver.delete(uri, null, null) } answers {
                closed shouldBe true
                exportGate.transferJob?.isCompleted shouldBe false
                throw IllegalStateException("Document provider failed")
            }
            every { backupRepository.exportLatest(any()) } answers {
                firstArg<java.io.OutputStream>().write(1)
                throw original
            }
            var delivered = false
            createViewModel().exportMigrationBackup(uri) { delivered = true }
            delivered shouldBe false
            closed shouldBe true
            exportGate.observedCancellation shouldBe original
            original.suppressed.toList() shouldBe listOf(cleanupFailure)
            exportGate.lastTransferJob?.isCompleted shouldBe true
        }

        @Test
        fun `document provider runtime failure returns cleanup required instead of escaping`() = runTest(testDispatcher) {
            val uri: Uri = mockk()
            val output = ByteArrayOutputStream()
            var opens = 0
            every { contentResolver.openOutputStream(uri, "rwt") } answers {
                if (opens++ == 0) {
                    output
                } else {
                    throw IllegalStateException("Provider unavailable")
                }
            }
            every { contentResolver.delete(uri, null, null) } throws IllegalStateException("Provider unavailable")
            every { backupRepository.exportLatest(any()) } answers {
                firstArg<java.io.OutputStream>().write(1)
                throw IOException("Copy failed")
            }
            var result: MigrationBackupExportResult? = null
            createViewModel().exportMigrationBackup(uri) { result = it }
            result shouldBe MigrationBackupExportResult.CleanupRequired
            exportGate.lastTransferJob?.isCompleted shouldBe true
        }
    }

    @Test
    fun `cancellation after a closed legacy export retains its completed user copy`() = runTest(testDispatcher) {
        val uri: Uri = mockk()
        var closeCount = 0
        val output = object : ByteArrayOutputStream() {
            override fun close() { closeCount++; super.close() }
        }
        every { contentResolver.openOutputStream(uri, "rwt") } returns output
        every { legacyDatabaseRepository.export(any()) } answers {
            val stream = firstArg<java.io.OutputStream>()
            stream.write(9)
            stream.close()
            requireNotNull(exportGate.transferJob).cancel()
            legacyInfo()
        }
        var delivered = false
        createViewModel().exportLegacyDatabase(uri) { delivered = true }
        delivered shouldBe false
        closeCount shouldBe 1
        output.toByteArray().toList() shouldBe listOf<Byte>(9)
        verify(exactly = 0) { contentResolver.delete(uri, null, null) }
        exportGate.lastTransferJob?.isCompleted shouldBe true
    }

    private class ExportGate : TrackingStartupGate {
        var startupResult: TrackingStartupResult = TrackingStartupResult.Ready(false, 0L)
        override var currentGeneration: Long = 1L
        var collectionAllowed = true
        var advanceGenerationOnReconcile = false
        var transferCount = 0
        var transferJob: Job? = null
        var lastTransferJob: Job? = null
        var observedCancellation: CancellationException? = null
        override val isReady: Boolean get() = startupResult is TrackingStartupResult.Ready
        override val permitsNewCollection: Boolean get() = collectionAllowed && isReady

        override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
            if (advanceGenerationOnReconcile) {
                currentGeneration++
            }
            return startupResult
        }

        override suspend fun <T> withReadyGenerationTransfer(
            expectedGeneration: Long,
            operation: suspend () -> T,
        ): T? {
            if (!isReadyGeneration(expectedGeneration)) {
                return null
            }
            transferCount++
            return coroutineScope {
                transferJob = currentCoroutineContext()[Job]
                try { operation() } catch (cancelled: CancellationException) {
                    observedCancellation = cancelled
                    throw cancelled
                } finally {
                    lastTransferJob = transferJob
                    transferJob = null
                }
            }
        }

        fun reset() {
            startupResult = TrackingStartupResult.Ready(false, 0L)
            currentGeneration = 1L
            collectionAllowed = true
            advanceGenerationOnReconcile = false
            transferCount = 0
            transferJob = null
            lastTransferJob = null
            observedCancellation = null
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
