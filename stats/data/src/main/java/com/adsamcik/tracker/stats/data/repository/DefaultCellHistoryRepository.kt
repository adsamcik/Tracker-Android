package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeletedCapturedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.DeletedImportedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReadBudget
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReadUsage
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReader
import com.adsamcik.tracker.shared.base.database.ImportedCellProductScanContextFailure
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableCellOriginComparison
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.authenticateDeletedImportedCellSelectionInTransaction
import com.adsamcik.tracker.shared.base.database.authenticateDeletedCapturedCellSelectionInTransaction
import com.adsamcik.tracker.shared.base.database.dao.CellLogicalHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntrySelection
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryPage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeContinuation
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeUnavailableReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDay
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDayCompleteness
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local Cell facade. Every dependency for a call is read in one bounded Room transaction. */
internal class DefaultCellHistoryRepository internal constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val ioDispatcher: CoroutineDispatcher,
	private val sharedHistoryPageCheckpoint: suspend (completedPageCount: Int) -> Unit,
) : CellHistoryRepository, CellImportedHistoryEligibleReader {
	@Inject
	constructor(
		database: AppDatabase,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		database,
		laneExecutionAuthority,
		ioDispatcher,
		{ currentCoroutineContext().ensureActive() },
	)

	private val importedProductReader = ImportedCellProductReader(database)
	private val rangeContinuationIssuer = Any()

	override suspend fun detail(selection: CellHistoryEntrySelection): CellHistoryQuery =
		when (selection) {
			is LocalCellHistorySelection -> local(selection)
			is ImportedCellHistorySelection -> imported(selection)
			else -> CellHistoryQuery.NotFound
		}

	private suspend fun local(selection: LocalCellHistorySelection): CellHistoryQuery =
		withContext(ioDispatcher) {
			database.withTransaction {
				val logicalIds = database.importedCellDao().liveLogicalTrackingIds(
					ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1,
				)
				if (logicalIds.size > ImportedCellDao.MAX_LIVE_OWNER_ROWS) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(selection, CellHistoryCause.READ_BUDGET_EXCEEDED),
					)
				}
				val matching = logicalIds.filter { logicalId ->
					PortableCellOpaqueIdentity.derive(
						PortableCellIdentityKind.LOGICAL_ENTRY,
						logicalId,
					).value == selection.identity.value
				}
				if (matching.isEmpty()) return@withTransaction CellHistoryQuery.NotFound
				if (matching.size != 1) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(selection, CellHistoryCause.ORIGIN_IDENTITY_CONFLICT),
					)
				}
				val logicalId = matching.single()
				val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
					listOf(logicalId),
					MAX_LOGICAL_MEMBERS + 1,
					null,
					null,
					null,
				)
				if (runs.size > MAX_LOGICAL_MEMBERS) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(selection, CellHistoryCause.READ_BUDGET_EXCEEDED),
					)
				}
				val selectedRun = runs.maxWithOrNull(
					compareBy(SourceServiceRunEntity::startedAtMs, SourceServiceRunEntity::serviceRunId),
				) ?: return@withTransaction CellHistoryQuery.Found(
					unverifiableLocalSelection(
						selection,
						CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
					),
				)
				val segmentId = selectedRun.sessionSegmentId
					?: return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(
							selection,
							CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
						),
					)
				val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
					?: return@withTransaction when (
						val deleted = try {
							database.authenticateDeletedCapturedCellSelectionInTransaction(
								logicalId,
								PortableCellOpaqueIdentity(selection.identity.value),
							)
						} catch (_: RuntimeException) {
							DeletedCapturedCellSelectionAuthentication.Unverifiable
						}
					) {
						DeletedCapturedCellSelectionAuthentication.Absent ->
							CellHistoryQuery.Found(unverifiableLocalSelection(
								selection,
								CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
							))
						DeletedCapturedCellSelectionAuthentication.Unverifiable ->
							CellHistoryQuery.Found(unverifiableLocalSelection(
								selection,
								CellHistoryCause.FACT_INTEGRITY_FAILED,
							))
						is DeletedCapturedCellSelectionAuthentication.Exact ->
							CellHistoryQuery.Found(deletedLocalSelection(selection, deleted.receipt))
					}
				if (seed.id != segmentId || seed.logicalTrackingId != logicalId ||
					seed.serviceRunId != selectedRun.serviceRunId
				) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(
							selection,
							CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
						),
					)
				}
				val snapshot = loadSnapshot(expandMembership(listOf(seed)))
				val entry = CellHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
					?: return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(
							selection,
							CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
						),
					)
				if (entry.selection != selection) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(
							selection,
							CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
						),
					)
				}
				CellHistoryQuery.Found(entry)
			}
		}

	override suspend fun session(segmentId: Long): CellHistoryQuery {
		require(segmentId > 0L)
		return withContext(ioDispatcher) {
			database.withTransaction { sessionInTransaction(segmentId) }
		}
	}

	internal suspend fun sessionInTransaction(segmentId: Long): CellHistoryQuery {
		require(segmentId > 0L)
		val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
			?: return CellHistoryQuery.NotFound
		val snapshot = loadSnapshot(expandMembership(listOf(seed)))
		return CellHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
			?.let(CellHistoryQuery::Found) ?: CellHistoryQuery.NotFound
	}

	override suspend fun imported(selection: ImportedCellHistorySelection): CellHistoryQuery =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					val evaluation = importedProductReader.selectIdentityInTransaction(
						PortableCellOpaqueIdentity(selection.identity.value),
					) ?: return@withTransaction when (val deleted =
						database.authenticateDeletedImportedCellSelectionInTransaction(
							PortableCellOpaqueIdentity(selection.identity.value),
							selection.importRevision,
							com.adsamcik.tracker.shared.base.database.PortableCellDigest(
								selection.contentChecksum.value,
							),
						)
					) {
						DeletedImportedCellSelectionAuthentication.Absent -> CellHistoryQuery.NotFound
						is DeletedImportedCellSelectionAuthentication.Exact ->
							CellHistoryQuery.Found(deletedImportedSelection(
								selection,
								deleted.receipt.startTimeMs,
								deleted.receipt.endTimeMs,
							))
						DeletedImportedCellSelectionAuthentication.Stale ->
							CellHistoryQuery.Found(unverifiableImportedSelection(
								selection,
								CellHistoryCause.IMPORTED_SELECTION_STALE,
							))
						DeletedImportedCellSelectionAuthentication.Unverifiable ->
							CellHistoryQuery.Found(unverifiableImportedSelection(
								selection,
								CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
							))
					}
					if (evaluation.candidate.importRevision != selection.importRevision ||
						evaluation.candidate.contentChecksum != selection.contentChecksum.value
					) {
						return@withTransaction CellHistoryQuery.Found(
							unverifiableImportedSelection(
								selection,
								CellHistoryCause.IMPORTED_SELECTION_STALE,
							),
						)
					}
					val originConflict = when (evaluation) {
						is ImportedCellProductEvaluation.Readable ->
							evaluation.localOriginHandle?.let { _ ->
								when (val local = importedProductReader.readLocalOriginInTransaction(
									evaluation,
									laneExecutionAuthority,
								)) {
									is ReadLocalPortableCapturedCellResult.Ready ->
										local.entry != evaluation.entry
									is ReadLocalPortableCapturedCellResult.Outcome -> true
									null -> false
								}
							} == true
						is ImportedCellProductEvaluation.Unverifiable ->
							evaluation.localOriginHandle != null
					}
					CellHistoryQuery.Found(
						evaluation.toPublicCellEntry(
							overrideFailure = CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
								.takeIf { originConflict },
						),
					)
				}
			} catch (cancelled: kotlinx.coroutines.CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				CellHistoryQuery.Found(
					unverifiableImportedSelection(
						selection,
						CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
					),
				)
			}
		}

	override suspend fun recent(limit: Int): CellHistoryPage {
		require(limit in 1..MAX_RESULTS)
		return withContext(ioDispatcher) {
			database.withTransaction { composeRecent(limit) }
		}
	}

	/** Exact local Cell groups for one bounded caller-owned physical candidate generation. */
	internal suspend fun selectBySegmentIdsInTransaction(
		segmentIds: List<Long>,
	): CellComposedPage {
		require(segmentIds.size <= MAX_RESULTS)
		require(segmentIds.all { it > 0L } && segmentIds.distinct().size == segmentIds.size)
		if (segmentIds.isEmpty()) return CellComposedPage.Available(emptyList())
		val seeds = database.trackingHistoryReadDao().segments(segmentIds)
		val expansion = expandMembership(seeds)
		val snapshot = loadSnapshot(expansion)
		if (snapshot.overflow) return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
		return CellComposedPage.Available(
			CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in logicalIds }
				.sortedWith(cellCompositionOrder),
		)
	}

	/** Intent-first local entries whose complete authenticated capture set is exactly Cell. */
	internal suspend fun recentCellOnlyInTransaction(limit: Int): CellComposedPage {
		require(limit in 1..MAX_RESULTS)
		return loadRecentCellCompositions(limit) { it.capturesOnlyCell }
	}

	/**
	 * One caller-owned transaction producing local physical groups and explicit imported rows.
	 * Exact authenticated full-v1 duplicates collapse without discarding local physical ownership.
	 */
	internal suspend fun recentCellHistoryInTransaction(limit: Int): CellSourceComposedPage {
		require(limit in 1..MAX_RESULTS)
		val local = when (val page = loadRecentCellCompositions(limit) { true }) {
			is CellComposedPage.Failed -> return CellSourceComposedPage.Failed(page.cause)
			is CellComposedPage.Available -> page.entries
		}
		local.asSequence()
			.flatMap { it.entry.causes.asSequence() }
			.firstOrNull { it.isIntegrityFailure }
			?.let { cause -> return CellSourceComposedPage.Failed(cause) }
		val imported = try {
			importedProductReader.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellSourceComposedPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		if (imported.any {
			it is ImportedCellProductEvaluation.Unverifiable &&
				it.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW
			}
		) return CellSourceComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		imported.filterIsInstance<ImportedCellProductEvaluation.Unverifiable>()
			.firstOrNull()
			?.let { unavailable ->
				return CellSourceComposedPage.Failed(unavailable.toSourceFailure())
			}
		if (local.isEmpty() && imported.isEmpty()) {
			return CellSourceComposedPage.Available(emptyList())
		}
		val directLocalCollisionIds = imported.filter { it.localOriginHandle != null }
			.mapTo(linkedSetOf()) { it.candidate.identity }
		val localCollisions = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }
			.associateBy { it.candidate.identity }
		if (localCollisions.size > MAX_RECENT_LOCAL_ORIGIN_COMPARISONS) {
			return CellSourceComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val localPortableEntries = try {
			loadLocalPortableCollisions(localCollisions)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellSourceComposedPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val visibleLocalEntryIdentities = local.mapTo(linkedSetOf()) { composed ->
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				composed.logicalTrackingId,
			).value
		}
		val localBySelection = local.mapNotNull { composed ->
			composed.entry.selection?.let { selection -> selection to composed }
		}.toMap()
		if (localBySelection.size != local.size) {
			return CellSourceComposedPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val importedRecencyBySelection =
			linkedMapOf<ImportedCellHistorySelection, CellSourceRecency>()
		for (evaluation in imported) {
			val readable = evaluation as? ImportedCellProductEvaluation.Readable
				?: return CellSourceComposedPage.Failed(
					CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
				)
			val newest = readable.entry.runs.maxWithOrNull(
				compareBy<PortableCapturedCellRunV1>(
					PortableCapturedCellRunV1::startTimeMs,
					{ it.identity.value },
				),
			) ?: return CellSourceComposedPage.Failed(
				CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
			val selection = ImportedCellHistorySelection(
				identity = ImportedCellHistoryIdentity(readable.candidate.identity),
				importRevision = readable.candidate.importRevision,
				contentChecksum = ImportedCellHistoryDigest(readable.candidate.contentChecksum),
			)
			if (importedRecencyBySelection.put(
					selection,
					CellSourceRecency(
						memberStartTimeMs = newest.startTimeMs,
						tieIdentity = newest.identity,
					),
				) != null
			) {
				return CellSourceComposedPage.Failed(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
			}
		}
		val combined = try {
			CellHistoryOriginComposer.compose(
				live = local.map(ComposedCellEntry::entry),
				visibleLocalEntryIdentities = visibleLocalEntryIdentities,
				localCollisionIdentities = directLocalCollisionIds,
				imported = imported,
				localPortableEntriesByIdentity = localPortableEntries,
				limit = local.size + imported.size,
			).map { entry ->
				when (entry.origin) {
					CellHistoryOrigin.Local -> CellSourceComposedEntry.Local(
						localBySelection[entry.selection]
							?: throw ImportedCellHistoryCompositionFailure(),
					)
					is CellHistoryOrigin.Imported -> CellSourceComposedEntry.Imported(
						entry = entry,
						selection = entry.origin.selection,
						recency = importedRecencyBySelection[entry.origin.selection]
							?: throw ImportedCellHistoryCompositionFailure(),
					)
				}
			}
		} catch (_: ImportedCellHistoryCompositionFailure) {
			return CellSourceComposedPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val selected = combined.sortedWith(cellSourceCompositionOrder)
			.take(limit)
		return CellSourceComposedPage.Available(selected)
	}

	override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<CellImportedHistoryEligibleEntry> {
		require(limit in 1..MAX_RESULTS)
		val accepted = mutableListOf<CellImportedHistoryEligibleEntry>()
		var scanned = 0
		var localOriginComparisons = 0
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		var remainingReadBudget = ImportedCellProductReadBudget.sharedHistory()
		val scanContext = try {
			importedProductReader.openRecentScanContextInTransaction()
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (failure: ImportedCellProductScanContextFailure) {
			return importedEligibleUnavailable(
				if (failure.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW) {
					SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
				} else {
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
				},
			)
		} catch (_: RuntimeException) {
			return importedEligibleUnavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		}
		var completedPageCount = 0
		while (true) {
			currentCoroutineContext().ensureActive()
			sharedHistoryPageCheckpoint(completedPageCount)
			val remaining = MAX_SHARED_HISTORY_IMPORTED_CANDIDATES - scanned
			if (remaining == 0) {
				val hasMore = try {
					importedProductReader.hasRecentCandidateInTransaction(
						beforeStartTimeMs,
						beforeIdentity,
					)
				} catch (cancelled: kotlinx.coroutines.CancellationException) {
					throw cancelled
				} catch (_: RuntimeException) {
					return importedEligibleUnavailable(
						SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
					)
				}
				if (hasMore) {
					return importedEligibleUnavailable(
						SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
					)
				}
				break
			}
			val pageLimit = minOf(SHARED_HISTORY_IMPORTED_PAGE_SIZE, remaining)
			val page = try {
				importedProductReader.selectRecentPageInTransaction(
					context = scanContext,
					budget = remainingReadBudget,
					limit = pageLimit,
					beforeStartTimeMs = beforeStartTimeMs,
					beforeIdentity = beforeIdentity,
				)
			} catch (cancelled: kotlinx.coroutines.CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				return importedEligibleUnavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				)
			}
			val evaluations = page.evaluations
			if (evaluations.isEmpty()) break
			remainingReadBudget = remainingReadBudget.consume(page.usage)
				?: return importedEligibleUnavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
				)
			scanned += evaluations.size
			for (evaluation in evaluations) {
				currentCoroutineContext().ensureActive()
				val readable = when (evaluation) {
					is ImportedCellProductEvaluation.Readable -> evaluation
					is ImportedCellProductEvaluation.Unverifiable -> {
						return importedEligibleUnavailable(
							when (evaluation.reason) {
								ImportedCellProductFailure.DEPENDENCY_OVERFLOW ->
									SourceAwareHistoryPageUnavailableReason
										.SOURCE_READ_BUDGET_EXCEEDED
								else -> SourceAwareHistoryPageUnavailableReason
									.SOURCE_INTEGRITY_FAILURE
							},
						)
					}
				}
				val newest = readable.entry.runs.maxWithOrNull(
					compareBy<PortableCapturedCellRunV1>(
						PortableCapturedCellRunV1::startTimeMs,
						{ it.identity.value },
					),
				) ?: return importedEligibleUnavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
				)
				if (newest.startTimeMs !in readable.entry.startTimeMs..readable.entry.endTimeMs) {
					return importedEligibleUnavailable(
						SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
					)
				}
				var originConflict = false
				if (readable.localOriginHandle != null) {
					localOriginComparisons += 1
					if (localOriginComparisons > MAX_SHARED_HISTORY_LOCAL_ORIGIN_COMPARISONS) {
						return importedEligibleUnavailable(
							SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
						)
					}
					val local = try {
						importedProductReader.readLocalOriginInTransaction(
							readable,
							laneExecutionAuthority,
						)
					} catch (cancelled: kotlinx.coroutines.CancellationException) {
						throw cancelled
					} catch (_: RuntimeException) {
						return importedEligibleUnavailable(
							SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
						)
					}
					when (local) {
						is ReadLocalPortableCapturedCellResult.Ready -> {
							val exact = PortableCellOriginComparison.areExactFullV1Duplicates(
								local.entry,
								readable.entry,
							)
							if (exact && readable.isReExportable) continue
							originConflict = !exact
						}
						is ReadLocalPortableCapturedCellResult.Outcome -> originConflict = true
						null -> return importedEligibleUnavailable(
							SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
						)
					}
				}
				val entry = try {
					readable.toPublicCellEntry(
						overrideFailure = CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
							.takeIf { originConflict },
					)
				} catch (_: RuntimeException) {
					return importedEligibleUnavailable(
						SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
					)
				}
				val selection = ImportedCellHistorySelection(
					identity = ImportedCellHistoryIdentity(readable.candidate.identity),
					importRevision = readable.candidate.importRevision,
					contentChecksum = ImportedCellHistoryDigest(readable.candidate.contentChecksum),
				)
				remainingReadBudget = remainingReadBudget.consume(
					ImportedCellProductReadUsage(
						revisionRows = 0,
						receiptRows = 0,
						runRows = 0,
						observationRows = 0,
						publicObservationRows = entry.observations.size,
						textBytes = 0L,
					),
				) ?: return importedEligibleUnavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
				)
				val carrier = try {
					CellImportedHistoryEligibleEntry(
						entry = entry,
						selection = selection,
						recency = ImportedHistoryRecency(
							source = HistorySource.CELL,
							newestMemberStartTimeMs = newest.startTimeMs,
							newestMemberTieIdentity = ImportedHistoryRecencyTieIdentity(
								newest.identity.value,
							),
						),
					)
				} catch (_: IllegalArgumentException) {
					return importedEligibleUnavailable(
						SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
					)
				}
				accepted += carrier
			}
			accepted.sortWith { left, right ->
				importedHistoryRecencyOrder.compare(left.recency, right.recency)
			}
			if (accepted.size > limit) {
				accepted.subList(limit, accepted.size).clear()
			}
			val last = evaluations.last().candidate
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			completedPageCount += 1
			if (evaluations.size < pageLimit) break
		}
		return ImportedHistoryEligiblePage.Available(
			accepted.toList(),
		)
	}

	@Suppress("CyclomaticComplexMethod")
	private suspend fun loadRecentCellCompositions(
		limit: Int,
		accept: (ComposedCellEntry) -> Boolean,
	): CellComposedPage {
		val accepted = mutableListOf<ComposedCellEntry>()
		var scanned = 0
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		while (accepted.size < limit) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_CANDIDATE_SCAN - scanned
			if (remaining == 0) {
				return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, remaining)
			val page = database.cellCapturedFactDao().logicalHistoryCandidatePageInWallRange(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				SourceDestinationOwnerEntity.SOURCE_CELL,
				0L,
				Long.MAX_VALUE,
				pageLimit,
				beforeStartTimeMs,
				beforeSegmentId,
			)
			if (page.isEmpty()) break
			if (page.size > pageLimit ||
				!page.hasValidRangeKeyset(beforeStartTimeMs, beforeSegmentId)
			) return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			scanned += page.size
			val seeds = page.map { it.segment }
			val snapshot = loadSnapshot(expandMembership(seeds))
			if (snapshot.overflow) {
				return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val candidateIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			accepted += CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in candidateIds && accept(it) }
				.sortedWith(cellCompositionOrder)
			val last = page.last()
			beforeStartTimeMs = last.logicalRecencyStartMs
			beforeSegmentId = last.logicalRecencySegmentId
			if (page.size < pageLimit) break
		}
		return CellComposedPage.Available(accepted.sortedWith(cellCompositionOrder).take(limit))
	}

	override suspend fun range(request: CellHistoryRangeRequest): CellHistoryRangePage {
		val continuation = request.continuation
		if (continuation != null) {
			if (continuation !is CellHistoryRangeContinuationSnapshot ||
				continuation.scope != request.scope ||
				continuation.issuer !== rangeContinuationIssuer
			) {
				return CellHistoryRangePage.Unavailable(
					CellHistoryRangeUnavailableReason.INVALID_CONTINUATION,
				)
			}
			return continuation.remaining.toRangePage(
				request.scope,
				request.limit,
				rangeContinuationIssuer,
			)
		}
		return withContext(ioDispatcher) {
			val result = database.withTransaction { composeRange(request.scope) }
			when (result) {
				is CellHistoryRangeBuild.Failed -> CellHistoryRangePage.Failed(result.cause)
				is CellHistoryRangeBuild.Ready ->
					result.entries.toRangePage(
						request.scope,
						request.limit,
						rangeContinuationIssuer,
					)
			}
		}
	}

	private suspend fun composeRange(scope: CellHistoryRangeScope): CellHistoryRangeBuild {
		val bounds = try {
			scope.queryBounds()
		} catch (_: ArithmeticException) {
			return CellHistoryRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val live = when (val result = loadLocalRange(bounds)) {
			is LocalCellRangeBuild.Failed -> return CellHistoryRangeBuild.Failed(result.cause)
			is LocalCellRangeBuild.Ready -> result.entries
		}
		val imported = try {
			when (scope) {
				is CellHistoryRangeScope.WallTime ->
					importedProductReader.selectWallRangeInTransaction(
						bounds.fromInclusiveMs,
						bounds.toExclusiveMs,
						MAX_RANGE_CANDIDATES_PER_ORIGIN,
					)
				is CellHistoryRangeScope.StructuralDays ->
					importedProductReader.selectStructuralRangeInTransaction(
						bounds.fromInclusiveMs,
						bounds.toExclusiveMs,
						MAX_RANGE_CANDIDATES_PER_ORIGIN,
					)
			}
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellHistoryRangeBuild.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		if (imported.any {
				it is ImportedCellProductEvaluation.Unverifiable &&
					it.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW
			}
		) return CellHistoryRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		val importedStructuralMemberships = if (scope is CellHistoryRangeScope.StructuralDays) {
			val memberships = linkedMapOf<String, ImportedCellStructuralMembership?>()
			for (evaluation in imported) {
				when (evaluation) {
					is ImportedCellProductEvaluation.Unverifiable ->
						return CellHistoryRangeBuild.Failed(
							CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
						)
					is ImportedCellProductEvaluation.Readable -> {
						val membership = try {
							evaluation.structuralMembership(scope)
						} catch (_: RuntimeException) {
							return CellHistoryRangeBuild.Failed(
								CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
							)
						}
						memberships[evaluation.candidate.identity] = membership
					}
				}
			}
			memberships
		} else {
			emptyMap()
		}

		val directLocalCollisionIds = imported.filter { it.localOriginHandle != null }
			.mapTo(linkedSetOf()) { it.candidate.identity }
		val localCollisions = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }
			.associateBy { it.candidate.identity }
		val localPortableEntries = try {
			loadLocalPortableCollisions(localCollisions)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellHistoryRangeBuild.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val visibleLocalEntryIdentities = live.mapNotNull { composed ->
			(composed.entry.selection as? LocalCellHistorySelection)?.identity?.value
		}.toSet()
		val composed = try {
			if (live.isEmpty() && imported.isEmpty()) {
				emptyList()
			} else {
				CellHistoryOriginComposer.compose(
					live = live.map(ComposedCellEntry::entry),
					visibleLocalEntryIdentities = visibleLocalEntryIdentities,
					localCollisionIdentities = directLocalCollisionIds,
					imported = imported,
					localPortableEntriesByIdentity = localPortableEntries,
					limit = live.size + imported.size,
				)
			}
		} catch (_: ImportedCellHistoryCompositionFailure) {
			return CellHistoryRangeBuild.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		return CellHistoryRangeBuild.Ready(
			composed.mapNotNull { entry ->
				entry.toRangeEntry(scope, importedStructuralMemberships)
			},
		)
	}

	private suspend fun loadLocalRange(bounds: CellRangeQueryBounds): LocalCellRangeBuild {
		val accepted = linkedMapOf<String, ComposedCellEntry>()
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		var candidateCount = 0
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_RANGE_CANDIDATES_PER_ORIGIN - candidateCount
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, remaining + 1)
			val candidates = database.cellCapturedFactDao().logicalHistoryCandidatePageInWallRange(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				SourceDestinationOwnerEntity.SOURCE_CELL,
				bounds.fromInclusiveMs,
				bounds.toExclusiveMs,
				pageLimit,
				beforeStartTimeMs,
				beforeSegmentId,
			)
			if (candidates.isEmpty()) break
			if (!candidates.hasValidRangeKeyset(beforeStartTimeMs, beforeSegmentId) ||
				candidates.size > remaining
			) return LocalCellRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			candidateCount += candidates.size
			val seeds = candidates.map { it.segment }
			val snapshot = loadSnapshot(expandMembership(seeds))
			if (snapshot.overflow) {
				return LocalCellRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val requested = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in requested }
				.forEach { accepted.putIfAbsent(it.logicalTrackingId, it) }
			val last = candidates.last()
			beforeStartTimeMs = last.logicalRecencyStartMs
			beforeSegmentId = last.logicalRecencySegmentId
			if (candidates.size < pageLimit) break
		}
		return LocalCellRangeBuild.Ready(accepted.values.toList())
	}

	private suspend fun composeRecent(limit: Int): CellHistoryPage {
		val live = loadRecentLive(limit)
		if (live.page is CellHistoryPage.Failed) return live.page
		val imported = try {
			importedProductReader.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val directLocalCollisionIds = imported.filter { it.localOriginHandle != null }
			.mapTo(linkedSetOf()) { it.candidate.identity }
		val localCollisions = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }
			.associateBy { it.candidate.identity }
		if (localCollisions.size > MAX_RECENT_LOCAL_ORIGIN_COMPARISONS) {
			return CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val localPortableEntries = try {
			loadLocalPortableCollisions(localCollisions)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val visibleLocalEntryIdentities = live.logicalTrackingIds.mapTo(linkedSetOf()) { logicalId ->
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
		}
		return try {
			CellHistoryPage.Available(
				CellHistoryOriginComposer.compose(
					live = (live.page as CellHistoryPage.Available).entries,
					visibleLocalEntryIdentities = visibleLocalEntryIdentities,
					localCollisionIdentities = directLocalCollisionIds,
					imported = imported,
					localPortableEntriesByIdentity = localPortableEntries,
					limit = limit,
				),
			)
		} catch (_: ImportedCellHistoryCompositionFailure) {
			CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private suspend fun loadLocalPortableCollisions(
		evaluationsByEntryIdentity: Map<String, ImportedCellProductEvaluation.Readable>,
	): Map<String, PortableCapturedCellEntryV1> {
		if (evaluationsByEntryIdentity.isEmpty()) return emptyMap()
		require(evaluationsByEntryIdentity.size <= MAX_RANGE_CANDIDATES_PER_ORIGIN)
		val entries = linkedMapOf<String, PortableCapturedCellEntryV1>()
		for ((entryIdentity, evaluation) in evaluationsByEntryIdentity) {
			currentCoroutineContext().ensureActive()
			when (val result = importedProductReader.readLocalOriginInTransaction(
				evaluation,
				laneExecutionAuthority,
			)) {
				is ReadLocalPortableCapturedCellResult.Ready -> {
					if (result.entry.identity.value != entryIdentity ||
						entries.put(entryIdentity, result.entry) != null
					) throw ImportedCellHistoryCompositionFailure()
				}
				is ReadLocalPortableCapturedCellResult.Outcome -> Unit
				null -> throw ImportedCellHistoryCompositionFailure()
			}
		}
		return entries
	}

	private suspend fun loadRecentLive(limit: Int): LiveCellHistoryPage {
		val accepted = linkedMapOf<String, ComposedCellEntry>()
		var beforeStart: Long? = null
		var beforeId: Long? = null
		var scanned = 0
		while (accepted.size < limit && scanned < MAX_CANDIDATE_SCAN) {
			currentCoroutineContext().ensureActive()
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, MAX_CANDIDATE_SCAN - scanned)
			val candidates = database.cellCapturedFactDao().logicalHistoryCandidatePage(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				pageLimit,
				beforeStart,
				beforeId,
			)
			if (candidates.isEmpty()) break
			scanned += candidates.size
			val seeds = candidates.map { it.segment }
			val snapshot = loadSnapshot(expandMembership(seeds))
			if (snapshot.overflow) {
				return LiveCellHistoryPage(
					CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED),
					emptySet(),
				)
			}
			val requested = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in requested }
				.forEach { accepted.putIfAbsent(it.logicalTrackingId, it) }
			val last = candidates.last()
			beforeStart = last.logicalRecencyStartMs
			beforeId = last.logicalRecencySegmentId
			if (candidates.size < pageLimit) break
		}
		if (accepted.size < limit && scanned >= MAX_CANDIDATE_SCAN) {
			return LiveCellHistoryPage(
				CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED),
				emptySet(),
			)
		}
		val selected = accepted.values.sortedWith(
			compareByDescending<ComposedCellEntry> { it.recencyStartTimeMs }
				.thenByDescending { it.recencySegmentId },
		).take(limit)
		return LiveCellHistoryPage(
			CellHistoryPage.Available(selected.map(ComposedCellEntry::entry)),
			selected.mapTo(linkedSetOf(), ComposedCellEntry::logicalTrackingId),
		)
	}

	private suspend fun expandMembership(seeds: List<SessionSegment>): CellMembershipExpansion {
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank).distinct()
		if (logicalIds.isEmpty()) return CellMembershipExpansion(seeds, emptyMap())
		val runs = mutableListOf<SourceServiceRunEntity>()
		var cursor: ServiceRunCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_LOGICAL_MEMBERS - runs.size
			val pageLimit = minOf(MEMBER_PAGE_SIZE, remaining + 1)
			val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
				logicalIds, pageLimit, cursor?.logicalId, cursor?.startedAtMs, cursor?.serviceRunId,
			)
			if (page.size > remaining) {
				return CellMembershipExpansion(seeds,
					logicalIds.associateWith { CellHistoryCause.READ_BUDGET_EXCEEDED }, overflow = true)
			}
			if (page.isEmpty()) break
			val next = ServiceRunCursor(page.last())
			check(cursor == null || next > cursor) { "Cell member cursor did not advance" }
			runs += page
			cursor = next
			if (page.size < pageLimit) break
		}
		currentCoroutineContext().ensureActive()
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val loaded = if (segmentIds.isEmpty()) emptyList() else database.trackingHistoryReadDao().segments(segmentIds)
		val segments = (loaded + seeds).distinctBy(SessionSegment::id)
		val byId = segments.associateBy(SessionSegment::id)
		val failures = linkedMapOf<String, CellHistoryCause>()
		logicalIds.forEach { logical ->
			if (runs.none { it.logicalTrackingId == logical }) {
				failures[logical] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		runs.forEach { run ->
			val segment = run.sessionSegmentId?.let(byId::get)
			if (segment == null || segment.logicalTrackingId != run.logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId
			) failures[run.logicalTrackingId] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
		}
		seeds.forEach { seed ->
			val logical = seed.logicalTrackingId ?: return@forEach
			val run = runs.singleOrNull { it.serviceRunId == seed.serviceRunId }
			if (run == null || run.logicalTrackingId != logical || run.sessionSegmentId != seed.id) {
				failures[logical] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		return CellMembershipExpansion(segments, failures)
	}

	@Suppress("LongMethod")
	private suspend fun loadSnapshot(expansion: CellMembershipExpansion): CellHistorySnapshot {
		if (expansion.overflow) return CellHistorySnapshot.empty(expansion).copy(overflow = true)
		val segments = expansion.segments
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId).filter(String::isNotBlank).distinct()
		if (runIds.isEmpty()) return CellHistorySnapshot.empty(expansion)
		val logicalIds = segments.mapNotNull(SessionSegment::logicalTrackingId).filter(String::isNotBlank).distinct()
		val readDao = database.trackingHistoryReadDao()
		val factDao = database.cellCapturedFactDao()
		val runs = readDao.serviceRuns(runIds)
		val manifests = readDao.manifests(runIds, MAX_MANIFESTS + 1)
		val sources = readDao.manifestSources(runIds, MAX_MANIFEST_SOURCES + 1)
		val policies = readDao.policiesForServiceRuns(CELL_SOURCE, runIds)
		val completeness = readDao.completeness(runIds, MAX_COMPLETENESS + 1)
		val sessions = database.sourceSessionDao().sessions(logicalIds)
		val factLoad = loadFactPages(runIds, logicalIds)
		val revisions = factLoad.revisions
		currentCoroutineContext().ensureActive()
		val scopedCursors = factDao.historyCursorsForScopes(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			runIds,
			logicalIds,
			MAX_CURSORS + 1,
		)
		val factIds = revisions.map(CellCapturedFactRevisionEntity::logicalFactId).distinct()
		val referencedCursors = factIds.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyCursors(ids, MAX_CURSORS + 1)
		}
		val cursors = (scopedCursors + referencedCursors).distinctBy { cursor ->
			Triple(cursor.writerProjectionId, cursor.writerProjectionVersion, cursor.logicalFactId)
		}
		val generations = factDao.historyDeletionGenerations(logicalIds, runIds, MAX_DELETION_GENERATIONS + 1)
		val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
		val planHeaders = if (planRevisions.isEmpty()) emptyList() else
			factDao.historyAcquisitionPlanRevisions(planRevisions, MAX_PLANS + 1)
		val desiredPlans = if (planRevisions.isEmpty()) emptyList() else
			factDao.historyDesiredPlans(CELL_SOURCE, planRevisions, MAX_PLANS + 1)
		val registrationGenerations = revisions.map(CellCapturedFactRevisionEntity::registrationGeneration).distinct()
		val providers = registrationGenerations.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyProviderRegistrations(CELL_SOURCE, ids, MAX_PROVIDERS + 1)
		}
		val authorizations = registrationGenerations.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyAuthorizations(CELL_SOURCE, ids, MAX_AUTHORIZATIONS + 1)
		}
		val demandIds = authorizations.mapNotNull { it.demandId }.distinct()
		val demands = demandIds.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			database.sourceBrokerDao().demandsByIds(ids)
		}
		val consentEpochs = sources.filter(::isCellCaptureMembership)
			.map(SessionManifestSourceEntity::consentEpoch).distinct()
		val consents = if (consentEpochs.isEmpty()) emptyList() else database.sourcePolicyDao().consentEpochs(
			CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, consentEpochs,
		)
		val scopePairs = segments.mapNotNull { segment ->
			val logical = segment.logicalTrackingId?.takeIf(String::isNotBlank)
			val run = segment.serviceRunId?.takeIf(String::isNotBlank)
			if (logical == null || run == null) null else logical to run
		}
		val digestToPair = scopePairs.associateBy { (logical, run) ->
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, logical, run,
			)
		}
		val fences = if (digestToPair.isEmpty()) emptyList() else readDao.deletionFences(
			CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, digestToPair.keys.toList(),
		)
		val lanes = readDao.productLanesForServiceRuns(CELL_SOURCE,
			SessionManifestPurposeCode.SESSION_CAPTURE, runIds)
		val through = maxOfOrNull(
			revisions.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			completeness.filter { it.sourceKind == CELL_SOURCE }
				.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull(),
		)
		val after = lanes.minOfOrNull { it.activationOrdinal }?.minus(1L)
		val failures = if (after == null || through == null || after >= through) emptyList() else
			readDao.terminalFailuresForServiceRuns(CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE,
				runIds, after, through, MAX_TERMINAL_FAILURES + 1)
		val evidence = database.sourceEvidenceStateDao().get()
		currentCoroutineContext().ensureActive()
		val overflow = factLoad.overflow || runs.size > MAX_LOGICAL_MEMBERS ||
			sessions.size > MAX_SESSIONS || manifests.size > MAX_MANIFESTS ||
			sources.size > MAX_MANIFEST_SOURCES || completeness.size > MAX_COMPLETENESS ||
			policies.size > MAX_POLICIES || consents.size > MAX_CONSENTS ||
			cursors.size > MAX_CURSORS || generations.size > MAX_DELETION_GENERATIONS ||
			planHeaders.size > MAX_PLANS || desiredPlans.size > MAX_PLANS ||
			providers.size > MAX_PROVIDERS || authorizations.size > MAX_AUTHORIZATIONS ||
			demands.size > MAX_DEMANDS ||
			fences.size > MAX_DELETION_FENCES || lanes.size > MAX_LANES ||
			failures.size > MAX_TERMINAL_FAILURES
		return CellHistorySnapshot(
			expansion = expansion,
			sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			runs = runs.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifests.take(MAX_MANIFESTS).groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = sources.take(MAX_MANIFEST_SOURCES).groupBy {
				CellManifestKey(it.logicalTrackingId, it.manifestRevision)
			},
			policies = policies.associateBy(SourcePolicyEntity::policyRevision),
			consents = consents.associateBy(SourceConsentEpochEntity::epoch),
			completenessByRun = completeness.take(MAX_COMPLETENESS)
				.groupBy { it.serviceRunId },
			revisions = revisions.take(MAX_FACT_REVISIONS),
			cursors = cursors.take(MAX_CURSORS),
			deletionGenerations = generations.take(MAX_DELETION_GENERATIONS)
				.associateBy { it.logicalTrackingId to it.serviceRunId },
			deletedScopes = fences.mapNotNull { digestToPair[it.scopeIdentityDigest] }.toSet(),
			planHeaders = planHeaders.take(MAX_PLANS).associateBy { it.revision },
			desiredPlans = desiredPlans.take(MAX_PLANS).associateBy(SourceDesiredPlanEntity::revision),
			providerRegistrations = providers.take(MAX_PROVIDERS).associateBy { it.registrationGeneration },
			authorizationsByRegistration = authorizations.take(MAX_AUTHORIZATIONS)
				.groupBy { it.registrationGeneration },
			demands = demands.take(MAX_DEMANDS).associateBy(SourceDemandEntity::demandId),
			lanes = lanes,
			terminalFailures = failures.take(MAX_TERMINAL_FAILURES),
			evidenceState = evidence,
			overflow = overflow,
		)
	}

	private suspend fun loadFactPages(
		runIds: List<String>, logicalIds: List<String>,
	): CellFactLoad {
		val facts = mutableListOf<CellCapturedFactRevisionEntity>()
		var cursor: FactCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_FACT_REVISIONS - facts.size
			val pageLimit = minOf(FACT_PAGE_SIZE, remaining + 1)
			val page = database.cellCapturedFactDao().historyRevisionPage(
				runIds, logicalIds, pageLimit, cursor?.projectionId, cursor?.projectionVersion,
				cursor?.logicalFactId, cursor?.semanticRevision,
			)
			if (page.size > remaining) return CellFactLoad(facts, overflow = true)
			if (page.isEmpty()) break
			val next = FactCursor(page.last())
			check(cursor == null || next > cursor) { "Cell fact cursor did not advance" }
			facts += page
			cursor = next
			if (page.size < pageLimit) break
		}
		return CellFactLoad(facts, overflow = false)
	}

	private data class CellFactLoad(val revisions: List<CellCapturedFactRevisionEntity>, val overflow: Boolean)

	private data class LiveCellHistoryPage(
		val page: CellHistoryPage,
		val logicalTrackingIds: Set<String>,
	)

	private data class ServiceRunCursor(
		val logicalId: String, val startedAtMs: Long, val serviceRunId: String,
	) : Comparable<ServiceRunCursor> {
		constructor(run: SourceServiceRunEntity) : this(run.logicalTrackingId, run.startedAtMs, run.serviceRunId)
		override fun compareTo(other: ServiceRunCursor): Int = compareValuesBy(this, other,
			ServiceRunCursor::logicalId, ServiceRunCursor::startedAtMs, ServiceRunCursor::serviceRunId)
	}

	private data class FactCursor(
		val projectionId: String, val projectionVersion: Int, val logicalFactId: String,
		val semanticRevision: Long,
	) : Comparable<FactCursor> {
		constructor(fact: CellCapturedFactRevisionEntity) : this(fact.writerProjectionId,
			fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision)
		override fun compareTo(other: FactCursor): Int = compareValuesBy(this, other,
			FactCursor::projectionId, FactCursor::projectionVersion, FactCursor::logicalFactId,
			FactCursor::semanticRevision)
	}

	private fun maxOfOrNull(left: Long?, right: Long?): Long? = when {
		left == null -> right
		right == null -> left
		else -> maxOf(left, right)
	}

	private companion object {
		const val MAX_RESULTS = 100
		const val MAX_RECENT_LOCAL_ORIGIN_COMPARISONS = 4
		const val MAX_SHARED_HISTORY_LOCAL_ORIGIN_COMPARISONS = 4
		const val MAX_SHARED_HISTORY_IMPORTED_CANDIDATES = 256
		const val SHARED_HISTORY_IMPORTED_PAGE_SIZE = 32
		const val MAX_RANGE_CANDIDATES_PER_ORIGIN = 256
		const val CANDIDATE_PAGE_SIZE = 32
		const val MAX_CANDIDATE_SCAN = 128
		const val MEMBER_PAGE_SIZE = 32
		const val MAX_LOGICAL_MEMBERS = 128
		const val FACT_PAGE_SIZE = 256
		const val MAX_FACT_REVISIONS = 4_096
		const val MAX_CURSORS = 4_096
		const val MAX_SESSIONS = 128
		const val MAX_MANIFESTS = 512
		const val MAX_MANIFEST_SOURCES = 4_096
		const val MAX_COMPLETENESS = 512
		const val MAX_POLICIES = 512
		const val MAX_CONSENTS = 512
		const val MAX_DELETION_GENERATIONS = 128
		const val MAX_DELETION_FENCES = 128
		const val MAX_LANES = 128
		const val MAX_PLANS = 512
		const val MAX_PROVIDERS = 512
		const val MAX_AUTHORIZATIONS = 4_096
		const val MAX_DEMANDS = 4_096
		const val MAX_TERMINAL_FAILURES = 512
		const val SQL_ID_BATCH = 400
	}
}

