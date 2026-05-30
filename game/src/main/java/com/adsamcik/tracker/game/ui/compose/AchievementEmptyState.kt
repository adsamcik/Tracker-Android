package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R

@Composable
internal fun AchievementEmptyState() {
	val infiniteTransition = rememberInfiniteTransition(label = "empty-state-pulse")
	val scale by infiniteTransition.animateFloat(
		initialValue = 1f,
		targetValue = 1.06f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = 1800),
			repeatMode = RepeatMode.Reverse,
		),
		label = "empty-state-pulse-scale",
	)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 32.dp, vertical = 40.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Box(
			modifier = Modifier
				.size(120.dp)
				.scale(scale)
				.clip(CircleShape)
				.background(
					Brush.radialGradient(
						colors = listOf(
							MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
							MaterialTheme.colorScheme.primary.copy(alpha = 0f),
						),
					),
				),
			contentAlignment = Alignment.Center,
		) {
			Box(
				modifier = Modifier
					.size(80.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.primaryContainer),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					Icons.Outlined.EmojiEvents,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(44.dp),
				)
			}
		}
		Text(
			stringResource(R.string.achievement_empty_state_title),
			style = MaterialTheme.typography.titleLarge,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Text(
			stringResource(R.string.achievement_empty_state_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}
