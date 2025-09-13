package com.adsamcik.tracker.activity.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
// Theme wrapper already replaced with MaterialTheme; legacy AppTheme removed.
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session activities manager in Compose (legacy base removed). Supports add / edit / swipe delete with undo.
 */
class SessionActivityActivityCompose : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        title = getString(R.string.settings_activity_title)
        setContent { androidx.compose.material3.MaterialTheme { SessionActivityRoute() } }
    }
}

@Composable
private fun SessionActivityRoute() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val items = remember { mutableStateListOf<SessionActivity>() }
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingActivity by remember { mutableStateOf<SessionActivity?>(null) }

    // Load activities on first composition
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.Default) {
            val activities = SessionActivity.getAll(context)
            withContext(Dispatchers.Main) {
                items.clear()
                items.addAll(activities)
            }
        }
    }

    SessionActivityScreen(
        items = items,
        snackbarHostState = snackbarHostState,
        onAddActivity = { showAddDialog = true },
        onEditActivity = { editingActivity = it },
        onDeleteActivity = { activity ->
            scope.launch(Dispatchers.Default) {
                // Remove from list immediately for UI responsiveness
                items.remove(activity)
                
                // Show snackbar with undo option
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.settings_activity_snackbar_message, activity.name),
                    actionLabel = context.getString(com.adsamcik.tracker.shared.base.R.string.generic_undo),
                    duration = SnackbarDuration.Long
                )
                
                if (result == SnackbarResult.ActionPerformed) {
                    // Undo: add back to list
                    items.add(activity)
                } else {
                    // Delete from database
                    AppDatabase.database(context).activityDao().delete(activity.id)
                }
            }
        }
    )

    // Add dialog
    if (showAddDialog) {
        ActivityEditDialog(
            activity = null,
            onDismiss = { showAddDialog = false },
            onSave = { name ->
                scope.launch(Dispatchers.Default) {
                    val newActivity = SessionActivity(0, name, null)
                    val dao = AppDatabase.database(context).activityDao()
                    val id = dao.insert(newActivity)
                    val savedActivity = newActivity.copy(id = id)
                    
                    withContext(Dispatchers.Main) {
                        items.add(savedActivity)
                        showAddDialog = false
                    }
                }
            }
        )
    }

    // Edit dialog
    editingActivity?.let { activity ->
        ActivityEditDialog(
            activity = activity,
            onDismiss = { editingActivity = null },
            onSave = { name ->
                scope.launch(Dispatchers.Default) {
                    val updatedActivity = activity.copy(name = name)
                    val dao = AppDatabase.database(context).activityDao()
                    dao.update(updatedActivity)
                    
                    withContext(Dispatchers.Main) {
                        val index = items.indexOfFirst { it.id == activity.id }
                        if (index >= 0) {
                            items[index] = updatedActivity
                        }
                        editingActivity = null
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActivityScreen(
    items: List<SessionActivity>,
    snackbarHostState: SnackbarHostState,
    onAddActivity: () -> Unit,
    onEditActivity: (SessionActivity) -> Unit,
    onDeleteActivity: (SessionActivity) -> Unit
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddActivity) {
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
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_delete),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ActivityItem(
    activity: SessionActivity,
    onEdit: (() -> Unit)?
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = activity.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Edit button for user-created activities
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_edit)
                    )
                }
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