private fun unverifiableImportedSelection(
	selection: ImportedCellHistorySelection,
	cause: CellHistoryCause,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-imported:${selection.identity.value}"),
	startTime = EpochMs(0L),
	endTime = EpochMs(0L),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.UNVERIFIABLE,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(cause),
	origin = CellHistoryOrigin.Imported(selection),
	selection = selection,
)

private fun deletedImportedSelection(
	selection: ImportedCellHistorySelection,
	startTimeMs: Long,
	endTimeMs: Long,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-imported:${selection.identity.value}"),
	startTime = EpochMs(startTimeMs),
	endTime = EpochMs(endTimeMs),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.DELETED,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(CellHistoryCause.DELETED),
	origin = CellHistoryOrigin.Imported(selection),
	selection = selection,
)

private fun unverifiableLocalSelection(
	selection: LocalCellHistorySelection,
	cause: CellHistoryCause,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-local:${selection.identity.value}"),
	startTime = EpochMs(0L),
	endTime = EpochMs(0L),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.FAILED,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(cause),
	origin = CellHistoryOrigin.Local,
	selection = selection,
)

private fun deletedLocalSelection(
	selection: LocalCellHistorySelection,
	receipt: CellCapturedEntryDeletionReceiptEntity,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-local:${selection.identity.value}"),
	startTime = EpochMs(receipt.startTimeMs),
	endTime = EpochMs(receipt.endTimeMs),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.DELETED,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(CellHistoryCause.DELETED),
	origin = CellHistoryOrigin.Local,
	selection = selection,
)

