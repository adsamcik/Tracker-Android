package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.dashboard.ui.compose.state.ChallengeUiModel
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Compact horizontal progress bars for active challenges during tracking.
 */
@Composable
internal fun LiveChallengeProgress(
	challenges: List<ChallengeUiModel>,
	modifier: Modifier = Modifier,
) {
	if (challenges.isEmpty()) return

	GlassCard(modifier = modifier.fillMaxWidth()) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			challenges.forEach { challenge ->
				ChallengeProgressRow(challenge = challenge)
			}
		}
	}
}

@Composable
private fun ChallengeProgressRow(
	challenge: ChallengeUiModel,
	modifier: Modifier = Modifier,
) {
	val animatedProgress by animateFloatAsState(
		targetValue = challenge.progress.coerceIn(0f, 1f),
		animationSpec = tween(MotionTokens.STANDARD_MS),
		label = "challenge_progress_${challenge.id}",
	)

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = challenge.title,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = "${(challenge.progress * 100).toInt()}%",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		LinearProgressIndicator(
			progress = { animatedProgress },
			modifier = Modifier.fillMaxWidth(),
			color = MaterialTheme.colorScheme.primary,
			trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
		)
	}
}
