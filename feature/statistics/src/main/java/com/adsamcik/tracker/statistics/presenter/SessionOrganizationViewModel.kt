package com.adsamcik.tracker.statistics.presenter

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

internal data class SessionEditorTarget(
	val session: OrganizedSession?, val epoch: Long, val interval: OrganizationInterval, val zoneId: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionOrganizationViewModel @Inject constructor(
	private val repository: PagedSessionOrganizationRepository,
	private val clock: Clock,
	private val lifecycle: CollectedDataLifecycleStore,
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
				if (_editor.value?.epoch?.let { it != snapshot.epoch } == true) {
					_editor.value = null
					_problem.value = OrganizationEditProblem.STALE
				}
			}
		}
	}

	fun showDay(day: LocalDate?) { range.value = day }
	fun showMonth(value: YearMonth) { month.value = value }
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

	fun save(intervalChange: OrganizationInterval?, zoneId: String, name: String?, label: Long?) = perform { target ->
		if (target.session == null) repository.create(target.epoch, intervalChange ?: target.interval, zoneId, name, label)
		else repository.edit(target.session, intervalChange, zoneId, name, label)
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