private sealed interface CellHistoryRangeBuild {
	data class Ready(val entries: List<CellHistoryRangeEntry>) : CellHistoryRangeBuild
	data class Failed(val cause: CellHistoryCause) : CellHistoryRangeBuild
}

private sealed interface LocalCellRangeBuild {
	data class Ready(val entries: List<ComposedCellEntry>) : LocalCellRangeBuild
	data class Failed(val cause: CellHistoryCause) : LocalCellRangeBuild
}

private data class CellRangeQueryBounds(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
)

private data class ImportedCellStructuralMembership(
	val days: Set<CellHistoryStructuralDay>,
	val completeness: CellHistoryStructuralDayCompleteness,
)

private fun ImportedCellProductEvaluation.Readable.structuralMembership(
	scope: CellHistoryRangeScope.StructuralDays,
): ImportedCellStructuralMembership? {
	if (entryDeleted || retentionLimited) return null
	val visibleRuns = entry.runs.filterNot { it.identity.value in deletedRunIdentities }
	val days = linkedSetOf<CellHistoryStructuralDay>()
	var ambiguous = false
	val partial = deletedRunIdentities.isNotEmpty() || visibleRuns.any { it.retentionLoss }
	for (observation in visibleRuns.flatMap { it.observations }) {
		val earliest = minOf(
			observation.coverageStartTimeMs,
			Math.subtractExact(
				observation.observedTimeMs,
				observation.wallTimeUncertaintyMs,
			),
		)
		val latest = Math.addExact(
			observation.observedTimeMs,
			observation.wallTimeUncertaintyMs,
		)
		val zone = ZoneId.of(observation.storedZoneId)
		val firstObservationDay = Instant.ofEpochMilli(earliest)
			.atZone(zone).toLocalDate().toEpochDay()
		val lastObservationDay = Instant.ofEpochMilli(latest)
			.atZone(zone).toLocalDate().toEpochDay()
		if (lastObservationDay < firstObservationDay ||
			lastObservationDay - firstObservationDay >= MAX_STRUCTURAL_DAYS_PER_RANGE_ENTRY
		) throw ImportedCellHistoryCompositionFailure()
		if (lastObservationDay > firstObservationDay) ambiguous = true
		val firstIncluded = maxOf(firstObservationDay, scope.firstEpochDay)
		val lastIncluded = minOf(lastObservationDay, scope.lastEpochDayInclusive)
		if (lastIncluded < firstIncluded) continue
		var epochDay = firstIncluded
		while (epochDay <= lastIncluded) {
			days += CellHistoryStructuralDay(epochDay, observation.storedZoneId)
			if (epochDay == Long.MAX_VALUE) break
			epochDay += 1L
		}
	}
	if (days.isEmpty()) return null
	val completeness = when {
		partial -> CellHistoryStructuralDayCompleteness.PARTIAL
		ambiguous && days.size > 1 -> CellHistoryStructuralDayCompleteness.AMBIGUOUS
		ambiguous -> CellHistoryStructuralDayCompleteness.PARTIAL
		else -> CellHistoryStructuralDayCompleteness.EXACT
	}
	return ImportedCellStructuralMembership(days, completeness)
}

