package com.adsamcik.tracker.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.fragment.CoreUIFragment
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.shared.utils.style.compose.DynamicTrackerTheme
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.detail.activity.StatsDetailActivity
import com.adsamcik.tracker.statistics.dialog.StatisticSummaryDialog
import com.adsamcik.tracker.statistics.list.recycler.SessionUiModel
import com.adsamcik.tracker.statistics.summary.SummaryGenerator
import com.adsamcik.tracker.statistics.wifi.WifiBrowseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fragment containing summary list of recent tracker sessions.
 */
@Suppress("unused")
class FragmentStats : CoreUIFragment(), IOnDemandView {
    private var viewModel: StatsViewModel? = null

    private fun requireViewModel() = requireNotNull(viewModel)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[StatsViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val activity = requireActivity()
        return ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DynamicTrackerTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        StatsScreen(viewModel = requireViewModel())
                    }
                }
            }
        }
    }

    override fun onEnter(activity: FragmentActivity): Unit = Unit

    override fun onLeave(activity: FragmentActivity): Unit = Unit

    override fun onPermissionResponse(requestCode: Int, success: Boolean): Unit = Unit
}

@Composable
private fun StatsScreen(viewModel: StatsViewModel) {
    val items = viewModel.sessionFlow.collectAsLazyPagingItems()
    val ctx = LocalContext.current

    // Compose equivalents for legacy dialogs
    var summaryReq by remember { mutableStateOf<SummaryReq?>(null) }

    // Crossfade between refresh states for a subtle transition
    val refreshUiState = remember(items.loadState.refresh, items.itemCount) {
        when (items.loadState.refresh) {
            is LoadState.Loading -> RefreshUiState.Loading
            is LoadState.Error -> RefreshUiState.Error
            is LoadState.NotLoading -> if (items.itemCount == 0) RefreshUiState.Empty else RefreshUiState.Content
        }
    }

    Crossfade(targetState = refreshUiState, animationSpec = tween(durationMillis = 200), label = "stats-refresh") { state ->
        when (state) {
            RefreshUiState.Loading -> LoadingContent()
            RefreshUiState.Error -> ErrorContent(onRetry = items::retry)
            RefreshUiState.Empty -> EmptyContent()
            RefreshUiState.Content -> {
                StatsList(
                    items = items,
                    onShowSummary = { summaryReq = SummaryReq.Summary },
                    onShowWeek = { summaryReq = SummaryReq.Week },
                    onOpenWifi = {
                        ctx.startActivity(android.content.Intent(ctx, WifiBrowseActivity::class.java))
                    }
                )
            }
        }
    }

    summaryReq?.let { req ->
        SummaryStatsDialog(req = req, onDismiss = { summaryReq = null })
    }
}

// Expressive spacing constants
private object StatsSpacing {
    val h = 16.dp
    val v = 8.dp
    val chip = 12.dp
    val cardV = 6.dp
}

private sealed class SummaryReq(val titleRes: Int) {
    data object Summary : SummaryReq(R.string.stats_sum_title)
    data object Week : SummaryReq(R.string.stats_weekly_title)
}

internal enum class RefreshUiState { Loading, Error, Empty, Content }

internal enum class AppendUiState { Loading, Error, NotLoading }

@androidx.annotation.VisibleForTesting
@Composable
internal fun StatsScreenTestHost(
    refreshState: RefreshUiState,
    appendState: AppendUiState,
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    includeSampleSessionRow: Boolean = false,
    onOpenDetails: () -> Unit = {},
) {
    Crossfade(targetState = refreshState, animationSpec = tween(durationMillis = 200), label = "stats-refresh-test") { state ->
        when (state) {
            RefreshUiState.Loading -> LoadingContent()
            RefreshUiState.Error -> ErrorContent(onRetry = onRetry)
            RefreshUiState.Empty -> EmptyContent()
            RefreshUiState.Content -> {
                // Show just the header actions to keep test surface minimal
                Column(modifier = Modifier.fillMaxSize()) {
                    ListHeaderRow(onShowSummary, onShowWeek, onOpenWifi, header = SessionUiModel.ListHeader(Time.todayMillis))

                    if (includeSampleSessionRow) {
                        TestSessionItemRow(onClick = onOpenDetails)
                    }

                    when (appendState) {
                        AppendUiState.Loading -> {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(200)),
                                exit = fadeOut(animationSpec = tween(150))
                            ) {
                                Column {
                                    repeat(3) { index ->
                                        Box(Modifier.testTag("stats_placeholder_$index")) {
                                            PlaceholderSessionRow()
                                        }
                                    }
                                }
                            }
                        }
                        AppendUiState.Error -> FooterErrorRow(onRetry = onRetry)
                        AppendUiState.NotLoading -> Unit
                    }
                }
            }
        }
    }
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun TestSessionItemRow(onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = StatsSpacing.h, vertical = StatsSpacing.cardV)
            .testTag("stats_session_row"),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = StatsSpacing.h, vertical = 12.dp)
                .semantics(mergeDescendants = true) { role = Role.Button }
        ) {
            Text(
                text = stringResource(id = R.string.stats_sum_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Sample Session",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryStatsDialog(req: SummaryReq, onDismiss: () -> Unit) {
    val context = LocalContext.current

    var isLoading by remember(req) { mutableStateOf(true) }
    var error: Throwable? by remember(req) { mutableStateOf(null) }
    var stats: List<com.adsamcik.tracker.statistics.data.Stat> by remember(req) { mutableStateOf(emptyList()) }

    LaunchedEffect(req) {
        isLoading = true
        error = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                when (req) {
                    SummaryReq.Summary -> SummaryGenerator.buildSummary(context)
                    SummaryReq.Week -> SummaryGenerator.buildSevenDaySummary(context)
                }
            }
            stats = loaded
        } catch (t: Throwable) {
            error = t
        } finally {
            isLoading = false
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = req.titleRes)) },
        text = {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Text(stringResource(id = R.string.stats_failed_to_load))
                }
                else -> {
                    LazyColumn {
                        items(count = stats.size) { index ->
                            val stat = stats[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = stat.iconRes),
                                    contentDescription = stringResource(id = stat.nameRes),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.padding(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(id = stat.nameRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stat.data.toString(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}

@Composable
private fun StatsList(
    items: LazyPagingItems<SessionUiModel>,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = StatsSpacing.v)
    ) {
        items(
            count = items.itemCount,
            key = { index ->
                when (val model = items.peek(index)) {
                    is SessionUiModel.SessionModel -> model.session.id
                    is SessionUiModel.SessionHeader -> model.date
                    is SessionUiModel.ListHeader -> model.date
                    else -> index.toLong()
                }
            }
        ) { index ->
            when (val model = items[index]) {
                is SessionUiModel.ListHeader -> ListHeaderRow(onShowSummary, onShowWeek, onOpenWifi, model)
                is SessionUiModel.SessionHeader -> SessionHeaderRow(model)
                is SessionUiModel.SessionModel -> SessionItemRow(model)
                null -> PlaceholderSessionRow()
            }
        }

        when (val append = items.loadState.append) {
            is LoadState.Loading -> {
                // Fade in placeholders while appending
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(200)),
                        exit = fadeOut(animationSpec = tween(150))
                    ) {
                        Column {
                            repeat(3) { PlaceholderSessionRow() }
                        }
                    }
                }
            }
            is LoadState.Error -> {
                item { FooterErrorRow(onRetry = items::retry) }
            }
            is LoadState.NotLoading -> {
                // no-op
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(id = R.string.stats_no_tracker_sessions),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ErrorContent(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(id = R.string.stats_error_generic),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text(stringResource(id = R.string.action_retry)) }
        }
    }
}

@Composable
private fun FooterErrorRow(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("stats_append_error"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.stats_append_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = onRetry) { Text(stringResource(id = R.string.action_retry)) }
    }
}

