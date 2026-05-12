package com.adsamcik.tracker.app.settings

import android.content.Context
import com.adsamcik.tracker.R
import app.cash.turbine.test
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
    private val preferences: Preferences = mockk()
    private val smartGoalNotificationsFlow = MutableStateFlow(true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        configFlow.value = RetentionConfigState()

        every { retentionConfigStore.config } returns configFlow
        every { exportPlanStore.plans } returns MutableStateFlow(emptyList())
        every { appContext.getString(R.string.settings_smart_goal_notifications_key) } returns "smartGoalNotifications"
        every { preferences.observeBoolean("smartGoalNotifications", true) } returns smartGoalNotificationsFlow
        every { preferences.edit(any()) } answers {
            firstArg<com.adsamcik.tracker.shared.preferences.MutablePreferences.() -> Unit>()
            Unit
        }
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
}
