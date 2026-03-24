package com.adsamcik.tracker.activity.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Session activities manager in Compose (legacy base removed). Supports add / edit / swipe delete with undo.
 * Redesigned with Outdoor Modern aesthetic.
 */
@AndroidEntryPoint
class SessionActivityActivityCompose : ComponentActivity() {
    private val viewModel: SessionActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        title = getString(R.string.settings_activity_title)
        setContent { 
            AppTheme { 
                SessionActivityRoute(viewModel = viewModel) 
            } 
        }
    }
}

@Composable
fun SessionActivityRoute(
    viewModel: SessionActivityViewModel,
    onNavigateBack: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val pendingDeleteIds = remember { mutableStateListOf<Long>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingActivity by remember { mutableStateOf<SessionActivity?>(null) }
    val items = uiState.items.filterNot { pendingDeleteIds.contains(it.id) }

    SessionActivityScreen(
        items = items,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onAddActivity = { showAddDialog = true },
        onEditActivity = { editingActivity = it },
        onDeleteActivity = { activity ->
            scope.launch {
                if (!pendingDeleteIds.contains(activity.id)) {
                    pendingDeleteIds.add(activity.id)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.settings_activity_snackbar_message, activity.name),
                    actionLabel = context.getString(com.adsamcik.tracker.shared.base.R.string.generic_undo),
                    duration = SnackbarDuration.Long
                )

                if (result == SnackbarResult.ActionPerformed) {
                    pendingDeleteIds.remove(activity.id)
                } else {
                    viewModel.deleteActivity(activity)
                    pendingDeleteIds.remove(activity.id)
                }
            }
        }
    )

    if (showAddDialog) {
        ActivityEditDialog(
            activity = null,
            onDismiss = { showAddDialog = false },
            onSave = { name ->
                viewModel.insertActivity(name)
                showAddDialog = false
            }
        )
    }

    editingActivity?.let { activity ->
        ActivityEditDialog(
            activity = activity,
            onDismiss = { editingActivity = null },
            onSave = { name ->
                viewModel.updateActivity(activity.copy(name = name))
                editingActivity = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActivityScreen(
    items: List<SessionActivity>,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: (() -> Unit)? = null,
    onAddActivity: () -> Unit,
    onEditActivity: (SessionActivity) -> Unit,
    onDeleteActivity: (SessionActivity) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (onNavigateBack != null) {
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(R.string.settings_activity_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_back)
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddActivity,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_add))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            items(items, key = { it.id }) { activity ->
                SwipeToDeleteActivityItem(
                    activity = activity,
                    onEdit = { onEditActivity(activity) },
                    onDelete = { if (activity.id >= 0) onDeleteActivity(activity) }
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(72.dp)) // Space for FAB
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteActivityItem(
    activity: SessionActivity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == SwipeToDismissBoxValue.EndToStart && activity.id >= 0) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            if (activity.id >= 0) {
                SwipeDeleteBackground()
            }
        },
        content = {
            ActivityItem(
                activity = activity,
                onEdit = if (activity.id >= 0) onEdit else null
            )
        }
    )
}

@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp) // Match card padding logic if needed, but GlassCard handles it internally usually. Background is behind.
            .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_delete),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 16.dp).size(24.dp)
        )
    }
}

@Composable
private fun ActivityItem(
    activity: SessionActivity,
    onEdit: (() -> Unit)?
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().clickable { onEdit?.invoke() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(), // GlassCard applies padding internally approx 16.dp
                // Wait, GlassCard doesn't force 16.dp padding on content, it uses `content: @Composable BoxScope.() -> Unit` and applies padding to the Column inside?
                // Checking previous implementation: `Box(modifier = Modifier.padding(16.dp))` inside GlassCard. 
                // Wait, I didn't verify GlassCard source deeply. Assuming it behaves like a Card with built-in padding or I need to add it.
                // In my DesignSystem.kt write: 
                // `Surface(...) { Box(modifier = Modifier.padding(16.dp)) { content() } }`
                // So yes, padding is built-in.
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = activity.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            // Edit button visibility
            if (onEdit != null) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_edit),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ActivityEditDialog(
    activity: SessionActivity?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(activity?.name ?: "") }
    val isEditing = activity != null

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        title = {
            Text(
                if (isEditing) 
                    stringResource(com.adsamcik.tracker.shared.base.R.string.generic_edit)
                else 
                    stringResource(com.adsamcik.tracker.shared.base.R.string.generic_add)
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(com.adsamcik.tracker.shared.base.R.string.activity_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    )
}
