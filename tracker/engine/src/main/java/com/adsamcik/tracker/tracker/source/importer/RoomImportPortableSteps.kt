package com.adsamcik.tracker.tracker.source.importer

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_ENTRY_ORDER
import com.adsamcik.tracker.stats.api.repository.PortableStepsConflictScope
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
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
 * Daily compatibility-cache repair remains a separate pre-exposure action. Source evidence and
 * Room invalidations make the exact imported facts visible immediately to source-qualified reads,
 * while obsolete aggregate consumers must never be made current by inventing zero.
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
	private val beforeMutation: suspend () -> Unit,
	private val afterPayloadInserted: suspend () -> Unit,
) : ImportPortableSteps {
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
		beforeMutation = {},
		afterPayloadInserted = {},
	)

	override suspend fun importEntry(entry: PortableStepsEntryV1): ImportPortableStepsResult =
		withContext(ioDispatcher) {
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
				importWhenReady(entry)
			} ?: concurrentState()
			if (result is ImportPortableStepsResult.Applied) {
				dirtyTracker.markDirty(
					setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
				)
			}
			result
		}

	private suspend fun importWhenReady(
		callerEntry: PortableStepsEntryV1,
	): ImportPortableStepsResult {
		val entry = try {
			callerEntry.snapshotAndValidate()
		} catch (_: PortableStepsSnapshotException) {
			return ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
		}
		entry.identityConflictWithinEntry()?.let { scope ->
			return ImportPortableStepsResult.Conflict(scope)
		}
		val lifecycle = try {
			lifecycleStore.snapshot()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return storageUnavailable()
		}
		if (entry.isOutsideRetention(lifecycle.retainedFromMs)) {
			return ImportPortableStepsResult.OutsideRetention
		}
		val storedRefusal = try {
			database.withTransaction { admissionRefusal(entry, lifecycle) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			return storageUnavailable()
		} catch (_: IllegalArgumentException) {
			return attributionUnverifiable()
		} catch (_: Exception) {
			return storageUnavailable()
		}
		storedRefusal?.let { return it }

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

		return try {
			database.withTransaction {
				if (lifecycleStore.snapshot() != lifecycle ||
					database.sourceEvidenceStateDao().get() != nativePreflight.evidenceState
				) {
					return@withTransaction concurrentState()
				}
				val transactionEntry = try {
					callerEntry.snapshotAndValidate()
				} catch (_: PortableStepsSnapshotException) {
					return@withTransaction concurrentState()
				}
				if (transactionEntry != entry) {
					return@withTransaction concurrentState()
				}

				admissionRefusal(entry, lifecycle)?.let { return@withTransaction it }
				when (val resolution = nativePreflight.resolution) {
					NativeAuthorityResolution.NoCollision -> Unit
					NativeAuthorityResolution.Duplicate ->
						return@withTransaction ImportPortableStepsResult.Duplicate
					is NativeAuthorityResolution.Conflict ->
						return@withTransaction ImportPortableStepsResult.Conflict(resolution.scope)
				}
				val owner = database.sourceDestinationOwnerDao().get(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				) ?: return@withTransaction attributionUnverifiable()
				if (owner.owner !in PORTABLE_ADMISSION_OWNERS) {
					return@withTransaction attributionUnverifiable()
				}

				val evidenceDao = database.sourceEvidenceStateDao()
				val appliedAtMs = clock.currentTimeMillis().coerceAtLeast(0L)
				val lifecycleChanged = evidenceDao.synchronizeLifecycle(
					epoch = lifecycle.epoch,
					retainedFromMs = lifecycle.retainedFromMs,
					updatedAtMs = appliedAtMs,
				)
				val evidence = requireNotNull(evidenceDao.get())
				if (evidence.collectedDataEpoch != lifecycle.epoch ||
					evidence.retainedFromMs != lifecycle.retainedFromMs
				) {
					throw PortableStepsConcurrentStateException()
				}

				beforeMutation()
				insertEntryPayload(entry, lifecycle, owner.ownerGeneration, appliedAtMs)
				afterPayloadInserted()
				if (!lifecycleChanged && evidenceDao.incrementRevision(appliedAtMs) != 1) {
					throw PortableStepsConcurrentStateException()
				}
				ImportPortableStepsResult.Applied(
					physicalRunCount = entry.runs.size,
					factCount = entry.runs.sumOf { run -> run.facts.size },
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
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

	private suspend fun admissionRefusal(
		entry: PortableStepsEntryV1,
		lifecycle: CollectedDataLifecycleSnapshot,
	): ImportPortableStepsResult? {
		if (entry.isOutsideRetention(lifecycle.retainedFromMs)) {
			return ImportPortableStepsResult.OutsideRetention
		}
		val digests = entry.runs.map { run -> run.deletionScopeDigest.value }
		val history = database.trackingHistoryReadDao()
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
		val existing = database.importedStepsDao().entry(entry.identity.value) ?: return null
		if (existing.contentChecksum != entry.contentChecksum.value) {
			return ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
		}
		return when (
			val retained = ImportedStepsRetainedReader(database)
				.readEntriesInTransaction(listOf(existing.identity))
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
	}

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

	private suspend fun insertEntryPayload(
		portable: PortableStepsEntryV1,
		lifecycle: CollectedDataLifecycleSnapshot,
		ownerGeneration: Long,
		appliedAtMs: Long,
	) {
		val importedDao = database.importedStepsDao()
		val metadata = ImportedStepsAdmissionRows.entry(portable, lifecycle.epoch, ownerGeneration)
		importedDao.insertEntry(metadata)
		for (run in portable.runs) {
			currentCoroutineContext().ensureActive()
			val segmentId = database.sessionSegmentDao().insert(run.toPresentationSegment(metadata, appliedAtMs))
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
				if (database.stepFactRevisionDao().insert(batch).any { rowId -> rowId == INSERT_IGNORED }) {
					throw PortableStepsConcurrentStateException()
				}
			}
		}
	}

	private companion object {
		const val INSERT_IGNORED = -1L
		const val INSERT_BATCH_SIZE = 256
		val GLOBAL_AUTHORITY_REQUEST = ExportPortableStepsRequest(0L, Long.MAX_VALUE)
		val PORTABLE_ADMISSION_OWNERS = setOf(
			SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		)
	}
}

private class NativeAuthorityCollector(
	private val candidate: PortableStepsEntryV1,
) {
	private val seenIdentities = hashSetOf<String>()
	private val seenDeletionScopes = hashSetOf<String>()
	private var previous: PortableStepsEntryV1? = null
	private var count = 0
	private var resolution: NativeAuthorityResolution = NativeAuthorityResolution.NoCollision

	suspend fun emit(value: PortableStepsEntryV1) {
		currentCoroutineContext().ensureActive()
		val entry = value.snapshotAndValidate()
		if (previous?.let { prior -> PORTABLE_STEPS_ENTRY_ORDER.compare(prior, entry) >= 0 } == true) {
			throw IllegalArgumentException("Portable Steps authority is not in canonical order")
		}
		entry.identityConflictWithinEntry()?.let {
			throw IllegalArgumentException("Portable Steps authority repeats an identity within an entry")
		}
		val existingIdentities = entry.identityScopes()
		if (existingIdentities.keys.any { identity -> !seenIdentities.add(identity) } ||
			entry.runs.any { run -> !seenDeletionScopes.add(run.deletionScopeDigest.value) }
		) {
			throw IllegalArgumentException("Portable Steps authority repeats an identity")
		}
		val next = when {
			entry.identity == candidate.identity -> if (entry == candidate) {
				NativeAuthorityResolution.Duplicate
			} else {
				NativeAuthorityResolution.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
			}
			else -> candidate.collisionWith(existingIdentities.keys, entry.runs.map { it.deletionScopeDigest.value })
		}
		resolution = resolution.combine(next)
		previous = entry
		count++
	}

	fun finish(
		result: ExportPortableStepsResult,
		evidence: SourceEvidenceState?,
	): NativeAuthorityPreflight = when (result) {
		is ExportPortableStepsResult.Exported -> if (result.entryCount == count && count > 0) {
			NativeAuthorityPreflight.Stable(evidence, resolution)
		} else {
			NativeAuthorityPreflight.Outcome(attributionUnverifiable())
		}
		ExportPortableStepsResult.NoEntries -> if (count == 0) {
			NativeAuthorityPreflight.Stable(evidence, NativeAuthorityResolution.NoCollision)
		} else {
			NativeAuthorityPreflight.Outcome(attributionUnverifiable())
		}
		is ExportPortableStepsResult.Unverifiable -> NativeAuthorityPreflight.Outcome(
			result.toImportResult(),
		)
		is ExportPortableStepsResult.RetryableFailure -> NativeAuthorityPreflight.Outcome(
			ImportPortableStepsResult.RetryableFailure(result.reason),
		)
	}

	private fun PortableStepsEntryV1.collisionWith(
		existingIdentities: Set<String>,
		existingDeletionScopes: List<String>,
	): NativeAuthorityResolution = when {
		identity.value in existingIdentities ->
			NativeAuthorityResolution.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
		runs.any { run -> run.identity.value in existingIdentities ||
			run.deletionScopeDigest.value in existingDeletionScopes
		} -> NativeAuthorityResolution.Conflict(PortableStepsConflictScope.PHYSICAL_RUN)
		runs.any { run -> run.facts.any { fact -> fact.identity.value in existingIdentities } } ->
			NativeAuthorityResolution.Conflict(PortableStepsConflictScope.FACT)
		else -> NativeAuthorityResolution.NoCollision
	}
}

private sealed interface NativeAuthorityPreflight {
	data class Stable(
		val evidenceState: SourceEvidenceState?,
		val resolution: NativeAuthorityResolution,
	) : NativeAuthorityPreflight

	data class Outcome(val result: ImportPortableStepsResult) : NativeAuthorityPreflight
}

private sealed interface NativeAuthorityResolution {
	data object NoCollision : NativeAuthorityResolution
	data object Duplicate : NativeAuthorityResolution
	data class Conflict(val scope: PortableStepsConflictScope) : NativeAuthorityResolution
}

private fun NativeAuthorityResolution.combine(other: NativeAuthorityResolution): NativeAuthorityResolution {
	if (this is NativeAuthorityResolution.Conflict || other is NativeAuthorityResolution.Conflict) {
		val scopes = listOfNotNull(
			(this as? NativeAuthorityResolution.Conflict)?.scope,
			(other as? NativeAuthorityResolution.Conflict)?.scope,
		)
		val scope = scopes.minBy { it.conflictPriority() }
		return NativeAuthorityResolution.Conflict(scope)
	}
	return if (this == NativeAuthorityResolution.Duplicate || other == NativeAuthorityResolution.Duplicate) {
		NativeAuthorityResolution.Duplicate
	} else {
		NativeAuthorityResolution.NoCollision
	}
}

private fun PortableStepsConflictScope.conflictPriority(): Int = when (this) {
	PortableStepsConflictScope.LOGICAL_ENTRY -> 0
	PortableStepsConflictScope.PHYSICAL_RUN -> 1
	PortableStepsConflictScope.FACT -> 2
}

private fun PortableStepsEntryV1.identityScopes(): Map<String, PortableStepsConflictScope> = buildMap {
	put(identity.value, PortableStepsConflictScope.LOGICAL_ENTRY)
	runs.forEach { run ->
		put(run.identity.value, PortableStepsConflictScope.PHYSICAL_RUN)
		run.facts.forEach { fact -> put(fact.identity.value, PortableStepsConflictScope.FACT) }
	}
}

private fun PortableStepsEntryV1.identityConflictWithinEntry(): PortableStepsConflictScope? {
	val seen = hashSetOf<String>()
	if (!seen.add(identity.value)) {
		return PortableStepsConflictScope.LOGICAL_ENTRY
	}
	for (run in runs) {
		if (!seen.add(run.identity.value)) {
			return PortableStepsConflictScope.PHYSICAL_RUN
		}
		for (fact in run.facts) {
			if (!seen.add(fact.identity.value)) {
				return PortableStepsConflictScope.FACT
			}
		}
	}
	return null
}

private fun PortableStepsEntryV1.snapshotAndValidate(): PortableStepsEntryV1 = try {
	copy(
		runs = runs.map { run ->
			run.copy(manifests = run.manifests.toList(), facts = run.facts.toList())
		},
	).also { snapshot ->
		require(
			com.adsamcik.tracker.stats.api.repository.PortableStepsIntegrity
				.expectedEntryChecksum(snapshot) == snapshot.contentChecksum,
		)
	}
} catch (failure: IllegalArgumentException) {
	throw PortableStepsSnapshotException(failure)
} catch (failure: ConcurrentModificationException) {
	throw PortableStepsSnapshotException(failure)
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

private fun ExportPortableStepsResult.Unverifiable.toImportResult(): ImportPortableStepsResult = when (reason) {
	PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING -> ImportPortableStepsResult.RetryableFailure(
		PortableStepsTransferRetryableReason.DAY_REPAIR_MATERIALIZING,
	)
	PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY ->
		ImportPortableStepsResult.OutsideRetention
	PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW,
	PortableStepsExportUnverifiableReason.RANGE_UNSUPPORTED,
	-> ImportPortableStepsResult.Unverifiable(PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW)
	PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	-> attributionUnverifiable()
}

private fun attributionUnverifiable() = ImportPortableStepsResult.Unverifiable(
	PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE,
)

private fun concurrentState() = ImportPortableStepsResult.RetryableFailure(
	PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
)

private fun storageUnavailable() = ImportPortableStepsResult.RetryableFailure(
	PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
)

private class PortableStepsSnapshotException(cause: Throwable) : IllegalStateException(cause)
private class PortableStepsConcurrentStateException : IllegalStateException()
