package com.adsamcik.tracker.game.ui.compose

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.session.GameSessionFailureReason
import com.adsamcik.tracker.shared.utils.compose.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing

/**
 * Full-screen Compose entry point for a mini-game session.
 *
 * The session's lifetime belongs to the foreground game service, NOT this
 * screen. There is deliberately no `LifecycleEventObserver` and no `onDispose`
 * stop: navigating away (including the back button) leaves the run untouched so
 * it keeps recording in the background. Re-opening the game re-attaches to the
 * live [GameSessionController] state and offers Continue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniGameSessionRoute(
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val viewModel: MiniGameSessionViewModel = hiltViewModel()
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val reducedMotion = LocalReducedMotion.current

	Scaffold(
		modifier = modifier.fillMaxSize(),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(viewModel.game.nameRes)) },
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
		) {
			AnimatedContent(
				targetState = uiState,
				contentKey = MiniGameSessionUiState::animationKey,
				transitionSpec = {
					if (reducedMotion) {
						EnterTransition.None togetherWith ExitTransition.None
					} else {
						fadeIn(animationSpec = RidgelineMotion.Settle) togetherWith
							fadeOut(animationSpec = RidgelineMotion.Settle)
					}
				},
				label = "minigame-session-state",
			) { state ->
				when (state) {
					is MiniGameSessionUiState.Setup -> MiniGameSetupPanel(
						state = state,
						descriptionRes = viewModel.game.descriptionRes,
						onSelectGoal = viewModel::selectGoal,
						onSelectDifficulty = viewModel::selectDifficulty,
						onSetRemember = viewModel::setRememberSetup,
						onStart = viewModel::start,
					)

					is MiniGameSessionUiState.LocationPermission -> LocationPermissionPanel(
						denied = state.denied,
						onResult = viewModel::onLocationPermissionResult,
						onRetry = viewModel::retryLocationPermission,
						onCancel = viewModel::cancelLocationPermission,
					)

					is MiniGameSessionUiState.NotificationPermission -> NotificationPermissionGate(
						onResult = viewModel::onNotificationPermissionResult,
					)

					is MiniGameSessionUiState.Starting -> LoadingPanel(
						message = stringResource(R.string.minigame_session_starting),
					)

					is MiniGameSessionUiState.Acquiring -> ActiveSessionPanel(
						configuration = state.configuration,
						snapshot = state.snapshot,
						paused = false,
						onPause = viewModel::pause,
						onResume = viewModel::resume,
						onFinish = viewModel::finish,
					)

					is MiniGameSessionUiState.Active -> ActiveSessionPanel(
						configuration = state.configuration,
						snapshot = state.snapshot,
						paused = false,
						onPause = viewModel::pause,
						onResume = viewModel::resume,
						onFinish = viewModel::finish,
					)

					is MiniGameSessionUiState.Paused -> ActiveSessionPanel(
						configuration = state.configuration,
						snapshot = state.snapshot,
						paused = true,
						onPause = viewModel::pause,
						onResume = viewModel::resume,
						onFinish = viewModel::finish,
					)

					is MiniGameSessionUiState.Finishing -> LoadingPanel(
						message = stringResource(R.string.minigame_session_finishing),
					)

					is MiniGameSessionUiState.Finished -> MiniGameCompletionPanel(
						result = state.result,
						scoreUnit = viewModel.game.scoreUnit,
						onPlayAgain = viewModel::playAgain,
						onChangeSetup = viewModel::changeSetup,
						onDone = onClose,
					)

					is MiniGameSessionUiState.Failed -> FailurePanel(
						reason = state.reason,
						onTryAgain = viewModel::changeSetup,
						onBack = onClose,
					)
				}
			}
		}
	}
}

private enum class MiniGameSessionAnimationKey {
	SETUP,
	PERMISSION,
	STARTING,
	PLAYING,
	PAUSED,
	FINISHING,
	FINISHED,
	FAILED,
}

private fun MiniGameSessionUiState.animationKey(): MiniGameSessionAnimationKey = when (this) {
	is MiniGameSessionUiState.Setup -> MiniGameSessionAnimationKey.SETUP
	is MiniGameSessionUiState.LocationPermission,
	is MiniGameSessionUiState.NotificationPermission,
	-> MiniGameSessionAnimationKey.PERMISSION
	is MiniGameSessionUiState.Starting -> MiniGameSessionAnimationKey.STARTING
	is MiniGameSessionUiState.Acquiring,
	is MiniGameSessionUiState.Active,
	-> MiniGameSessionAnimationKey.PLAYING
	is MiniGameSessionUiState.Paused -> MiniGameSessionAnimationKey.PAUSED
	is MiniGameSessionUiState.Finishing -> MiniGameSessionAnimationKey.FINISHING
	is MiniGameSessionUiState.Finished -> MiniGameSessionAnimationKey.FINISHED
	is MiniGameSessionUiState.Failed -> MiniGameSessionAnimationKey.FAILED
}

@Composable
private fun LocationPermissionPanel(
	denied: Boolean,
	onResult: (Boolean) -> Unit,
	onRetry: () -> Unit,
	onCancel: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(vertical = RidgelineSpacing.Lg),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
	) {
		Text(
			text = stringResource(R.string.minigame_session_permission_title),
			style = MaterialTheme.typography.titleLarge,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
		Text(
			text = stringResource(R.string.minigame_session_permission_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)

		if (denied) {
			Text(
				text = stringResource(R.string.minigame_failure_permission),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.tertiary,
				textAlign = TextAlign.Center,
			)
			Button(
				onClick = onRetry,
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 48.dp),
			) {
				Text(stringResource(R.string.minigame_session_permission_grant))
			}
			OutlinedButton(
				onClick = onCancel,
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 48.dp),
			) {
				Text(stringResource(R.string.minigame_session_close))
			}
		} else {
			ContextualPermissionRequest(
				permissionType = PermissionType.LOCATION_FOREGROUND,
				permission = Manifest.permission.ACCESS_FINE_LOCATION,
				onPermissionResult = onResult,
				onDismiss = onCancel,
			)
		}
	}
}

@Composable
private fun NotificationPermissionGate(
	onResult: (Boolean) -> Unit,
) {
	val launcher = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.RequestPermission(),
		onResult = onResult,
	)
	LaunchedEffect(Unit) {
		launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
	}
	LoadingPanel(message = stringResource(R.string.minigame_session_starting))
}

@Composable
private fun LoadingPanel(message: String) {
	Column(
		modifier = Modifier.fillMaxSize(),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		CircularProgressIndicator()
		Spacer(Modifier.size(RidgelineSpacing.Md))
		Text(
			text = message,
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
	}
}

@Composable
internal fun ActiveSessionPanel(
	configuration: MiniGameConfiguration,
	snapshot: MiniGameSnapshot,
	paused: Boolean,
	onPause: () -> Unit,
	onResume: () -> Unit,
	onFinish: () -> Unit,
) {
	// Pause/Finish are pinned outside the scrollable area so they stay reachable
	// without scrolling no matter how tall a given game's visualization is.
	Column(modifier = Modifier.fillMaxSize()) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(vertical = RidgelineSpacing.Md),
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			if (paused) {
				Text(
					text = stringResource(R.string.minigame_session_paused_title),
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}

			MiniGameVisualization(snapshot = snapshot)

			GoalProgress(snapshot.goalProgress)

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column {
					Text(
						text = stringResource(R.string.minigame_session_elapsed_label),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = formatElapsed(snapshot.elapsedActiveTimeMs),
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurface,
						fontWeight = FontWeight.SemiBold,
					)
				}
				Text(
					text = signalLabel(snapshot.signal),
					style = MaterialTheme.typography.labelLarge,
					color = if (snapshot.signal.isStale) {
						MaterialTheme.colorScheme.tertiary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
				)
			}

			Text(
				text = stringResource(R.string.minigame_session_background_note),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = RidgelineSpacing.Md),
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
		) {
			if (paused) {
				Button(
					onClick = onResume,
					modifier = Modifier
						.weight(1f)
						.heightIn(min = 48.dp),
				) {
					Icon(Icons.Outlined.PlayArrow, contentDescription = null)
					Spacer(Modifier.size(RidgelineSpacing.Xs))
					Text(stringResource(R.string.minigame_session_resume_cta))
				}
			} else {
				FilledTonalButton(
					onClick = onPause,
					modifier = Modifier
						.weight(1f)
						.heightIn(min = 48.dp),
				) {
					Icon(Icons.Outlined.Pause, contentDescription = null)
					Spacer(Modifier.size(RidgelineSpacing.Xs))
					Text(stringResource(R.string.minigame_session_pause_cta))
				}
			}
			Button(
				onClick = onFinish,
				modifier = Modifier
					.weight(1f)
					.heightIn(min = 48.dp),
			) {
				Icon(Icons.Outlined.Stop, contentDescription = null)
				Spacer(Modifier.size(RidgelineSpacing.Xs))
				Text(stringResource(R.string.minigame_session_finish_cta))
			}
		}
	}

	MiniGameFeedbackAnnouncer(feedback = snapshot.latestFeedback)
}

@Composable
private fun GoalProgress(progress: MiniGameGoalProgress) {
	when (progress) {
		MiniGameGoalProgress.NotConfigured -> Unit
		is MiniGameGoalProgress.Tracked -> {
			val percent = (progress.fraction * 100.0).toInt()
			val label = if (progress.isReached) {
				stringResource(R.string.minigame_session_goal_reached)
			} else {
				stringResource(R.string.minigame_session_goal_progress, percent)
			}
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.semantics { contentDescription = label },
				verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
			) {
				Text(
					text = label,
					style = MaterialTheme.typography.labelLarge,
					color = if (progress.isReached) {
						MaterialTheme.colorScheme.tertiary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					fontWeight = FontWeight.SemiBold,
				)
				LinearProgressIndicator(
					progress = { progress.fraction.toFloat() },
					modifier = Modifier
						.fillMaxWidth()
						.height(8.dp),
					color = MaterialTheme.colorScheme.primary,
					trackColor = MaterialTheme.colorScheme.surfaceVariant,
				)
			}
		}
	}
}

@Composable
internal fun FailurePanel(
	reason: GameSessionFailureReason,
	onTryAgain: () -> Unit,
	onBack: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(vertical = RidgelineSpacing.Lg),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
	) {
		Text(
			text = stringResource(R.string.minigame_session_failed_title),
			style = MaterialTheme.typography.headlineSmall,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.error,
			textAlign = TextAlign.Center,
		)
		Text(
			text = stringResource(failureMessageRes(reason)),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
		Button(
			onClick = onTryAgain,
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 48.dp),
		) {
			Text(stringResource(R.string.minigame_session_try_again))
		}
		OutlinedButton(
			onClick = onBack,
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 48.dp),
		) {
			Text(stringResource(R.string.minigame_session_close))
		}
	}
}

private fun failureMessageRes(reason: GameSessionFailureReason): Int = when (reason) {
	GameSessionFailureReason.PERMISSION_REQUIRED -> R.string.minigame_failure_permission
	GameSessionFailureReason.LOCATION_UNAVAILABLE -> R.string.minigame_failure_location
	GameSessionFailureReason.PERSISTENCE_FAILED -> R.string.minigame_failure_persistence
	GameSessionFailureReason.INVALID_COMMAND,
	GameSessionFailureReason.INTERNAL_ERROR,
	-> R.string.minigame_failure_generic
}

private fun formatElapsed(elapsedMs: Long): String {
	val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
	val minutes = totalSeconds / 60L
	val seconds = totalSeconds % 60L
	return "%d:%02d".format(minutes, seconds)
}
