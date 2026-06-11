package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun RadioCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    minHeight: Dp = 56.dp,
    selectedBorder: BorderStroke? = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    cardColors: CardColors? = null,
    cardBorder: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val usesMaterialCard = cardColors != null || cardBorder != null || contentPadding != null
    val interactionSource = remember { MutableInteractionSource() }
    val radioModifier = modifier
        .then(
            if (!usesMaterialCard && selected && selectedBorder != null) {
                Modifier.border(selectedBorder, shape)
            } else {
                Modifier
            }
        )
        .heightIn(min = minHeight)
        .selectable(
            selected = selected,
            interactionSource = interactionSource,
            indication = ripple(),
            onClick = onClick,
            role = Role.RadioButton,
        )

    if (usesMaterialCard) {
        Card(
            modifier = radioModifier,
            shape = shape,
            colors = cardColors ?: CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            border = cardBorder ?: if (selected) selectedBorder else null,
        ) {
            RadioCardRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding ?: PaddingValues(16.dp)),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = verticalAlignment,
                content = content,
            )
        }
    } else {
        GlassCard(
            modifier = radioModifier,
            shape = shape,
        ) {
            RadioCardRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = verticalAlignment,
                content = content,
            )
        }
    }
}

@Composable
private fun RadioCardRow(
    modifier: Modifier,
    horizontalArrangement: Arrangement.Horizontal,
    verticalAlignment: Alignment.Vertical,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = verticalAlignment,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}
