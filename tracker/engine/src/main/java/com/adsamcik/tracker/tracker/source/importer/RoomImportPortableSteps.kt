package com.adsamcik.tracker.tracker.source.importer

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryLockedDays
import com.adsamcik.tracker.shared.base.database.authenticatedGraph
import com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainRootEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.enqueueStepsGoalRepairDay
import com.adsamcik.tracker.shared.base.database.hasCompletePortableOwnerLineages
import com.adsamcik.tracker.shared.base.database.insertAuthenticatedGraph
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedSessionCountDomainBinding
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsV1WithReceipt
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsV2
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsV2Request
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainGraphV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsConflictScope
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportMetadataV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.tracker.source.deletion.StepsDailySummaryRepairComposer
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPlan
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import java.time.ZoneId
import java.util.ConcurrentModificationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Authoritative, source-local admission for one already-decoded portable Steps logical entry.
 *
 * This command is intentionally inert with respect to live collection: it creates no provider
 * demand, local lifecycle session, service run, policy, consent, elapsed clock, or positive legacy
 * sample count. The current destination-owner generation is retained only as the local admission
 * receipt; portable facts keep their independent fixed representation binding.
 *
 * Stored-zone day repair is part of the owning transaction. Partial or Int-unrepresentable Steps
 * may preserve an existing compatibility integer, but cannot create a fabricated zero. Source
 * evidence and table invalidations publish only after the complete import and repair commit.
 */
