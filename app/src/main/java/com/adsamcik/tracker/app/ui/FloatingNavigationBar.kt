package com.adsamcik.tracker.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppShapes
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

@Composable
fun FloatingNavigationBar(
    items: List<NavigationItem>,
    selectedItem: NavigationItem,
    onItemClick: (NavigationItem) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    Box(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Glass effect container
        GlassCard(
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier
                .height(72.dp)
                .then(
                    if (hazeState != null) {
                        val backgroundColor = MaterialTheme.colorScheme.surface
                        Modifier
                            .hazeEffect(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = if (backgroundColor != Color.Unspecified) backgroundColor else Color.Black,
                                    tint = null
                                )
                            )
                    } else {
                        Modifier
                    }
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    FloatingNavItem(
                        item = item,
                        isSelected = item == selectedItem,
                        onClick = { onItemClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    item: NavigationItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.2f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    
    val color by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "color"
    )

    Column(
        modifier = Modifier
            .testTag(item.testTag)
            .semantics { 
                this.selected = isSelected 
                if (item.stateDescription != null) {
                    this.stateDescription = item.stateDescription
                }
            }
            .requiredSize(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // Custom ripple or none for cleaner look
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.contentDescription,
            tint = color,
            modifier = Modifier.size(24.dp).scale(scale)
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = item.contentDescription,
            style = MaterialTheme.typography.labelSmall,
            color = color,
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
