package com.adsamcik.tracker.impexp.exporter.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

@Composable
internal fun UnfinishedExportFilesNotice(
    state: ImportExportUiState,
    removeFile: (String) -> Unit,
    reload: () -> Unit,
) {
    if (!state.hasUnfinishedFileRecovery) return
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.unfinishedFilesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.unfinishedFilesLoadFailed) {
                Text(stringResource(R.string.export_unfinished_load_failed))
                TextButton(onClick = reload, enabled = !state.unfinishedFilesLoading) {
                    Text(stringResource(R.string.export_unfinished_retry))
                }
            }
            if (state.unfinishedFileNoticeSaveFailed) {
                Text(stringResource(R.string.export_unfinished_notice_save_failed), color = MaterialTheme.colorScheme.error)
            }
            state.unfinishedFiles.forEach { file ->
                val removalDescription = stringResource(R.string.export_unfinished_remove_accessibility, file.name)
                Text(file.name, style = MaterialTheme.typography.titleSmall)
                state.unfinishedFileErrors[file.uri]?.let { error ->
                    Text(stringResource(when (error) {
                        UnfinishedFileRemovalError.FILE_ACCESS -> R.string.export_unfinished_files_guidance
                        UnfinishedFileRemovalError.NOTICE_STORAGE -> R.string.export_unfinished_notice_update_failed
                        UnfinishedFileRemovalError.STARTUP -> R.string.export_unfinished_startup_unavailable
                    }), color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = { removeFile(file.uri) },
                    enabled = state.unfinishedFileRemoving == null,
                    modifier = Modifier.semantics { contentDescription = removalDescription },
                ) {
                    Text(stringResource(R.string.export_unfinished_remove))
                }
                if (state.unfinishedFileRemoving == file.uri) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
