package com.adsamcik.tracker.stats.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
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
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.base.database.dao.OrganizationSessionRow
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

/**
 * Stores optional session annotations over retained observations, with epoch/revision guards and bounded Room
 * paging.
 */
@Singleton
// One annotation owner keeps paging and epoch-guarded edits on the existing lifecycle lease.
@Suppress("TooManyFunctions")
class RoomSessionOrganizationRepository @Inject constructor(
	private val database: AppDatabase,
	@ApplicationContext private val context: Context,
	private val clock: Clock,
	private val lifecycle: CollectedDataLifecycleStore,
	private val operationLease: RetentionAuthorityOperationLease,
	@IoDispatcher private val io: CoroutineDispatcher,
) : PagedSessionOrganizationRepository {
	private val metadataTables = arrayOf("organization_annotation", "logical_tracking_session", "source_service_run",
		"session_segment", "session_manifest_version", "source_evidence_state", "activity",
            "collected_data_deletion_operation")
	private val dao get() = database.organizationAnnotationDao()

	override fun observe(fromMs: Long?, toMs: Long?, limit: Int): Flow<SessionOrganizationRead> =
		database.invalidationTracker.createFlow(
			*metadataTables,
			emitInitialState = true,
		).combine(lifecycle.snapshots) { _, _ -> read(fromMs, toMs, limit) }.catch { failure ->
			if (failure is CancellationException) {
				throw failure
			}
			if (failure is SQLiteException) {
				emit(SessionOrganizationRead.Unavailable)
			} else {
				throw failure
			}
		}.distinctUntilChanged()

	override suspend fun read(fromMs: Long?, toMs: Long?, limit: Int): SessionOrganizationRead =
		readPage(fromMs, toMs, limit, 0)

	override suspend fun find(id: OrganizationSessionId): SessionOrganizationRead =
		readPage(null, null, 1, 0, id)

	override fun observeSession(id: OrganizationSessionId): Flow<SessionOrganizationRead> =
		database.invalidationTracker.createFlow(*metadataTables, emitInitialState = true)
			.combine(lifecycle.snapshots) { _, _ -> find(id) }.distinctUntilChanged()

	private suspend fun readPage(fromMs: Long?, toMs: Long?, limit: Int, offset: Int,
        selected: OrganizationSessionId? = null): SessionOrganizationRead = withContext(io) {
		require(limit in 1..100)
		require((fromMs == null && toMs == null) ||
			(fromMs != null && toMs != null && fromMs >= 0L && fromMs < toMs))
		try {
			operationLease.withPermit { permit ->
				val current = lifecycle.snapshot()
				permit.validate()
				if (permit.commitRoomMutation { database.activeRetentionFloorSettlement() } != null)
					{
					return@withPermit SessionOrganizationRead.Unavailable
				}
				if (permit.commitRoomMutation { database.bootstrapSourceEvidenceForRetention(current.epoch,
					current.retainedFromMs,
                        clock.currentTimeMillis()) } !is SourceEvidenceRetentionBootstrapResult.Ready)
					{
					return@withPermit SessionOrganizationRead.Unavailable
				}
				permit.commitRoomMutation { database.withTransaction {
				readPageRows(fromMs, toMs, limit, offset, selected, current)
				} }
			}
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// Stored selection/zone validation and Room failures share this read's Unavailable result.
			if (failure is CancellationException) {
				throw failure
			}
			SessionOrganizationRead.Unavailable
		}
	}

	private suspend fun readPageRows(
		fromMs: Long?,
		toMs: Long?,
		limit: Int,
		offset: Int,
		selected: OrganizationSessionId?,
		current: CollectedDataLifecycleSnapshot,
	): SessionOrganizationRead {
		val state = database.sourceEvidenceStateDao().get()
		if (state == null || state.collectedDataEpoch != current.epoch ||
                    state.retainedFromMs != current.retainedFromMs)
			{
			return SessionOrganizationRead.Unavailable
		}
		val epoch = state.collectedDataEpoch
		val rows = dao.visible(fromMs, toMs, limit + 1, offset,
			(selected as? OrganizationSessionId.Annotation)?.localId,
			(selected as? OrganizationSessionId.Recording)?.adapterKey)
		val labels = database.activityDao().getByIds(rows.take(limit).mapNotNull { it.activityLabelId }
			.filter { it >= 0L }.distinct()).associate { it.id to it.name } +
			NativeSessionActivity.entries.associate { it.id to context.getString(it.nameRes) }
		return SessionOrganizationRead.Content(rows.take(limit).map { it.toOrganizedSession(epoch, labels) },
			rows.size > limit, epoch)
	}

	// One saved row projects its optional interval, imported selection and display labels without changing authority.
	@Suppress("CyclomaticComplexMethod")
	private fun OrganizationSessionRow.toOrganizedSession(epoch: Long, labels: Map<Long, String>): OrganizedSession {
		val start = fromMs?.takeIf { it >= 0L }
		val end = toMs?.takeIf { start != null && it >= start }
		val importedSelection = importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode)
		check(importedSelection == null || adapterKey == null)
		check(importedActivityLabel == null || importedSelection != null)
		check(importedActivityLabel?.let {
			it.codePointCount(0, it.length) > MAX_IMPORTED_ACTIVITY_LABEL_CODE_POINTS
		} != true)
		return OrganizedSession(
			id = adapterKey?.let { OrganizationSessionId.Recording(it) }
				?: OrganizationSessionId.Annotation(requireNotNull(localId)),
			revision = revision, collectedDataEpoch = epoch, name = name,
			activityLabelId = activityLabelId,
			activityLabelName = labels[activityLabelId] ?: importedActivityLabel,
			displayZoneId = (displayZoneId ?: ZoneId.systemDefault().id).also { ZoneId.of(it) },
			fromMs = start, toMs = end, isActive = isActive,
			timeUncertain = timeUncertain || start == null || (!isActive && end == null),
			originalSegmentId = originalSegmentId,
			importedSourceSelection = importedSelection,
		)
	}

	override fun pagingSource(fromMs: Long?, toMs: Long?): PagingSource<Int, OrganizedSession> =
		object : PagingSource<Int, OrganizedSession>() {
			init {
				registerPagingInvalidation(database.invalidationTracker, this, metadataTables)
			}
			override suspend fun load(params: LoadParams<Int>): LoadResult<Int, OrganizedSession> {
				val requested = params.key ?: 0
				val maxSize = params.loadSize.coerceAtMost(100)
				val offset = if (params is LoadParams.Prepend) {
					(requested - maxSize).coerceAtLeast(0)
				} else {
					requested
				}
				val size = if (params is LoadParams.Prepend) {
					(requested - offset).coerceAtLeast(1)
				} else {
					maxSize
				}
				return when (val result = readPage(fromMs, toMs, size, offset)) {
					SessionOrganizationRead.Unavailable ->
                        LoadResult.Error(IllegalStateException("Organization metadata unavailable"))
					is SessionOrganizationRead.Content -> if (invalid) {
						LoadResult.Invalid()
					} else {
						LoadResult.Page(
						data = result.sessions,
						prevKey = if (offset == 0) {
							null
						} else {
							offset
						},
						nextKey = if (result.hasMore) {
							Math.addExact(offset, result.sessions.size)
						} else {
							null
						},
					)
					}
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
		if (!sameEpoch(expectedEpoch)) {
			return@mutate rejected(OrganizationEditProblem.STALE)
		}
		validate(interval, displayZoneId, name, activityLabelId)?.let { return@mutate rejected(it) }
		val now = clock.currentTimeMillis()
		dao.insert(OrganizationAnnotationEntity(UUID.randomUUID().toString(), 1L, now, now, null,
			interval.fromMs, interval.toMs, displayZoneId, name.normalized(), activityLabelId, false))
		OrganizationMutationResult.Saved
	}

	override suspend fun edit(selection: OrganizedSession, intervalChange: OrganizationInterval?,
		displayZoneId: String, name: String?, activityLabelId: Long?,
		clearImportedActivityLabel: Boolean): OrganizationMutationResult = mutate {
		if (!sameEpoch(selection.collectedDataEpoch)) {
			return@mutate rejected(OrganizationEditProblem.STALE)
		}
		val existing = annotation(selection.id)
		selectionVersionProblem(selection, existing)?.let { return@mutate rejected(it) }
		if (selection.id is OrganizationSessionId.Annotation && existing == null)
			{
			return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
		}
		val live = activeRecording(selection.id)
		recordingEditProblem(selection, existing, live, intervalChange, displayZoneId)
			?.let { return@mutate rejected(it) }
		validate(intervalChange, displayZoneId, name, activityLabelId)?.let { return@mutate rejected(it) }
		val now = clock.currentTimeMillis()
		val row = editedAnnotation(selection, existing, intervalChange, displayZoneId, name, activityLabelId,
			clearImportedActivityLabel, now)
		if (existing == null) {
			dao.insert(row)
		} else {
			check(dao.update(row) == 1)
		}
		OrganizationMutationResult.Saved
	}

	override suspend fun remove(selection: OrganizedSession): OrganizationMutationResult = mutate {
		if (!sameEpoch(selection.collectedDataEpoch)) {
			return@mutate rejected(OrganizationEditProblem.STALE)
		}
		val existing = annotation(selection.id)
		selectionVersionProblem(selection, existing)?.let { return@mutate rejected(it) }
		if (selection.id is OrganizationSessionId.Annotation) {
			if (existing == null) {
				return@mutate rejected(OrganizationEditProblem.NOT_FOUND)
			}
			dao.delete(existing.localId)
		} else {
			return@mutate removeRecording(selection, existing)
		}
		OrganizationMutationResult.Saved
	}

	private suspend fun removeRecording(
		selection: OrganizedSession,
		existing: OrganizationAnnotationEntity?,
	): OrganizationMutationResult {
		val active = activeRecording(selection.id)
		if (active == null) {
			if (existing == null) {
				return rejected(OrganizationEditProblem.NOT_FOUND)
			}
			dao.delete(existing.localId)
			return OrganizationMutationResult.Saved
		}
		if (active) {
			return rejected(OrganizationEditProblem.END_SESSION_FIRST)
		}
		val now = clock.currentTimeMillis()
		val hidden = OrganizationAnnotationEntity(existing?.localId ?: UUID.randomUUID().toString(),
			Math.addExact(selection.revision, 1L), existing?.createdAtMs ?: now, now,
			(selection.id as OrganizationSessionId.Recording).adapterKey, null, null, null, null, null, true)
		if (existing == null) {
			dao.insert(hidden)
		} else {
			check(dao.update(hidden) == 1)
		}
		return OrganizationMutationResult.Saved
	}

	// Keep the fixed saved-field projection and its eight original immutable inputs together.
	// Pass the already sampled time directly, without a carrier object or another clock read.
	@Suppress("CyclomaticComplexMethod", "LongParameterList")
	private fun editedAnnotation(
		selection: OrganizedSession,
		existing: OrganizationAnnotationEntity?,
		intervalChange: OrganizationInterval?,
		displayZoneId: String,
		name: String?,
		activityLabelId: Long?,
		clearImportedActivityLabel: Boolean,
		now: Long,
	): OrganizationAnnotationEntity {
		return OrganizationAnnotationEntity(existing?.localId ?: UUID.randomUUID().toString(),
			Math.addExact(selection.revision, 1L), existing?.createdAtMs ?: now, now,
			(selection.id as? OrganizationSessionId.Recording)?.adapterKey,
			intervalChange?.fromMs ?: existing?.startTimeMs,
			intervalChange?.toMs ?: existing?.endTimeMs, displayZoneId, name.normalized(), activityLabelId, false,
			importedSourceSelection = existing?.importedSourceSelection,
			importedActivityLabel = existing?.importedActivityLabel.takeIf {
				!clearImportedActivityLabel && activityLabelId == existing?.activityLabelId })
	}

	private suspend fun recordingEditProblem(
		selection: OrganizedSession,
		existing: OrganizationAnnotationEntity?,
		live: Boolean?,
		intervalChange: OrganizationInterval?,
		displayZoneId: String,
	): OrganizationEditProblem? {
		if (live == null && existing == null && selection.id is OrganizationSessionId.Recording)
			{
			return OrganizationEditProblem.NOT_FOUND
		}
		if (live == true && (intervalChange != null || displayZoneId != (existing?.displayZoneId ?:
            dao.recordingZone((selection.id as OrganizationSessionId.Recording).adapterKey) ?:
                ZoneId.systemDefault().id)))
			{
			return OrganizationEditProblem.END_SESSION_FIRST
		}
		return null
	}

	private fun selectionVersionProblem(
		selection: OrganizedSession,
		existing: OrganizationAnnotationEntity?,
	): OrganizationEditProblem? {
		if (selection.id is OrganizationSessionId.Annotation && existing?.recordingAdapterKey != null)
			{
			return OrganizationEditProblem.NOT_FOUND
		}
		if (existing?.hidden == true || (existing?.revision ?: 0L) != selection.revision)
			{
			return OrganizationEditProblem.STALE
		}
		return null
	}

	private suspend fun annotation(id: OrganizationSessionId) = when (id) {
		is OrganizationSessionId.Annotation -> dao.byId(id.localId)
		is OrganizationSessionId.Recording -> dao.byAdapter(id.adapterKey)
	}

	/** Rechecked inside the same transaction as the edit, not from a stale screen snapshot. */
	private suspend fun activeRecording(id: OrganizationSessionId): Boolean? {
		if (id !is OrganizationSessionId.Recording) {
			return false
		}
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

	// One input has a fixed name, zone, activity label and interval contract, including one optional label lookup.
	@Suppress("ReturnCount", "CyclomaticComplexMethod") // Keep ordered missing-reference and rejection exits together.
	private suspend fun validate(interval: OrganizationInterval?, zone: String,
		name: String?, label: Long?): OrganizationEditProblem? {
		if (name.normalized()?.let { it.codePointCount(0, it.length) > MAX_SESSION_NAME_CODE_POINTS } == true)
			{
			return OrganizationEditProblem.NAME_TOO_LONG
		}
		if (zone !in ZoneId.getAvailableZoneIds()) {
			return OrganizationEditProblem.INVALID_ZONE
		}
		try { ZoneId.of(zone) } catch (_: IllegalArgumentException) { return OrganizationEditProblem.INVALID_ZONE }
		catch (_: java.time.DateTimeException) { return OrganizationEditProblem.INVALID_ZONE }
		if (label != null && NativeSessionActivity.entries.none { it.id == label } &&
			database.activityDao().get(label) == null) {
			return OrganizationEditProblem.MISSING_ACTIVITY_LABEL
		}
		if (interval != null) {
			if (interval.fromMs < 0L || interval.toMs <= interval.fromMs)
				{
				return OrganizationEditProblem.INVALID_INTERVAL
			}
			if (interval.toMs > clock.currentTimeMillis()) {
				return OrganizationEditProblem.FUTURE_END
			}
		}
		return null
	}

	private suspend fun sameEpoch(epoch: Long) = database.sourceEvidenceStateDao().get()?.collectedDataEpoch == epoch
	private fun String?.normalized() = this?.trim()?.takeIf { it.isNotEmpty() }
	private fun rejected(problem: OrganizationEditProblem) = OrganizationMutationResult.Rejected(problem)
	private suspend fun mutate(block: suspend () -> OrganizationMutationResult): OrganizationMutationResult =
        withContext(io) {
		try {
			operationLease.withPermit { permit ->
				val current = lifecycle.snapshot()
				permit.validate()
				if (permit.commitRoomMutation { database.activeRetentionFloorSettlement() } != null)
					{
					return@withPermit rejected(OrganizationEditProblem.STORAGE_UNAVAILABLE)
				}
				if (permit.commitRoomMutation { database.bootstrapSourceEvidenceForRetention(current.epoch,
					current.retainedFromMs,
                        clock.currentTimeMillis()) } !is SourceEvidenceRetentionBootstrapResult.Ready)
					{
					return@withPermit rejected(OrganizationEditProblem.STALE)
				}
				permit.commitRoomMutation { database.withTransaction {
					val state = database.sourceEvidenceStateDao().get()
					if (state == null || state.collectedDataEpoch != current.epoch ||
                        state.retainedFromMs != current.retainedFromMs)
						{
						return@withTransaction rejected(OrganizationEditProblem.STALE)
					}
					block()
				} }
			}
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// Lease/access rejection and transaction failures must report a failed edit after rollback.
			if (failure is CancellationException) {
				throw failure
			}
			rejected(OrganizationEditProblem.STORAGE_UNAVAILABLE)
		}
	}
}

private const val MAX_IMPORTED_ACTIVITY_LABEL_CODE_POINTS = 200

private const val MAX_SESSION_NAME_CODE_POINTS = 200
