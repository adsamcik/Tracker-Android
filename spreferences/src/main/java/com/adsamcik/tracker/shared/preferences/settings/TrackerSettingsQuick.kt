package com.adsamcik.tracker.shared.preferences.settings

import android.content.Context
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.preferences.extension.getPreferredLengthSystem
import com.adsamcik.tracker.shared.preferences.type.LengthSystem

/** Lightweight convenience accessors for call sites not yet fully DI-converted. */
object TrackerSettingsQuick {
    fun snapshot(context: Context): TrackerSettingsState = TrackerSettingsAccess.snapshot(context)

    fun lengthSystem(context: Context): LengthSystem = snapshot(context).lengthSystem

    fun effectiveLengthSystem(context: Context, sessionActivity: SessionActivity?): LengthSystem {
        val settings = snapshot(context)
        val base = settings.lengthSystem
        if (!settings.autoUnitSwitch || sessionActivity == null) return base
        return sessionActivity.getPreferredLengthSystem() ?: base
    }
}
