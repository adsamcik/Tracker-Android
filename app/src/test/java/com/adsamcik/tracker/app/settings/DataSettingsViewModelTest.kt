package com.adsamcik.tracker.app.settings

import android.content.Context
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

@OptIn(ExperimentalCoroutinesApi::class)
class DataSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val mockPrefs: Preferences = mockk(relaxed = true)

    // Controllable flows for each preference
    private val autoCleanupFlow = MutableStateFlow(false)
    private val dataRetentionFlow = MutableStateFlow("1")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Inject mock Preferences singleton
        val field: Field = Preferences::class.java.getDeclaredField("preferences")
        field.isAccessible = true
        field.set(null, mockPrefs)

        // Mock PreferenceFlows object
        mockkObject(PreferenceFlows)
        every { PreferenceFlows.boolean(any(), any<Int>(), any<Int>()) } returns autoCleanupFlow
        every { PreferenceFlows.string(any(), any<Int>(), any<Int>()) } returns dataRetentionFlow
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(PreferenceFlows)
        val field: Field = Preferences::class.java.getDeclaredField("preferences")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun createViewModel() = DataSettingsViewModel(context)

    // =========================================================================
    // Initial state
    // =========================================================================

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `autoCleanupEnabled defaults to false`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.autoCleanupEnabled.first() shouldBe false
        }

        @Test
        fun `dataRetentionYears defaults to 1`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.dataRetentionYears.first() shouldBe "1"
        }
    }

    // =========================================================================
    // Preference observation
    // =========================================================================

    @Nested
    @DisplayName("Preference observation")
    inner class PreferenceObservation {

        @Test
        fun `autoCleanupEnabled reflects flow changes`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            autoCleanupFlow.value = true
            advanceUntilIdle()
            vm.autoCleanupEnabled.first() shouldBe true

            autoCleanupFlow.value = false
            advanceUntilIdle()
            vm.autoCleanupEnabled.first() shouldBe false
        }

        @Test
        fun `dataRetentionYears reflects flow changes`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            dataRetentionFlow.value = "3"
            advanceUntilIdle()
            vm.dataRetentionYears.first() shouldBe "3"

            dataRetentionFlow.value = "5"
            advanceUntilIdle()
            vm.dataRetentionYears.first() shouldBe "5"
        }
    }

    // =========================================================================
    // Setters
    // =========================================================================

    @Nested
    @DisplayName("Setters")
    inner class Setters {

        @Test
        fun `setAutoCleanupEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoCleanupEnabled(true)
            advanceUntilIdle()
            vm.autoCleanupEnabled.first() shouldBe true
        }

        @Test
        fun `setAutoCleanupEnabled can disable after enabling`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoCleanupEnabled(true)
            advanceUntilIdle()
            vm.setAutoCleanupEnabled(false)
            advanceUntilIdle()
            vm.autoCleanupEnabled.first() shouldBe false
        }

        @Test
        fun `setDataRetentionYears updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setDataRetentionYears("5")
            advanceUntilIdle()
            vm.dataRetentionYears.first() shouldBe "5"
        }

        @Test
        fun `setDataRetentionYears accepts various values`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setDataRetentionYears("2")
            advanceUntilIdle()
            vm.dataRetentionYears.first() shouldBe "2"

            vm.setDataRetentionYears("10")
            advanceUntilIdle()
            vm.dataRetentionYears.first() shouldBe "10"
        }
    }
}
