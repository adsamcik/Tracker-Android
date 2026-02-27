package com.adsamcik.tracker.game.ui.compose

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * UI model for a mini-game displayed in the grid.
 */
data class MiniGameUi(
    val id: String,
    val name: String,
    val description: String,
    val unlockLevel: Int,
    val isUnlocked: Boolean,
    val isAvailable: Boolean = true,
)

/**
 * 2-column grid showing available mini-games.
 * Locked games appear dimmed with level requirement overlay.
 */
@Composable
fun MiniGamesGrid(
    games: List<MiniGameUi>,
    modifier: Modifier = Modifier,
) {
    val rows = remember(games) { games.chunked(2) }
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { game ->
                    MiniGameCard(
                        game = game,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MiniGameCard(
    game: MiniGameUi,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isPlayable = game.isUnlocked && game.isAvailable
    val alphaValue = if (isPlayable) 1f else 0.4f
    val statusText = when {
        isPlayable -> game.description
        !game.isUnlocked -> stringResource(R.string.minigame_locked, game.unlockLevel)
        else -> stringResource(R.string.minigame_coming_soon)
    }

    GlassCard(
        modifier = modifier
            .alpha(alphaValue)
            .heightIn(min = 48.dp)
            .then(
                if (!game.isUnlocked) {
                    Modifier.clickable(role = Role.Button) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.minigame_locked, game.unlockLevel),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    Modifier
                }
            )
            .testTag("minigame_card_${game.id}")
            .semantics(mergeDescendants = true) {
                contentDescription = "${game.name}: $statusText"
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (game.isUnlocked) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = game.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (game.isUnlocked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (isPlayable) {
                Text(
                    text = game.description,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!game.isUnlocked) {
                Text(
                    text = stringResource(R.string.minigame_locked, game.unlockLevel),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.minigame_coming_soon),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
