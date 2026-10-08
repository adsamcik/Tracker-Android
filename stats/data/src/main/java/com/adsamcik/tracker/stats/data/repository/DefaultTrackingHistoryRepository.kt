package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.TRACKING_HISTORY_INVALIDATION_TABLES
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
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
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupRequest
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupResult
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as ApiStepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.PagedTrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed, read-only history facade. */
@Singleton
internal class DefaultTrackingHistoryRepository private constructor(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
	private val logicalHistoryReader: LogicalTrackingHistoryReader,
	private val pressureSelector: PressureHistorySelector,
	private val pressurePageReader: PressureHistoryPageReader,
	private val sourceUnionReader: TrackingHistorySourceUnionReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val integrationObserver: TrackingHistoryIntegrationObserver =
		TrackingHistoryIntegrationObserver(),
	private val sourceFactLookupReader: RoomSourceFactLookupReader =
		RoomSourceFactLookupReader(database, ioDispatcher),
	private val rangeReader: UnifiedHistoryRangeReader? = null,
    private val savedLocationUpdates: SavedLocationLiveUpdateSignal = SavedLocationLiveUpdateSignal(),
) : PagedTrackingHistoryRepository {
	private val importedReader = ImportedStepsProductReader(database)
    private val savedLocationSummaries by lazy { SavedLocationSummaryReader(database, ioDispatcher) }
    private val liveLocationSummaries by lazy { SavedLocationLiveSummaryReader(database, ioDispatcher, savedLocationUpdates,
        savedLocationSummaries) }

    override suspend fun readSavedLocationSummary(selection: SavedLocationSummarySelection) =
        when (selection) {
            is SavedLocationSummarySelection.Session ->
                savedLocationSummaries.readSession(selection.segmentId)
            is SavedLocationSummarySelection.Window ->
                savedLocationSummaries.readWindow(selection.window)
        }

    override fun observeLiveSavedLocationSummary(segmentId: Long) = liveLocationSummaries.observeSession(segmentId)

	@Inject
	internal constructor(
		database: AppDatabase,
		stepsSelector: StepsSegmentHistorySelector,
		logicalHistoryReader: LogicalTrackingHistoryReader,
		pressureSelector: PressureHistorySelector,
		pressurePageReader: PressureHistoryPageReader,
		sourceUnionReader: TrackingHistorySourceUnionReader,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		bootClockDomainProvider: BootClockDomainProvider,
		rangeReader: UnifiedHistoryRangeReader,
		integrationObserver: TrackingHistoryIntegrationObserver =
			TrackingHistoryIntegrationObserver(),
        savedLocationUpdates: SavedLocationLiveUpdateSignal = SavedLocationLiveUpdateSignal(),
	) : this(
		database = database,
		stepsSelector = stepsSelector,
		logicalHistoryReader = logicalHistoryReader,
		pressureSelector = pressureSelector,
		pressurePageReader = pressurePageReader,
		sourceUnionReader = sourceUnionReader,
		ioDispatcher = ioDispatcher,
		integrationObserver = integrationObserver,
		rangeReader = rangeReader,
        savedLocationUpdates = savedLocationUpdates,
		sourceFactLookupReader = RoomSourceFactLookupReader(
			database,
			ioDispatcher,
			bootClockDomainProvider,
		),
	)

	override suspend fun readRange(request: HistoryRangeRequest): HistoryRangeRead =
		rangeReader?.read(request) ?: HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeRange(request: HistoryRangeRequest): Flow<HistoryRangeRead> =
		database.invalidationTracker.createFlow(*UnifiedHistoryRangeReader.invalidationTables.toTypedArray(),
			emitInitialState = true).mapLatest { readRange(request) }.distinctUntilChanged().flowOn(ioDispatcher)

	override fun pagingSource(range: HistoryRange):
		androidx.paging.PagingSource<com.adsamcik.tracker.stats.api.repository.HistoryContinuation,
			com.adsamcik.tracker.stats.api.repository.HistoryPagingItem> =
		UnifiedHistoryPagingSource(database, range, ::readRange)

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
			activityHistoryRepository,
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

	override suspend fun lookupSourceFact(
		request: SourceFactLookupRequest,
	): SourceFactLookupResult = withContext(ioDispatcher) {
		runCatching {
			database.withTransaction {
				// Deletion authority survives the payload and presentation cascade.
				when (val sourceLocal = sourceFactLookupReader.lookupInTransaction(request)) {
					is SourceFactLookupResult.Found ->
						sourceFactPresentationRejection(request) ?: sourceLocal
					else -> sourceLocal
				}
			}
		}.getOrElse { failure ->
			if (failure is CancellationException) throw failure
			SourceFactLookupResult.Failed(SourceProofReason.REPOSITORY_FAILURE)
		}
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
		return historyInvalidations().mapLatest { invalidatedTables ->
			if ("lifecycle_desired_action" in invalidatedTables) {
				integrationObserver.record(
					TrackingHistoryIntegrationCheckpoint
						.SOURCE_AWARE_LIFECYCLE_INVALIDATION_READ,
				)
			}
			integrationObserver.record(
				TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_STARTED,
			)
			val result = try {
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
					val importedRows = when (
						val imported = importedReader.recentSourceAwareInTransaction(limit)
					) {
						is ImportedStepsRecentRead.Ready -> imported.entries.map(
							HistoricalSourceAwarePageEntry::ImportedSteps,
						)
						is ImportedStepsRecentRead.Unavailable ->
							return@withTransaction SourceAwareHistoryPageQuery.Unavailable(
								reason = imported.reason.toSourceAwareUnavailableReason(),
								source = HistorySource.STEPS,
							)
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
			integrationObserver.record(
				TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_FINISHED,
			)
			result
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

	private suspend fun sourceFactPresentationRejection(
		request: SourceFactLookupRequest,
	): SourceFactLookupResult? {
		val scope = request.key.scope as? SourceProofScope.Run
			?: return SourceFactLookupResult.Failed(SourceProofReason.SCOPE_MISMATCH)
		val run = database.sourceSessionDao().serviceRun(scope.serviceRunId)
			?: return SourceFactLookupResult.NotFound()
		if (run.logicalTrackingId != scope.logicalTrackingId) {
			return SourceFactLookupResult.Failed(SourceProofReason.SCOPE_MISMATCH)
		}
		val segmentId = run.sessionSegmentId
			?: return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		val selected = selectLiveSessionInTransaction(segmentId)
		val history = when (val session = selected.session) {
			SessionHistoryQuery.NotFound -> return SourceFactLookupResult.NotFound()
			is SessionHistoryQuery.Unavailable -> return SourceFactLookupResult.Unavailable(
				when (session.reason) {
					TrackingHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_UNAVAILABLE ->
						SourceProofReason.IMMUTABLE_AUTHORITY_MISSING
					TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED ->
						SourceProofReason.PARTIAL_COMPLETENESS
					TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE,
					TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID,
					-> SourceProofReason.INTEGRITY_FAILURE
				},
			)
			is SessionHistoryQuery.Found -> session.history
		}
		val historySource = request.key.source.toHistorySource()
		val capture = history.capture as? HistoryCapture.Exact
			?: return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		val captured = capture.revisions.any { revision ->
			historySource in revision.capturedSources
		}
		if (!captured) return SourceFactLookupResult.NotFound(SourceProofReason.QUERY_MISS)
		if (request.key.source != TrackingSource.LOCATION &&
			historySource !in history.qualifiedSources
		) {
			return SourceFactLookupResult.NotFound(SourceProofReason.QUERY_MISS)
		}
		return null
	}

	private fun historyInvalidations() = database.invalidationTracker.createFlow(
		*TRACKING_HISTORY_INVALIDATION_TABLES.toTypedArray(),
		emitInitialState = true,
	)

	private companion object {
		const val MAX_RECENT_ENTRY_COUNT = 100
		const val MAX_RECENT_PRESSURE_ENTRY_COUNT = 64
	}
}

private fun TrackingSource.toHistorySource(): HistorySource = when (this) {
	TrackingSource.LOCATION -> HistorySource.LOCATION
	TrackingSource.ACTIVITY -> HistorySource.ACTIVITY
	TrackingSource.STEPS -> HistorySource.STEPS
	TrackingSource.PRESSURE -> HistorySource.PRESSURE
	TrackingSource.WIFI -> HistorySource.WIFI
	TrackingSource.CELL -> HistorySource.CELL
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

private fun ImportedStepsReadFailure.toSourceAwareUnavailableReason():
	SourceAwareHistoryPageUnavailableReason =
	when (this) {
		ImportedStepsReadFailure.DEPENDENCY_OVERFLOW ->
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
		ImportedStepsReadFailure.RETENTION,
		ImportedStepsReadFailure.DELETION,
		ImportedStepsReadFailure.INTEGRITY,
		ImportedStepsReadFailure.MISSING,
		-> SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
	}

private data class RecentProductPageRow(
	val entry: StepsAwareHistoryPageEntry,
	val startTimeMs: Long,
	val segmentId: Long,
)

private sealed interface HistoricalSourceAwarePageEntry {
	val recency: HistoricalSourceUnionRecency
	val logicalTrackingId: String?
	val nativePhysicalSegmentIds: List<Long>

	data class Existing(
		val entry: HistoricalStepsAwarePageEntry,
	) : HistoricalSourceAwarePageEntry {
		override val recency = HistoricalSourceUnionRecency.Native(
			entry.recencyStartTimeMs,
			entry.recencySegmentId,
		)
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
		override val recency = HistoricalSourceUnionRecency.Native(
			newest.startTime.raw,
			newest.segmentId,
		)
		override val nativePhysicalSegmentIds: List<Long> get() = emptyList()
	}

	data class SourceOnly(
		val history: HistoricalSourceUnionEntry,
	) : HistoricalSourceAwarePageEntry {
		override val recency: HistoricalSourceUnionRecency get() = history.recency
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

private val sourceAwarePageOrder = Comparator<HistoricalSourceAwarePageEntry> { left, right ->
	val leftRecency = left.recency
	val rightRecency = right.recency
	val startOrder = compareValues(rightRecency.startTimeMs, leftRecency.startTimeMs)
	if (startOrder != 0) {
		startOrder
	} else {
		when (leftRecency) {
			is HistoricalSourceUnionRecency.Native -> when (rightRecency) {
				is HistoricalSourceUnionRecency.Native ->
					compareValues(rightRecency.segmentId, leftRecency.segmentId)
				is HistoricalSourceUnionRecency.Imported -> -1
			}
			is HistoricalSourceUnionRecency.Imported -> when (rightRecency) {
				is HistoricalSourceUnionRecency.Native -> 1
				is HistoricalSourceUnionRecency.Imported ->
					importedHistoryRecencyOrder.compare(
						leftRecency.value,
						rightRecency.value,
					)
			}
		}
	}
}

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
