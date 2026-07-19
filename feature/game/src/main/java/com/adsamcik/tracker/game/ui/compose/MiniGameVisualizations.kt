package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameFeedback
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.FuseRunVisualPayload
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.SwitchbackVisualPayload

/**
 * Dispatches to the game-specific live visualization using only Compose
 * primitives and Material/Ridgeline tokens. No raw coordinates or global cell
 * keys ever reach these composables — they consume the privacy-safe
 * [MiniGameVisualPayload] only.
 *
 * Each game owns its own presentation in a dedicated file
 * ([OutrunGapRail], [TerritoryGrid], [ZenPaceGauge], [FuseRunComposable],
 * [SwitchbackComposable]) so each game remains visually isolated.
 */
@Composable
internal fun MiniGameVisualization(
	snapshot: MiniGameSnapshot,
	modifier: Modifier = Modifier,
) {
	when (snapshot.visualPayload) {
		is MiniGameVisualPayload.Outrun -> OutrunGapRail(snapshot, modifier)
		is MiniGameVisualPayload.Territory -> TerritoryGrid(snapshot, modifier)
		is MiniGameVisualPayload.ZenWalk -> ZenPaceGauge(snapshot, modifier)
		is FuseRunVisualPayload -> FuseRunComposable(snapshot.visualPayload, modifier)
		is SwitchbackVisualPayload -> SwitchbackComposable(snapshot.visualPayload, modifier)
		MiniGameVisualPayload.Pending -> Unit
	}
}

/**
 * Invisible live region that announces mini-game feedback to TalkBack. It
 * updates its description ONLY when a new feedback [MiniGameFeedback.eventId]
 * arrives, so continuous per-sample/per-tick snapshot changes are never spoken.
 */
@Composable
internal fun MiniGameFeedbackAnnouncer(
	feedback: MiniGameFeedback?,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	var announcement by remember { mutableStateOf("") }
	var lastEventId by remember { mutableStateOf(0L) }

	LaunchedEffect(feedback?.eventId?.value) {
		val current = feedback ?: return@LaunchedEffect
		if (current.eventId.value != lastEventId) {
			lastEventId = current.eventId.value
			announcement = context.getString(feedbackAnnouncementRes(current.cue))
		}
	}

	Box(
		modifier = modifier
			.size(1.dp)
			.semantics(mergeDescendants = true) {
				liveRegion = LiveRegionMode.Polite
				contentDescription = announcement
			},
	)
}

private fun feedbackAnnouncementRes(cue: MiniGameFeedbackCue): Int = when (cue) {
	MiniGameFeedbackCue.OutrunDanger -> R.string.minigame_feedback_outrun_danger
	MiniGameFeedbackCue.PersonalBestCrossed -> R.string.minigame_feedback_pb_crossed
	MiniGameFeedbackCue.TerritoryCellClaimed -> R.string.minigame_feedback_territory_claimed
	MiniGameFeedbackCue.FuseCritical -> R.string.minigame_feedback_fuse_critical
	MiniGameFeedbackCue.FuseDefused -> R.string.minigame_feedback_fuse_defused
	MiniGameFeedbackCue.SwitchbackTurnCarved -> R.string.minigame_feedback_turn_carved
	MiniGameFeedbackCue.GoalReached -> R.string.minigame_feedback_goal_reached
	MiniGameFeedbackCue.ZenZoneEntered -> R.string.minigame_feedback_zen_entered
	MiniGameFeedbackCue.ZenZoneExited -> R.string.minigame_feedback_zen_exited
	is MiniGameFeedbackCue.SessionCompleted -> R.string.minigame_feedback_completed
}

/** Human-readable GPS signal label, including a delayed-fix hint when stale. */
@Composable
internal fun signalLabel(signal: MiniGameSignal): String {
	val base = stringResource(
		when (signal.quality) {
			MiniGameSignalQuality.GOOD -> R.string.minigame_signal_good
			MiniGameSignalQuality.FAIR -> R.string.minigame_signal_fair
			MiniGameSignalQuality.POOR -> R.string.minigame_signal_poor
			MiniGameSignalQuality.UNAVAILABLE -> R.string.minigame_signal_unavailable
			MiniGameSignalQuality.UNKNOWN -> R.string.minigame_signal_unknown
		},
	)
	return if (signal.isStale) {
		"$base · ${stringResource(R.string.minigame_signal_stale)}"
	} else {
		base
	}
}
