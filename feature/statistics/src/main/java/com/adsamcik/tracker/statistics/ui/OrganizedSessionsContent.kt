package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.LoadState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun OrganizedSessionsContent(read: SessionOrganizationRead?, selectedDay: LocalDate?,
	onEdit: (OrganizedSession) -> Unit, onShowAll: () -> Unit,
	pages: LazyPagingItems<OrganizedSession>, modifier: Modifier = Modifier) {
	Column(modifier.padding(horizontal = 16.dp)) {
		if (selectedDay != null) Row {
			Text(selectedDay.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)))
			TextButton(onClick = onShowAll) { Text(stringResource(R.string.organization_show_all)) }
		}
		when (read) {
			null -> CircularProgressIndicator()
			SessionOrganizationRead.Unavailable -> Text(stringResource(R.string.organization_unavailable))
			is SessionOrganizationRead.Content -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				if (pages.itemCount == 0 && pages.loadState.refresh is LoadState.Loading) item { CircularProgressIndicator() }
				if (pages.itemCount == 0 && pages.loadState.refresh is LoadState.NotLoading) item { Text(stringResource(R.string.organization_empty)) }
				items(count = pages.itemCount, key = { index -> pages.peek(index)?.id?.toString() ?: "loading:$index" }) { index ->
					val session = pages[index] ?: return@items
					if (session.collectedDataEpoch != read.collectedDataEpoch) return@items
					Card(onClick = { onEdit(session) }, modifier = Modifier.fillMaxWidth()) {
						Column(Modifier.padding(16.dp)) {
							Text(session.name ?: stringResource(R.string.organization_unnamed))
							Text(organizationTimeLabel(session))
							session.activityLabelName?.let { Text(it) }
							if (session.isActive) Text(stringResource(R.string.organization_active))
							if (session.timeUncertain) Text(stringResource(R.string.organization_time_uncertain))
							if (selectedDay != null && session.fromMs?.let { it < selectedDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() } == true)
								Text(stringResource(R.string.organization_continued))
						}
					}
				}
				if (pages.loadState.append is LoadState.Loading) item { CircularProgressIndicator() }
				if (pages.loadState.refresh is LoadState.Error || pages.loadState.append is LoadState.Error) item {
					Text(stringResource(R.string.organization_unavailable))
					TextButton(onClick = pages::retry) { Text(stringResource(R.string.organization_retry)) }
				}
			}
		}
	}
}

internal fun organizationTimeLabel(session: OrganizedSession): String {
	val zone = ZoneId.of(session.displayZoneId)
	val format = DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM,
		java.time.format.FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(zone)
	return listOfNotNull(session.fromMs?.let { format.format(Instant.ofEpochMilli(it)) },
		session.toMs?.let { format.format(Instant.ofEpochMilli(it)) }).joinToString(" – ") + " · " + zone.id
}