@Singleton
internal class RoomImportPortableSteps internal constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val startupGate: TrackingStartupGate,
	private val clock: Clock,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val dirtyTracker: MetricDirtyTracker,
	private val nativeStepsExporter: ExportPortableSteps,
	private val afterDayLocksAcquired: suspend () -> Unit,
	private val beforeMutation: suspend () -> Unit,
	private val afterPayloadInserted: suspend () -> Unit,
) : ImportPortableSteps, ImportPortableStepsV1WithReceipt, ImportPortableStepsV2 {
	@Inject
	constructor(
		database: AppDatabase,
		lifecycleStore: CollectedDataLifecycleStore,
		startupGate: TrackingStartupGate,
		clock: Clock,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		dirtyTracker: MetricDirtyTracker,
		nativeStepsExporter: ExportPortableSteps,
	) : this(
		database = database,
		lifecycleStore = lifecycleStore,
		startupGate = startupGate,
		clock = clock,
		ioDispatcher = ioDispatcher,
		dirtyTracker = dirtyTracker,
		nativeStepsExporter = nativeStepsExporter,
		afterDayLocksAcquired = {},
		beforeMutation = {},
		afterPayloadInserted = {},
	)

	override suspend fun importEntry(
		entry: PortableStepsEntryV1,
		expectedCollectedDataEpoch: Long?,
	): ImportPortableStepsResult =
		importPrepared(
			entry.withExplicitUnprovenCountDomain(),
			sourceSchemaVersion = 1,
			fileReceipt = null,
			entryOrdinal = 0,
			sourcePayloadChecksum = entry.contentChecksum.value,
			expectedCollectedDataEpoch = expectedCollectedDataEpoch,
		)

	override suspend fun importEntry(
		entry: PortableStepsEntryV1,
		receipt: PortableStepsImportReceipt,
		entryOrdinal: Int,
		expectedCollectedDataEpoch: Long,
	): ImportPortableStepsResult = importPrepared(
		entry.withExplicitUnprovenCountDomain(),
		sourceSchemaVersion = 1,
		fileReceipt = receipt,
		entryOrdinal = entryOrdinal,
		sourcePayloadChecksum = entry.contentChecksum.value,
		expectedCollectedDataEpoch = expectedCollectedDataEpoch,
	)

	override suspend fun importEntry(
		request: ImportPortableStepsV2Request,
	): ImportPortableStepsResult {
		val expected = request.metadata
		val graph = request.entry.countDomainGraph
		if (request.metadata.entryCount <= request.entryOrdinal ||
			!graph.fitsImportMetadata(request.metadata) || !graph.hasCompletePortableOwnerLineages()
		) {
			return ImportPortableStepsResult.Unverifiable(
				PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE,
			)
		}
		return importPrepared(
			request.entry,
			sourceSchemaVersion = 2,
			fileReceipt = request.receipt,
			entryOrdinal = request.entryOrdinal,
			sourcePayloadChecksum = expected.archiveContentChecksum.value,
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
		)
	}

	private suspend fun importPrepared(
		entry: PortableStepsEntryV2,
		sourceSchemaVersion: Int,
		fileReceipt: PortableStepsImportReceipt?,
		entryOrdinal: Int,
		sourcePayloadChecksum: String,
		expectedCollectedDataEpoch: Long?,
	): ImportPortableStepsResult =
		withContext(ioDispatcher) {
			require(expectedCollectedDataEpoch == null || expectedCollectedDataEpoch >= 0L)
			val startup = try {
				startupGate.reconcile()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				return@withContext storageUnavailable()
			}
			when (startup) {
				is TrackingStartupResult.Ready -> Unit
				is TrackingStartupResult.Blocked,
				is TrackingStartupResult.RetryableFailure,
				-> return@withContext storageUnavailable()
			}
			val generation = startupGate.currentGeneration
			val result = startupGate.withReadyGenerationOperation(generation) {
				importWhenReady(
					entry,
					sourceSchemaVersion,
					fileReceipt,
					entryOrdinal,
					sourcePayloadChecksum,
					expectedCollectedDataEpoch,
				)
			} ?: concurrentState()
			if (result is ImportPortableStepsResult.Applied) {
				dirtyTracker.markDirty(
					setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
				)
			}
			result
		}

	private suspend fun importWhenReady(
		callerEntryV2: PortableStepsEntryV2,
		sourceSchemaVersion: Int,
		fileReceipt: PortableStepsImportReceipt?,
		entryOrdinal: Int,
		sourcePayloadChecksum: String,
		expectedCollectedDataEpoch: Long?,
	): ImportPortableStepsResult {
		val callerEntry = callerEntryV2.product
		val prepared = try {
			callerEntry.prepareForImport()
		} catch (_: PortableStepsSnapshotException) {
			return ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
		} catch (unverifiable: PortableStepsImportUnverifiableException) {
			return ImportPortableStepsResult.Unverifiable(unverifiable.reason)
		}
		return if (prepared.entry != callerEntryV2.product) {
			concurrentState()
		} else {
			val graph = callerEntryV2.snapshotImportGraph(prepared.entry)
			if (graph == null) {
				attributionUnverifiable()
			} else {
				val conflict = prepared.entry.identityConflictWithinEntry()
				if (conflict != null) {
					ImportPortableStepsResult.Conflict(conflict)
				} else {
					prepared.importRetainedSnapshot(
						callerEntry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal,
						sourcePayloadChecksum, expectedCollectedDataEpoch,
					)
				}
			}
		}
	}

	// Keep the original caller snapshot and immutable admission inputs explicit through preflight.
	@Suppress("LongParameterList")
	private suspend fun PreparedPortableStepsDayScope.importRetainedSnapshot(
		callerEntry: PortableStepsEntryV1,
		graph: PortableCountDomainGraphV2,
		sourceSchemaVersion: Int,
		fileReceipt: PortableStepsImportReceipt?,
		entryOrdinal: Int,
		sourcePayloadChecksum: String,
		expectedCollectedDataEpoch: Long?,
	): ImportPortableStepsResult {
		val lifecycle = try {
			lifecycleStore.snapshot()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return storageUnavailable()
		}
		return when {
			expectedCollectedDataEpoch != null && lifecycle.epoch != expectedCollectedDataEpoch -> concurrentState()
			entry.isOutsideRetention(lifecycle.retainedFromMs) -> ImportPortableStepsResult.OutsideRetention
			else -> database.readAdmissionRefusal(
				entry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal, sourcePayloadChecksum, lifecycle,
			) ?: preflightAndImport(
				callerEntry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal,
				sourcePayloadChecksum, lifecycle, expectedCollectedDataEpoch,
			)
		}
	}

	// The accepted lifecycle and original caller are carried unchanged into the locked operation.
	@Suppress("LongParameterList")
	private suspend fun PreparedPortableStepsDayScope.preflightAndImport(
		callerEntry: PortableStepsEntryV1,
		graph: PortableCountDomainGraphV2,
		sourceSchemaVersion: Int,
		fileReceipt: PortableStepsImportReceipt?,
		entryOrdinal: Int,
		sourcePayloadChecksum: String,
		lifecycle: CollectedDataLifecycleSnapshot,
		expectedCollectedDataEpoch: Long?,
	): ImportPortableStepsResult {
		val nativePreflight = when (val preflight = preflightNativeAuthority(entry)) {
			is NativeAuthorityPreflight.Outcome -> return preflight.result
			is NativeAuthorityPreflight.Stable -> preflight
		}
		val lifecycleAfterPreflight = try {
			lifecycleStore.snapshot()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return storageUnavailable()
		}
		if (lifecycleAfterPreflight != lifecycle) {
			return concurrentState()
		}
		return importUnderDayLocks(
			callerEntry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal,
			sourcePayloadChecksum, lifecycle, nativePreflight, expectedCollectedDataEpoch,
		)
	}

	// One locked import keeps its captured proof and file receipt inputs without a second state holder.
	@Suppress("LongParameterList")
	private suspend fun PreparedPortableStepsDayScope.importUnderDayLocks(
		callerEntry: PortableStepsEntryV1,
		graph: PortableCountDomainGraphV2,
		sourceSchemaVersion: Int,
		fileReceipt: PortableStepsImportReceipt?,
		entryOrdinal: Int,
		sourcePayloadChecksum: String,
		lifecycle: CollectedDataLifecycleSnapshot,
		nativePreflight: NativeAuthorityPreflight.Stable,
		expectedCollectedDataEpoch: Long?,
	): ImportPortableStepsResult {
		val lockCoordinator = DailySummaryAggregator(
			dailySummaryDao = database.dailySummaryDao(),
			sessionSegmentDao = database.sessionSegmentDao(),
			zoneId = defaultZoneId,
		)
		return try {
			lockCoordinator.withDayLocks(lockDays) { lockedDays ->
				afterDayLocksAcquired()
				ensureLifecycle(lifecycle)
				database.withTransaction {
					importInTransaction(
						callerEntry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal,
						sourcePayloadChecksum, lifecycle, nativePreflight, expectedCollectedDataEpoch, lockedDays,
					)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (dayRepair: PortableStepsDayRepairException) {
			dayRepair.result
		} catch (unverifiable: PortableStepsImportUnverifiableException) {
			ImportPortableStepsResult.Unverifiable(unverifiable.reason)
		} catch (_: PortableStepsConcurrentStateException) {
			concurrentState()
		} catch (_: SQLiteException) {
			storageUnavailable()
		} catch (_: IllegalArgumentException) {
			attributionUnverifiable()
		} catch (_: Exception) {
			storageUnavailable()
		}
	}

	// Complete transaction admission uses the same snapshots, receipt and already-held day locks.
	@Suppress("LongParameterList")
	private suspend fun PreparedPortableStepsDayScope.importInTransaction(
		callerEntry: PortableStepsEntryV1,
		graph: PortableCountDomainGraphV2,
		sourceSchemaVersion: Int,
		fileReceipt: PortableStepsImportReceipt?,
		entryOrdinal: Int,
		sourcePayloadChecksum: String,
		lifecycle: CollectedDataLifecycleSnapshot,
		nativePreflight: NativeAuthorityPreflight.Stable,
		expectedCollectedDataEpoch: Long?,
		lockedDays: DailySummaryLockedDays,
	): ImportPortableStepsResult {
		if (!matchesPreflight(callerEntry, lifecycle, nativePreflight)) {
			return concurrentState()
		}
		return database.admissionRefusal(
			entry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal, sourcePayloadChecksum, lifecycle,
		) ?: when (val resolution = nativePreflight.resolution) {
			NativeAuthorityResolution.NoCollision -> insertApprovedPayload(
				graph, sourceSchemaVersion, fileReceipt, entryOrdinal, sourcePayloadChecksum,
				lifecycle, expectedCollectedDataEpoch, lockedDays,
			)
			NativeAuthorityResolution.Duplicate -> ImportPortableStepsResult.Duplicate
			is NativeAuthorityResolution.Conflict -> ImportPortableStepsResult.Conflict(resolution.scope)
		}
	}

	private suspend fun PreparedPortableStepsDayScope.matchesPreflight(
		callerEntry: PortableStepsEntryV1,
		lifecycle: CollectedDataLifecycleSnapshot,
		nativePreflight: NativeAuthorityPreflight.Stable,
	): Boolean {
		if (lifecycleStore.snapshot() != lifecycle ||
			database.sourceEvidenceStateDao().get() != nativePreflight.evidenceState
		) {
			return false
		}
		val transactionEntry = try {
			callerEntry.snapshotAndValidate()
		} catch (_: PortableStepsSnapshotException) {
			return false
		}
		return transactionEntry == entry
	}

	// Preserve one accepted operation's graph, lifecycle and receipt through payload and daily repair.
	@Suppress("LongParameterList")
	private suspend fun PreparedPortableStepsDayScope.insertApprovedPayload(
		graph: PortableCountDomainGraphV2,
		sourceSchemaVersion: Int,
		fileReceipt: PortableStepsImportReceipt?,
		entryOrdinal: Int,
		sourcePayloadChecksum: String,
		lifecycle: CollectedDataLifecycleSnapshot,
		expectedCollectedDataEpoch: Long?,
		lockedDays: DailySummaryLockedDays,
	): ImportPortableStepsResult {
		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		) ?: return attributionUnverifiable()
		if (owner.owner !in PORTABLE_ADMISSION_OWNERS) {
			return attributionUnverifiable()
		}
		val evidenceDao = database.sourceEvidenceStateDao()
		val appliedAtMs = clock.currentTimeMillis().coerceAtLeast(0L)
		val lifecycleChanged = evidenceDao.synchronizeImportLifecycle(lifecycle, expectedCollectedDataEpoch, appliedAtMs)
		val repairZones = database.resolvePortableStepsRepairZones(this)
		beforeMutation()
		database.insertEntryPayload(entry, lifecycle, owner.ownerGeneration, appliedAtMs)
		database.insertCountDomainGraph(
			entry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal, sourcePayloadChecksum,
		)
		afterPayloadInserted()
		repairImportedDays(this, repairZones, lockedDays)
		ensureLifecycle(lifecycle)
		if (!lifecycleChanged && evidenceDao.incrementRevision(appliedAtMs) != 1) {
			throw PortableStepsConcurrentStateException()
		}
		repairZones.keys.forEach { epochDay -> database.enqueueStepsGoalRepairDay(epochDay) }
		return ImportPortableStepsResult.Applied(
			physicalRunCount = entry.runs.size,
			factCount = entry.runs.sumOf { run -> run.facts.size },
		)
	}

	private suspend fun ensureLifecycle(lifecycle: CollectedDataLifecycleSnapshot) {
		if (lifecycleStore.snapshot() != lifecycle) {
			throw PortableStepsConcurrentStateException()
		}
	}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun preflightNativeAuthority(
		entry: PortableStepsEntryV1,
	): NativeAuthorityPreflight {
		val evidenceDao = database.sourceEvidenceStateDao()
		val evidenceBefore = evidenceDao.get()
		val collector = NativeAuthorityCollector(entry)
		val exportResult = try {
			nativeStepsExporter.export(GLOBAL_AUTHORITY_REQUEST, collector::emit)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: PortableStepsSnapshotException) {
			return NativeAuthorityPreflight.Outcome(attributionUnverifiable())
		} catch (_: IllegalArgumentException) {
			return NativeAuthorityPreflight.Outcome(attributionUnverifiable())
		} catch (_: ConcurrentModificationException) {
			return NativeAuthorityPreflight.Outcome(concurrentState())
		} catch (_: Exception) {
			return NativeAuthorityPreflight.Outcome(storageUnavailable())
		}
		val evidenceAfter = evidenceDao.get()
		if (evidenceAfter != evidenceBefore) {
			return NativeAuthorityPreflight.Outcome(concurrentState())
		}
		return collector.finish(exportResult, evidenceAfter)
	}

	private suspend fun repairImportedDays(
		prepared: PreparedPortableStepsDayScope,
		repairZones: Map<Long, ZoneId>,
		lockedDays: DailySummaryLockedDays,
	) {
		val plans = database.readImportedDayRepairPlans(repairZones)
		if (plans.associate { plan -> plan.epochDay to plan.zoneId } != repairZones) {
			throw dayRepairUnverifiable()
		}
		val aggregator = DailySummaryAggregator(
			dailySummaryDao = database.dailySummaryDao(),
			sessionSegmentDao = database.sessionSegmentDao(),
			zoneId = prepared.defaultZoneId,
		)
		plans.forEach { plan ->
			aggregator.withCalendarZone(plan.zoneId).repairDayFromSourceTotalsWhileLocked(
				epochDay = plan.epochDay,
				lockedDays = lockedDays,
				totals = plan.totals,
			)
		}
	}

	private companion object {
		val GLOBAL_AUTHORITY_REQUEST = ExportPortableStepsRequest(0L, Long.MAX_VALUE)
		val PORTABLE_ADMISSION_OWNERS = setOf(
			SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		)
	}
}

private suspend fun SourceEvidenceStateDao.synchronizeImportLifecycle(
	lifecycle: CollectedDataLifecycleSnapshot,
	expectedCollectedDataEpoch: Long?,
	appliedAtMs: Long,
): Boolean {
	val lifecycleChanged = synchronizeLifecycle(
		epoch = lifecycle.epoch,
		retainedFromMs = lifecycle.retainedFromMs,
		updatedAtMs = appliedAtMs,
	)
	val evidence = requireNotNull(get())
	if (expectedCollectedDataEpoch?.let { evidence.collectedDataEpoch != it } == true ||
		evidence.collectedDataEpoch != lifecycle.epoch || evidence.retainedFromMs != lifecycle.retainedFromMs
	) {
		throw PortableStepsConcurrentStateException()
	}
	return lifecycleChanged
}

private fun PortableCountDomainGraphV2.fitsImportMetadata(metadata: PortableStepsImportMetadataV2): Boolean =
	metadata.receiptCount >= receipts.size && metadata.ownerRevisionCount >= ownerRevisions.size &&
		metadata.completenessMarkerCount >= completenessMarkers.size && metadata.rootCount >= roots.size

private fun PortableStepsEntryV2.snapshotImportGraph(
	entry: PortableStepsEntryV1,
): PortableCountDomainGraphV2? = try {
	countDomainGraph.copy(
		receipts = countDomainGraph.receipts.map { it.copy() },
		ownerRevisions = countDomainGraph.ownerRevisions.map { it.copy() },
		completenessMarkers = countDomainGraph.completenessMarkers.map { it.copy() },
		roots = countDomainGraph.roots.map { it.copy() },
	).also { graph -> PortableStepsEntryV2(entry, graph) }
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (_: IllegalArgumentException) {
	null
} catch (_: IllegalStateException) {
	null
} catch (_: ArithmeticException) {
	null
}

private suspend fun AppDatabase.readImportedDayRepairPlans(
	repairZones: Map<Long, ZoneId>,
): List<StepsDayRepairPlan> = when (
	val repair = StepsDailySummaryRepairComposer(this).composeForImportedMutation(repairZones)
) {
	is StepsDayRepairPreflight.Ready -> repair.plans
	is StepsDayRepairPreflight.Unsupported -> throw dayRepairUnverifiable()
	StepsDayRepairPreflight.Materializing -> throw PortableStepsDayRepairException(
		ImportPortableStepsResult.RetryableFailure(PortableStepsTransferRetryableReason.DAY_REPAIR_MATERIALIZING),
	)
}

// Keep the complete file receipt and lifecycle inputs with the saved-data admission operation.
@Suppress("LongParameterList")
private suspend fun AppDatabase.readAdmissionRefusal(
	entry: PortableStepsEntryV1,
	graph: PortableCountDomainGraphV2,
	sourceSchemaVersion: Int,
	fileReceipt: PortableStepsImportReceipt?,
	entryOrdinal: Int,
	sourcePayloadChecksum: String,
	lifecycle: CollectedDataLifecycleSnapshot,
): ImportPortableStepsResult? = try {
	withTransaction {
		admissionRefusal(entry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal, sourcePayloadChecksum, lifecycle)
	}
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (_: SQLiteException) {
	storageUnavailable()
} catch (_: IllegalArgumentException) {
	attributionUnverifiable()
} catch (_: Exception) {
	storageUnavailable()
}

// Admission keeps deletion and duplicate checks on the exact captured file and lifecycle inputs.
@Suppress("LongParameterList")
private suspend fun AppDatabase.admissionRefusal(
	entry: PortableStepsEntryV1,
	graph: PortableCountDomainGraphV2,
	sourceSchemaVersion: Int,
	fileReceipt: PortableStepsImportReceipt?,
	entryOrdinal: Int,
	sourcePayloadChecksum: String,
	lifecycle: CollectedDataLifecycleSnapshot,
): ImportPortableStepsResult? {
	require(sourceSchemaVersion in 1..2)
	require(entryOrdinal >= 0)
	require(ImportedPortableCountDomainIdentity.isOpaque(sourcePayloadChecksum))
	return if (entry.isOutsideRetention(lifecycle.retainedFromMs)) {
		ImportPortableStepsResult.OutsideRetention
	} else {
		deletedImportRefusal(entry, graph) ?: existingImportRefusal(
			entry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal, sourcePayloadChecksum,
		)
	}
}

private suspend fun AppDatabase.deletedImportRefusal(
	entry: PortableStepsEntryV1,
	graph: PortableCountDomainGraphV2,
): ImportPortableStepsResult? {
	val digests = entry.runs.map { run -> run.deletionScopeDigest.value }
	val history = trackingHistoryReadDao()
	if (history.deletionFences(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digests,
		).isNotEmpty()
	) {
		return ImportPortableStepsResult.DeletedScope
	}
	if (history.deletionFences(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digests,
		).isNotEmpty()
	) {
		return ImportPortableStepsResult.OutsideRetention
	}
	val graphDao = importedPortableStepsCountDomainDao()
	val graphOwnerIdentities = graph.roots.map { it.ownerIdentity.value }.distinct()
	val graphFences = graphOwnerIdentities.chunked(GRAPH_QUERY_BATCH_SIZE).flatMap { identities ->
		graphDao.ownerFences(identities, identities.size + 1)
	}
	return ImportPortableStepsResult.DeletedScope.takeIf { graphFences.isNotEmpty() }
}

private suspend fun AppDatabase.existingImportRefusal(
	entry: PortableStepsEntryV1,
	graph: PortableCountDomainGraphV2,
	sourceSchemaVersion: Int,
	fileReceipt: PortableStepsImportReceipt?,
	entryOrdinal: Int,
	sourcePayloadChecksum: String,
): ImportPortableStepsResult? {
	val existing = importedStepsDao().entry(entry.identity.value)
	val roots = importedPortableStepsCountDomainDao().readImportOwnerRoots(graph)
		?: return ImportPortableStepsResult.Unverifiable(PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW)
	return when {
		existing == null -> ImportPortableStepsResult.Conflict(PortableStepsConflictScope.FACT)
			.takeIf { roots.isNotEmpty() }
		existing.contentChecksum != entry.contentChecksum.value ->
			ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
		else -> {
			val productResult = retainedImportProductResult(existing.identity, entry)
			if (productResult != ImportPortableStepsResult.Duplicate) {
				productResult
			} else {
				duplicateCountDomainResult(
					entry, graph, sourceSchemaVersion, fileReceipt, entryOrdinal, sourcePayloadChecksum,
				)
			}
		}
	}
}

private suspend fun ImportedPortableStepsCountDomainDao.readImportOwnerRoots(
	graph: PortableCountDomainGraphV2,
): List<ImportedPortableStepsCountDomainRootEntity>? {
	val roots = mutableListOf<ImportedPortableStepsCountDomainRootEntity>()
	val ownerIdentities = graph.roots.map { it.ownerIdentity.value }.distinct()
	ownerIdentities.chunked(GRAPH_QUERY_BATCH_SIZE).forEach { identities ->
		roots += rootsForOwners(identities, MAX_IMPORTED_ROOT_LOOKUP - roots.size + 1)
		if (roots.size > MAX_IMPORTED_ROOT_LOOKUP) {
			return null
		}
	}
	return roots.takeUnless { roots.size > MAX_IMPORTED_ROOT_LOOKUP }
}

private suspend fun AppDatabase.retainedImportProductResult(
	identity: String,
	entry: PortableStepsEntryV1,
): ImportPortableStepsResult = when (
	val retained = ImportedStepsRetainedReader(this).readEntriesInTransaction(listOf(identity))
) {
	is ImportedStepsRetainedRead.Ready -> {
		val exact = retained.entries.singleOrNull()?.portable
		when {
			retained.unverifiableEntries.isNotEmpty() || exact == null -> attributionUnverifiable()
			exact == entry -> ImportPortableStepsResult.Duplicate
			else -> ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
		}
	}
	is ImportedStepsRetainedRead.Unverifiable -> retained.reason.toImportResult()
}

private suspend fun AppDatabase.duplicateCountDomainResult(
	entry: PortableStepsEntryV1,
	graph: PortableCountDomainGraphV2,
	sourceSchemaVersion: Int,
	fileReceipt: PortableStepsImportReceipt?,
	entryOrdinal: Int,
	sourcePayloadChecksum: String,
): ImportPortableStepsResult {
	val binding = importedPortableStepsCountDomainDao().binding(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		entry.identity.value,
		IMPORTED_PRODUCT_REVISION,
	) ?: return attributionUnverifiable()
	val authenticated = try {
		loadAuthenticatedImportedSessionCountDomainBinding(entry)
	} catch (_: IllegalArgumentException) {
		return attributionUnverifiable()
	} catch (_: IllegalStateException) {
		return attributionUnverifiable()
	}
	return if (authenticated == null || binding.sourceSchemaVersion != sourceSchemaVersion ||
		binding.graphIdentity != graph.identity.value || authenticated.binding != binding
	) {
		ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
	} else if (authenticated.graph != graph) {
		ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
	} else {
		admitDuplicateFileReceipt(entry, graph, fileReceipt, entryOrdinal, sourcePayloadChecksum)
	}
}

private suspend fun AppDatabase.admitDuplicateFileReceipt(
	entry: PortableStepsEntryV1,
	graph: PortableCountDomainGraphV2,
	fileReceipt: PortableStepsImportReceipt?,
	entryOrdinal: Int,
	sourcePayloadChecksum: String,
): ImportPortableStepsResult {
	if (fileReceipt == null) {
		return ImportPortableStepsResult.Duplicate
	}
	val row = fileReceipt.toEntity(entry, graph, entryOrdinal, sourcePayloadChecksum)
	return when (admitFileReceipt(row)) {
		FileReceiptAdmission.INSERTED,
		FileReceiptAdmission.EXACT_REPLAY,
		-> ImportPortableStepsResult.Duplicate
		FileReceiptAdmission.LIMIT_REACHED -> ImportPortableStepsResult.Unverifiable(
			PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		FileReceiptAdmission.CONFLICT -> ImportPortableStepsResult.Conflict(
			PortableStepsConflictScope.LOGICAL_ENTRY,
		)
	}
}

private suspend fun AppDatabase.insertCountDomainGraph(
	entry: PortableStepsEntryV1,
	graph: PortableCountDomainGraphV2,
	sourceSchemaVersion: Int,
	fileReceipt: PortableStepsImportReceipt?,
	entryOrdinal: Int,
	sourcePayloadChecksum: String,
) {
	val dao = importedPortableStepsCountDomainDao()
	val stored = dao.graph(graph.identity.value)
	if (stored == null) {
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
	} else if (dao.authenticatedGraph(
			graph.identity.value,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		) != graph
	) {
		throw IllegalStateException("Imported portable count-domain graph conflict")
	}
	dao.insertBinding(
		ImportedPortableStepsCountDomainBindingEntity(
			productKind =
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			productIdentity = entry.identity.value,
			productRevision = IMPORTED_PRODUCT_REVISION,
			graphIdentity = graph.identity.value,
			sourceSchemaVersion = sourceSchemaVersion,
			sourceReceiptIdentity = fileReceipt?.let {
				ImportedPortableCountDomainIdentity.fileReceipt(it.jobId, it.entryKey)
			},
			sourceArchiveIdentity = null,
			sourceArchiveContentChecksum = fileReceipt?.let { sourcePayloadChecksum },
		),
	)
	fileReceipt?.let { receipt ->
		check(
			admitFileReceipt(
				receipt.toEntity(entry, graph, entryOrdinal, sourcePayloadChecksum),
			) == FileReceiptAdmission.INSERTED,
		) {
			"New imported Steps product did not receive one bounded file receipt"
		}
	}
}

private suspend fun AppDatabase.admitFileReceipt(
	candidate: ImportedPortableStepsFileReceiptEntity,
): FileReceiptAdmission {
	check(inTransaction()) {
		"Imported Steps file receipt admission must be atomic with its product"
	}
	val dao = importedPortableStepsCountDomainDao()
	val stored = dao.fileReceipt(candidate.importJobId, candidate.entryKey)
	if (stored != null) {
		return if (stored == candidate) {
			FileReceiptAdmission.EXACT_REPLAY
		} else {
			FileReceiptAdmission.CONFLICT
		}
	}
	val count = dao.fileReceiptCountForEntry(candidate.entryIdentity)
	if (count >= ImportedPortableStepsCountDomainDao.MAX_FILE_RECEIPTS_PER_ENTRY) {
		return FileReceiptAdmission.LIMIT_REACHED
	}
	dao.insertFileReceipt(candidate)
	return FileReceiptAdmission.INSERTED
}

private suspend fun AppDatabase.insertEntryPayload(
	portable: PortableStepsEntryV1,
	lifecycle: CollectedDataLifecycleSnapshot,
	ownerGeneration: Long,
	appliedAtMs: Long,
) {
	val importedDao = importedStepsDao()
	val metadata = ImportedStepsAdmissionRows.entry(portable, lifecycle.epoch, ownerGeneration)
	importedDao.insertEntry(metadata)
	for (run in portable.runs) {
		currentCoroutineContext().ensureActive()
		val segmentId = sessionSegmentDao().insert(run.toPresentationSegment(metadata, appliedAtMs))
		if (segmentId <= 0L) {
			throw PortableStepsConcurrentStateException()
		}
		importedDao.insertRun(ImportedStepsAdmissionRows.run(metadata, run, segmentId))
		ImportedStepsAdmissionRows.manifests(run).chunked(INSERT_BATCH_SIZE).forEach { batch ->
			currentCoroutineContext().ensureActive()
			importedDao.insertManifests(batch)
		}
		val facts = run.facts.map { fact ->
			ImportedStepsAdmissionRows.fact(metadata, run, fact, appliedAtMs)
		}
		facts.chunked(INSERT_BATCH_SIZE).forEach { batch ->
			currentCoroutineContext().ensureActive()
			if (stepFactRevisionDao().insert(batch).any { rowId -> rowId == INSERT_IGNORED }) {
				throw PortableStepsConcurrentStateException()
			}
		}
	}
}

private const val INSERT_IGNORED = -1L
private const val INSERT_BATCH_SIZE = 256
private const val IMPORTED_PRODUCT_REVISION = 1L
private const val MAX_IMPORTED_ROOT_LOOKUP = 131_072
private const val GRAPH_QUERY_BATCH_SIZE = 256

private enum class FileReceiptAdmission {
	INSERTED,
	EXACT_REPLAY,
	LIMIT_REACHED,
	CONFLICT,
}

private fun PortableStepsImportReceipt.toEntity(
	entry: PortableStepsEntryV1,
	graph: com.adsamcik.tracker.stats.api.repository.PortableCountDomainGraphV2,
	entryOrdinal: Int,
	sourcePayloadChecksum: String,
): ImportedPortableStepsFileReceiptEntity {
	require(entryOrdinal >= 0)
	require(ImportedPortableCountDomainIdentity.isOpaque(sourcePayloadChecksum))
	return ImportedPortableStepsFileReceiptEntity(
		importJobId = jobId,
		entryKey = entryKey,
		receiptIdentity = ImportedPortableCountDomainIdentity.fileReceipt(jobId, entryKey),
		sourceName = sourceName,
		receivedAtMs = receivedAtMs,
		archiveContentChecksum = sourcePayloadChecksum,
		entryOrdinal = entryOrdinal,
		entryIdentity = entry.identity.value,
		graphIdentity = graph.identity.value,
	)
}

private fun PortableStepsEntryV1.isOutsideRetention(retainedFromMs: Long?): Boolean =
	retainedFromMs != null && runs.any { run ->
		run.startTimeMs < retainedFromMs || run.facts.any { fact ->
			fact.intervalStartTimeMs < retainedFromMs || fact.intervalEndTimeMs < retainedFromMs
		}
	}

private fun com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1.toPresentationSegment(
	entry: ImportedStepsEntryEntity,
	appliedAtMs: Long,
): SessionSegment = SessionSegment(
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	distanceM = 0f,
	steps = null,
	primaryActivity = null,
	activityConfidence = null,
	sampleCount = 0,
	source = SegmentSource.PORTABLE_STEPS_IMPORT,
	inferenceVersion = null,
	createdAt = appliedAtMs,
	hasDistanceAnomaly = false,
	logicalTrackingId = entry.identity,
	serviceRunId = identity.value,
)

private fun ImportedStepsReadFailure.toImportResult(): ImportPortableStepsResult = when (this) {
	ImportedStepsReadFailure.RETENTION -> ImportPortableStepsResult.OutsideRetention
	ImportedStepsReadFailure.DELETION -> ImportPortableStepsResult.DeletedScope
	ImportedStepsReadFailure.DEPENDENCY_OVERFLOW -> ImportPortableStepsResult.Unverifiable(
		PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
	)
	ImportedStepsReadFailure.INTEGRITY,
	ImportedStepsReadFailure.MISSING,
	-> attributionUnverifiable()
}

private fun concurrentState() = ImportPortableStepsResult.RetryableFailure(
	PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
)

private fun storageUnavailable() = ImportPortableStepsResult.RetryableFailure(
	PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
)

internal class PortableStepsSnapshotException(cause: Throwable) : IllegalStateException(cause)
private class PortableStepsDayRepairException(
	val result: ImportPortableStepsResult,
) : IllegalStateException()
private class PortableStepsConcurrentStateException : IllegalStateException()

