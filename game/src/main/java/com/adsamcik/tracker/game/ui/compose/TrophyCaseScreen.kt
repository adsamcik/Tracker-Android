package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.repository.LifetimeStatsUi
import com.adsamcik.tracker.game.repository.PersonalRecordUi
import com.adsamcik.tracker.game.repository.TrophyItemUi
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrophyCaseScreen(
	activeChallenges: List<ChallengeUi>?,
	trophies: List<TrophyItemUi>?,
	personalRecords: List<PersonalRecordUi>?,
	lifetimeStats: LifetimeStatsUi?,
	currentFilter: TrophyFilter,
	onFilterChanged: (TrophyFilter) -> Unit,
	onChallengeClick: (ChallengeUi) -> Unit,
	onBack: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.windowInsetsPadding(WindowInsets.safeDrawing),
			contentPadding = PaddingValues(bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			// Top bar
			item {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 8.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(onClick = onBack) {
						Icon(
							Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.action_navigate_back),
							tint = MaterialTheme.colorScheme.onSurface,
						)
					}
					Text(
						text = stringResource(R.string.game_trophy_case_title),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.padding(start = 8.dp),
					)
				}
			}

			// Filter chips
			item {
				FilterChipsRow(
					currentFilter = currentFilter,
					onFilterChanged = onFilterChanged,
				)
			}

			if (activeChallenges == null) {
				item {
					TrophyCaseLoadingCard()
				}
			} else if (activeChallenges.isNotEmpty()) {
				item {
					Text(
						text = stringResource(R.string.game_active_challenges),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.padding(horizontal = 16.dp),
					)
				}
				item {
					ActiveChallengesRow(
						challenges = activeChallenges,
						onChallengeClick = onChallengeClick,
						onStartStreakClick = {},
					)
				}
			}

			// Personal records
			if (personalRecords == null) {
				item {
					TrophyCaseLoadingCard()
				}
			} else if (personalRecords.isNotEmpty()) {
				item {
					Text(
						text = stringResource(R.string.game_personal_records_title),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.padding(horizontal = 16.dp),
					)
				}
				item {
					PersonalRecordsRow(records = personalRecords)
				}
			}

			// Trophy list
			if (trophies == null) {
				item {
					TrophyCaseLoadingCard()
				}
			} else if (trophies.isEmpty()) {
				item {
					EmptyStateCard(
						icon = Icons.Outlined.EmojiEvents,
						title = stringResource(R.string.game_trophy_case_empty),
						subtitle = stringResource(R.string.game_trophy_case_empty_subtitle),
						modifier = Modifier.padding(horizontal = 16.dp),
					)
				}
			} else {
				items(trophies, key = { it.id }) { trophy ->
					TrophyCard(trophy = trophy)
				}
			}

			// Lifetime stats
			item {
				Spacer(modifier = Modifier.height(8.dp))
				if (lifetimeStats == null) {
					TrophyCaseLoadingCard()
				} else {
					LifetimeStatsSection(stats = lifetimeStats)
				}
			}
		}
	}
}

@Composable
private fun TrophyCaseLoadingCard() {
	GlassCard(
		modifier = Modifier
			.padding(horizontal = 16.dp)
			.fillMaxWidth()
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 32.dp),
			contentAlignment = Alignment.Center,
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				androidx.compose.material3.CircularProgressIndicator(
					modifier = Modifier.size(32.dp),
					color = MaterialTheme.colorScheme.primary,
				)
				Text(
					text = stringResource(R.string.game_loading),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun FilterChipsRow(
	currentFilter: TrophyFilter,
	onFilterChanged: (TrophyFilter) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		TrophyFilters.forEach { (filter, labelRes) ->
			FilterChip(
				selected = currentFilter == filter,
				onClick = { onFilterChanged(filter) },
				label = { Text(text = stringResource(labelRes)) },
			)
		}
	}
}

@Composable
private fun PersonalRecordsRow(records: List<PersonalRecordUi>) {
	LazyRow(
		contentPadding = PaddingValues(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		items(records, key = { "${it.challengeType}_${it.metric}" }) { record ->
			GlassCard(
				modifier = Modifier.width(140.dp),
			) {
				Column {
					Icon(
						Icons.Outlined.MilitaryTech,
						contentDescription = null,
						modifier = Modifier.size(20.dp),
						tint = MaterialTheme.colorScheme.tertiary,
					)
					Spacer(modifier = Modifier.height(4.dp))
					Text(
						text = record.metric.replace('_', ' ').replaceFirstChar { it.uppercase() },
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = formatRecordValue(record.value),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					Text(
						text = record.challengeType.replace('_', ' ').replaceFirstChar { it.uppercase() },
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun TrophyCard(trophy: TrophyItemUi) {
	val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
	val medalEmoji = when (trophy.medal) {
		"GOLD" -> "🥇"
		"SILVER" -> "🥈"
		"BRONZE" -> "🥉"
		else -> ""
	}
	GlassCard(
		modifier = Modifier
			.padding(horizontal = 16.dp)
			.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				Icons.Outlined.EmojiEvents,
				contentDescription = null,
				modifier = Modifier.size(32.dp),
				tint = MaterialTheme.colorScheme.primary,
			)
			Column(
				modifier = Modifier
					.padding(start = 12.dp)
					.weight(1f),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = trophy.challengeType.replace('_', ' ')
							.replaceFirstChar { it.uppercase() },
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					if (medalEmoji.isNotEmpty()) {
						Text(
							text = " $medalEmoji",
							style = MaterialTheme.typography.titleSmall,
						)
					}
				}
				Text(
					text = trophy.difficulty.replaceFirstChar { it.uppercase() },
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (trophy.completedAt != null) {
					Text(
						text = stringResource(
							R.string.game_trophy_completed_on,
							dateFormatter.format(Date(trophy.completedAt)),
						),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else {
					Text(
						text = stringResource(R.string.game_trophy_expired),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
			if (trophy.xpAwarded > 0) {
				Text(
					text = stringResource(R.string.game_trophy_xp_earned, trophy.xpAwarded),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.primary,
				)
			}
		}
	}
}

private fun formatRecordValue(value: Double): String {
	return if (value == value.toLong().toDouble()) {
		value.toLong().toString()
	} else {
		"%.1f".format(value)
	}
}

private val TrophyFilters = listOf(
	TrophyFilter.ALL to R.string.game_trophy_filter_all,
	TrophyFilter.GOLD to R.string.game_trophy_filter_gold,
	TrophyFilter.SILVER to R.string.game_trophy_filter_silver,
	TrophyFilter.BRONZE to R.string.game_trophy_filter_bronze,
)
