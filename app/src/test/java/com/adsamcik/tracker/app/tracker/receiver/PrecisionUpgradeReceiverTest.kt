package com.adsamcik.tracker.app.tracker.receiver

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
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

/**
 * Unit tests for PrecisionUpgradeReceiver (Phase 2 contextual permission prompts).
 * 
 * Tests session counting, threshold triggering, dismissal flag handling, and edge cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PrecisionUpgradeReceiverTest {
    private lateinit var context: Context
    private lateinit var receiver: PrecisionUpgradeReceiver
    private lateinit var prefs: Preferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = FakePreferencesHelper.setup()
        
        // Mock Logger to avoid initialization requirement - stub log to do nothing
        mockkObject(Logger)
        io.mockk.every { Logger.log(any()) } returns Unit
        
        // Set default values
        FakePreferencesHelper.data[PrefR.string.settings_location_precision_key] = "APPROXIMATE"
        
        receiver = PrecisionUpgradeReceiver()
        
        // Reset state
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

    @After
    fun tearDown() {
        FakePreferencesHelper.tearDown()
        unmockkAll()
    }

    @Test
    fun `onReceive ignores non-session-final actions`() {
        val intent = Intent("com.example.SOME_OTHER_ACTION")
        
        receiver.onReceive(context, intent)
        
        // Counter should remain 0
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, count)
    }

    @Test
    fun `onReceive increments counter for approximate mode session completion`() {
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        receiver.onReceive(context, intent)
        
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(1, count)
    }

    @Test
    fun `onReceive sets prompt flag after reaching threshold`() {
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        // First session
        receiver.onReceive(context, intent)
        var shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse("Prompt should not show after 1 session", shouldShow)
        
        // Second session (reaches threshold of 2)
        receiver.onReceive(context, intent)
        shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertTrue("Prompt should show after 2 sessions", shouldShow)
        
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(2, count)
    }

    @Test
    fun `onReceive does not increment if already dismissed`() {
        // Set dismissal flag
        prefs.edit {
            setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, true)
        }
        
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        receiver.onReceive(context, intent)
        
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, count)
        
        val shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse(shouldShow)
    }

    @Test
    fun `onReceive ignores sessions when already in precise mode`() {
        // Set precision mode to PRECISE
        prefs.edit {
            setString(
                PrefR.string.settings_location_precision_key,
                "PRECISE" // Use string directly as we don't have context for resources in mock
            )
        }
        // Update mock data directly to ensure getStringRes returns PRECISE
        FakePreferencesHelper.data[PrefR.string.settings_location_precision_key] = "PRECISE"
        
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        receiver.onReceive(context, intent)
        
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, count)
        
        val shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse(shouldShow)
    }

    @Test
    fun `multiple sessions beyond threshold keep flag set`() {
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        // Complete 3 sessions
        repeat(3) {
            receiver.onReceive(context, intent)
        }
        
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(3, count)
        
        val shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertTrue("Prompt flag should remain set after multiple sessions", shouldShow)
    }

    @Test
    fun `counter persists across receiver instances`() {
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        // First receiver instance
        val receiver1 = PrecisionUpgradeReceiver()
        receiver1.onReceive(context, intent)
        
        var count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(1, count)
        
        // Second receiver instance (simulates app restart)
        val receiver2 = PrecisionUpgradeReceiver()
        receiver2.onReceive(context, intent)
        
        count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(2, count)
    }

    @Test
    fun `dismissal flag prevents future prompts even after threshold`() {
        prefs.edit {
            setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, true)
        }
        
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        // Complete sessions beyond threshold
        repeat(5) {
            receiver.onReceive(context, intent)
        }
        
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, count)
        
        val shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse("Prompt should never show when dismissed", shouldShow)
    }

    @Test
    fun `threshold is exactly 2 sessions`() {
        val intent = Intent(TrackerSession.ACTION_SESSION_FINAL)
        
        receiver.onReceive(context, intent)
        var shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertFalse("1 session: should not show", shouldShow)
        
        receiver.onReceive(context, intent)
        shouldShow = prefs.getBooleanRes(
            PrefR.string.settings_should_show_precision_upgrade_key,
            false
        )
        assertTrue("2 sessions: should show", shouldShow)
    }

    @Test
    fun `null intent action is handled gracefully`() {
        val intent = Intent() // No action set
        
        // Should not crash
        receiver.onReceive(context, intent)
        
        val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
        assertEquals(0, count)
    }
}
