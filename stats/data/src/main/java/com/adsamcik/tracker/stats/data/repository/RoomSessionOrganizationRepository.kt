package com.adsamcik.tracker.stats.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.adsamcik.tracker.stats.api.repository.PagedSessionOrganizationRepository
import android.content.Context
import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.bootstrapSourceEvidenceForRetention
import com.adsamcik.tracker.shared.base.database.SourceEvidenceRetentionBootstrapResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.stats.api.repository.OrganizationActivityLabel
import com.adsamcik.tracker.stats.api.repository.OrganizationEditProblem
import com.adsamcik.tracker.stats.api.repository.OrganizationInterval
import com.adsamcik.tracker.stats.api.repository.OrganizationMutationResult
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.util.UUID
import javax.inject.Singleton
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

@Singleton
class RoomSessionOrganizationRepository @Inject constructor(
	private val database: AppDatabase,
	@ApplicationContext private val context: Context,
	private val clock: Clock,
	private val lifecycle: CollectedDataLifecycleStore,
	private val operationLease: RetentionAuthorityOperationLease,
	@IoDispatcher private val io: CoroutineDispatcher,
) : PagedSessionOrganizationRepository {
	private val metadataTables = arrayOf("organization_annotation", "logical_tracking_session", "source_service_run",
		"session_segment", "session_manifest_version", "source_evidence_state", "activity", "collected_data_deletion_operation")
	private val dao get() = database.organizationAnnotationDao()

	override fun observe(fromMs: Long?, toMs: Long?, limit: Int): Flow<SessionOrganizationRead> =
		database.invalidationTracker.createFlow(
			*metadataTables,
			emitInitialState = true,
		).combine(lifecycle.snapshots) { _, _ -> read(fromMs, toMs, limit) }.catch { failure ->
			if (failure is CancellationException) throw failure
			if (failure is SQLiteException) emit(SessionOrganizationRead.Unavailable) else throw failure
		}.distinctUntilChanged()

	override suspend fun read(fromMs: Long?, toMs: Long?, limit: Int): SessionOrganizationRead =
		readPage(fromMs, toMs, limit, 0)

	override suspend fun find(id: OrganizationSessionId): SessionOrganizationRead =
		readPage(null, null, 1, 0, id)

	override fun observeSession(id: OrganizationSessionId): Flow<SessionOrganizationRead> =
		database.invalidationTracker.createFlow(*metadataTables, emitInitialState = true)
			.combine(lifecycle.snapshots) { _, _ -> find(id) }.distinctUntilChanged()

	private suspend fun readPage(fromMs: Long?, toMs: Long?, limit: Int, offset: Int, selected: OrganizationSessionId? = null): SessionOrganizationRead = withContext(io) {
		require(limit in 1..100)
		require((fromMs == null && toMs == null) ||
			(fromMs != null && toMs != null && fromMs >= 0L && fromMs < toMs))
		try {
			operationLease.withPermit { permit ->
				val current = lifecycle.snapshot()
				permit.validate()
				if (permit.commitRoomMutation { database.activeRetentionFloorSettlement() } != null)
					return@withPermit SessionOrganizationRead.Unavailable
				if (permit.commitRoomMutation { database.bootstrapSourceEvidenceForRetention(current.epoch,
					current.retainedFromMs, clock.currentTimeMillis()) } !is SourceEvidenceRetentionBootstrapResult.Ready)
					return@withPermit SessionOrganizationRead.Unavailable
				permit.commitRoomMutation { database.withTransaction {
				val state = database.sourceEvidenceStateDao().get()
				if (state == null || state.collectedDataEpoch != current.epoch || state.retainedFromMs != current.retainedFromMs)
					return@withTransaction SessionOrganizationRead.Unavailable
				val epoch = state.collectedDataEpoch
				val rows = dao.visible(fromMs, toMs, limit + 1, offset,
					(selected as? OrganizationSessionId.Annotation)?.localId,
					(selected as? OrganizationSessionId.Recording)?.adapterKey)
				val labels = database.activityDao().getByIds(rows.take(limit).mapNotNull { it.activityLabelId }
					.filter { it >= 0L }.distinct()).associate { it.id to it.name } +
					NativeSessionActivity.entries.associate { it.id to context.getString(it.nameRes) }
				SessionOrganizationRead.Content(rows.take(limit).map { row ->
					val start = row.fromMs?.takeIf { it >= 0L }
					val end = row.toMs?.takeIf { start != null && it >= start }
					val importedSelection = row.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode)
					check(importedSelection == null || row.adapterKey == null)
					check(row.importedActivityLabel == null || importedSelection != null)
					check(row.importedActivityLabel?.let { it.codePointCount(0, it.length) > 200 } != true)
					OrganizedSession(
						id = row.adapterKey?.let { OrganizationSessionId.Recording(it) }
							?: OrganizationSessionId.Annotation(requireNotNull(row.localId)),
						revision = row.revision, collectedDataEpoch = epoch, name = row.name,
						activityLabelId = row.activityLabelId,
						activityLabelName = labels[row.activityLabelId] ?: row.importedActivityLabel,
						displayZoneId = (row.displayZoneId ?: ZoneId.systemDefault().id).also { ZoneId.of(it) },
						fromMs = start, toMs = end, isActive = row.isActive,
						timeUncertain = row.timeUncertain || start == null || (!row.isActive && end == null),
						originalSegmentId = row.originalSegmentId,
						importedSourceSelection = importedSelection,
					)
				}, rows.size > limit, epoch)
				} }
			}
		} catch (failure: Exception) {
			if (failure is CancellationException) throw failure
			SessionOrganizationRead.Unavailable
		}
	}

	override fun pagingSource(fromMs: Long?, toMs: Long?): PagingSource<Int, OrganizedSession> =
		object : PagingSource<Int, OrganizedSession>() {
			private val observer = object : InvalidationTracker.Observer(metadataTables) {
				override fun onInvalidated(tables: Set<String>) { invalidate() }
			}
			init {
				database.invalidationTracker.addWeakObserver(observer)
				registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
			}
			override suspend fun load(params: LoadParams<Int>): LoadResult<Int, OrganizedSession> {
				val requested = params.key ?: 0
				val maxSize = params.loadSize.coerceAtMost(100)
				val offset = if (params is LoadParams.Prepend) (requested - maxSize).coerceAtLeast(0) else requested
				val size = if (params is LoadParams.Prepend) (requested - offset).coerceAtLeast(1) else maxSize
				return when (val result = readPage(fromMs, toMs, size, offset)) {
					SessionOrganizationRead.Unavailable -> LoadResult.Error(IllegalStateException("Organization metadata unavailable"))
					is SessionOrganizationRead.Content -> if (invalid) LoadResult.Invalid() else LoadResult.Page(
						data = result.sessions,
						prevKey = if (offset == 0) null else offset,
						nextKey = if (result.hasMore) Math.addExact(offset, result.sessions.size) else null,
					)
				}
			}
			override fun getRefreshKey(state: PagingState<Int, OrganizedSession>): Int? =
				state.anchorPosition?.let { anchor -> state.closestPageToPosition(anchor)?.let { page ->
					page.prevKey ?: page.nextKey?.minus(page.data.size) ?: 0
				} }
		}

	override suspend fun activityLabels(): List<OrganizationActivityLabel> = withContext(io) {
		database.activityDao().getAllUser().map { OrganizationActivityLabel(it.id, it.name) } +
			NativeSessionActivity.entries.map { OrganizationActivityLabel(it.id, context.getString(it.nameRes)) }
	}

	override suspend fun create(expectedEpoch: Long, interval: OrganizationInterval,
		displayZoneId: String, name: String?, activityLabelId: Long?): OrganizationMutationResult = mutate {
		if (!sameEpoch(expectedEpoch)) return@mutate rejected(OrganizationEditProblem.STALE)
		validate(interval, displayZoneId, name, activityLabelId)?.let { return@mutate rejected(it) }
		val now = clock.currentTimeMillis()
		dao.insert(OrganizationAnnotationEntity(UUID.randomUUID().toString(), 1L, now, now, null,
			interval.fromMs, interval.toMs, displayZoneId, name.normalized(), activityLabelId, false))
		OrganizationMutationResult.Saved
	}

	override suspend fun edit(selection: OrganizedSession, intervalChange: OrganizationInterval?,
		displayZoneId: String, name: String?, activityLabelId: Long?,
		clearImportedActivityLabel: Boolean): OrganizationMutationResult = mutate {
		if (!sameEpoch(selection.collectedDataEpoch)) return@mutate rejected(OrganizationEditProblem.STALE)
		val existing = annotation(selection.id)
		if (selection.id is OrganizationSessionId.Annotation && existing?.recordingAdapterKey != null)
			return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
		if (existing?.hidden == true || (existing?.revision ?: 0L) != selection.revision)
			return@mutate rejected(OrganizationEditProblem.STALE)
		if (selection.id is OrganizationSessionId.Annotation && existing == null)
			return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
		val live = activeRecording(selection.id)
		if (live == null && existing == null && selection.id is OrganizationSessionId.Recording)
			return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
		if (live == true && (intervalChange != null || displayZoneId != (existing?.displayZoneId ?: dao.recordingZone((selection.id as OrganizationSessionId.Recording).adapterKey) ?: ZoneId.systemDefault().id)))
			return@mutate rejected(OrganizationEditProblem.END_SESSION_FIRST)
		validate(intervalChange, displayZoneId, name, activityLabelId)?.let { return@mutate rejected(it) }
		val now = clock.currentTimeMillis()
		val row = OrganizationAnnotationEntity(existing?.localId ?: UUID.randomUUID().toString(),
			Math.addExact(selection.revision, 1L), existing?.createdAtMs ?: now, now,
			(selection.id as? OrganizationSessionId.Recording)?.adapterKey,
			intervalChange?.fromMs ?: existing?.startTimeMs,
			intervalChange?.toMs ?: existing?.endTimeMs, displayZoneId, name.normalized(), activityLabelId, false,
			importedSourceSelection = existing?.importedSourceSelection,
			importedActivityLabel = existing?.importedActivityLabel.takeIf {
				!clearImportedActivityLabel && activityLabelId == existing?.activityLabelId })
		if (existing == null) dao.insert(row) else check(dao.update(row) == 1)
		OrganizationMutationResult.Saved
	}

	override suspend fun remove(selection: OrganizedSession): OrganizationMutationResult = mutate {
		if (!sameEpoch(selection.collectedDataEpoch)) return@mutate rejected(OrganizationEditProblem.STALE)
		val existing = annotation(selection.id)
		if (selection.id is OrganizationSessionId.Annotation && existing?.recordingAdapterKey != null)
			return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
		if (existing?.hidden == true || (existing?.revision ?: 0L) != selection.revision)
			return@mutate rejected(OrganizationEditProblem.STALE)
		if (selection.id is OrganizationSessionId.Annotation) {
			if (existing == null) return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
			dao.delete(existing.localId)
		} else {
			val active = activeRecording(selection.id)
			if (active == null) {
				if (existing == null) return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
				dao.delete(existing.localId)
				return@mutate OrganizationMutationResult.Saved
			}
			if (active) return@mutate rejected(OrganizationEditProblem.END_SESSION_FIRST)
			val now = clock.currentTimeMillis()
			val hidden = OrganizationAnnotationEntity(existing?.localId ?: UUID.randomUUID().toString(),
				Math.addExact(selection.revision, 1L), existing?.createdAtMs ?: now, now,
				(selection.id as OrganizationSessionId.Recording).adapterKey, null, null, null, null, null, true)
			if (existing == null) dao.insert(hidden) else check(dao.update(hidden) == 1)
		}
		OrganizationMutationResult.Saved
	}

	private suspend fun annotation(id: OrganizationSessionId) = when (id) {
		is OrganizationSessionId.Annotation -> dao.byId(id.localId)
		is OrganizationSessionId.Recording -> dao.byAdapter(id.adapterKey)
	}

	/** Rechecked inside the same transaction as the edit, not from a stale screen snapshot. */
	private suspend fun activeRecording(id: OrganizationSessionId): Boolean? {
		if (id !is OrganizationSessionId.Recording) return false
		return when {
			id.adapterKey.startsWith("logical:") -> database.sourceSessionDao()
				.session(id.adapterKey.removePrefix("logical:"))?.state?.let {
					it !in setOf("FINALIZED", "CLOSED", "FAILED")
				}
			id.adapterKey.startsWith("segment:") -> id.adapterKey.removePrefix("segment:")
				.toLongOrNull()?.let { database.sessionSegmentDao().getById(it) }
				?.takeIf { it.logicalTrackingId == null && it.serviceRunId == null }?.let { false }
			else -> null
		}
	}

	private suspend fun validate(interval: OrganizationInterval?, zone: String,
		name: String?, label: Long?): OrganizationEditProblem? {
		if (name.normalized()?.let { it.codePointCount(0, it.length) > 200 } == true)
			return OrganizationEditProblem.NAME_TOO_LONG
		if (zone !in ZoneId.getAvailableZoneIds()) return OrganizationEditProblem.INVALID_ZONE
		try { ZoneId.of(zone) } catch (_: IllegalArgumentException) { return OrganizationEditProblem.INVALID_ZONE }
		catch (_: java.time.DateTimeException) { return OrganizationEditProblem.INVALID_ZONE }
		if (label != null && NativeSessionActivity.entries.none { it.id == label } &&
			database.activityDao().get(label) == null) return OrganizationEditProblem.MISSING_ACTIVITY_LABEL
		if (interval != null) {
			if (interval.fromMs < 0L || interval.toMs <= interval.fromMs)
				return OrganizationEditProblem.INVALID_INTERVAL
			if (interval.toMs > clock.currentTimeMillis()) return OrganizationEditProblem.FUTURE_END
		}
		return null
	}

	private suspend fun sameEpoch(epoch: Long) = database.sourceEvidenceStateDao().get()?.collectedDataEpoch == epoch
	private fun String?.normalized() = this?.trim()?.takeIf { it.isNotEmpty() }
	private fun rejected(problem: OrganizationEditProblem) = OrganizationMutationResult.Rejected(problem)
	private suspend fun mutate(block: suspend () -> OrganizationMutationResult): OrganizationMutationResult = withContext(io) {
		try {
			operationLease.withPermit { permit ->
				val current = lifecycle.snapshot()
				permit.validate()
				if (permit.commitRoomMutation { database.activeRetentionFloorSettlement() } != null)
					return@withPermit rejected(OrganizationEditProblem.STORAGE_UNAVAILABLE)
				if (permit.commitRoomMutation { database.bootstrapSourceEvidenceForRetention(current.epoch,
					current.retainedFromMs, clock.currentTimeMillis()) } !is SourceEvidenceRetentionBootstrapResult.Ready)
					return@withPermit rejected(OrganizationEditProblem.STALE)
				permit.commitRoomMutation { database.withTransaction {
					val state = database.sourceEvidenceStateDao().get()
					if (state == null || state.collectedDataEpoch != current.epoch || state.retainedFromMs != current.retainedFromMs)
						return@withTransaction rejected(OrganizationEditProblem.STALE)
					block()
				} }
			}
		} catch (failure: Exception) {
			if (failure is CancellationException) throw failure
			rejected(OrganizationEditProblem.STORAGE_UNAVAILABLE)
		}
	}
}
