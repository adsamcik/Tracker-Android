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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Contract: Settings item with inline help icon for non-obvious settings.
 * Inputs: title, optional subtitle, optional help text resource ID, optional icon, content composable
 * Outputs: Shows help dialog on help icon tap; delegates main interaction to content
 * Failure modes: None (UI component only)
 * 
 * Progressive disclosure pattern: Help icon is subtle (small, muted) to avoid clutter.
 * Use for settings requiring explanation of trade-offs (battery, accuracy, performance).
 */
@Composable
fun SliderSettingsItemWithHelp(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
    helpTextRes: Int? = null,
    enabled: Boolean = true,
) {
    var showHelp by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            
            if (helpTextRes != null) {
                IconButton(
                    onClick = { showHelp = true }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(com.adsamcik.tracker.R.string.action_help),
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
    
    if (showHelp && helpTextRes != null) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(title) },
            text = { Text(stringResource(helpTextRes)) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(com.adsamcik.tracker.R.string.got_it))
                }
            }
        )
    }
}

/**
 * Contract: Switch settings item with inline help for non-obvious toggles.
 * Inputs: title, optional subtitle, checked state, onCheckedChange callback, optional help text resource ID
 * Outputs: Calls onCheckedChange(newState) when switch toggled; shows help dialog on help icon tap
 * Failure modes: None (UI component only)
 */
@Composable
fun SwitchSettingsItemWithHelp(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    helpTextRes: Int? = null,
    enabled: Boolean = true,
) {
    var showHelp by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                
                if (helpTextRes != null) {
                    IconButton(
                        onClick = { showHelp = true }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(com.adsamcik.tracker.R.string.action_help),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
    
    if (showHelp && helpTextRes != null) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(title) },
            text = { Text(stringResource(helpTextRes)) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(com.adsamcik.tracker.R.string.got_it))
                }
            }
        )
    }
}
