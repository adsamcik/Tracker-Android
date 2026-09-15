package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryMember
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryProducts
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as ApiStepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/** Room-backed, read-only history facade. */
internal class DefaultTrackingHistoryRepository private constructor(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
	private val logicalHistoryReader: LogicalTrackingHistoryReader,
	private val pressureSelector: PressureHistorySelector,
	private val pressurePageReader: PressureHistoryPageReader,
	private val sourceUnionReader: TrackingHistorySourceUnionReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TrackingHistoryRepository {
	private val importedReader = ImportedStepsProductReader(database)

	@Inject
	internal constructor(
		database: AppDatabase,
		stepsSelector: StepsSegmentHistorySelector,
		logicalHistoryReader: LogicalTrackingHistoryReader,
		pressureSelector: PressureHistorySelector,
		pressurePageReader: PressureHistoryPageReader,
		sourceUnionReader: TrackingHistorySourceUnionReader,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		database = database,
		stepsSelector = stepsSelector,
		logicalHistoryReader = logicalHistoryReader,
		pressureSelector = pressureSelector,
		pressurePageReader = pressurePageReader,
		sourceUnionReader = sourceUnionReader,
		ioDispatcher = ioDispatcher,
	)

	/** Steps-only host fixtures install no active Pressure lane. Production uses Hilt. */
	internal constructor(
		database: AppDatabase,
		stepsSelector: StepsSegmentHistorySelector,
		logicalHistoryReader: LogicalTrackingHistoryReader,
		ioDispatcher: CoroutineDispatcher,
	) : this(
		database, stepsSelector, logicalHistoryReader,
		PressureHistorySelector(
			database,
			com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority { false },
		), ioDispatcher,
	)

	internal constructor(
		database: AppDatabase,
		stepsSelector: StepsSegmentHistorySelector,
		logicalHistoryReader: LogicalTrackingHistoryReader,
		pressureSelector: PressureHistorySelector,
		ioDispatcher: CoroutineDispatcher,
	) : this(
			database,
			stepsSelector,
			logicalHistoryReader,
			pressureSelector,
			DefaultActivityHistoryRepository(
				database,
			com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority { false },
			ioDispatcher,
		),
		ioDispatcher,
	)

	internal constructor(
		database: AppDatabase,
		stepsSelector: StepsSegmentHistorySelector,
		logicalHistoryReader: LogicalTrackingHistoryReader,
		pressureSelector: PressureHistorySelector,
		activityHistoryRepository: DefaultActivityHistoryRepository,
		ioDispatcher: CoroutineDispatcher,
	) : this(
		database = database,
		stepsSelector = stepsSelector,
		logicalHistoryReader = logicalHistoryReader,
		pressureSelector = pressureSelector,
		pressurePageReader = PressureHistoryPageReader(
			database = database,
			liveSelector = pressureSelector,
			importedEvaluator = ImportedPressureHistoryEvaluator(database),
			portableReader = PortablePressureRoomReader(database, pressureSelector),
		),
		sourceUnionReader = LegacyTrackingHistorySourceUnionReader(
			activityHistoryRepository,
			pressureSelector,
			PressureHistoryPageReader(
				database = database,
				liveSelector = pressureSelector,
				importedEvaluator = ImportedPressureHistoryEvaluator(database),
				portableReader = PortablePressureRoomReader(database, pressureSelector),
			),
		),
		ioDispatcher = ioDispatcher,
	)

	internal constructor(
		database: AppDatabase,
		stepsSelector: StepsSegmentHistorySelector,
		logicalHistoryReader: LogicalTrackingHistoryReader,
		pressureSelector: PressureHistorySelector,
		sourceUnionReader: TrackingHistorySourceUnionReader,
		ioDispatcher: CoroutineDispatcher,
	) : this(
		database = database,
		stepsSelector = stepsSelector,
		logicalHistoryReader = logicalHistoryReader,
		pressureSelector = pressureSelector,
		pressurePageReader = PressureHistoryPageReader(
			database = database,
			liveSelector = pressureSelector,
			importedEvaluator = ImportedPressureHistoryEvaluator(database),
			portableReader = PortablePressureRoomReader(database, pressureSelector),
		),
		sourceUnionReader = sourceUnionReader,
		ioDispatcher = ioDispatcher,
	)

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeSession(segmentId: Long): Flow<SessionHistoryQuery> {
		require(segmentId > 0L) { "Session history segment id must be positive" }
		return historyInvalidations().mapLatest {
			database.withTransaction {
				selectLiveSessionInTransaction(segmentId).session
			}
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeLiveSession(segmentId: Long): Flow<LiveSessionHistorySnapshot> {
		require(segmentId > 0L) { "Live session segment id must be positive" }
		return historyInvalidations().mapLatest {
			database.withTransaction {
				selectLiveSessionInTransaction(segmentId)
			}
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	private suspend fun selectLiveSessionInTransaction(
		segmentId: Long,
	): LiveSessionHistorySnapshot {
		val readSnapshot = database.sourceEvidenceStateDao().get()
			?.takeIf { it.hasValidHistorySnapshotShape() }
			?.toHistoryReadSnapshot()
			?: return LiveSessionHistorySnapshot(
				segmentId = segmentId,
				session = SessionHistoryQuery.Unavailable(
					TrackingHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_UNAVAILABLE,
				),
				activity = ActivityHistoryQuery.NotFound,
				pressure = PressureSessionHistoryQuery.NotFound,
			)
		val segment = database.trackingHistoryReadDao()
			.segments(listOf(segmentId))
			.singleOrNull()
			?: return LiveSessionHistorySnapshot(
				segmentId = segmentId,
				session = SessionHistoryQuery.NotFound,
				activity = ActivityHistoryQuery.NotFound,
				pressure = PressureSessionHistoryQuery.NotFound,
				readSnapshot = readSnapshot,
			)
		if (segment.source == SegmentSource.PORTABLE_STEPS_IMPORT) {
			val imported = requireNotNull(
				importedReader.selectSessionsInTransaction(listOf(segmentId))[segmentId],
			) { "Imported Steps presentation must resolve a typed retained result" }
			val verified = imported.capture is HistoryCapture.ImportedSteps
			val activity = ActivityHistoryQuery.Found(
				ActivityHistoryEntry(
					key = ActivityHistoryEntryKey("imported-steps:$segmentId"),
					startTime = EpochMs(segment.startTimeMs),
					endTime = EpochMs(segment.endTimeMs),
					storedZoneIds = emptySet(),
					state = ActivityHistoryProductState.UNAVAILABLE,
					coverage = ActivityHistoryCoverage.NONE,
					activeTime = null,
					fragments = emptyList(),
					causes = setOf(
						if (verified) {
							ActivityHistoryCause.SOURCE_NOT_CAPTURED
						} else {
							ActivityHistoryCause.LEGACY_UNVERIFIABLE
						},
					),
					origin = ActivityHistoryOrigin.IMPORTED,
				),
			)
			val pressure = PressureSessionHistoryQuery.Found(
				com.adsamcik.tracker.stats.api.repository.PressureSessionHistory(
					segmentId = segmentId,
					capture = imported.capture,
					qualifiedSources = emptySet(),
					pressure = PressureHistory(
						availability = if (verified) {
							HistoryAvailability.DISABLED
						} else {
							HistoryAvailability.UNAVAILABLE
						},
						evidence = HistoryEvidence.NONE,
						productState = HistoryProductState.DEGRADED,
						coverage = PressureHistoryCoverage.NONE,
						windows = emptyList(),
						causes = setOf(
							if (verified) {
								PressureHistoryCause.SOURCE_NOT_CAPTURED
							} else {
								PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
							},
						),
					),
				),
			)
			val products = SessionHistoryProducts(
				segmentId = segmentId,
				wifi = com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery.NotFound,
				cell = com.adsamcik.tracker.stats.api.repository.CellHistoryQuery.NotFound,
				activity = activity,
				pressure = pressure,
			)
			val selected = imported.copy(
				qualifiedSources = SessionHistory.deriveQualifiedSources(
					imported.capture,
					imported.steps,
					products,
				),
				sourceProducts = products,
				readSnapshot = readSnapshot,
			)
			return LiveSessionHistorySnapshot(
				segmentId = segmentId,
				session = SessionHistoryQuery.Found(selected),
				activity = activity,
				pressure = pressure,
				wifi = products.wifi,
				cell = products.cell,
				readSnapshot = readSnapshot,
			)
		}
		val selected = stepsSelector.selectManyInTransaction(listOf(segment))
			.singleOrNull { it.segment.id == segmentId }
			?: return unavailableLiveSession(
				segmentId,
				TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID,
				HistorySource.STEPS,
				readSnapshot,
			)
		return when (val sourceRead = sourceUnionReader.sessionInTransaction(segment)) {
			is HistoricalSessionSourceRead.Unavailable -> unavailableLiveSession(
				segmentId,
				sourceRead.reason,
				sourceRead.source,
				readSnapshot,
			)
			is HistoricalSessionSourceRead.Available -> {
				val history = selected.toPublicSessionHistory(
					products = sourceRead.publicProducts,
					readSnapshot = readSnapshot,
				)
				LiveSessionHistorySnapshot(
					segmentId = segmentId,
					session = SessionHistoryQuery.Found(history),
					activity = sourceRead.activity,
					pressure = sourceRead.pressure,
					wifi = sourceRead.wifi,
					cell = sourceRead.cell,
					readSnapshot = readSnapshot,
				)
			}
		}
	}

	private fun unavailableLiveSession(
		segmentId: Long,
		reason: TrackingHistoryUnavailableReason,
		source: HistorySource,
		readSnapshot: TrackingHistoryReadSnapshot,
	) = LiveSessionHistorySnapshot(
		segmentId = segmentId,
		session = SessionHistoryQuery.Unavailable(reason, source, readSnapshot),
		activity = ActivityHistoryQuery.NotFound,
		pressure = PressureSessionHistoryQuery.NotFound,
		readSnapshot = readSnapshot,
	)

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeRecentStepsOnlyEntries(
		limit: Int,
	): Flow<List<StepsOnlyHistoryEntry>> {
		require(limit in 1..MAX_RECENT_ENTRY_COUNT) {
			"Recent Steps-only history limit must be between 1 and $MAX_RECENT_ENTRY_COUNT"
		}
		return historyInvalidations().mapLatest {
			logicalHistoryReader.selectRecentStepsOnlyEntries(limit).mapToPublicStepsOnlyEntries()
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeRecentStepsAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<List<StepsAwareHistoryPageEntry>> {
		validateStepsAwarePageRequest(candidateSegmentIds, limit)
		val stableCandidateSegmentIds = candidateSegmentIds.toList()
		return historyInvalidations().mapLatest {
			database.withTransaction {
				val segments = database.trackingHistoryReadDao().segments(stableCandidateSegmentIds).associateBy { it.id }
				val liveIds = stableCandidateSegmentIds.filter { segments[it]?.source != SegmentSource.PORTABLE_STEPS_IMPORT }
				val live = logicalHistoryReader.selectRecentStepsAwarePage(liveIds, limit)
					.map { RecentProductPageRow(it.toPublicPageEntry(), it.recencyStartTimeMs, it.recencySegmentId) }
				val imported = importedReader.recentInTransaction(limit).map { entry ->
					val newest = entry.physicalMembers.maxWith(
						compareBy<ImportedStepsHistoryMember> { it.startTime }.thenBy { it.segmentId },
					)
					RecentProductPageRow(StepsAwareHistoryPageEntry.ImportedSteps(entry), newest.startTime.raw, newest.segmentId)
				}
				(live + imported).sortedWith(
					compareByDescending<RecentProductPageRow> { it.startTimeMs }.thenByDescending { it.segmentId },
				).take(limit).map { it.entry }
			}
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeRecentSourceAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<SourceAwareHistoryPageQuery> {
		validateSourceAwarePageRequest(candidateSegmentIds, limit)
		val stableCandidateSegmentIds = candidateSegmentIds.toList()
		return historyInvalidations().mapLatest {
			try {
				database.withTransaction {
					val readSnapshot = database.sourceEvidenceStateDao().get()
						?.takeIf { it.hasValidHistorySnapshotShape() }
						?.toHistoryReadSnapshot()
						?: return@withTransaction SourceAwareHistoryPageQuery.Unavailable(
							SourceAwareHistoryPageUnavailableReason
								.SOURCE_EVIDENCE_STATE_UNAVAILABLE,
						)
					val candidateSegments = database.trackingHistoryReadDao()
						.segments(stableCandidateSegmentIds).associateBy { it.id }
					val liveCandidateIds = stableCandidateSegmentIds.filter {
						candidateSegments[it]?.source?.let { source ->
							source != SegmentSource.PORTABLE_STEPS_IMPORT
						} == true
					}
					val existingRows =
						logicalHistoryReader.selectRecentSourceAwareStepsCandidatesInTransaction(
							candidateSegmentIds = liveCandidateIds,
							sourceOnlyLimit = limit,
						)
					val candidateSourceMemberships = when (
						val sourceRead = sourceUnionReader
							.candidateNativeOnlyInTransaction(liveCandidateIds)
					) {
						is HistoricalSourceUnionRead.Available -> sourceRead.value
						is HistoricalSourceUnionRead.Unavailable ->
							return@withTransaction SourceAwareHistoryPageQuery.Unavailable(
								sourceRead.reason,
								sourceRead.source,
							)
					}
					val candidateNativeOnlyMemberships = buildList {
						existingRows.mapNotNullTo(this) { entry ->
							(entry as? HistoricalStepsAwarePageEntry.StepsOnly)
								?.history?.toNativeMembership()
						}
						addAll(candidateSourceMemberships)
					}
					if (candidateNativeOnlyMemberships.hasNativeMembershipCollision) {
						return@withTransaction SourceAwareHistoryPageQuery.Unavailable(
							SourceAwareHistoryPageUnavailableReason.SOURCE_IDENTITY_COLLISION,
						)
					}
					val suppressedPhysicalIds = candidateSourceMemberships.flatMapTo(hashSetOf()) {
						membership -> membership.physicalSegmentIds
					}
					val retainedExistingRows = existingRows.mapNotNull { entry ->
						when (entry) {
							is HistoricalStepsAwarePageEntry.Physical -> entry.takeUnless {
								it.segment.id in suppressedPhysicalIds
							}?.let(HistoricalSourceAwarePageEntry::Existing)
							is HistoricalStepsAwarePageEntry.StepsOnly ->
								HistoricalSourceAwarePageEntry.Existing(entry)
						}
					}
					val sourceRows = when (val sourceRead =
						sourceUnionReader.recentInTransaction(limit)
					) {
						is HistoricalSourceUnionRead.Available ->
							sourceRead.value.map(HistoricalSourceAwarePageEntry::SourceOnly)
						is HistoricalSourceUnionRead.Unavailable ->
							return@withTransaction SourceAwareHistoryPageQuery.Unavailable(
								sourceRead.reason,
								sourceRead.source,
							)
					}
					val importedRows = importedReader.recentInTransaction(limit).map {
						HistoricalSourceAwarePageEntry.ImportedSteps(it)
					}
					val combined = retainedExistingRows + sourceRows + importedRows
					if (combined.hasSourceIdentityCollision) {
						return@withTransaction SourceAwareHistoryPageQuery.Unavailable(
							SourceAwareHistoryPageUnavailableReason.SOURCE_IDENTITY_COLLISION,
						)
					}
					SourceAwareHistoryPageQuery.Content(
						combined
							.sortedWith(sourceAwarePageOrder)
							.take(limit)
							.map(HistoricalSourceAwarePageEntry::toPublicPageEntry),
						readSnapshot = readSnapshot,
					)
				}
			} catch (overflow: LogicalHistoryPageDependencyOverflow) {
				SourceAwareHistoryPageQuery.Unavailable(
					when (overflow.limit) {
						HistoryPageDependencyLimit.CANDIDATE_SCAN ->
							SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT
						HistoryPageDependencyLimit.LOGICAL_MEMBERSHIP ->
							SourceAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT
					},
				)
			}
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observePressureSession(segmentId: Long): Flow<PressureSessionHistoryQuery> {
		require(segmentId > 0L) { "Pressure session segment id must be positive" }
		return historyInvalidations().mapLatest {
			pressureSelector.selectBySegmentId(segmentId)?.let { selected ->
				PressureSessionHistoryQuery.Found(selected.toPublicPressureSessionHistory())
			} ?: PressureSessionHistoryQuery.NotFound
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeRecentPressureOnlyEntries(
		limit: Int,
	): Flow<List<PressureOnlyHistoryEntry>> {
		require(limit in 1..MAX_RECENT_PRESSURE_ENTRY_COUNT) {
			"Recent Pressure-only history limit must be between 1 and " +
				MAX_RECENT_PRESSURE_ENTRY_COUNT
		}
		return historyInvalidations().mapLatest {
			pressurePageReader.selectRecent(limit)
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	private fun validateStepsAwarePageRequest(
		candidateSegmentIds: List<Long>,
		limit: Int,
	) {
		require(candidateSegmentIds.size <= MAX_RECENT_ENTRY_COUNT) {
			"Physical history candidate count cannot exceed $MAX_RECENT_ENTRY_COUNT"
		}
		require(candidateSegmentIds.all { it > 0L }) {
			"Physical history candidate ids must be positive"
		}
		require(candidateSegmentIds.distinct().size == candidateSegmentIds.size) {
			"Physical history candidate ids must be distinct"
		}
		require(limit in 1..MAX_RECENT_ENTRY_COUNT) {
			"Recent Steps-aware history limit must be between 1 and $MAX_RECENT_ENTRY_COUNT"
		}
	}

	private fun validateSourceAwarePageRequest(
		candidateSegmentIds: List<Long>,
		limit: Int,
	) {
		require(candidateSegmentIds.size <= MAX_RECENT_PRESSURE_ENTRY_COUNT) {
			"Physical history candidate count cannot exceed $MAX_RECENT_PRESSURE_ENTRY_COUNT"
		}
		require(candidateSegmentIds.all { it > 0L }) {
			"Physical history candidate ids must be positive"
		}
		require(candidateSegmentIds.distinct().size == candidateSegmentIds.size) {
			"Physical history candidate ids must be distinct"
		}
		require(limit in 1..MAX_RECENT_PRESSURE_ENTRY_COUNT) {
			"Recent source-aware history limit must be between 1 and " +
				MAX_RECENT_PRESSURE_ENTRY_COUNT
		}
	}

	private fun historyInvalidations() = database.invalidationTracker.createFlow(
		SESSION_SEGMENT_TABLE,
		SERVICE_RUN_TABLE,
		MANIFEST_TABLE,
		MANIFEST_SOURCE_TABLE,
		SOURCE_POLICY_TABLE,
		SOURCE_CONSENT_EPOCH_TABLE,
		SOURCE_DESTINATION_OWNER_TABLE,
		SOURCE_EVENT_WAL_TABLE,
		SOURCE_PROJECTION_REGISTRATION_TABLE,
		SOURCE_PROJECTION_CHECKPOINT_TABLE,
		PRODUCT_LANE_TABLE,
		PROJECTION_FAILURE_TABLE,
		SOURCE_EVIDENCE_TABLE,
		SOURCE_DELETION_FENCE_TABLE,
		SOURCE_DEMAND_TABLE,
		STEP_FACT_TABLE,
		PRESSURE_FACT_TABLE,
		WIFI_FACT_TABLE,
		WIFI_CURSOR_TABLE,
		WIFI_DELETION_TABLE,
		CELL_FACT_TABLE,
		CELL_CURSOR_TABLE,
		CELL_DELETION_TABLE,
		ACTIVITY_FACT_TABLE,
		ACTIVITY_FRAGMENT_TABLE,
		ACTIVITY_EVIDENCE_TABLE,
		ACTIVITY_CURSOR_TABLE,
		ACTIVITY_REGISTRATION_PLAN_TABLE,
		ACQUISITION_PLAN_TABLE,
		DESIRED_PLAN_TABLE,
		PROVIDER_REGISTRATION_TABLE,
		SOURCE_AUTHORIZATION_TABLE,
		SOURCE_SESSION_TABLE,
		SESSION_COMPLETENESS_TABLE,
		"imported_steps_entry",
		"imported_steps_run",
		"imported_steps_manifest",
		IMPORTED_WIFI_ENTRY_TABLE,
		IMPORTED_WIFI_RECEIPT_TABLE,
		IMPORTED_WIFI_RUN_TABLE,
		IMPORTED_WIFI_ZONE_TABLE,
		IMPORTED_WIFI_OBSERVATION_TABLE,
		IMPORTED_WIFI_ENTRY_DELETION_TABLE,
		IMPORTED_WIFI_DELETION_TABLE,
		IMPORTED_CELL_ENTRY_TABLE,
		IMPORTED_CELL_RECEIPT_TABLE,
		IMPORTED_CELL_RUN_TABLE,
		IMPORTED_CELL_OBSERVATION_TABLE,
		IMPORTED_CELL_ENTRY_DELETION_TABLE,
		IMPORTED_CELL_DELETION_TABLE,
		IMPORTED_CELL_DELETION_RECEIPT_TABLE,
		IMPORTED_CELL_DELETED_IDENTITY_TABLE,
		IMPORTED_ACTIVITY_ENTRY_TABLE,
		IMPORTED_ACTIVITY_RECEIPT_TABLE,
		IMPORTED_ACTIVITY_RUN_TABLE,
		IMPORTED_ACTIVITY_ZONE_TABLE,
		IMPORTED_ACTIVITY_WINDOW_TABLE,
		IMPORTED_ACTIVITY_FRAGMENT_TABLE,
		IMPORTED_ACTIVITY_RETENTION_TABLE,
		IMPORTED_ACTIVITY_RETAINED_IDENTITY_TABLE,
		IMPORTED_ACTIVITY_ENTRY_DELETION_TABLE,
		IMPORTED_ACTIVITY_DELETION_RECEIPT_TABLE,
		IMPORTED_ACTIVITY_DELETION_TABLE,
		IMPORTED_PRESSURE_ENTRY_TABLE,
		IMPORTED_PRESSURE_RECEIPT_TABLE,
		IMPORTED_PRESSURE_RUN_TABLE,
		IMPORTED_PRESSURE_WINDOW_TABLE,
		IMPORTED_PRESSURE_ENTRY_DELETION_TABLE,
		IMPORTED_PRESSURE_DELETION_TABLE,
		emitInitialState = true,
	)

	private companion object {
		const val MAX_RECENT_ENTRY_COUNT = 100
		const val MAX_RECENT_PRESSURE_ENTRY_COUNT = 64
		const val SESSION_SEGMENT_TABLE = "session_segment"
		const val SERVICE_RUN_TABLE = "source_service_run"
		const val MANIFEST_TABLE = "session_manifest_version"
		const val MANIFEST_SOURCE_TABLE = "session_manifest_source"
		const val SOURCE_POLICY_TABLE = "source_policy"
		const val SOURCE_CONSENT_EPOCH_TABLE = "source_consent_epoch"
		const val SOURCE_DESTINATION_OWNER_TABLE = "source_destination_owner"
		const val SOURCE_EVENT_WAL_TABLE = "source_event_wal"
		const val SOURCE_PROJECTION_REGISTRATION_TABLE = "source_projection_registration"
		const val SOURCE_PROJECTION_CHECKPOINT_TABLE = "source_projection_checkpoint"
		const val PRODUCT_LANE_TABLE = "source_product_projection_lane"
		const val PROJECTION_FAILURE_TABLE = "source_projection_failure"
		const val SOURCE_EVIDENCE_TABLE = "source_evidence_state"
		const val SOURCE_DELETION_FENCE_TABLE = "source_deletion_fence"
		const val SOURCE_DEMAND_TABLE = "source_demand"
		const val STEP_FACT_TABLE = "step_fact_revision"
		const val PRESSURE_FACT_TABLE = "pressure_fact_revision"
		const val WIFI_FACT_TABLE = "wifi_captured_fact_revision"
		const val WIFI_CURSOR_TABLE = "wifi_captured_fact_cursor"
		const val WIFI_DELETION_TABLE = "wifi_capture_deletion_generation"
		const val CELL_FACT_TABLE = "cell_captured_fact_revision"
		const val CELL_CURSOR_TABLE = "cell_captured_fact_cursor"
		const val CELL_DELETION_TABLE = "cell_capture_deletion_generation"
		const val ACTIVITY_FACT_TABLE = "activity_captured_window_revision"
		const val ACTIVITY_FRAGMENT_TABLE = "activity_captured_fragment"
		const val ACTIVITY_EVIDENCE_TABLE = "activity_captured_evidence"
		const val ACTIVITY_CURSOR_TABLE = "activity_captured_window_cursor"
		const val ACTIVITY_REGISTRATION_PLAN_TABLE = "activity_captured_registration_plan"
		const val ACQUISITION_PLAN_TABLE = "acquisition_plan_revision"
		const val DESIRED_PLAN_TABLE = "source_desired_plan"
		const val PROVIDER_REGISTRATION_TABLE = "provider_registration_generation"
		const val SOURCE_AUTHORIZATION_TABLE = "source_authorization"
		const val SOURCE_SESSION_TABLE = "logical_tracking_session"
		const val SESSION_COMPLETENESS_TABLE = "source_session_completeness"
		const val IMPORTED_WIFI_ENTRY_TABLE = "imported_wifi_entry_revision"
		const val IMPORTED_WIFI_RECEIPT_TABLE = "imported_wifi_receipt"
		const val IMPORTED_WIFI_RUN_TABLE = "imported_wifi_run"
		const val IMPORTED_WIFI_ZONE_TABLE = "imported_wifi_run_zone"
		const val IMPORTED_WIFI_OBSERVATION_TABLE = "imported_wifi_observation"
		const val IMPORTED_WIFI_ENTRY_DELETION_TABLE = "imported_wifi_entry_deletion"
		const val IMPORTED_WIFI_DELETION_TABLE = "imported_wifi_deletion_generation"
		const val IMPORTED_CELL_ENTRY_TABLE = "imported_cell_entry_revision"
		const val IMPORTED_CELL_RECEIPT_TABLE = "imported_cell_receipt"
		const val IMPORTED_CELL_RUN_TABLE = "imported_cell_run"
		const val IMPORTED_CELL_OBSERVATION_TABLE = "imported_cell_observation"
		const val IMPORTED_CELL_ENTRY_DELETION_TABLE = "imported_cell_entry_deletion"
		const val IMPORTED_CELL_DELETION_TABLE = "imported_cell_deletion_generation"
		const val IMPORTED_CELL_DELETION_RECEIPT_TABLE =
			"imported_cell_entry_deletion_receipt"
		const val IMPORTED_CELL_DELETED_IDENTITY_TABLE = "imported_cell_deleted_identity"
		const val IMPORTED_ACTIVITY_ENTRY_TABLE = "imported_activity_entry_revision"
		const val IMPORTED_ACTIVITY_RECEIPT_TABLE = "imported_activity_receipt"
		const val IMPORTED_ACTIVITY_RUN_TABLE = "imported_activity_run"
		const val IMPORTED_ACTIVITY_ZONE_TABLE = "imported_activity_zone_epoch"
		const val IMPORTED_ACTIVITY_WINDOW_TABLE = "imported_activity_window"
		const val IMPORTED_ACTIVITY_FRAGMENT_TABLE = "imported_activity_fragment"
		const val IMPORTED_ACTIVITY_RETENTION_TABLE = "imported_activity_retention_receipt"
		const val IMPORTED_ACTIVITY_RETAINED_IDENTITY_TABLE =
			"imported_activity_retained_identity"
		const val IMPORTED_ACTIVITY_ENTRY_DELETION_TABLE =
			"imported_activity_entry_deletion"
		const val IMPORTED_ACTIVITY_DELETION_RECEIPT_TABLE =
			"imported_activity_entry_deletion_receipt"
		const val IMPORTED_ACTIVITY_DELETION_TABLE = "imported_activity_deletion_generation"
		const val IMPORTED_PRESSURE_ENTRY_TABLE = "imported_pressure_entry_revision"
		const val IMPORTED_PRESSURE_RECEIPT_TABLE = "imported_pressure_receipt"
		const val IMPORTED_PRESSURE_RUN_TABLE = "imported_pressure_run"
		const val IMPORTED_PRESSURE_WINDOW_TABLE = "imported_pressure_window"
		const val IMPORTED_PRESSURE_ENTRY_DELETION_TABLE = "imported_pressure_entry_deletion"
		const val IMPORTED_PRESSURE_DELETION_TABLE = "imported_pressure_deletion_generation"
	}
}

private fun SourceEvidenceState.hasValidHistorySnapshotShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID &&
		revision >= 0L &&
		collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false &&
		deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L

private fun SourceEvidenceState.toHistoryReadSnapshot() = TrackingHistoryReadSnapshot(
	collectedDataEpoch = collectedDataEpoch,
	sourceEvidenceRevision = revision,
)

private data class RecentProductPageRow(
	val entry: StepsAwareHistoryPageEntry,
	val startTimeMs: Long,
	val segmentId: Long,
)

private sealed interface HistoricalSourceAwarePageEntry {
	val recencyStartTimeMs: Long
	val recencyTieBreaker: Long
	val logicalTrackingId: String?
	val nativePhysicalSegmentIds: List<Long>

	data class Existing(
		val entry: HistoricalStepsAwarePageEntry,
	) : HistoricalSourceAwarePageEntry {
		override val recencyStartTimeMs: Long get() = entry.recencyStartTimeMs
		override val recencyTieBreaker: Long get() = entry.recencySegmentId
		override val logicalTrackingId: String?
			get() = when (entry) {
				is HistoricalStepsAwarePageEntry.Physical -> entry.segment.logicalTrackingId
				is HistoricalStepsAwarePageEntry.StepsOnly ->
					(entry.history.identity as? HistoricalEntryIdentity.Logical)?.logicalTrackingId
			}
		override val nativePhysicalSegmentIds: List<Long>
			get() = when (entry) {
				is HistoricalStepsAwarePageEntry.Physical -> emptyList()
				is HistoricalStepsAwarePageEntry.StepsOnly ->
					entry.history.physicalMembers.map { it.segment.id }
			}
	}

	data class ImportedSteps(
		val history: com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryEntry,
	) : HistoricalSourceAwarePageEntry {
		// ImportedStepsProductReader already authenticates its separate imported/local namespace.
		// Public physical detail members do not expose a local logical-run authority.
		override val logicalTrackingId: String? get() = null
		private val newest = history.physicalMembers.maxWith(
			compareBy<ImportedStepsHistoryMember> { it.startTime }.thenBy { it.segmentId },
		)
		override val recencyStartTimeMs: Long get() = newest.startTime.raw
		override val recencyTieBreaker: Long get() = newest.segmentId
		override val nativePhysicalSegmentIds: List<Long> get() = emptyList()
	}

	data class SourceOnly(
		val history: HistoricalSourceUnionEntry,
	) : HistoricalSourceAwarePageEntry {
		override val recencyStartTimeMs: Long get() = history.recencyStartTimeMs
		override val recencyTieBreaker: Long get() = history.recencyTieBreaker
		override val logicalTrackingId: String?
			get() = history.nativeMembership?.logicalTrackingId
		override val nativePhysicalSegmentIds: List<Long>
			get() = history.nativeMembership?.physicalSegmentIds.orEmpty()
	}
}

private val List<HistoricalSourceOnlyMembership>.hasNativeMembershipCollision: Boolean
	get() {
		val logicalIds = map(HistoricalSourceOnlyMembership::logicalTrackingId)
		if (logicalIds.size != logicalIds.distinct().size) return true
		val physicalIds = flatMap(HistoricalSourceOnlyMembership::physicalSegmentIds)
		return physicalIds.size != physicalIds.distinct().size
	}

private val List<HistoricalSourceAwarePageEntry>.hasSourceIdentityCollision: Boolean
	get() {
		val sourceOnlyIds = mapNotNull { entry ->
			entry.logicalTrackingId.takeUnless {
				entry is HistoricalSourceAwarePageEntry.Existing &&
					entry.entry is HistoricalStepsAwarePageEntry.Physical
			}
		}
		if (sourceOnlyIds.size != sourceOnlyIds.distinct().size) return true
		val nativePhysicalIds = flatMap(HistoricalSourceAwarePageEntry::nativePhysicalSegmentIds)
		if (nativePhysicalIds.size != nativePhysicalIds.distinct().size) return true
		val physicalRows = filterIsInstance<HistoricalSourceAwarePageEntry.Existing>()
			.filter { it.entry is HistoricalStepsAwarePageEntry.Physical }
		return physicalRows.mapNotNull(HistoricalSourceAwarePageEntry::logicalTrackingId)
			.any(sourceOnlyIds.toHashSet()::contains) ||
			physicalRows.map { (it.entry as HistoricalStepsAwarePageEntry.Physical).segment.id }
				.any(nativePhysicalIds.toHashSet()::contains)
	}

private val sourceAwarePageOrder =
compareByDescending<HistoricalSourceAwarePageEntry> { it.recencyStartTimeMs }
	.thenByDescending { it.recencyTieBreaker }

private fun HistoricalSourceAwarePageEntry.toPublicPageEntry(): SourceAwareHistoryPageEntry =
	when (this) {
		is HistoricalSourceAwarePageEntry.Existing -> when (val existing = entry) {
			is HistoricalStepsAwarePageEntry.Physical ->
				SourceAwareHistoryPageEntry.Physical(existing.segment.id)
			is HistoricalStepsAwarePageEntry.StepsOnly ->
				SourceAwareHistoryPageEntry.StepsOnly(existing.history.toPublicStepsOnlyEntry())
		}
		is HistoricalSourceAwarePageEntry.ImportedSteps ->
			SourceAwareHistoryPageEntry.ImportedSteps(history)
		is HistoricalSourceAwarePageEntry.SourceOnly -> history.entry
	}

private fun HistoricalSegmentEvidence.toPublicSessionHistory(
	products: SessionHistoryProducts?,
	readSnapshot: TrackingHistoryReadSnapshot,
): SessionHistory {
	val publicCapture = captureAuthority.toPublicCapture()
	val publicSteps = steps.toPublicHistory()
	val publicQualifiedSources = products?.let {
		SessionHistory.deriveQualifiedSources(publicCapture, publicSteps, it)
	} ?: qualifiedSources.mapTo(linkedSetOf(), TrackingSourceComponent::toPublicSource)
	return SessionHistory(
		segmentId = segment.id,
		capture = publicCapture,
		qualifiedSources = publicQualifiedSources,
		steps = publicSteps,
		sourceProducts = products,
		readSnapshot = readSnapshot,
	)
}

private fun HistoricalTrackingEntryEvidence.toNativeMembership():
	HistoricalSourceOnlyMembership {
	check(hasExactStepsOnlyIntent)
	val logicalIdentity = identity as? HistoricalEntryIdentity.Logical
		?: error("Exact Steps-only entry requires logical identity")
	return HistoricalSourceOnlyMembership(
		source = HistorySource.STEPS,
		logicalTrackingId = logicalIdentity.logicalTrackingId,
		physicalSegmentIds = physicalMembers.map { it.segment.id },
	)
}

internal fun HistoricalCaptureAuthority.toPublicCapture(): HistoryCapture = when (this) {
	is HistoricalCaptureAuthority.Exact -> HistoryCapture.Exact(
		revisions.map { revision ->
			HistoryCaptureRevision(
				revision = revision.manifestRevision,
				effectiveAt = EpochMs(revision.effectiveWallTimeMs),
				capturedSources = revision.capturedSources.mapTo(
					linkedSetOf(),
					TrackingSourceComponent::toPublicSource,
				),
				controlSources = revision.controlSources.mapTo(
					linkedSetOf(),
					TrackingSourceComponent::toPublicSource,
				),
			)
		},
	)
	is HistoricalCaptureAuthority.Unverifiable -> HistoryCapture.Unverifiable
}

internal fun TrackingSourceComponent.toPublicSource(): HistorySource = when (this) {
	TrackingSourceComponent.LOCATION -> HistorySource.LOCATION
	TrackingSourceComponent.WIFI -> HistorySource.WIFI
	TrackingSourceComponent.CELL -> HistorySource.CELL
	TrackingSourceComponent.ACTIVITY -> HistorySource.ACTIVITY
	TrackingSourceComponent.STEPS -> HistorySource.STEPS
	TrackingSourceComponent.PRESSURE -> HistorySource.PRESSURE
}

private fun List<HistoricalTrackingEntryEvidence>.mapToPublicStepsOnlyEntries() =
	map(HistoricalTrackingEntryEvidence::toPublicStepsOnlyEntry)

internal fun HistoricalTrackingEntryEvidence.toPublicStepsOnlyEntry(): StepsOnlyHistoryEntry {
	check(hasExactStepsOnlyIntent) { "Steps-only reader returned a non-Steps-only entry" }
	val logicalIdentity = identity as? HistoricalEntryIdentity.Logical
		?: error("Exact Steps-only entry requires logical identity")
	return StepsOnlyHistoryEntry(
		key = TrackingHistoryEntryKey("logical:${logicalIdentity.logicalTrackingId}"),
		startTime = EpochMs(physicalMembers.minOf { it.segment.startTimeMs }),
		endTime = EpochMs(physicalMembers.maxOf { it.segment.endTimeMs }),
		state = physicalMembers.map { it.steps.toPublicHistory() }.toStepsOnlyListState(),
	)
}

private fun HistoricalStepsAwarePageEntry.toPublicPageEntry(): StepsAwareHistoryPageEntry =
	when (this) {
		is HistoricalStepsAwarePageEntry.Physical ->
			StepsAwareHistoryPageEntry.Physical(segment.id)
		is HistoricalStepsAwarePageEntry.StepsOnly ->
			StepsAwareHistoryPageEntry.StepsOnly(history.toPublicStepsOnlyEntry())
	}

internal fun List<StepsHistory>.toStepsOnlyListState(): StepsOnlyHistoryListState {
	require(isNotEmpty()) { "Steps-only logical entry requires a physical member" }
	return when {
		any { it.productState == HistoryProductState.MATERIALIZING } ->
			StepsOnlyHistoryListState.MATERIALIZING
		all(StepsHistory::hasCompleteValue) -> StepsOnlyHistoryListState.AVAILABLE
		else -> StepsOnlyHistoryListState.PARTIAL
	}
}

internal fun StepsSegmentHistoryResult.toPublicHistory(): StepsHistory {
	val publicAvailability = toPublicAvailability()
	val publicEvidence = toPublicEvidence()
	val publicProductState = toPublicProductState()
	val publicCauses = reasons.mapTo(linkedSetOf(), StepsHistoryReason::toPublicCause)
	if (publicAvailability == HistoryAvailability.UNAVAILABLE) {
		publicCauses += StepsHistoryCause.AVAILABILITY_UNAVAILABLE
	}
	when (evidence) {
		StepsHistoryEvidence.BASELINE -> publicCauses += StepsHistoryCause.BASELINE_ONLY
		StepsHistoryEvidence.NO_OBSERVATION -> if (
			publicProductState != HistoryProductState.READY && publicCauses.isEmpty()
		) {
			publicCauses += StepsHistoryCause.NO_OBSERVATION
		}
		else -> Unit
	}
	return StepsHistory(
		count = count,
		availability = publicAvailability,
		evidence = publicEvidence,
		productState = publicProductState,
		coverage = coverage.toPublicCoverage(),
		causes = publicCauses,
	)
}

private fun StepsSegmentHistoryResult.toPublicAvailability(): HistoryAvailability =
	when (availability) {
		StepsHistoryAvailability.DISABLED -> HistoryAvailability.DISABLED
		StepsHistoryAvailability.AVAILABLE,
		StepsHistoryAvailability.DELETED -> HistoryAvailability.AVAILABLE
		StepsHistoryAvailability.UNAVAILABLE -> HistoryAvailability.UNAVAILABLE
	}

private fun StepsSegmentHistoryResult.toPublicEvidence(): HistoryEvidence = when (evidence) {
	StepsHistoryEvidence.NO_OBSERVATION -> if (StepsHistoryReason.SERVICE_RUN_ACTIVE in reasons) {
		HistoryEvidence.STARTING
	} else {
		HistoryEvidence.NONE
	}
	StepsHistoryEvidence.BASELINE -> HistoryEvidence.NONE
	StepsHistoryEvidence.COVERED_ZERO -> HistoryEvidence.ACTIVE
	StepsHistoryEvidence.RECORDED,
	StepsHistoryEvidence.LEGACY_RECORDED -> HistoryEvidence.RECORDED
}

private fun StepsSegmentHistoryResult.toPublicProductState(): HistoryProductState =
	when (materialization) {
		StepsHistoryMaterialization.NOT_APPLICABLE -> HistoryProductState.READY
		StepsHistoryMaterialization.MATERIALIZING -> HistoryProductState.MATERIALIZING
		StepsHistoryMaterialization.DEGRADED -> HistoryProductState.DEGRADED
		StepsHistoryMaterialization.FAILED -> if (
			StepsHistoryReason.OUTSIDE_RETAINED_FLOOR in reasons ||
				StepsHistoryReason.RETENTION_TRUNCATED_RUN in reasons
		) {
			HistoryProductState.PARTIAL
		} else {
			HistoryProductState.FAILED
		}
		StepsHistoryMaterialization.READY -> if (
			coverage == StepsHistoryCoverage.COMPLETE &&
			availability != StepsHistoryAvailability.DELETED
		) {
			HistoryProductState.READY
		} else {
			HistoryProductState.PARTIAL
		}
	}

private fun StepsHistoryCoverage.toPublicCoverage(): ApiStepsHistoryCoverage =
	when (this) {
		StepsHistoryCoverage.NONE -> ApiStepsHistoryCoverage.NONE
		StepsHistoryCoverage.COMPLETE -> ApiStepsHistoryCoverage.COMPLETE
		StepsHistoryCoverage.PARTIAL -> ApiStepsHistoryCoverage.PARTIAL
		StepsHistoryCoverage.UNKNOWN -> ApiStepsHistoryCoverage.UNKNOWN
	}

// Exhaustiveness is intentional: adding an internal selector failure cannot silently erase the
// stable product-facing explanation required by the history contract.
@Suppress("CyclomaticComplexMethod")
internal fun StepsHistoryReason.toPublicCause(): StepsHistoryCause = when (this) {
	StepsHistoryReason.SOURCE_NOT_CAPTURED -> StepsHistoryCause.SOURCE_NOT_CAPTURED
	StepsHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN -> StepsHistoryCause.CAPTURE_PARTIAL
	StepsHistoryReason.SERVICE_RUN_ACTIVE -> StepsHistoryCause.SESSION_STILL_ACTIVE
	StepsHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE,
	StepsHistoryReason.SERVICE_RUN_MISSING,
	StepsHistoryReason.SERVICE_RUN_MEMBERSHIP_MISMATCH,
	StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
	StepsHistoryReason.MANIFEST_MISSING,
	StepsHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH -> StepsHistoryCause.HISTORY_MEMBERSHIP_UNAVAILABLE
	StepsHistoryReason.MANIFEST_INTEGRITY_FAILED,
	StepsHistoryReason.SOURCE_POLICY_ATTRIBUTION_INVALID,
	StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED,
	StepsHistoryReason.COMPLETENESS_INVALID -> StepsHistoryCause.HISTORY_INTEGRITY_FAILED
	StepsHistoryReason.MIXED_WRITER_WITHIN_SERVICE_RUN,
	StepsHistoryReason.UNKNOWN_WRITER,
	StepsHistoryReason.CANDIDATE_PROVENANCE_INCOMPLETE,
	StepsHistoryReason.PRODUCT_LANE_MISSING,
	StepsHistoryReason.PRODUCT_LANE_INVALID,
	StepsHistoryReason.TARGET_BEFORE_LANE_ACTIVATION -> StepsHistoryCause.WRITER_PROVENANCE_INVALID
	StepsHistoryReason.LEGACY_UNATTRIBUTED,
	StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED,
	StepsHistoryReason.LEGACY_ZERO_UNVERIFIED,
	StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE ->
		StepsHistoryCause.LEGACY_UNVERIFIED
	StepsHistoryReason.PRODUCT_LANE_BEHIND -> StepsHistoryCause.MATERIALIZATION_BEHIND
	StepsHistoryReason.PRODUCT_LANE_CUTOFF_BEFORE_TARGET,
	StepsHistoryReason.PRODUCT_LANE_RETIRED_BEFORE_TARGET,
	StepsHistoryReason.TERMINAL_PROJECTION_FAILURE,
	StepsHistoryReason.BATCH_DEPENDENCY_OVERFLOW -> StepsHistoryCause.MATERIALIZATION_UNAVAILABLE
	StepsHistoryReason.COMPLETENESS_MISSING,
	StepsHistoryReason.APP_DRAIN_INCOMPLETE,
	StepsHistoryReason.STOP_INCOMPLETE -> StepsHistoryCause.ACQUISITION_INCOMPLETE
	StepsHistoryReason.UNRESOLVED_PROVIDER_SEQUENCE,
	StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE,
	StepsHistoryReason.RESET_GAP,
	StepsHistoryReason.PARTIAL_FACT -> StepsHistoryCause.PROVIDER_GAP
	StepsHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN -> StepsHistoryCause.FACTS_MISSING
	StepsHistoryReason.DELETED_FACTS -> StepsHistoryCause.DELETED
	StepsHistoryReason.OUTSIDE_RETAINED_FLOOR,
	StepsHistoryReason.RETENTION_CROSSES_SEGMENT,
	StepsHistoryReason.RETENTION_TRUNCATED_RUN -> StepsHistoryCause.RETENTION_LIMIT
	StepsHistoryReason.SOURCE_EVIDENCE_STATE_MISSING -> StepsHistoryCause.EVIDENCE_STATE_UNAVAILABLE
	StepsHistoryReason.STALE_COLLECTED_DATA_EPOCH -> StepsHistoryCause.PRIVACY_EPOCH_MISMATCH
	StepsHistoryReason.COUNT_OVERFLOW -> StepsHistoryCause.VALUE_OVERFLOW
}
