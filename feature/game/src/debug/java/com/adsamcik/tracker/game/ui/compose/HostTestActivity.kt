package com.adsamcik.tracker.game.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Debug-only host activity used by connected instrumentation tests to host Compose content.
 * Content is provided by the Compose test rule via setContent, so no setup is required here.
 */
class HostTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
