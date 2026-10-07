package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SessionEditorTarget
import com.adsamcik.tracker.stats.api.repository.OrganizationActivityLabel
import com.adsamcik.tracker.stats.api.repository.OrganizationEditProblem
import com.adsamcik.tracker.stats.api.repository.OrganizationInterval
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun SessionOrganizationEditor(target: SessionEditorTarget, labels: List<OrganizationActivityLabel>,
	busy: Boolean, problem: OrganizationEditProblem?, onDismiss: () -> Unit, onReload: () -> Unit,
	onSave: (OrganizationInterval?, String, String?, Long?, Boolean) -> Unit, onRemove: () -> Unit,
	onRecording: ((Long) -> Unit)? = null) {
	val initialStart = remember(target) { Instant.ofEpochMilli(target.interval.fromMs).atZone(ZoneId.of(target.zoneId)) }
	val initialEnd = remember(target) { Instant.ofEpochMilli(target.interval.toMs).atZone(ZoneId.of(target.zoneId)) }
	var name by remember(target) { mutableStateOf(target.session?.name.orEmpty()) }
	var label by remember(target) { mutableStateOf(target.session?.activityLabelId) }
	var clearImportedActivityLabel by remember(target) { mutableStateOf(false) }
	var zone by remember(target) { mutableStateOf(target.zoneId) }
	var pendingZone by remember(target) { mutableStateOf(target.zoneId) }
	var startDate by remember(target) { mutableStateOf(if (target.session != null && target.session.fromMs == null) "" else initialStart.toLocalDate().toString()) }
	var startTime by remember(target) { mutableStateOf(if (target.session != null && target.session.fromMs == null) "" else initialStart.toLocalTime().toString()) }
	var endDate by remember(target) { mutableStateOf(if (target.session != null && target.session.toMs == null) "" else initialEnd.toLocalDate().toString()) }
	var endTime by remember(target) { mutableStateOf(if (target.session != null && target.session.toMs == null) "" else initialEnd.toLocalTime().toString()) }
	var startOffset by remember(target) { mutableStateOf(initialStart.offset.id) }
	var endOffset by remember(target) { mutableStateOf(initialEnd.offset.id) }
	var intervalEdited by remember(target) { mutableStateOf(false) }
	var attempted by remember(target) { mutableStateOf(false) }
	var confirmRemove by remember(target) { mutableStateOf(false) }
	var chooseLabel by remember(target) { mutableStateOf(false) }
	val active = target.session?.isActive == true
	val start = resolveSessionEditorTime(startDate, startTime, zone, startOffset)
	val end = resolveSessionEditorTime(endDate, endTime, zone, endOffset)
	val fieldsEnabled = !busy && !active
	val validZone = pendingZone in ZoneId.getAvailableZoneIds()
	val nameTooLong = name.trim().codePointCount(0, name.trim().length) > 200
	AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
		title = { Text(stringResource(if (target.session == null) R.string.organization_add else R.string.organization_edit)) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState())) {
				OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.organization_name)) },
					enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
					isError = nameTooLong, supportingText = { if (nameTooLong) Text(stringResource(R.string.organization_name_long)) })
				TextButton(onClick = { chooseLabel = true }, enabled = !busy) {
					Text(stringResource(R.string.organization_label) + ": " +
						(labels.firstOrNull { it.id == label }?.name ?: target.session?.activityLabelName?.takeIf { !clearImportedActivityLabel && label == target.session.activityLabelId }
							?: stringResource(R.string.organization_no_label)))
				}
				HorizontalDivider()
				if (active) Text(stringResource(R.string.organization_end_first))
				if (target.session?.timeUncertain == true) Text(stringResource(R.string.organization_time_uncertain))
				TimeFields(R.string.organization_start, startDate, startTime, start, startOffset,
					fieldsEnabled, attempted, { startDate = it; startOffset = ""; intervalEdited = true },
					{ startTime = it; startOffset = ""; intervalEdited = true }, { startOffset = it; intervalEdited = true })
				TimeFields(R.string.organization_end, endDate, endTime, end, endOffset,
					fieldsEnabled, attempted, { endDate = it; endOffset = ""; intervalEdited = true },
					{ endTime = it; endOffset = ""; intervalEdited = true }, { endOffset = it; intervalEdited = true })
				OutlinedTextField(pendingZone, { pendingZone = it }, enabled = fieldsEnabled,
					label = { Text(stringResource(R.string.organization_zone)) }, singleLine = true,
					isError = !validZone, supportingText = { if (!validZone) Text(stringResource(R.string.organization_invalid_zone)) })
				TextButton(enabled = fieldsEnabled && validZone && pendingZone != zone && start is SessionEditorTimeResult.Exact && end is SessionEditorTimeResult.Exact,
					onClick = {
						val next = runCatching { ZoneId.of(pendingZone) }.getOrNull()
						if (next != null && start is SessionEditorTimeResult.Exact && end is SessionEditorTimeResult.Exact) {
							val from = Instant.ofEpochMilli(start.epochMs).atZone(next)
							val to = Instant.ofEpochMilli(end.epochMs).atZone(next)
							zone = next.id; startDate = from.toLocalDate().toString(); startTime = from.toLocalTime().toString()
							endDate = to.toLocalDate().toString(); endTime = to.toLocalTime().toString()
							startOffset = from.offset.id; endOffset = to.offset.id
						}
					}) { Text(stringResource(R.string.organization_apply_zone)) }
				Text(zone)
				if (pendingZone != zone) Text(stringResource(R.string.organization_apply_zone_first))
				if (problem != null) Text(stringResource(organizationProblemText(problem)))
				if (problem == OrganizationEditProblem.STALE) TextButton(onClick = onReload, enabled = !busy) {
					Text(stringResource(R.string.organization_reload))
				}
				target.session?.originalSegmentId?.let { id -> if (onRecording != null) TextButton(
					onClick = { onDismiss(); onRecording(id) }, enabled = !busy) { Text(stringResource(R.string.organization_recording)) } }
				if (target.session != null) TextButton(onClick = { confirmRemove = true }, enabled = !busy && !active) {
					Text(stringResource(R.string.organization_remove))
				}
			}
		},
		confirmButton = { Button(enabled = !busy, onClick = {
			attempted = true
			if (pendingZone != zone || !validZone || nameTooLong) return@Button
			val change = if (!active && (target.session == null || intervalEdited)) {
				if (start !is SessionEditorTimeResult.Exact || end !is SessionEditorTimeResult.Exact) return@Button
				OrganizationInterval(start.epochMs, end.epochMs)
			} else null
			onSave(change, zone, name, label, clearImportedActivityLabel)
		}) { Text(stringResource(R.string.organization_save)) } },
		dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.organization_cancel)) } },
	)
	if (chooseLabel) AlertDialog(onDismissRequest = { chooseLabel = false },
		title = { Text(stringResource(R.string.organization_label)) },
		text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
			item { TextButton(onClick = { label = null; clearImportedActivityLabel = true; chooseLabel = false }) { Text(stringResource(R.string.organization_no_label)) } }
			items(labels, key = { it.id }) { item ->
				TextButton(onClick = { label = item.id; clearImportedActivityLabel = false; chooseLabel = false }) { Text(item.name) }
			}
		} },
		confirmButton = {}, dismissButton = { TextButton(onClick = { chooseLabel = false }) { Text(stringResource(R.string.organization_cancel)) } },
	)
	if (confirmRemove) AlertDialog(onDismissRequest = { if (!busy) confirmRemove = false },
		title = { Text(stringResource(R.string.organization_remove_title)) },
		text = { Text(stringResource(R.string.organization_remove_body)) },
		confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }, enabled = !busy) { Text(stringResource(R.string.organization_remove)) } },
		dismissButton = { TextButton(onClick = { confirmRemove = false }, enabled = !busy) { Text(stringResource(R.string.organization_cancel)) } },
	)
}