private class CellHistoryRangeContinuationSnapshot(
	val scope: CellHistoryRangeScope,
	val remaining: List<CellHistoryRangeEntry>,
	val issuer: Any,
) : CellHistoryRangeContinuation {
	override fun toString(): String = "CellHistoryRangeContinuation"
}

private fun List<CellHistoryRangeEntry>.toRangePage(
	scope: CellHistoryRangeScope,
	limit: Int,
	issuer: Any,
): CellHistoryRangePage {
	val page = take(limit)
	val remaining = drop(limit)
	return CellHistoryRangePage.Available(
		entries = page,
		continuation = remaining.takeIf(List<CellHistoryRangeEntry>::isNotEmpty)?.let {
			CellHistoryRangeContinuationSnapshot(scope, it, issuer)
		},
	)
}

private fun CellHistoryRangeScope.queryBounds(): CellRangeQueryBounds = when (this) {
	is CellHistoryRangeScope.WallTime -> CellRangeQueryBounds(
		fromInclusiveMs = fromInclusive.raw,
		toExclusiveMs = toExclusive.raw,
	)
	is CellHistoryRangeScope.StructuralDays -> {
		val firstDayStart = Math.multiplyExact(firstEpochDay, MILLIS_PER_DAY)
		val lastDayEnd = Math.multiplyExact(
			Math.addExact(lastEpochDayInclusive, 1L),
			MILLIS_PER_DAY,
		)
		CellRangeQueryBounds(
			fromInclusiveMs = maxOf(0L, firstDayStart - MAX_ZONE_OFFSET_MILLIS),
			toExclusiveMs = Math.addExact(lastDayEnd, MAX_ZONE_OFFSET_MILLIS),
		)
	}
}

