package com.adsamcik.tracker.app.ui

import androidx.compose.animation.animateColorAsState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.tweenQuick

private val NavBarShape = RoundedCornerShape(32.dp)
private val NavRailShape = RoundedCornerShape(28.dp)

@Composable
fun FloatingNavigationBar(
    items: List<NavigationItem>,
    selectedItem: NavigationItem,
    onItemClick: (NavigationItem) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    // Horizontal inset to keep the pill inset from screen edges; vertical padding above is supplied by
    // the caller via `navigationBarsPadding()` so we don't double up with the system nav-bar clearance.
    Box(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val navContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    FloatingNavItem(
                        item = item,
                        isSelected = item == selectedItem,
                        onClick = { onItemClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (hazeState != null) {
            val surfaceColor = MaterialTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .height(80.dp)
                    .fillMaxWidth()
                    .clip(NavBarShape)
                    .hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = surfaceColor,
                            tints = listOf(
                                HazeTint(surfaceColor.copy(alpha = 0.78f)),
                            ),
                        ),
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        ),
                        NavBarShape
                    )
                    .padding(16.dp),
            ) {
                navContent()
            }
        } else {
            GlassCard(
                shape = NavBarShape,
                modifier = Modifier.height(80.dp)
            ) {
                navContent()
            }
        }
    }
}

@Composable
fun AdaptiveNavigationRail(
    items: List<NavigationItem>,
    selectedItem: NavigationItem,
    onItemClick: (NavigationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = NavRailShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        ),
    ) {
        NavigationRail(
            containerColor = Color.Transparent,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            items.forEach { item ->
                NavigationRailItem(
                    selected = item == selectedItem,
                    onClick = { onItemClick(item) },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.contentDescription,
                        )
                    },
                    label = {
                        Text(
                            text = item.contentDescription,
                            maxLines = 1,
                        )
                    },
                    alwaysShowLabel = false,
                    modifier = Modifier
                        .testTag(item.testTag)
                        .semantics {
                            this.selected = item == selectedItem
                            if (item.stateDescription != null) {
                                this.stateDescription = item.stateDescription
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    item: NavigationItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorAnimationSpec = tweenQuick<Color>()
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = colorAnimationSpec,
        label = "iconTint"
    )

    val indicatorColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = colorAnimationSpec,
        label = "indicatorColor"
    )

    val labelColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = colorAnimationSpec,
        label = "labelColor"
    )

    Column(
        modifier = modifier
            .testTag(item.testTag)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .selectable(
                selected = isSelected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 28.dp),
                onClick = onClick,
                role = Role.Tab
            )
            .semantics(mergeDescendants = true) {
                contentDescription = item.contentDescription
                if (item.stateDescription != null) {
                    this.stateDescription = item.stateDescription
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // M3 pill-shaped indicator behind the icon
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(indicatorColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = item.contentDescription,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1
        )
    }
}

data class NavigationItem(
    val id: Any,
    val icon: ImageVector,
    val contentDescription: String,
    val testTag: String,
    val stateDescription: String? = null
)