@Composable
private fun TimeFields(title: Int, date: String, time: String, result: SessionEditorTimeResult, offset: String,
	enabled: Boolean, attempted: Boolean, onDate: (String) -> Unit, onTime: (String) -> Unit, onOffset: (String) -> Unit) {
	Text(stringResource(title))
	OutlinedTextField(date, onDate, enabled = enabled, singleLine = true,
		label = { Text(stringResource(R.string.organization_date)) }, supportingText = { Text("YYYY-MM-DD") },
		isError = attempted && result == SessionEditorTimeResult.Invalid)
	OutlinedTextField(time, onTime, enabled = enabled, singleLine = true,
		label = { Text(stringResource(R.string.organization_time)) }, supportingText = { Text("HH:mm") },
		isError = attempted && (result == SessionEditorTimeResult.Invalid || result == SessionEditorTimeResult.Nonexistent))
	if (result == SessionEditorTimeResult.Nonexistent) Text(stringResource(R.string.organization_dst_gap))
	if (result is SessionEditorTimeResult.ChooseOffset) {
		Text(stringResource(R.string.organization_dst_overlap))
		result.offsets.forEach { value -> Row {
			RadioButton(selected = offset == value.id, onClick = { onOffset(value.id) }, enabled = enabled)
			TextButton(onClick = { onOffset(value.id) }, enabled = enabled) { Text(value.id) }
		} }
	}
}

internal fun organizationProblemText(problem: OrganizationEditProblem): Int = when (problem) {
	OrganizationEditProblem.INVALID_INTERVAL -> R.string.organization_invalid_interval
	OrganizationEditProblem.FUTURE_END -> R.string.organization_future_end
	OrganizationEditProblem.INVALID_ZONE -> R.string.organization_invalid_zone
	OrganizationEditProblem.NAME_TOO_LONG -> R.string.organization_name_long
	OrganizationEditProblem.MISSING_ACTIVITY_LABEL -> R.string.organization_label_missing
	OrganizationEditProblem.END_SESSION_FIRST -> R.string.organization_end_first
	OrganizationEditProblem.STALE -> R.string.organization_stale
	OrganizationEditProblem.NOT_FOUND -> R.string.organization_missing
	OrganizationEditProblem.STORAGE_UNAVAILABLE -> R.string.organization_unavailable
}