private fun List<CellLogicalHistoryCandidate>.hasValidRangeKeyset(
	beforeStartTimeMs: Long?,
	beforeSegmentId: Long?,
): Boolean {
	if ((beforeStartTimeMs == null) != (beforeSegmentId == null) ||
		mapNotNull { it.segment.logicalTrackingId }.distinct().size != size
	) return false
	val cursors = buildList {
		if (beforeStartTimeMs != null && beforeSegmentId != null) {
			add(beforeStartTimeMs to beforeSegmentId)
		}
		addAll(map { it.logicalRecencyStartMs to it.logicalRecencySegmentId })
	}
	return cursors.zipWithNext().all { (left, right) ->
		left.first > right.first || left.first == right.first && left.second > right.second
	}
}

private fun CellHistoryEntry.toRangeEntry(
	scope: CellHistoryRangeScope,
	importedStructuralMemberships: Map<String, ImportedCellStructuralMembership?>,
): CellHistoryRangeEntry? {
	if (scope is CellHistoryRangeScope.WallTime &&
		(startTime.raw >= scope.toExclusive.raw || endTime.raw <= scope.fromInclusive.raw)
	) return null
	if (scope is CellHistoryRangeScope.StructuralDays &&
		origin is CellHistoryOrigin.Imported
	) {
		val identity = origin.selection.identity.value
		if (!importedStructuralMemberships.containsKey(identity)) {
			throw ImportedCellHistoryCompositionFailure()
		}
		val membership = importedStructuralMemberships[identity] ?: return null
		return CellHistoryRangeEntry(
			entry = this,
			structuralDays = membership.days,
			structuralDayCompleteness = membership.completeness,
		)
	}
	if (observations.isEmpty()) {
		return if (scope is CellHistoryRangeScope.WallTime) {
			CellHistoryRangeEntry(
				entry = this,
				structuralDays = emptySet(),
				structuralDayCompleteness = CellHistoryStructuralDayCompleteness.UNAVAILABLE,
			)
		} else {
			null
		}
	}
	val days = linkedSetOf<CellHistoryStructuralDay>()
	var ambiguous = false
	var partial = false
	for (observation in observations) {
		try {
			val earliest = minOf(
				observation.intervalStartTime.raw,
				Math.subtractExact(
					observation.observedTime.raw,
					observation.wallTimeUncertaintyMs,
				),
			)
			val latest = Math.addExact(
				observation.observedTime.raw,
				observation.wallTimeUncertaintyMs,
			)
			val zone = ZoneId.of(observation.storedZoneId)
			val firstObservationDay = Instant.ofEpochMilli(earliest)
				.atZone(zone).toLocalDate().toEpochDay()
			val lastObservationDay = Instant.ofEpochMilli(latest)
				.atZone(zone).toLocalDate().toEpochDay()
			if (lastObservationDay < firstObservationDay) {
				partial = true
				continue
			}
			if (lastObservationDay > firstObservationDay) ambiguous = true
			val firstIncludedDay = when (scope) {
				is CellHistoryRangeScope.StructuralDays ->
					maxOf(firstObservationDay, scope.firstEpochDay)
				is CellHistoryRangeScope.WallTime -> firstObservationDay
			}
			val lastIncludedDay = when (scope) {
				is CellHistoryRangeScope.StructuralDays ->
					minOf(lastObservationDay, scope.lastEpochDayInclusive)
				is CellHistoryRangeScope.WallTime -> lastObservationDay
			}
			if (lastIncludedDay < firstIncludedDay) continue
			if (lastIncludedDay - firstIncludedDay >= MAX_STRUCTURAL_DAYS_PER_RANGE_ENTRY) {
				partial = true
				continue
			}
			var epochDay = firstIncludedDay
			while (epochDay <= lastIncludedDay) {
				days += CellHistoryStructuralDay(epochDay, observation.storedZoneId)
				if (epochDay == Long.MAX_VALUE) break
				epochDay += 1L
			}
		} catch (_: ArithmeticException) {
			partial = true
		} catch (_: RuntimeException) {
			partial = true
		}
	}
	if (scope is CellHistoryRangeScope.StructuralDays && days.isEmpty()) return null
	val completeness = when {
		days.isEmpty() -> CellHistoryStructuralDayCompleteness.UNAVAILABLE
		partial -> CellHistoryStructuralDayCompleteness.PARTIAL
		ambiguous && days.size > 1 -> CellHistoryStructuralDayCompleteness.AMBIGUOUS
		ambiguous -> CellHistoryStructuralDayCompleteness.PARTIAL
		else -> CellHistoryStructuralDayCompleteness.EXACT
	}
	return CellHistoryRangeEntry(this, days, completeness)
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_ZONE_OFFSET_MILLIS = 18L * 60L * 60L * 1_000L
private const val MAX_STRUCTURAL_DAYS_PER_RANGE_ENTRY = 370L

internal sealed interface CellComposedPage {
	data class Available(val entries: List<ComposedCellEntry>) : CellComposedPage
	data class Failed(val cause: CellHistoryCause) : CellComposedPage
}

internal sealed interface CellSourceComposedEntry {
	val entry: CellHistoryEntry
	val recency: CellSourceRecency

	data class Local(
		val group: ComposedCellEntry,
	) : CellSourceComposedEntry {
		override val entry: CellHistoryEntry get() = group.entry
		override val recency: CellSourceRecency = CellSourceRecency(
			memberStartTimeMs = group.recencyStartTimeMs,
			tieIdentity = group.recencyTieIdentity,
		)
	}

	data class Imported(
		override val entry: CellHistoryEntry,
		val selection: ImportedCellHistorySelection,
		override val recency: CellSourceRecency,
	) : CellSourceComposedEntry {
		init {
			require(entry.origin == CellHistoryOrigin.Imported(selection))
			require(entry.selection == selection)
		}
	}
}

internal sealed interface CellSourceComposedPage {
	data class Available(
		val entries: List<CellSourceComposedEntry>,
	) : CellSourceComposedPage

	data class Failed(val cause: CellHistoryCause) : CellSourceComposedPage
}

private fun importedEligibleUnavailable(
	reason: SourceAwareHistoryPageUnavailableReason,
): ImportedHistoryEligiblePage.Unavailable = ImportedHistoryEligiblePage.Unavailable(reason)

/** Authenticated newest physical member ordering without exposing its native database identity. */
internal data class CellSourceRecency(
	val memberStartTimeMs: Long,
	val tieIdentity: PortableCellOpaqueIdentity,
) {
	init {
		require(memberStartTimeMs >= 0L)
	}
}

internal val cellCompositionOrder =
	compareByDescending<ComposedCellEntry> { it.recencyStartTimeMs }
		.thenByDescending { it.recencySegmentId }

internal val cellSourceCompositionOrder =
	compareByDescending<CellSourceComposedEntry> { it.recency.memberStartTimeMs }
		.thenByDescending { it.recency.tieIdentity.value }
		.thenBy { it is CellSourceComposedEntry.Imported }

private fun ImportedCellProductEvaluation.Unverifiable.toSourceFailure(): CellHistoryCause =
	if (localOriginHandle != null) {
		CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
	} else {
		when (reason) {
			ImportedCellProductFailure.DEPENDENCY_OVERFLOW ->
				CellHistoryCause.READ_BUDGET_EXCEEDED
			ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH ->
				CellHistoryCause.IMPORTED_PRIVACY_EPOCH_MISMATCH
			ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT ->
				CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
			ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
			ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			ImportedCellProductFailure.VALUE_OVERFLOW,
			-> CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
		}
	}

internal fun isCellCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == CELL_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		source.persistenceEligible