@Composable
private fun PlaceholderSessionRow() {
    Box(
        modifier = Modifier
            .padding(horizontal = StatsSpacing.h, vertical = StatsSpacing.v/2)
            .fillMaxWidth()
            .height(56.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("stats_placeholder")
    )
                // .animateItemPlacement()
}

@Composable
private fun ListHeaderRow(
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    header: SessionUiModel.ListHeader
) {
    Column(modifier = Modifier
        .padding(top = StatsSpacing.v, bottom = StatsSpacing.v/2)
    ) {
        // .animateItemPlacement()
        SessionHeaderRow(SessionUiModel.SessionHeader(header.date))
        HeaderActionsRow(onShowSummary, onShowWeek, onOpenWifi)
    }
}

@Composable
private fun HeaderActionsRow(
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = StatsSpacing.h, vertical = StatsSpacing.v/2),
        horizontalArrangement = Arrangement.spacedBy(StatsSpacing.chip)
    ) {
    ActionChip(icon = Icons.Default.Info, text = stringResource(id = R.string.stats_sum_title)) { onShowSummary() }
    ActionChip(icon = Icons.Default.CalendarMonth, text = stringResource(id = R.string.stats_weekly_title)) { onShowWeek() }
    ActionChip(icon = Icons.Default.Wifi, text = stringResource(id = R.string.stats_wifi_label)) { onOpenWifi() }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { role = Role.Button; contentDescription = text },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(imageVector = icon, contentDescription = text, tint = MaterialTheme.colorScheme.primary)
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SessionHeaderRow(header: SessionUiModel.SessionHeader) {
    val text = android.text.format.DateUtils.getRelativeTimeSpanString(
        header.date,
        com.adsamcik.tracker.shared.base.Time.todayMillis,
        android.text.format.DateUtils.DAY_IN_MILLIS
    ).toString()
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(horizontal = StatsSpacing.h, vertical = StatsSpacing.v)
            .semantics { heading() }
    )
}

@Composable
private fun SessionItemRow(model: SessionUiModel.SessionModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = model.session

    val timeText = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(session.start))

    // Title mimics legacy adapter using SunSetRise and localized activity if available
    val sun = rememberSun()
    val title = StatsFormat.createTitle(
        context,
        session.start,
        session.end,
        SessionActivity.UNKNOWN,
        sun
    )

    val info = run {
        val time = (session.end - session.start).formatAsDuration(context)
        val lengthSystem = Preferences.getLengthSystem(context, SessionActivity.UNKNOWN)
        val distance = context.resources.formatDistance(session.distanceInM, 1, lengthSystem)
        "$time | $distance"
    }

    ElevatedCard(
        onClick = {
            // Navigate to detail
            context.startActivity(
                android.content.Intent(context, StatsDetailActivity::class.java).apply {
                    putExtra(StatsDetailActivity.ARG_SESSION_ID, session.id)
                }
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = StatsSpacing.h, vertical = StatsSpacing.cardV),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = StatsSpacing.h, vertical = 12.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "$timeText, $title, $info"
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberSun(): SunSetRise {
    // lightweight helper to match legacy title; no location updates here
    return SunSetRise().apply {
        // No initialize() with context since we only pass it for formatting in StatsFormat
    }
}
