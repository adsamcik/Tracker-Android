package com.adsamcik.tracker.app.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Contract: Standard clickable settings item with optional icon.
 * Inputs: title, optional subtitle, optional icon, click callback
 * Outputs: Triggers onClick when tapped
 * Failure modes: None (UI component only)
 */
@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (subtitle != null) "$title: $subtitle" else title }
    )
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
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title: $value" }
    )
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
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
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
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
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
                modifier = Modifier.weight(1f)
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Contract: Settings item with an inline slider, value label, and optional help icon.
 * Inputs: title, current value, value range, steps, optional help text, value label formatter, onValueChange callback
 * Outputs: Calls onValueChange(newValue) when slider adjusted; shows help dialog when help icon tapped
 * Failure modes: None (UI component only)
 */
@Composable
fun SliderSettingsItemWithHelp(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
    helpText: String? = null
) {
    var showHelp by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 4.dp)
            )
            
            if (helpText != null) {
                IconButton(
                    onClick = { showHelp = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = "Help",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = valueLabel(value),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 60.dp)
            )
        }
    }
    
    if (showHelp && helpText != null) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(title) },
            text = { Text(helpText) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

/**
 * Contract: Settings item with a toggle switch and optional help icon.
 * Inputs: title, optional subtitle, checked state, optional help text, onCheckedChange callback
 * Outputs: Calls onCheckedChange(newState) when switch toggled; shows help dialog when help icon tapped
 * Failure modes: None (UI component only)
 */
@Composable
fun SwitchSettingsItemWithHelp(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    helpText: String? = null
) {
    var showHelp by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                
                if (helpText != null) {
                    IconButton(
                        onClick = { 
                            showHelp = true
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.HelpOutline,
                            contentDescription = "Help",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        Spacer(Modifier.width(16.dp))
        
        Switch(
            checked = checked,
            onCheckedChange = null // Handled by row click
        )
    }
    
    if (showHelp && helpText != null) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(title) },
            text = { Text(helpText) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text("Got it")
                }
            }
        )
    }
}
