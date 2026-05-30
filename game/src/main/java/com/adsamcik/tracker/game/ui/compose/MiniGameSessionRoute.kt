package com.adsamcik.tracker.game.ui.compose

import android.Manifest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionType
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineDurations
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import androidx.compose.animation.core.FastOutSlowInEasing
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Full-screen Compose entry point for a mini-game session.
 *
 * Navigation:
 *  - Reached from the Game screen when the user taps an unlocked mini-game card.
 *  - The bottom navigation bar should be hidden while this route is on top.
 *  - Pressing the close button returns to the previous destination.
 *
 * Lifecycle wiring:
 *  - [DisposableEffect] guarantees the session stops (and persists score + XP)
 *    when the user navigates away even without tapping the Stop button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniGameSessionRoute(
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val vm: MiniGameSessionViewModel = hiltViewModel()
	val uiState by vm.uiState.collectAsStateWithLifecycle()

	DisposableEffect(vm) {
		onDispose { vm.stop() }
	}

	Scaffold(
		modifier = modifier.fillMaxSize(),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(vm.game.nameRes)) },
				navigationIcon = {
					IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
						Icon(
							Icons.AutoMirrored.Outlined.ArrowBack,
							contentDescription = stringResource(R.string.minigame_session_close),
						)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.surface,
				),
			)
		},
	) { innerPadding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.padding(horizontal = RidgelineSpacing.Lg),
			contentAlignment = Alignment.Center,
		) {
			AnimatedContent(
				targetState = uiState,
				transitionSpec = {
					(fadeIn(animationSpec = tween(RidgelineDurations.STANDARD_MS, easing = FastOutSlowInEasing))
						togetherWith fadeOut(animationSpec = tween(RidgelineDurations.STANDARD_MS, easing = FastOutSlowInEasing)))
				},
				label = "minigame-session-state",
			) { state ->
				when (state) {
					is MiniGameUiState.Idle -> IdlePanel(
						descriptionRes = vm.game.descriptionRes,
						onStart = vm::start,
					)
					is MiniGameUiState.PermissionNeeded -> PermissionPanel(
						onResult = vm::onPermissionResult,
					)
					is MiniGameUiState.Active -> ActivePanel(
						state = state,
						onStop = vm::stop,
					)
					is MiniGameUiState.Finished -> FinishedPanel(
						state = state,
						onPlayAgain = {
							vm.reset()
							vm.start()
						},
						onClose = onClose,
					)
				}
			}
		}
	}
}

