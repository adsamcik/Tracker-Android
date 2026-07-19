package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameAccentRole
import com.adsamcik.tracker.game.minigame.MiniGameIcon
import com.adsamcik.tracker.game.minigame.MiniGameShapeRole
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.MomentumPillShape
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.TerrainCardShape
import com.adsamcik.tracker.shared.utils.style.compose.WaypointShape

/**
 * A distinct, identity-driven play card for one mini-game.
 *
 * Each game gets its own Material accent role, icon and Ridgeline shape
 * treatment (see [com.adsamcik.tracker.game.minigame.MiniGame.presentation]),
 * a measurable goal line and — when a run exists — a personal-best badge.
 *
 * Locked cards keep full-opacity text (never dimmed), stay TalkBack-readable,
 * and reveal how to unlock in an accessible inline expansion instead of a
 * transient Toast.
 */
@Composable
internal fun MiniGamePlayCard(
	game: MiniGamePlayCardUi,
	onPlay: (gameId: String) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (game.isUnlocked) {
		UnlockedPlayCard(game = game, onPlay = onPlay, modifier = modifier)
	} else {
		LockedPlayCard(game = game, modifier = modifier)
	}
}

@Composable
private fun UnlockedPlayCard(
	game: MiniGamePlayCardUi,
	onPlay: (gameId: String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val accent = game.accentRole.color()
	val name = stringResource(game.nameRes)
	val goal = stringResource(game.goalLineRes)
	val pbText = game.personalBest?.let { pb ->
		miniGameScoreLabel(miniGameScoreDisplay(game.scoreUnit, pb))
	}
	val description = buildString {
		append(name)
		append(". ")
		append(goal)
		append(". ")
		if (pbText != null) {
			append(stringResource(R.string.minigame_play_personal_best_talkback, pbText))
			append(". ")
		}
		append(stringResource(R.string.minigame_play_action_play))
	}
	GlassCard(
		modifier = modifier
			.heightIn(min = 96.dp)
			.clickable(role = Role.Button) { onPlay(game.id) }
			.testTag("minigame_play_card_${game.id}")
			.semantics(mergeDescendants = true) { contentDescription = description },
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				GameIconBadge(icon = game.icon, shapeRole = game.shapeRole, accent = accent)
				Spacer(modifier = Modifier.size(RidgelineSpacing.Md))
				Text(
					text = name,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
			}
			// The badge gets its own row instead of competing with the title for
			// width: on narrow two-column cards, squeezing both into one row left
			// the title with almost no space and forced it into unreadable
			// multi-line wrapping (e.g. "Outrun" broken into "Ou"/"tru"/"n").
			if (pbText != null) {
				Spacer(modifier = Modifier.height(RidgelineSpacing.Xs))
				PersonalBestBadge(text = pbText, accent = accent)
			}
			Spacer(modifier = Modifier.height(RidgelineSpacing.Sm))
			Text(
				text = goal,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun LockedPlayCard(
	game: MiniGamePlayCardUi,
	modifier: Modifier = Modifier,
) {
	var expanded by rememberSaveable(game.id) { mutableStateOf(false) }
	val name = stringResource(game.nameRes)
	val lockedSummary = stringResource(R.string.minigame_play_locked_summary, game.unlockLevel)
	val lockedDetail = stringResource(R.string.minigame_play_locked_detail)
	// Collapsed announces the summary + an affordance; expanded announces the
	// full unlock path so TalkBack users never depend on a transient Toast.
	val description = if (expanded) {
		"$name. $lockedSummary. $lockedDetail"
	} else {
		"$name. $lockedSummary. ${stringResource(R.string.minigame_play_locked_expand_cta)}"
	}
	GlassCard(
		modifier = modifier
			.heightIn(min = 96.dp)
			.clickable(role = Role.Button) { expanded = !expanded }
			.testTag("minigame_play_card_${game.id}")
			.semantics(mergeDescendants = true) { contentDescription = description },
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				GameIconBadge(
					icon = MiniGameIcon.entries.first(),
					shapeRole = game.shapeRole,
					accent = MaterialTheme.colorScheme.onSurfaceVariant,
					overrideIcon = Icons.Outlined.Lock,
				)
				Spacer(modifier = Modifier.size(RidgelineSpacing.Md))
				// Full opacity — text stays legible and never dimmed while locked.
				Text(
					text = name,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
			}
			Spacer(modifier = Modifier.height(RidgelineSpacing.Sm))
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					imageVector = Icons.Outlined.Lock,
					contentDescription = null,
					modifier = Modifier.size(16.dp),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(modifier = Modifier.size(RidgelineSpacing.Xs))
				Text(
					text = lockedSummary,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
			// Inline, in-place explanation. Marked clearAndSetSemantics so the
			// merged card description (which already contains this text when
			// expanded) is not duplicated by TalkBack.
			AnimatedVisibility(visible = expanded) {
				Text(
					text = lockedDetail,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(top = RidgelineSpacing.Sm)
						.clearAndSetSemantics {},
				)
			}
		}
	}
}

@Composable
private fun GameIconBadge(
	icon: MiniGameIcon,
	shapeRole: MiniGameShapeRole,
	accent: Color,
	overrideIcon: ImageVector? = null,
) {
	Box(
		modifier = Modifier
			.size(48.dp)
			.clip(shapeRole.shape())
			.background(accent.copy(alpha = 0.16f)),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			imageVector = overrideIcon ?: icon.vector(),
			contentDescription = null,
			modifier = Modifier.size(26.dp),
			tint = accent,
		)
	}
}

@Composable
private fun PersonalBestBadge(text: String, accent: Color) {
	Row(
		modifier = Modifier
			.clip(RoundedCornerShape(percent = 50))
			.background(accent.copy(alpha = 0.16f))
			.padding(horizontal = RidgelineSpacing.Sm, vertical = RidgelineSpacing.Xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = stringResource(R.string.minigame_play_personal_best, text),
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Bold,
			color = accent,
		)
	}
}

/** Resolve a typed score into a localized, unit-aware label (e.g. "87 m", "12 cells", "8:30"). */
@Composable
internal fun miniGameScoreLabel(display: MiniGameScoreDisplay): String = when (display) {
	is MiniGameScoreDisplay.Meters ->
		stringResource(R.string.minigame_unit_meters, display.meters)
	is MiniGameScoreDisplay.Cells ->
		pluralStringResource(R.plurals.minigame_unit_cells, display.cells, display.cells)
	is MiniGameScoreDisplay.Charges ->
		pluralStringResource(R.plurals.minigame_unit_charges, display.charges, display.charges)
	is MiniGameScoreDisplay.Turns ->
		pluralStringResource(R.plurals.minigame_unit_turns, display.turns, display.turns)
	is MiniGameScoreDisplay.Duration -> display.formatted
	is MiniGameScoreDisplay.Raw -> display.value.toString()
}

@Composable
private fun MiniGameAccentRole.color(): Color = when (this) {
	MiniGameAccentRole.PRIMARY -> MaterialTheme.colorScheme.primary
	MiniGameAccentRole.SECONDARY -> MaterialTheme.colorScheme.secondary
	MiniGameAccentRole.TERTIARY -> MaterialTheme.colorScheme.tertiary
}

private fun MiniGameShapeRole.shape() = when (this) {
	MiniGameShapeRole.MOMENTUM -> MomentumPillShape
	MiniGameShapeRole.TERRAIN -> TerrainCardShape
	MiniGameShapeRole.WAYPOINT -> WaypointShape
}

private fun MiniGameIcon.vector(): ImageVector = when (this) {
	MiniGameIcon.GHOST -> Icons.Outlined.Speed
	MiniGameIcon.GRID_FLAG -> Icons.Outlined.GridView
	MiniGameIcon.PACE -> Icons.AutoMirrored.Outlined.DirectionsWalk
	MiniGameIcon.FUSE -> Icons.Outlined.LocalFireDepartment
	MiniGameIcon.SWITCHBACK -> Icons.Outlined.Route
}
