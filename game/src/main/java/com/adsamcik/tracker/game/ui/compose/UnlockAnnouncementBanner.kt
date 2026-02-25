package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import kotlinx.coroutines.delay

/**
 * Slide-in banner announcing a new unlock. Auto-dismisses after [displayDurationMs].
 */
@Composable
fun UnlockAnnouncementBanner(
	featureName: String?,
	newLevel: Int,
	modifier: Modifier = Modifier,
	displayDurationMs: Long = 4000L,
	onDismissed: () -> Unit = {},
) {
	var visible by remember(featureName) { mutableStateOf(featureName != null) }

	LaunchedEffect(featureName) {
		if (featureName != null) {
			visible = true
			delay(displayDurationMs)
			visible = false
			onDismissed()
		}
	}

	AnimatedVisibility(
		visible = visible,
		enter = slideInVertically(initialOffsetY = { -it }),
		exit = slideOutVertically(targetOffsetY = { -it }),
		modifier = modifier,
	) {
		Surface(
			color = MaterialTheme.colorScheme.primaryContainer,
			shadowElevation = 8.dp,
			shape = MaterialTheme.shapes.medium,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			Row(
				modifier = Modifier.padding(16.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Icon(
					Icons.Outlined.LockOpen,
					contentDescription = null,
					modifier = Modifier.size(28.dp),
					tint = MaterialTheme.colorScheme.onPrimaryContainer,
				)
				Column {
					Text(
						text = stringResource(R.string.game_unlock_title),
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
					Text(
						text = stringResource(R.string.game_unlock_message, newLevel),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
					if (featureName != null) {
						Text(
							text = featureName,
							style = MaterialTheme.typography.labelLarge,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.primary,
						)
					}
				}
			}
		}
	}
}