@Composable
private fun IdlePanel(
	descriptionRes: Int,
	onStart: () -> Unit,
) {
	GlassCard(modifier = Modifier.fillMaxWidth().testTag("minigame_session_idle")) {
		Column(
			modifier = Modifier.fillMaxWidth(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			Text(
				text = stringResource(descriptionRes),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				textAlign = TextAlign.Center,
			)
			Text(
				text = stringResource(R.string.minigame_session_idle_hint),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
			Button(
				onClick = onStart,
				modifier = Modifier.testTag("minigame_session_start"),
			) {
				Icon(Icons.Outlined.PlayArrow, contentDescription = null)
				Spacer(Modifier.size(RidgelineSpacing.Sm))
				Text(stringResource(R.string.minigame_session_start_cta))
			}
		}
	}
}

@Composable
private fun PermissionPanel(
	onResult: (Boolean) -> Unit,
) {
	// rememberSaveable not needed — VM owns the truth; if the user dismisses the
	// rationale we report "denied" and the VM stays in PermissionNeeded so the
	// user can tap "Grant access" again to re-show the dialog.
	var showRequest by remember { mutableStateOf(true) }

	GlassCard(modifier = Modifier.fillMaxWidth().testTag("minigame_session_permission")) {
		Column(
			modifier = Modifier.fillMaxWidth(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			Icon(
				imageVector = Icons.Outlined.LocationOn,
				contentDescription = null,
				modifier = Modifier.size(48.dp),
				tint = MaterialTheme.colorScheme.primary,
			)
			Text(
				text = stringResource(R.string.minigame_session_permission_title),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				fontWeight = FontWeight.SemiBold,
				textAlign = TextAlign.Center,
			)
			Text(
				text = stringResource(R.string.minigame_session_permission_body),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
			FilledTonalButton(onClick = { showRequest = true }) {
				Text(stringResource(R.string.minigame_session_permission_grant))
			}
		}
	}

	if (showRequest) {
		ContextualPermissionRequest(
			permissionType = PermissionType.LOCATION_FOREGROUND,
			permission = Manifest.permission.ACCESS_FINE_LOCATION,
			onPermissionResult = { granted ->
				showRequest = false
				onResult(granted)
			},
			onDismiss = { showRequest = false },
		)
	}
}

@Composable
private fun ActivePanel(
	state: MiniGameUiState.Active,
	onStop: () -> Unit,
) {
	val accent = stateColor(state.state)
	val numberFormat = remember { NumberFormat.getInstance() }

	GlassCard(modifier = Modifier.fillMaxWidth().testTag("minigame_session_active")) {
		Column(
			modifier = Modifier.fillMaxWidth(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			Box(
				modifier = Modifier
					.size(160.dp)
					.clip(CircleShape)
					.background(accent.copy(alpha = 0.18f)),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = numberFormat.format(state.score.roundToInt()),
					style = MaterialTheme.typography.displayLarge,
					fontWeight = FontWeight.Bold,
					color = accent,
				)
			}
			Text(
				text = stringResource(R.string.minigame_session_score_label),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			val displayedStatus = state.statusText.ifBlank {
				stringResource(R.string.minigame_session_waiting_for_fix)
			}
			Text(
				text = displayedStatus,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
				textAlign = TextAlign.Center,
			)
			Row(
				horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = formatElapsed(state.elapsedMs),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Button(
				onClick = onStop,
				modifier = Modifier.testTag("minigame_session_stop"),
			) {
				Icon(Icons.Outlined.Stop, contentDescription = null)
				Spacer(Modifier.size(RidgelineSpacing.Sm))
				Text(stringResource(R.string.minigame_session_stop_cta))
			}
		}
	}
}

@Composable
private fun FinishedPanel(
	state: MiniGameUiState.Finished,
	onPlayAgain: () -> Unit,
	onClose: () -> Unit,
) {
	val transition = rememberInfiniteTransition(label = "minigame-finish-pulse")
	val pulseScale by transition.animateFloat(
		initialValue = 0.94f,
		targetValue = 1.06f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = 1200),
			repeatMode = RepeatMode.Reverse,
		),
		label = "minigame-finish-scale",
	)

	GlassCard(modifier = Modifier.fillMaxWidth().testTag("minigame_session_finished")) {
		Column(
			modifier = Modifier.fillMaxWidth(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			Box(
				modifier = Modifier
					.size(96.dp)
					.scale(pulseScale)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = Icons.Outlined.EmojiEvents,
					contentDescription = null,
					modifier = Modifier.size(56.dp),
					tint = MaterialTheme.colorScheme.tertiary,
				)
			}
			Text(
				text = stringResource(R.string.minigame_session_finished_title),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = stringResource(
					R.string.minigame_session_final_score,
					NumberFormat.getInstance().format(state.finalScore.roundToInt()),
				),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = if (state.pointsEarned > 0) {
					stringResource(R.string.minigame_session_points_earned, state.pointsEarned)
				} else {
					stringResource(R.string.minigame_session_points_zero)
				},
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.primary,
			)
			Spacer(Modifier.height(RidgelineSpacing.Sm))
			Row(
				horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
			) {
				FilledTonalButton(onClick = onClose) {
					Text(stringResource(R.string.minigame_session_close))
				}
				Button(
					onClick = onPlayAgain,
					modifier = Modifier.testTag("minigame_session_play_again"),
				) {
					Text(stringResource(R.string.minigame_session_play_again))
				}
			}
		}
	}
}

@Composable
private fun stateColor(state: MiniGameState): Color = when (state) {
	MiniGameState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
	MiniGameState.RUNNING -> MaterialTheme.colorScheme.primary
	MiniGameState.WARNING -> MaterialTheme.colorScheme.error
	MiniGameState.FINISHED -> MaterialTheme.colorScheme.tertiary
}

private fun formatElapsed(elapsedMs: Long): String {
	val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
	val minutes = totalSeconds / 60L
	val seconds = totalSeconds % 60L
	return "%d:%02d".format(minutes, seconds)
}
