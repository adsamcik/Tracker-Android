package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Entry point composable for the Dashboard tab.
 * Replaces TrackerRoute as the app's home screen.
 */
@Composable
fun DashboardRoute(
	onOpenSettings: () -> Unit = {},
	onOpenMap: () -> Unit = {},
	onOpenGame: (() -> Unit)? = null,
	onSessionDetailClick: ((Long) -> Unit)? = null,
	contentPadding: PaddingValues = PaddingValues(),
) {
	// Placeholder - will be replaced with full dashboard implementation
	Box(
		modifier = Modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		Text("Dashboard - Coming Soon")
	}
}
