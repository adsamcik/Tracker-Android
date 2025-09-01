package com.adsamcik.tracker.statistics.detail.activity

import android.content.Context
import android.os.Bundle
// removed unused rememberLauncher/activity result imports
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.shared.base.data.BaseLocation
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.requireValue
import com.adsamcik.tracker.shared.base.extension.toCalendar
import com.adsamcik.tracker.shared.utils.activity.ComposeDetailActivity
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.data.source.StatisticDataManager
import com.adsamcik.tracker.statistics.detail.SessionActivitySelection
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData
import com.adsamcik.tracker.statistics.detail.recycler.data.InformationStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.LineChartStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.MapStatisticsData
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Activity for statistic details (Compose)
 */
class StatsDetailActivity : ComposeDetailActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Title set later once session is loaded
		onConfigure(Configuration(showBackButton = true, title = ""))
	}

	@Composable
	override fun Content() {
		val context = LocalContext.current
		val vm: ViewModel = viewModel()

		val sessionId = remember { intent.getLongExtra(ARG_SESSION_ID, -1) }
		require(sessionId > 0L) { "Argument $ARG_SESSION_ID must be set with valid value!" }

		LaunchedEffect(sessionId) {
			vm.initialize(context, sessionId)
		}

		val session = vm.session.observeAsStateCompat()

		var items by remember { mutableStateOf<List<StatisticsDetailData>>(emptyList()) }
		var showDelete by remember { mutableStateOf(false) }

		LaunchedEffect(session.value?.id) {
			val s = session.value ?: return@LaunchedEffect
			// Update title once we have session
			val sessionActivity = withContext(Dispatchers.IO) { resolveSessionActivity(context, s) }
			val title = StatsFormat.createTitle(
				context,
				s.start,
				s.end,
				sessionActivity,
				vm.sunSetRise
			)
			setActivityTitle(title)

			// Load stats for content
			withContext(Dispatchers.IO) {
				com.adsamcik.tracker.statistics.preference.SessionActivityContext.withSessionActivity(sessionActivity) {
					StatisticDataManager().getForSession(context, s.id, true) { stat ->
						// Switch back to main when updating Compose state
						launchMain {
							items = items + convertToDisplayData(stat)
						}
					}
				}
			}
		}

		val startEnd = remember(session.value?.start, session.value?.end) {
			session.value?.let {
				val endCalendar = Date(it.end).toCalendar()
				val startCalendar = Date(it.start).toCalendar()
				StatsFormat.formatRange(startCalendar, endCalendar)
			} ?: ""
		}

		// App bar actions: edit session, delete session
		LaunchedEffect(Unit) {
			updateActions(
				listOf(
					{ IconButton(onClick = { showEditSession(context, vm) }) { Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.edit_session)) } },
					{ IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.remove_session)) } }
				)
			)
		}

		if (session.value == null) {
			// If session not found, close activity
			LaunchedEffect(Unit) { finish() }
			return
		}

		Column(modifier = Modifier.fillMaxSize()) {
			if (startEnd.isNotEmpty()) {
				Text(
					text = startEnd,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
				)
			}

			LazyColumn(
				modifier = Modifier.fillMaxSize()
			) {
				items(items) { item ->
					when (item) {
						is InformationStatisticsData -> InformationItem(item)
						is MapStatisticsData -> MapItem(item)
						is LineChartStatisticsData -> LineChartItem(item)
					}
				}
				item { Spacer(modifier = Modifier.height(24.dp)) }
			}
		}

		if (showDelete) {
			AlertDialog(
				onDismissRequest = { showDelete = false },
				title = { Text(text = stringResource(id = com.adsamcik.tracker.shared.base.R.string.alert_confirm_generic)) },
				text = {
					Text(
						text = stringResource(
							id = com.adsamcik.tracker.shared.base.R.string.alert_confirm,
							stringResource(id = R.string.remove_session)
						)
					)
				},
				confirmButton = {
					IconButton(onClick = {
						removeSession(vm) { finish() }
						showDelete = false
					}) { Icon(Icons.Default.Delete, contentDescription = "Confirm delete") }
				},
				dismissButton = {
					IconButton(onClick = { showDelete = false }) { Text(text = stringResource(id = com.adsamcik.tracker.shared.base.R.string.generic_no)) }
				}
			)
		}
	}

	private fun showEditSession(context: Context, vm: ViewModel) {
		val activities = SessionActivity.getAll(context)
		SessionActivitySelection(
			context,
			activities,
			vm.session.value ?: return
		).showActivitySelectionDialog()
	}

	private fun removeSession(vm: ViewModel, onDone: () -> Unit) {
		// Offload to IO
		launchMainIO {
			val dao = AppDatabase.database(this@StatsDetailActivity).sessionDao()
			dao.delete(vm.session.requireValue)
			launchMain { onDone() }
		}
	}

	@Composable
	private fun InformationItem(data: InformationStatisticsData) {
		androidx.compose.material3.ListItem(
			leadingContent = {
				Icon(
					painter = painterResource(id = data.iconRes),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary
				)
			},
			headlineContent = { Text(text = stringResource(id = data.titleRes)) },
			supportingContent = { Text(text = data.value) }
		)
	}

	@Composable
	private fun MapItem(data: MapStatisticsData) {
		val cameraPositionState = rememberCameraPositionState()

		LaunchedEffect(data.bounds) {
			val bounds = LatLngBounds.Builder()
				.include(LatLng(data.bounds.bottom, data.bounds.left))
				.include(LatLng(data.bounds.top, data.bounds.right))
				.build()
			cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 32))
		}

		val points = remember(data.locations) {
			data.locations.map { LatLng(it.latitude, it.longitude) }
		}

		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(220.dp)
				.padding(horizontal = 16.dp, vertical = 8.dp)
		) {
			GoogleMap(
				modifier = Modifier.fillMaxSize(),
				cameraPositionState = cameraPositionState,
				uiSettings = MapUiSettings(zoomControlsEnabled = false)
			) {
				if (points.isNotEmpty()) {
					Polyline(points = points)
				}
			}
		}
	}

	@Composable
	private fun LineChartItem(data: LineChartStatisticsData) {
		val label = stringResource(id = data.titleRes)
		val color = MaterialTheme.colorScheme.primary.toArgb()
		androidx.compose.ui.viewinterop.AndroidView(
			modifier = Modifier
				.fillMaxWidth()
				.height(200.dp)
				.padding(horizontal = 16.dp, vertical = 8.dp),
			factory = { ctx ->
				LineChart(ctx).apply {
					description.isEnabled = false
				}
			},
			update = { chart ->
				val dataSet = LineDataSet(data.values, label).apply {
					setDrawCircles(false)
					setDrawValues(false)
					setDrawFilled(false)
					lineWidth = 1f
					mode = LineDataSet.Mode.LINEAR
					this.color = color
				}
				chart.data = LineData(dataSet)
				chart.invalidate()
			}
		)
	}

	private suspend fun resolveSessionActivity(context: Context, session: TrackerSession): SessionActivity {
		val activityId = session.sessionActivityId
		return when {
			activityId == null -> null
			activityId < -1 -> NativeSessionActivity.entries.find { it.id == activityId }?.getSessionActivity(context)
			else -> if (activityId == 0L || activityId == -1L) {
				null
			} else {
				val activityDao = AppDatabase.database(context).activityDao()
				activityDao.get(activityId)
			}
		} ?: SessionActivity.UNKNOWN
	}

	@Suppress("UNCHECKED_CAST")
	private fun convertToDisplayData(stat: Stat): StatisticsDetailData {
		return when (stat.displayType) {
			StatisticDisplayType.Information -> InformationStatisticsData(
				stat.iconRes,
				stat.nameRes,
				stat.data.toString()
			)
			StatisticDisplayType.Map -> MapStatisticsData(stat.data as List<BaseLocation>)
			StatisticDisplayType.LineChart -> LineChartStatisticsData(
				stat.iconRes,
				stat.nameRes,
				stat.data as List<Entry>
			)
		}
	}

	class ViewModel : androidx.lifecycle.ViewModel() {
		private var initialized = false
		private val sessionMutable: MutableLiveData<TrackerSession?> = MutableLiveData()

		val sunSetRise = SunSetRise()

		val session: LiveData<TrackerSession?> get() = sessionMutable

		@WorkerThread
		fun initialize(context: Context, sessionId: Long) {
			if (initialized) return
			initialized = true

			val database = AppDatabase.database(context)
			sessionMutable.postValue(database.sessionDao().get(sessionId))
			sunSetRise.initialize(context)
		}
	}

	companion object {
		const val ARG_SESSION_ID: String = "session_id"
	}
}

// region Compose helpers
@Composable
private fun <T> LiveData<T>.observeAsStateCompat(): androidx.compose.runtime.State<T?> {
	val state = androidx.compose.runtime.remember { mutableStateOf<T?>(null) }
	androidx.compose.runtime.DisposableEffect(this) {
		val observer = androidx.lifecycle.Observer<T> { value -> state.value = value }
		observeForever(observer)
		onDispose { removeObserver(observer) }
	}
	return state
}

private fun launchMain(block: suspend () -> Unit) {
	kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
		block()
	}
}

private fun launchMainIO(block: suspend () -> Unit) {
	kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
		block()
	}
}
// endregion

