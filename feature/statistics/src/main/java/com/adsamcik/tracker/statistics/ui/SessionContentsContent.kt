package com.adsamcik.tracker.statistics.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExporter
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportFailure
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SessionExportChoices
import com.adsamcik.tracker.statistics.presenter.SessionOrganizationViewModel
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import java.time.ZoneId

/** Sessions organize the same saved readings shown in Timeline and Calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Keep this cohesive Compose declaration together; its operation and condition checks remain active.
@Suppress("LongMethod", "CyclomaticComplexMethod")
// Keep the selected session presentation and its existing edit/export action bindings together.
internal fun SessionContentsContent(
    session: OrganizedSession,
    pages: LazyPagingItems<HistoryPagingItem>,
    unavailable: Boolean,
    exportBusy: Boolean,
    exportFailure: SessionBundleExportFailure?,
    choices: SessionExportChoices?,
    organization: SessionOrganizationViewModel,
) {
    BackHandler(onBack = organization::closeContents)
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SessionBundleExporter.MIME_TYPE),
        onResult = organization::exportTo)
    val snackbar = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.organization_export_saved)
    val expandedMessage = stringResource(R.string.organization_export_saved_extra_time)
    LaunchedEffect(organization, savedMessage, expandedMessage) {
        organization.exportSaved.collect { snackbar.showSnackbar(if (it.includesExtraTime) {
            expandedMessage
        } else {
            savedMessage
        }) }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(session.name ?: stringResource(R.string.organization_unnamed)) },
            navigationIcon = { IconButton(onClick = organization::closeContents) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_navigate_back))
            } }, actions = {
                TextButton(onClick =
                    { organization.edit(session) }) { Text(stringResource(R.string.organization_edit)) }
                TextButton(onClick = organization::chooseExportSources, enabled = !exportBusy &&
                    !unavailable && !session.isActive &&
                    session.fromMs != null && (session.toMs != null || session.isActive)) {
                    Text(stringResource(R.string.organization_export))
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(organizationTimeLabel(session), Modifier.padding(horizontal = 16.dp))
            session.activityLabelName?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
            if (session.isActive) {
                Text(stringResource(R.string.organization_end_first), Modifier.padding(horizontal = 16.dp))
            }
            if (unavailable) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.history_value_unavailable), Modifier.weight(1f))
                TextButton(onClick = organization::retryContents) { Text(stringResource(R.string.action_retry)) }
            }
            }
            if (exportBusy) {
                CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
            }
            exportFailure?.let { Text(stringResource(when (it) {
                SessionBundleExportFailure.EMPTY -> R.string.history_measurements_empty
                SessionBundleExportFailure.STALE -> R.string.organization_stale
                SessionBundleExportFailure.UNAVAILABLE -> R.string.history_value_unavailable
                SessionBundleExportFailure.STORAGE -> R.string.organization_export_failed
            }), Modifier.padding(horizontal = 16.dp)) }
            if (session.fromMs == null || session.toMs == null && !session.isActive) {
                Text(stringResource(R.string.organization_time_uncertain), Modifier.padding(16.dp))
            } else {
                MeasuredHistoryContent(pages, ZoneId.of(session.displayZoneId), Modifier.weight(1f))
            }
        }
    }
    choices?.let { selection -> SessionExportChoicesDialog(selection, onDismiss = organization::dismissExportChoices,
        onExport = { sources ->
            if (organization.prepareExport(sources)) {
                save.launch(sessionExportFileName(selection.session))
            }
        }) }
}

@Composable
internal fun SessionExportChoicesDialog(choices: SessionExportChoices, onDismiss: () -> Unit,
    onExport: (Set<HistorySource>) -> Unit) {
    var selected by remember(choices) { mutableStateOf(choices.availableSources) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.organization_export)) },
        text = { Column {
            if (choices.availableSources.isEmpty()) {
                Text(stringResource(R.string.history_measurements_empty))
            }
            choices.availableSources.sortedBy { it.ordinal }.forEach { source ->
                Row(Modifier.fillMaxWidth().toggleable(source in selected, role = Role.Checkbox,
                    onValueChange = { checked -> selected = if (checked) {
                        selected + source
                    } else {
                        selected - source
                    } })) {
                    Checkbox(checked = source in selected, onCheckedChange = null)
                    Text(stringResource(source.labelResource), Modifier.padding(vertical = 12.dp))
                }
            }
            choices.unavailableSources.sortedBy { it.ordinal }.forEach { source ->
                Text(stringResource(source.labelResource) + " · " + stringResource(R.string.history_value_unavailable))
            }
        } },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onExport(selected) }) {
            Text(stringResource(R.string.organization_export))
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.organization_cancel)) } })
}

internal fun sessionExportFileName(session: OrganizedSession): String {
    val name = session.name?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")?.trim()?.take(80)
        ?.takeIf(String::isNotEmpty) ?: "session"
    return "$name.${SessionBundleExporter.EXTENSION}"
}
