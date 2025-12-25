package com.adsamcik.tracker.app.tracker

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.app.tracker.receiver.PrecisionUpgradeReceiver
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.R as PrefR
import com.adsamcik.tracker.app.test.FakePreferencesHelper
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Integration tests for complete precision upgrade flow (Phase 2).
 * 
 * Tests the end-to-end user journey:
 * 1. User selects APPROXIMATE mode in onboarding
 * 2. Completes 2 tracking sessions
 * 3. Prompt flag is set
 * 4. User chooses upgrade or dismiss
 * 5. State resets appropriately
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@LooperMode(LooperMode.Mode.LEGACY)
class PrecisionUpgradeFlowTest {
    private lateinit var context: Context
    private lateinit var prefs: Preferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = FakePreferencesHelper.setup()
        
        // Mock Logger to avoid initialization requirement - stub log to do nothing
        mockkObject(Logger)
        io.mockk.every { Logger.log(any()) } returns Unit
        
        // Set default values expected by logic
        FakePreferencesHelper.data[PrefR.string.settings_location_precision_key] = "APPROXIMATE"
        
        resetPreferences()
    }

    @After
    fun tearDown() {
        FakePreferencesHelper.tearDown()
        unmockkAll()
    }

    private fun resetPreferences() {
        prefs.edit {
            setInt(PrefR.string.settings_approximate_session_count_key, 0)
            setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
            setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, false)
            setString(
                PrefR.string.settings_location_precision_key,
                "APPROXIMATE" 
            )
        }
    }

    @Test
    fun `complete upgrade flow - user upgrades to precise`() {
        // Step 1: User completes onboarding with APPROXIMATE mode
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                context.getString(PrefR.string.settings_location_precision_approximate)
            )
        }

        // Step 2: User completes 2 tracking sessions
        val receiver = PrecisionUpgradeReceiver()
        val sessionIntent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        receiver.onReceive(context, sessionIntent) // Session 1
        receiver.onReceive(context, sessionIntent) // Session 2

        // Step 3: Verify prompt flag is set
        var shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertTrue("Prompt should be triggered after 2 sessions", shouldShow)

        // Step 4: User chooses "Upgrade" → permission granted
        // MainRoot would handle this by updating preferences
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                context.getString(PrefR.string.settings_location_precision_precise)
            )
            setInt(PrefR.string.settings_approximate_session_count_key, 0)
            setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
        }

        // Step 5: Verify state is correct
        val precisionMode = prefs.getStringRes(
            PrefR.string.settings_location_precision_key,
            PrefR.string.settings_location_precision_default
        )
        assertEquals(
            context.getString(PrefR.string.settings_location_precision_precise),
            precisionMode
        )

        val counter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, counter)

        shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse("Prompt flag should be cleared after upgrade", shouldShow)

        // Step 6: Future sessions should NOT increment counter (now in PRECISE mode)
        receiver.onReceive(context, sessionIntent)
        val newCounter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, newCounter)
    }

    @Test
    fun `complete dismiss flow - user declines upgrade`() {
        // Setup: APPROXIMATE mode
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                context.getString(PrefR.string.settings_location_precision_approximate)
            )
        }

        // User completes 2 sessions
        val receiver = PrecisionUpgradeReceiver()
        val sessionIntent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        receiver.onReceive(context, sessionIntent)
        receiver.onReceive(context, sessionIntent)

        // Verify prompt triggered
        var shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertTrue(shouldShow)

        // User chooses "Not Now" (dismiss)
        // MainRoot would set dismissal flag and reset
        prefs.edit {
            setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, true)
            setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
            setInt(PrefR.string.settings_approximate_session_count_key, 0)
        }

        // Verify dismissal state
        val wasDismissed = prefs.getBooleanRes(
            PrefR.string.settings_precision_upgrade_dismissed_key,
            false
        )
        assertTrue("Dismissal flag should be set", wasDismissed)

        shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse("Prompt flag should be cleared", shouldShow)

        // Future sessions should NOT trigger prompt again
        repeat(10) {
            receiver.onReceive(context, sessionIntent)
        }

        shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse("Prompt should never trigger again after dismissal", shouldShow)

        val counter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, counter)
    }

    @Test
    fun `user starts with PRECISE mode - no prompts ever`() {
        // User selects PRECISE in onboarding (has permission already)
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                context.getString(PrefR.string.settings_location_precision_precise)
            )
        }

        val receiver = PrecisionUpgradeReceiver()
        val sessionIntent = Intent(TrackerSession.ACTION_SESSION_FINAL)

        // Complete many sessions
        repeat(10) {
            receiver.onReceive(context, sessionIntent)
        }

        // Verify no counter increments
        val counter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, counter)

        // Verify prompt never triggered
        val shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse(shouldShow)
    }

    @Test
    fun `user manually changes to PRECISE in settings mid-flow`() {
        // Start with APPROXIMATE
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                context.getString(PrefR.string.settings_location_precision_approximate)
            )
        }

        val receiver = PrecisionUpgradeReceiver()
        val sessionIntent = Intent(TrackerSession.ACTION_SESSION_FINAL)

        // Complete 1 session
        receiver.onReceive(context, sessionIntent)
        var counter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(1, counter)

        // User manually enables precise location in settings (grants permission)
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                context.getString(PrefR.string.settings_location_precision_precise)
            )
        }

        // Complete another session
        receiver.onReceive(context, sessionIntent)

        // Counter should NOT increment (already in PRECISE mode)
        counter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(1, counter) // Still at 1, not incremented

        // Prompt should NOT trigger
        val shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse(shouldShow)
    }

    @Test
    fun `preference keys exist and have correct defaults`() {
        // Verify all required preference keys are defined
        val counterKey = PrefR.string.settings_approximate_session_count_key
        val flagKey = PrefR.string.settings_should_show_precision_upgrade_key
        val dismissedKey = PrefR.string.settings_precision_upgrade_dismissed_key
        val precisionKey = PrefR.string.settings_location_precision_key

        assertNotEquals(0, counterKey)
        assertNotEquals(0, flagKey)
        assertNotEquals(0, dismissedKey)
        assertNotEquals(0, precisionKey)

        // Verify defaults (clean state)
        val defaultCounter = prefs.getIntRes(counterKey, -1)
        val defaultFlag = prefs.getBooleanRes(flagKey, true) // Default should be false
        val defaultDismissed = prefs.getBooleanRes(dismissedKey, true) // Default should be false

        assertEquals(0, defaultCounter)
        assertFalse(defaultFlag)
        assertFalse(defaultDismissed)
    }

    @Test
    fun `app restart preserves state correctly`() {
        // Simulate first app session
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                context.getString(PrefR.string.settings_location_precision_approximate)
            )
        }

        val receiver1 = PrecisionUpgradeReceiver()
        val sessionIntent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        receiver1.onReceive(context, sessionIntent)

        var counter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(1, counter)

        // Simulate app restart (new receiver instance, but same preferences)
        val receiver2 = PrecisionUpgradeReceiver()
        receiver2.onReceive(context, sessionIntent)

        counter = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(2, counter)

        val shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertTrue("Prompt should trigger after restart + second session", shouldShow)
    }
}
