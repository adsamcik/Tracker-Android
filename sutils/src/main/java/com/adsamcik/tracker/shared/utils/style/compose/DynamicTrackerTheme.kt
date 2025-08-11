package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.runtime.Composable

/**
 * A version of TrackerTheme that uses the current StyleManager state
 * when the Compose screen is created.
 */
@Composable
fun DynamicTrackerTheme(content: @Composable () -> Unit) = TrackerTheme(content)
