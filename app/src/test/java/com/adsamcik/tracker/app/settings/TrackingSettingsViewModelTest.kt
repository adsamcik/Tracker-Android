package com.adsamcik.tracker.app.settings

import android.content.Context
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
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
class TrackingSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val mockPrefs: Preferences = mockk(relaxed = true)

    // Controllable preference flows
    private val locationEnabledFlow = MutableStateFlow(true)
    private val activityEnabledFlow = MutableStateFlow(true)
    private val stepsEnabledFlow = MutableStateFlow(true)
    private val wifiEnabledFlow = MutableStateFlow(true)
    private val cellEnabledFlow = MutableStateFlow(true)
    private val wifiNetworkEnabledFlow = MutableStateFlow(true)
    private val wifiLocationCountFlow = MutableStateFlow(true)
    private val autoTrackingFlow = MutableStateFlow(0) // intFromString -> map { it > 0 }
    private val transitionDetectionFlow = MutableStateFlow(true)
    private val notificationStyledFlow = MutableStateFlow(true)

    // Preset and tracking parameter flows
    private val presetFlow = MutableStateFlow(TrackingPolicyPreset.DEFAULT.name)
    private val minDistanceFlow = MutableStateFlow(10)
    private val minTimeFlow = MutableStateFlow(2)
    private val requiredAccuracyFlow = MutableStateFlow(50)

    // Track PreferenceFlows.boolean call count to assign correct flows
    private var booleanCallIndex = 0

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        booleanCallIndex = 0

        // Inject mock Preferences singleton
        val field: Field = Preferences::class.java.getDeclaredField("preferences")
        field.isAccessible = true
        field.set(null, mockPrefs)

        // Mock context.getString to return a distinct key per resource ID
        every { context.getString(any()) } answers { "key_${firstArg<Int>()}" }

        // Mock hasSelfPermission (called by inline hasPreciseLocationPermission)
        mockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
        every { context.hasSelfPermission(any()) } returns true

        // Mock prefs.observeString for preset tracking
        every { mockPrefs.observeString(any(), any()) } returns presetFlow
        // Discriminate observeInt calls by default value (each parameter has a unique default)
        every { mockPrefs.observeInt(any(), eq(10)) } returns minDistanceFlow
        every { mockPrefs.observeInt(any(), eq(2)) } returns minTimeFlow
        every { mockPrefs.observeInt(any(), eq(50)) } returns requiredAccuracyFlow

        // Mock PreferenceFlows
        mockkObject(PreferenceFlows)

        // Boolean flows are called in init order matching the ViewModel's init block:
        // location, activity, steps, wifi, cell, wifiNetwork, wifiLocationCount,
        // transitionDetection, notificationStyled
        val booleanFlows = listOf(
            locationEnabledFlow,
            activityEnabledFlow,
            stepsEnabledFlow,
            wifiEnabledFlow,
            cellEnabledFlow,
            wifiNetworkEnabledFlow,
            wifiLocationCountFlow,
            transitionDetectionFlow,
            notificationStyledFlow,
        )

        every { PreferenceFlows.boolean(any(), any<Int>(), any<Int>()) } answers {
            val index = booleanCallIndex++
            if (index < booleanFlows.size) booleanFlows[index] else MutableStateFlow(false)
        }

        // intFromString flow for auto-tracking
        every { PreferenceFlows.intFromString(any(), any<Int>(), any<Int>()) } returns autoTrackingFlow
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(PreferenceFlows)
        unmockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
        val field: Field = Preferences::class.java.getDeclaredField("preferences")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun createViewModel(): TrackingSettingsViewModel {
        booleanCallIndex = 0
        return TrackingSettingsViewModel(context)
    }

    // =========================================================================
    // Initial state
    // =========================================================================

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `currentPreset defaults to DEFAULT`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.currentPreset.first() shouldBe TrackingPolicyPreset.DEFAULT
        }

        @Test
        fun `locationEnabled defaults to true`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.locationEnabled.first() shouldBe true
        }

        @Test
        fun `hasValidSources is true when sources enabled`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.hasValidSources.first() shouldBe true
        }

        @Test
        fun `minDistance defaults to 10`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.minDistance.first() shouldBe 10
        }

        @Test
        fun `minTime defaults to 2`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.minTime.first() shouldBe 2
        }

        @Test
        fun `requiredAccuracy defaults to 50`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.requiredAccuracy.first() shouldBe 50
        }
    }

    // =========================================================================
    // Source toggles
    // =========================================================================

    @Nested
    @DisplayName("Source toggles")
    inner class SourceToggles {

        @Test
        fun `setLocationEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLocationEnabled(false)
            advanceUntilIdle()
            vm.locationEnabled.first() shouldBe false
        }

        @Test
        fun `setActivityEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setActivityEnabled(false)
            advanceUntilIdle()
            vm.activityEnabled.first() shouldBe false
        }

        @Test
        fun `setStepsEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setStepsEnabled(false)
            advanceUntilIdle()
            vm.stepsEnabled.first() shouldBe false
        }

        @Test
        fun `setWifiEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setWifiEnabled(false)
            advanceUntilIdle()
            vm.wifiEnabled.first() shouldBe false
        }

        @Test
        fun `setCellEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setCellEnabled(false)
            advanceUntilIdle()
            vm.cellEnabled.first() shouldBe false
        }
    }

    // =========================================================================
    // WiFi sub-options
    // =========================================================================

    @Nested
    @DisplayName("WiFi sub-options")
    inner class WifiSubOptions {

        @Test
        fun `setWifiNetworkEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setWifiNetworkEnabled(false)
            advanceUntilIdle()
            vm.wifiNetworkEnabled.first() shouldBe false
        }

        @Test
        fun `setWifiLocationCountEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setWifiLocationCountEnabled(false)
            advanceUntilIdle()
            vm.wifiLocationCountEnabled.first() shouldBe false
        }
    }

    // =========================================================================
    // Tracking parameters
    // =========================================================================

    @Nested
    @DisplayName("Tracking parameters")
    inner class TrackingParameters {

        @Test
        fun `setMinDistance updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setMinDistance(25)
            advanceUntilIdle()
            vm.minDistance.first() shouldBe 25
        }

        @Test
        fun `setMinTime updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setMinTime(15)
            advanceUntilIdle()
            vm.minTime.first() shouldBe 15
        }

        @Test
        fun `setRequiredAccuracy updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setRequiredAccuracy(100)
            advanceUntilIdle()
            vm.requiredAccuracy.first() shouldBe 100
        }
    }

    // =========================================================================
    // Notification and auto-tracking
    // =========================================================================

    @Nested
    @DisplayName("Notification and auto-tracking")
    inner class NotificationAndAutoTracking {

        @Test
        fun `setNotificationStyled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setNotificationStyled(false)
            advanceUntilIdle()
            vm.notificationStyled.first() shouldBe false
        }

        @Test
        fun `setTransitionDetectionEnabled updates state`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setTransitionDetectionEnabled(false)
            advanceUntilIdle()
            vm.transitionDetectionEnabled.first() shouldBe false
        }

        @Test
        fun `autoTrackingEnabled reflects intFromString flow`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            // Default is 0 -> false
            vm.autoTrackingEnabled.first() shouldBe false

            autoTrackingFlow.value = 1
            advanceUntilIdle()
            vm.autoTrackingEnabled.first() shouldBe true
        }
    }

    // =========================================================================
    // Source validation
    // =========================================================================

    @Nested
    @DisplayName("Source validation")
    inner class SourceValidation {

        @Test
        fun `disabling all sources sets hasValidSources to false`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLocationEnabled(false)
            vm.setActivityEnabled(false)
            vm.setStepsEnabled(false)
            vm.setWifiEnabled(false)
            vm.setCellEnabled(false)
            advanceUntilIdle()

            vm.hasValidSources.first() shouldBe false
        }

        @Test
        fun `re-enabling one source restores hasValidSources`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLocationEnabled(false)
            vm.setActivityEnabled(false)
            vm.setStepsEnabled(false)
            vm.setWifiEnabled(false)
            vm.setCellEnabled(false)
            advanceUntilIdle()
            vm.hasValidSources.first() shouldBe false

            vm.setLocationEnabled(true)
            advanceUntilIdle()
            vm.hasValidSources.first() shouldBe true
        }

        @Test
        fun `single source enabled keeps hasValidSources true`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLocationEnabled(false)
            vm.setActivityEnabled(false)
            vm.setStepsEnabled(false)
            vm.setWifiEnabled(false)
            // cellEnabled still true
            advanceUntilIdle()

            vm.hasValidSources.first() shouldBe true
        }
    }

    // =========================================================================
    // Preset management
    // =========================================================================

    @Nested
    @DisplayName("Preset management")
    inner class PresetManagement {

        @Test
        fun `applyPreset BATTERY_SAVER updates all settings`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPolicyPreset.BATTERY_SAVER)
            advanceUntilIdle()

            val config = TrackingPolicyPreset.BATTERY_SAVER.settings
            vm.locationEnabled.first() shouldBe config.locationEnabled
            vm.activityEnabled.first() shouldBe config.activityEnabled
            vm.stepsEnabled.first() shouldBe config.stepsEnabled
            vm.wifiEnabled.first() shouldBe config.wifiEnabled
            vm.cellEnabled.first() shouldBe config.cellEnabled
            vm.minDistance.first() shouldBe config.minDistanceMeters
            vm.minTime.first() shouldBe config.minTimeSeconds
            vm.requiredAccuracy.first() shouldBe config.requiredAccuracyMeters
            vm.currentPreset.first() shouldBe TrackingPolicyPreset.BATTERY_SAVER
            vm.currentBatteryImpact.first() shouldBe BatteryImpact.LOW
        }

        @Test
        fun `applyPreset HIGH_PRECISION updates all settings`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPolicyPreset.HIGH_PRECISION)
            advanceUntilIdle()

            val config = TrackingPolicyPreset.HIGH_PRECISION.settings
            vm.locationEnabled.first() shouldBe config.locationEnabled
            vm.wifiEnabled.first() shouldBe config.wifiEnabled
            vm.wifiLocationCountEnabled.first() shouldBe config.wifiLocationCountEnabled
            vm.cellEnabled.first() shouldBe config.cellEnabled
            vm.transitionDetectionEnabled.first() shouldBe config.useTransitionDetection
            vm.minDistance.first() shouldBe config.minDistanceMeters
            vm.minTime.first() shouldBe config.minTimeSeconds
            vm.requiredAccuracy.first() shouldBe config.requiredAccuracyMeters
            vm.currentPreset.first() shouldBe TrackingPolicyPreset.HIGH_PRECISION
            vm.currentBatteryImpact.first() shouldBe BatteryImpact.HIGH
        }

        @Test
        fun `changing a setting after preset marks custom`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPolicyPreset.BALANCED)
            advanceUntilIdle()
            vm.currentPreset.first() shouldBe TrackingPolicyPreset.BALANCED

            vm.setMinDistance(999)
            advanceUntilIdle()
            // null indicates custom preset
            vm.currentPreset.first().shouldBeNull()
        }

        @Test
        fun `applying preset after custom restores named preset`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            // Go custom
            vm.setMinDistance(999)
            advanceUntilIdle()
            vm.currentPreset.first().shouldBeNull()

            // Apply named preset
            vm.applyPreset(TrackingPolicyPreset.BALANCED)
            advanceUntilIdle()
            vm.currentPreset.first() shouldBe TrackingPolicyPreset.BALANCED
        }

        @Test
        fun `applyPreset validates sources`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            // All presets have at least location enabled
            vm.applyPreset(TrackingPolicyPreset.BATTERY_SAVER)
            advanceUntilIdle()
            vm.hasValidSources.first() shouldBe true
        }
    }

    // =========================================================================
    // Battery impact
    // =========================================================================

    @Nested
    @DisplayName("Battery impact")
    inner class BatteryImpactTests {

        @Test
        fun `applyPreset updates battery impact`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPolicyPreset.BATTERY_SAVER)
            advanceUntilIdle()
            vm.currentBatteryImpact.first() shouldBe BatteryImpact.LOW

            vm.applyPreset(TrackingPolicyPreset.HIGH_PRECISION)
            advanceUntilIdle()
            vm.currentBatteryImpact.first() shouldBe BatteryImpact.HIGH
        }
    }
}
