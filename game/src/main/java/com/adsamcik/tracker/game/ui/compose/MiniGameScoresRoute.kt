package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSectionHeader
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Read-only history of past mini-game runs grouped by game.
 *
 * Each group is rendered as a [RidgelineSectionHeader] + ranked rows. Rank #1
 * is the player's personal best for that game.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniGameScoresRoute(
	onBack: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val vm: MiniGameScoresViewModel = hiltViewModel()
	val groups by vm.groups.collectAsStateWithLifecycle()

	Scaffold(
		modifier = modifier.fillMaxSize(),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.minigame_scores_title)) },
				navigationIcon = {
					IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
						Icon(
							Icons.AutoMirrored.Outlined.ArrowBack,
							contentDescription = stringResource(R.string.minigame_scores_back),
						)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.surface,
				),
			)
		},
	) { innerPadding ->
		val current = groups
		when {
			current == null -> Box(
				modifier = Modifier.fillMaxSize().padding(innerPadding),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = stringResource(R.string.game_loading),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			current.isEmpty() -> EmptyScoresState(
				modifier = Modifier
					.fillMaxSize()
					.padding(innerPadding)
					.padding(horizontal = RidgelineSpacing.Lg),
			)
			else -> LazyColumn(
				modifier = Modifier
					.fillMaxSize()
					.padding(innerPadding),
				contentPadding = PaddingValues(
					top = RidgelineSpacing.Lg,
					bottom = RidgelineSpacing.Xxxl,
				),
				verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
			) {
				current.forEach { group ->
					item(key = "header-${group.gameId}") {
						RidgelineSectionHeader(
							title = group.nameRes?.let { stringResource(it) }
								?: stringResource(R.string.minigame_scores_unknown_game),
						)
					}
					item(key = "card-${group.gameId}") {
						ScoreGroupCard(group = group)
					}
				}
			}
		}
	}
}

@Composable
private fun ScoreGroupCard(group: MiniGameScoreGroup) {
	GlassCard(
		modifier = Modifier
			.padding(horizontal = RidgelineSpacing.Lg)
			.fillMaxWidth()
			.testTag("minigame_scores_group_${group.gameId}"),
	) {
		Column(
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
		) {
			group.entries.forEachIndexed { index, entry ->
				ScoreRow(rank = index + 1, entry = entry)
			}
		}
	}
}

@Composable
private fun ScoreRow(
	rank: Int,
	entry: MiniGameScoreEntity,
) {
	val numberFormat = remember { NumberFormat.getInstance() }
	val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
	) {
		Text(
			text = stringResource(R.string.minigame_scores_rank_format, rank),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Bold,
			color = if (rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
		)
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = stringResource(
					R.string.minigame_scores_score_format,
					numberFormat.format(entry.score.roundToInt()),
				),
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = dateFormat.format(Date(entry.playedAt)),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Text(
			text = stringResource(R.string.minigame_scores_points_format, entry.xpAwarded),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.primary,
		)
	}
}

@Composable
private fun EmptyScoresState(
	modifier: Modifier = Modifier,
) {
	val transition = rememberInfiniteTransition(label = "minigame-scores-empty-pulse")
	val pulseAlpha by transition.animateFloat(
		initialValue = 0.6f,
		targetValue = 1.0f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = 1800),
			repeatMode = RepeatMode.Reverse,
		),
		label = "minigame-scores-empty-alpha",
	)
	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Icon(
			imageVector = Icons.Outlined.Insights,
			contentDescription = null,
			modifier = Modifier
				.size(96.dp)
				.alpha(pulseAlpha),
			tint = MaterialTheme.colorScheme.primary,
		)
		Text(
			text = stringResource(R.string.minigame_scores_empty_title),
			style = MaterialTheme.typography.headlineSmall,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
			modifier = Modifier.padding(top = RidgelineSpacing.Lg),
		)
		Text(
			text = stringResource(R.string.minigame_scores_empty_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.padding(top = RidgelineSpacing.Sm),
		)
	}
}
