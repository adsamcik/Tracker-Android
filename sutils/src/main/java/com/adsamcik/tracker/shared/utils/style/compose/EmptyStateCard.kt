package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Reusable empty-state card with icon, title, subtitle, and optional action slot.
 * Wraps in [GlassCard] for consistent styling across the app.
 */
@Composable
fun EmptyStateCard(
	icon: ImageVector,
	title: String,
	subtitle: String,
	modifier: Modifier = Modifier,
	action: (@Composable () -> Unit)? = null
) {
	GlassCard(
		modifier = modifier
			.fillMaxWidth()
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(RidgelineSpacing.Xxl),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg)
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				modifier = Modifier.size(64.dp),
				tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
			)
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.onSurface
			)
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodyMedium,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			if (action != null) {
				action()
			}
		}
	}
}
