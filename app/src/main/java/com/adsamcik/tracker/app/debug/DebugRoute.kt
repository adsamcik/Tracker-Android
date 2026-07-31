package com.adsamcik.tracker.app.debug

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.compose.ConfirmDialog
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.tracker.controller.LockManager
import kotlinx.coroutines.launch

private val defaultDispatchers = DefaultDispatchersProvider

/**
 * Compose-based debug tooling screen integrating system status.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DebugRoute(onNavigateBack: () -> Unit = {}, viewModel: DebugViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val lockManager = viewModel.lockManager
    val clearDialog = remember { mutableStateOf(false) }
    var statusExpanded by remember { mutableStateOf(false) }
    Scaffold(
        // Explicit opaque container so the push/pop nav transition doesn't bleed the
        // previous Settings screen through during the animation.
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_debug_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Button(
                    onClick = { clearDialog.value = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { role = Role.Button }
                        .testTag("debug_clear_preferences_button")
                ) {
                    Text(stringResource(R.string.settings_clear_preferences_title))
                }
            }

            if (com.adsamcik.tracker.BuildConfig.DEBUG || com.adsamcik.tracker.BuildConfig.BUILD_TYPE == "dev") {
                item {
                    SeedDataSection()
                }
            }

            item {
                SystemStatusSection(
                    lockManager = lockManager,
                    expanded = statusExpanded,
                    onToggle = { statusExpanded = !statusExpanded }
                )
            }
        }
    }

    ConfirmDialog(
        visible = clearDialog.value,
        title = stringResource(R.string.settings_clear_preferences_title),
        message = stringResource(R.string.settings_clear_preferences_message),
        confirmLabel = stringResource(BaseR.string.generic_yes),
        dismissLabel = stringResource(BaseR.string.generic_no),
        onConfirm = { clearPreferences(ctx) },
        onDismiss = { clearDialog.value = false },
    )
}

@OptIn(ExperimentalStdlibApi::class)
@Composable
private fun SystemStatusSection(
    lockManager: LockManager,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val isLocked by lockManager.isLockedFlow.collectAsState()
    val isTimeLocked = lockManager.isTimeLocked
    val isChargeLocked = lockManager.isChargeLocked
    
    var hasRechargeJob by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(defaultDispatchers.io) {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosByTag("disableTillRecharge").get()
            hasRechargeJob = workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("debug_system_status_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                StatusRow("Is time locked", isTimeLocked)
                StatusRow("Is locked until recharge", isChargeLocked)
                StatusRow("Is locked", isLocked)
                
                StatusRow("Has active wait for recharge job", hasRechargeJob)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: Boolean?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        
        when (value) {
            true -> Text(
                text = "true",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            false -> Text(
                text = "false",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            null -> CircularProgressIndicator(
                modifier = Modifier
                    .width(16.dp)
                    .height(16.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

private fun clearPreferences(context: Context) {
    Preferences(context).edit { clear() }
}
