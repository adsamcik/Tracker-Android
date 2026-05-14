package com.adsamcik.tracker.app.debug

import androidx.compose.runtime.Composable
import com.adsamcik.tracker.BuildConfig

/**
 * Debug-only entry point embedded by DebugRoute and DebugSettingsScreen.
 */
@Composable
fun SeedDataSection() {
    if (!BuildConfig.DEBUG) return
    TestDataScreen()
}
