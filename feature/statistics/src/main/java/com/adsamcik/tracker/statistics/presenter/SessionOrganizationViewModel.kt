package com.adsamcik.tracker.statistics.presenter

import android.net.Uri
import androidx.paging.PagingData
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExporter
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportRequest
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportResult
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportFailure
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PagedTrackingHistoryRepository
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.adsamcik.tracker.stats.api.repository.PagedSessionOrganizationRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.OrganizationActivityLabel
import com.adsamcik.tracker.stats.api.repository.OrganizationEditProblem
import com.adsamcik.tracker.stats.api.repository.OrganizationInterval
import com.adsamcik.tracker.stats.api.repository.OrganizationMutationResult
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal data class SessionEditorTarget(
	val session: OrganizedSession?, val epoch: Long, val interval: OrganizationInterval, val zoneId: String,
)

internal data class SessionExportChoices(
	val session: OrganizedSession,
	val availableSources: Set<HistorySource>,
	val unavailableSources: Set<HistorySource>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionOrganizationViewModel @Inject constructor(
	private val repository: PagedSessionOrganizationRepository,
	private val clock: Clock,
	private val lifecycle: CollectedDataLifecycleStore,
	private val history: PagedTrackingHistoryRepository,
	private val exporter: SessionBundleExporter,
) : ViewModel() {
	private var currentEpoch: Long? = null
	private val range = MutableStateFlow<LocalDate?>(null)
	private val month = MutableStateFlow(YearMonth.now())
	private val _read = MutableStateFlow<SessionOrganizationRead?>(null)
	val read = _read.asStateFlow()
	private val _monthRead = MutableStateFlow<SessionOrganizationRead?>(null)
	val monthRead = _monthRead.asStateFlow()
	private val _editor = MutableStateFlow<SessionEditorTarget?>(null)
	internal val editor = _editor.asStateFlow()
	private val _labels = MutableStateFlow<List<OrganizationActivityLabel>>(emptyList())
	val labels = _labels.asStateFlow()
	private val _problem = MutableStateFlow<OrganizationEditProblem?>(null)
	val problem = _problem.asStateFlow()
	private val _busy = MutableStateFlow(false)
	val busy = _busy.asStateFlow()
	val selectedDay = range.asStateFlow()
	private val _contents = MutableStateFlow<OrganizedSession?>(null)
	val contents = _contents.asStateFlow()
	private val _contentsUnavailable = MutableStateFlow(false)
	val contentsUnavailable = _contentsUnavailable.asStateFlow()
	private var contentsJob: Job? = null
	private val _exportChoices = MutableStateFlow<SessionExportChoices?>(null)
	internal val exportChoices = _exportChoices.asStateFlow()
	private val _exportBusy = MutableStateFlow(false)
	val exportBusy = _exportBusy.asStateFlow()
	private val _exportFailure = MutableStateFlow<SessionBundleExportFailure?>(null)
	val exportFailure = _exportFailure.asStateFlow()
	private var exportJob: Job? = null
	private var exportGeneration = 0L
	private var pendingExport: SessionBundleExportRequest? = null
	private val _exportSaved = MutableSharedFlow<SessionBundleExportResult.Success>(extraBufferCapacity = 1)
	val exportSaved = _exportSaved.asSharedFlow()

	val pagedContents = _contents.flatMapLatest { session ->
		val selection = session?.contentsRange()
		if (selection == null) flowOf(PagingData.empty<HistoryPagingItem>())
		else Pager(PagingConfig(pageSize = 50, initialLoadSize = 100, enablePlaceholders = false),
			pagingSourceFactory = { history.pagingSource(selection) }).flow
	}.cachedIn(viewModelScope)

	val pagedSessions = combine(range, lifecycle.snapshots.map { it.epoch }.distinctUntilChanged()) { day, epoch -> day to epoch }
		.flatMapLatest { (day, _) ->
			val zone = ZoneId.systemDefault()
			Pager(PagingConfig(pageSize = 50, initialLoadSize = 100, enablePlaceholders = false),
				pagingSourceFactory = { repository.pagingSource(day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
					day?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()) }).flow
		}.cachedIn(viewModelScope)

	init {
		viewModelScope.launch {
			range.collectLatest { day ->
				val zone = ZoneId.systemDefault()
				repository.observe(day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
					day?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()).combine(lifecycle.snapshots) { result, snapshot ->
						if (result is SessionOrganizationRead.Content && result.collectedDataEpoch != snapshot.epoch) null else result
					}.collect { _read.value = it }
			}
		}
		viewModelScope.launch {
			month.collectLatest { value ->
				val zone = ZoneId.systemDefault()
				repository.observe(value.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
					value.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli())
					.combine(lifecycle.snapshots) { result, snapshot ->
						if (result is SessionOrganizationRead.Content && result.collectedDataEpoch != snapshot.epoch) null else result
					}.collect { _monthRead.value = it }
			}
		}
		viewModelScope.launch {
			lifecycle.snapshots.collect { snapshot ->
				val changedEpoch = currentEpoch != snapshot.epoch
				currentEpoch = snapshot.epoch
				if ((_read.value as? SessionOrganizationRead.Content)?.collectedDataEpoch?.let { it != snapshot.epoch } == true) _read.value = null
				if ((_monthRead.value as? SessionOrganizationRead.Content)?.collectedDataEpoch?.let { it != snapshot.epoch } == true) _monthRead.value = null
				if (changedEpoch) _labels.value = emptyList()
				if (_contents.value?.collectedDataEpoch?.let { it != snapshot.epoch } == true) closeContents()
				if (_editor.value?.epoch?.let { it != snapshot.epoch } == true) {
					_editor.value = null
					_problem.value = OrganizationEditProblem.STALE
				}
			}
		}
	}

	fun showDay(day: LocalDate?) { range.value = day }
	fun showMonth(value: YearMonth) { month.value = value }
	fun showContents(session: OrganizedSession) {
		if (currentEpoch?.let { it != session.collectedDataEpoch } == true) return
		contentsJob?.cancel()
		_contents.value = session
		_contentsUnavailable.value = false
		_exportFailure.value = null
		contentsJob = viewModelScope.launch {
			try { repository.observeSession(session.id).collect { read ->
				if (_contents.value?.id != session.id) return@collect
				when (read) {
					SessionOrganizationRead.Unavailable -> _contentsUnavailable.value = true
					is SessionOrganizationRead.Content -> {
						val fresh = read.sessions.singleOrNull()
						if (fresh == null || currentEpoch?.let { fresh.collectedDataEpoch != it } == true) closeContents()
						else {
							_contents.value = fresh; _contentsUnavailable.value = false
							if (_exportChoices.value?.session?.let { it != fresh } == true) {
								_exportChoices.value = null; _exportFailure.value = SessionBundleExportFailure.STALE
							}
						}
					}
				}
			} } catch (failure: Exception) {
				if (failure is CancellationException) throw failure
				if (_contents.value?.id == session.id) _contentsUnavailable.value = true
			}
		}
	}
	fun retryContents() { _contents.value?.let(::showContents) }
	fun closeContents() {
		++exportGeneration
		contentsJob?.cancel(); contentsJob = null
		_contents.value = null; _contentsUnavailable.value = false
		_exportChoices.value = null; pendingExport = null; _exportFailure.value = null
		exportJob?.cancel(); exportJob = null; _exportBusy.value = false
	}

	/** Probe existing source-qualified pages only when the user opens Export. */
	fun chooseExportSources() {
		val session = _contents.value ?: return
		val selection = session.contentsRange() ?: return
		if (_exportBusy.value) return
		val generation = ++exportGeneration
		_exportBusy.value = true; _exportFailure.value = null
		exportJob = viewModelScope.launch {
			try {
				val available = linkedSetOf<HistorySource>()
				val unavailable = linkedSetOf<HistorySource>()
				for (source in HistorySource.entries) {
					val sourceRange = selection.copy(sources = setOf(source))
					var continuation: HistoryContinuation? = null
					do {
						when (val page = history.readRange(HistoryRangeRequest(sourceRange, after = continuation))) {
							is HistoryRangeRead.Unavailable -> { unavailable += source; break }
							is HistoryRangeRead.Page -> {
								if (page.snapshot.collectedDataEpoch != session.collectedDataEpoch) {
									if (generation == exportGeneration) _exportFailure.value = SessionBundleExportFailure.STALE
									return@launch
								}
								if (page.rows.isNotEmpty()) { available += source; break }
								if (page.problems.isNotEmpty()) { unavailable += source; break }
								continuation = page.next
							}
						}
					} while (continuation != null)
				}
				if (generation == exportGeneration && _contents.value == session)
					_exportChoices.value = SessionExportChoices(session, available, unavailable)
			} catch (failure: Exception) {
				if (failure is CancellationException) throw failure
				if (generation == exportGeneration) _exportFailure.value = SessionBundleExportFailure.UNAVAILABLE
			} finally { if (generation == exportGeneration) _exportBusy.value = false }
		}
	}
	fun dismissExportChoices() { _exportChoices.value = null }
	internal fun prepareExport(sources: Set<HistorySource>): Boolean {
		val choices = _exportChoices.value ?: return false
		if (sources.isEmpty() || !choices.availableSources.containsAll(sources) || _contents.value != choices.session) return false
		pendingExport = SessionBundleExportRequest(choices.session.id, choices.session.revision,
			choices.session.collectedDataEpoch, sources.toSet())
		_exportChoices.value = null
		return true
	}
	fun exportTo(destination: Uri?) {
		val request = pendingExport ?: return
		pendingExport = null
		if (destination == null || _exportBusy.value) return
		val generation = ++exportGeneration
		_exportBusy.value = true; _exportFailure.value = null
		exportJob = viewModelScope.launch {
			try {
				val result = exporter.export(destination, request)
				if (generation != exportGeneration) return@launch
				when (result) {
					is SessionBundleExportResult.Success -> _exportSaved.emit(result)
					is SessionBundleExportResult.Failure -> _exportFailure.value = result.reason
				}
			} catch (failure: Exception) {
				if (failure is CancellationException) throw failure
				if (generation == exportGeneration) _exportFailure.value = SessionBundleExportFailure.STORAGE
			} finally { if (generation == exportGeneration) _exportBusy.value = false }
		}
	}
	fun add(day: LocalDate?) {
		val epoch = (_read.value as? SessionOrganizationRead.Content)?.collectedDataEpoch ?: return
		val zone = ZoneId.systemDefault()
		val now = clock.currentTimeMillis()
		val start = day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: (now - 3_600_000L).coerceAtLeast(0L)
		val end = day?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.coerceAtMost(now) ?: now
		open(SessionEditorTarget(null, epoch, OrganizationInterval(start, end), zone.id))
	}
	fun edit(session: OrganizedSession) {
		val now = clock.currentTimeMillis()
		open(SessionEditorTarget(session, session.collectedDataEpoch,
			OrganizationInterval(session.fromMs ?: (now - 3_600_000L).coerceAtLeast(0L), session.toMs ?: now),
			session.displayZoneId))
	}
	private fun open(target: SessionEditorTarget) {
		if (_busy.value) return
		if (currentEpoch?.let { it != target.epoch } == true) {
			_editor.value = null; _problem.value = OrganizationEditProblem.STALE
			return
		}
		_problem.value = null
		_editor.value = target
		viewModelScope.launch {
			try {
				val labels = repository.activityLabels()
				if (_editor.value == target && currentEpoch == target.epoch) _labels.value = labels
			}
			catch (failure: Exception) {
				if (failure is CancellationException) throw failure
				if (_editor.value == target && currentEpoch == target.epoch) _problem.value = OrganizationEditProblem.STORAGE_UNAVAILABLE
			}
		}
	}
	fun dismiss() { if (!_busy.value) { _editor.value = null; _problem.value = null } }
	/** Explicit reload discards the stale draft only after the user chooses to reopen. */
	fun reload() {
		val old = _editor.value ?: return
		val id = old.session?.id ?: return
		if (_busy.value) return
		_busy.value = true
		viewModelScope.launch {
			try {
				val result = repository.find(id)
				if (_editor.value != old) return@launch
				when (result) {
					SessionOrganizationRead.Unavailable -> _problem.value = OrganizationEditProblem.STORAGE_UNAVAILABLE
					is SessionOrganizationRead.Content -> {
						val fresh = result.sessions.singleOrNull()
						if (fresh == null) { _editor.value = null; _problem.value = OrganizationEditProblem.NOT_FOUND }
						else if (fresh.collectedDataEpoch == old.epoch) {
							_busy.value = false
							edit(fresh)
						}
					}
				}
			} catch (failure: Exception) {
				if (failure is CancellationException) throw failure
				_problem.value = OrganizationEditProblem.STORAGE_UNAVAILABLE
			} finally { _busy.value = false }
		}
	}

	fun save(intervalChange: OrganizationInterval?, zoneId: String, name: String?, label: Long?,
		clearImportedActivityLabel: Boolean = false) = perform { target ->
		if (target.session == null) repository.create(target.epoch, intervalChange ?: target.interval, zoneId, name, label)
		else repository.edit(target.session, intervalChange, zoneId, name, label, clearImportedActivityLabel)
	}
	fun remove() = perform { target ->
		target.session?.let { repository.remove(it) }
			?: OrganizationMutationResult.Rejected(OrganizationEditProblem.NOT_FOUND)
	}
	private fun perform(action: suspend (SessionEditorTarget) -> OrganizationMutationResult) {
		val target = _editor.value ?: return
		if (_busy.value) return
		_busy.value = true
		viewModelScope.launch {
			try {
				when (val result = action(target)) {
					OrganizationMutationResult.Saved -> { _editor.value = null; _problem.value = null }
					is OrganizationMutationResult.Rejected -> _problem.value = result.problem
				}
			} finally { _busy.value = false }
		}
	}
}

internal fun OrganizedSession.contentsRange(): HistoryRange? {
	val from = fromMs ?: return null
	val to = toMs ?: if (isActive) Long.MAX_VALUE else return null
	if (to < from || from == Long.MAX_VALUE) return null
	val queryEnd = if (to == from) to + 1L else to
	return HistoryRange(from, queryEnd, displayZoneId, mode = HistoryBrowseMode.OBSERVATIONS,
		organizationSessionId = id, expectedCollectedDataEpoch = collectedDataEpoch)
}
