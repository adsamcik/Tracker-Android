package com.adsamcik.tracker.app.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R

/**
 * Contract: Standard clickable settings item with optional icon.
 * Inputs: title, optional subtitle, optional icon, click callback
 * Outputs: Triggers onClick when tapped
 * Failure modes: None (UI component only)
 *
 * M3 list item dimensions: 56dp min for single-line, 72dp min for two-line; we
 * use 56dp min plus 14dp vertical padding so titles with optional subtitles
 * read with a comfortable rhythm and the row's hit target meets WCAG 48dp.
 */
@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (subtitle != null) "$title: $subtitle" else title
            }
    ) {
        SettingsItemContent(
            title = title,
            subtitle = subtitle,
            icon = icon,
        )
    }
}

/**
 * Contract: Settings item displaying title with a value label (non-editable display).
 * Inputs: title, current value display string, optional icon, click callback
 * Outputs: Triggers onClick when tapped
 * Failure modes: None (UI component only)
 */
@Composable
fun SettingsItemWithValue(
    title: String,
    value: String,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title: $value"
            }
    ) {
        SettingsItemContent(
            title = title,
            subtitle = value,
            icon = icon,
            subtitleStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Contract: Settings item with a toggle switch.
 * Inputs: title, optional subtitle, checked state, onCheckedChange callback
 * Outputs: Calls onCheckedChange(newState) when switch toggled
 * Failure modes: None (UI component only)
 */
@Composable
fun SwitchSettingsItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val switchOnDesc = stringResource(R.string.switch_state_on)
    val switchOffDesc = stringResource(R.string.switch_state_off)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics {
                stateDescription = if (checked) switchOnDesc else switchOffDesc
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SettingsItemContent(
                title = title,
                subtitle = subtitle,
                icon = icon,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Contract: Settings item with an inline slider and value label.
 * Inputs: title, current value, value range, steps, value label formatter, onValueChange callback
 * Outputs: Calls onValueChange(newValue) when slider adjusted
 * Failure modes: None (UI component only)
 */
@Composable
fun SliderSettingsItem(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = valueLabel(value),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 60.dp)
            )
        }
    }
}

/**
 * Contract: Section header text with primary color styling.
 * Inputs: header text
 * Outputs: None (pure display)
 * Failure modes: None
 */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() }
    )
}

/**
 * Contract: Card container for grouping settings items, matching the Dashboard design.
 * Inputs: optional title, content composable (ColumnScope)
 * Outputs: Displays content within a styled Card
 * Failure modes: None (UI component only)
 *
 * Visual rhythm:
 *  - Group title: titleMedium, 16dp horizontal padding, 12dp bottom padding so
 *    the card has breathing room from the header text instead of touching it.
 *  - Card content rows separated by full-width hairline dividers (inset to
 *    align with the title text, leaving the icon column clear). The dividers
 *    give each row its own visual lane instead of making items run together
 *    as a wall of text.
 */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                    .semantics { heading() }
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                 containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsItemContent(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    subtitleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = subtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
