package com.adsamcik.tracker.app.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.R as PrefR

/**
 * Monitors session completion to trigger contextual precision upgrade prompts.
 * 
 * Flow (Apple-style progressive disclosure):
 * 1. User completes 2-3 sessions with APPROXIMATE location mode
 * 2. After threshold, prompt appears explaining benefits of precise location
 * 3. User can upgrade (requests permission) or dismiss (sets flag to prevent re-prompts)
 * 4. Counter resets after upgrade or dismissal
 * 
 * Principles:
 * - Non-blocking: User can dismiss and continue with approximate mode indefinitely
 * - Contextual: Only suggests upgrade after demonstrating value (successful tracking)
 * - Respectful: Remembers dismissal, doesn't nag
 * - Transparent: Explains battery trade-offs honestly
 */
class PrecisionUpgradeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TrackerSession.ACTION_SESSION_FINAL) return
        
        Logger.log(LogData(
            message = "Session finalized, checking precision upgrade eligibility",
            source = PRECISION_UPGRADE_LOG_SOURCE
        ))
        
        val prefs = Preferences.getPref(context)
        
        // Check if user already dismissed the prompt
        val wasDismissed = prefs.getBooleanRes(
            PrefR.string.settings_precision_upgrade_dismissed_key,
            false
        )
        if (wasDismissed) {
            Logger.log(LogData(
                message = "Precision upgrade prompt previously dismissed",
                source = PRECISION_UPGRADE_LOG_SOURCE
            ))
            return
        }
        
        // Check current location precision mode
        val precisionMode = prefs.getStringRes(
            PrefR.string.settings_location_precision_key,
            PrefR.string.settings_location_precision_default
        )
        
        if (precisionMode != "APPROXIMATE") {
            // User already using precise mode, no need to prompt
            Logger.log(LogData(
                message = "User already in PRECISE mode, skipping upgrade prompt",
                source = PRECISION_UPGRADE_LOG_SOURCE
            ))
            return
        }
        
        // Increment session counter for approximate mode
        val currentCount = prefs.getIntResValue(
            PrefR.string.settings_approximate_session_count_key,
            0
        )
        val newCount = currentCount + 1
        
        prefs.edit {
            setInt(PrefR.string.settings_approximate_session_count_key, newCount)
        }
        
        Logger.log(LogData(
            message = "Incremented approximate session count to $newCount",
            source = PRECISION_UPGRADE_LOG_SOURCE
        ))
        
        // Trigger prompt at threshold (2-3 sessions as per copilot instructions)
        if (newCount >= UPGRADE_PROMPT_THRESHOLD) {
            Logger.log(LogData(
                message = "Reached threshold ($UPGRADE_PROMPT_THRESHOLD sessions), setting upgrade prompt flag",
                source = PRECISION_UPGRADE_LOG_SOURCE
            ))
            
            // Set flag that MainRoot will observe
            prefs.edit {
                setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, true)
            }
        }
    }

    companion object {
        private const val PRECISION_UPGRADE_LOG_SOURCE = "PrecisionUpgrade"
        
        /**
         * Number of approximate-mode sessions before suggesting upgrade.
         * Set to 2 per Apple-style principle: demonstrate value before asking for more permissions.
         */
        private const val UPGRADE_PROMPT_THRESHOLD = 2
    }
}
