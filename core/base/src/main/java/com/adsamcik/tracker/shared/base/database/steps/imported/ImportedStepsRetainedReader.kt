package com.adsamcik.tracker.shared.base.database.steps.imported

import androidx.room.useReaderConnection
import java.time.DateTimeException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsManifestEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_FACT_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_RUN_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsManifestV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSessionMode
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1

/** Failure is typed; malformed retained evidence never becomes a numeric zero or live capture. */
enum class ImportedStepsReadFailure { RETENTION, DELETION, INTEGRITY, DEPENDENCY_OVERFLOW, MISSING }

/** Authentication outcome for one bounded Room transaction snapshot. Storage failures propagate. */
sealed interface ImportedStepsRetainedRead {
	/** Verified members plus isolated entry-local failures, without per-row Room fan-out. */
	data class Ready(
		val entries: List<RetainedImportedStepsEntry>,
		val unverifiableEntries: Map<String, ImportedStepsReadFailure> = emptyMap(),
	) : ImportedStepsRetainedRead
	/** Complete singleton failure or unresolved batch dependency failure. */
	data class Unverifiable(val reason: ImportedStepsReadFailure) : ImportedStepsRetainedRead
}

/** Result of a keyset traversal that never retains more than one complete imported entry. */
sealed interface ImportedStepsRetainedTraversal {
	data class Complete(val entryCount: Long) : ImportedStepsRetainedTraversal {
		init {
			require(entryCount >= 0L)
		}
	}

	data class Unverifiable(
		val entryIdentity: String,
		val reason: ImportedStepsReadFailure,
	) : ImportedStepsRetainedTraversal
}

private sealed interface ImportedStepsRetainedTraversalStep {
	data object End : ImportedStepsRetainedTraversalStep

	data class Consumed(
		val startTimeMs: Long,
		val identity: String,
	) : ImportedStepsRetainedTraversalStep

	data class Unverifiable(
		val entryIdentity: String,
		val reason: ImportedStepsReadFailure,
	) : ImportedStepsRetainedTraversalStep
}

/**
 * Independently authenticated surviving members. [portable] is non-null only if the complete
 * original entry checksum still matches; deleting one sibling does not disqualify another.
 */
data class RetainedImportedStepsEntry(
	val metadata: ImportedStepsEntryEntity,
	val runs: List<ImportedStepsRunEntity>,
	val segmentsByRun: Map<String, SessionSegment>,
	val factsByRun: Map<String, List<StepFactRevisionEntity>>,
	val portableRunsById: Map<String, PortableStepsRunV1>,
	val portable: PortableStepsEntryV1?,
	val retentionTruncatedRunIds: Set<String> = emptySet(),
)

/**
 * Source-specific retained authentication shared by history, export and destructive preflight.
 * Caller owns the Room transaction. Reads use the immutable admission-owner receipt, not today's
 * destination owner: rolling back a writer must not reinterpret previously accepted history.
 */
class ImportedStepsRetainedReader(private val database: AppDatabase) {
	/** Normal product read, including current monotonic retention floor. */
	suspend fun readEntriesInTransaction(entryIds: List<String>): ImportedStepsRetainedRead =
		read(entryIds, enforceFloor = true)

	/** Same authentication before retention pruning; only the floor rejection is postponed. */
	suspend fun readEntriesForRetentionInTransaction(entryIds: List<String>): ImportedStepsRetainedRead =
		read(entryIds, enforceFloor = false)

	/**
	 * Authenticates newest-first keyset members one at a time. The callback completes before the
	 * next product is assembled, so destructive callers can stage compact global authority on disk
	 * without retaining a page of maximum-shaped entries.
	 */
	suspend fun forEachEntryForRetentionInTransaction(
		maximumEntries: Long = Long.MAX_VALUE,
		consume: suspend (RetainedImportedStepsEntry) -> Unit,
	): ImportedStepsRetainedTraversal {
		require(maximumEntries > 0L)
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		var entryCount = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			when (
				val next = authenticateAndConsumeNext(
					beforeStartTimeMs = beforeStartTimeMs,
					beforeIdentity = beforeIdentity,
					canConsume = entryCount < maximumEntries,
					consume = consume,
				)
			) {
				ImportedStepsRetainedTraversalStep.End ->
					return ImportedStepsRetainedTraversal.Complete(entryCount)
				is ImportedStepsRetainedTraversalStep.Unverifiable ->
					return ImportedStepsRetainedTraversal.Unverifiable(
						entryIdentity = next.entryIdentity,
						reason = next.reason,
					)
				is ImportedStepsRetainedTraversalStep.Consumed -> {
					entryCount = Math.addExact(entryCount, 1L)
					beforeStartTimeMs = next.startTimeMs
					beforeIdentity = next.identity
				}
			}
		}
	}

	private suspend fun authenticateAndConsumeNext(
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		canConsume: Boolean,
		consume: suspend (RetainedImportedStepsEntry) -> Unit,
	): ImportedStepsRetainedTraversalStep {
		val page = if (beforeStartTimeMs == null) {
			check(beforeIdentity == null)
			database.importedStepsDao().firstEntryPage(SINGLE_ENTRY_PAGE_SIZE)
		} else {
			database.importedStepsDao().entryPageAfter(
				beforeStartTimeMs,
				requireNotNull(beforeIdentity),
				SINGLE_ENTRY_PAGE_SIZE,
			)
		}
		if (page.isEmpty()) return ImportedStepsRetainedTraversalStep.End
		check(page.size == SINGLE_ENTRY_PAGE_SIZE)
		val metadata = page.single()
		check(
			beforeStartTimeMs == null ||
				metadata.startTimeMs < beforeStartTimeMs ||
				(metadata.startTimeMs == beforeStartTimeMs &&
					metadata.identity < requireNotNull(beforeIdentity)),
		) {
			"Imported Steps traversal keyset did not advance"
		}
		if (!canConsume) {
			return ImportedStepsRetainedTraversalStep.Unverifiable(
				entryIdentity = metadata.identity,
				reason = ImportedStepsReadFailure.DEPENDENCY_OVERFLOW,
			)
		}
		val retained = when (
			val read = readWithoutAdaptiveSplit(
				listOf(metadata.identity),
				enforceFloor = false,
			)
		) {
			is ImportedStepsRetainedRead.Unverifiable ->
				return ImportedStepsRetainedTraversalStep.Unverifiable(
					entryIdentity = metadata.identity,
					reason = read.reason,
				)
			is ImportedStepsRetainedRead.Ready -> {
				val entry = read.entries.singleOrNull()
					?: return ImportedStepsRetainedTraversalStep.Unverifiable(
						entryIdentity = metadata.identity,
						reason = read.unverifiableEntries[metadata.identity]
							?: ImportedStepsReadFailure.MISSING,
					)
				if (read.unverifiableEntries.isNotEmpty() || entry.metadata != metadata) {
					return ImportedStepsRetainedTraversalStep.Unverifiable(
						entryIdentity = metadata.identity,
						reason = read.unverifiableEntries[metadata.identity]
							?: ImportedStepsReadFailure.INTEGRITY,
					)
				}
				entry
			}
		}
		currentCoroutineContext().ensureActive()
		consume(retained)
		return ImportedStepsRetainedTraversalStep.Consumed(
			startTimeMs = metadata.startTimeMs,
			identity = metadata.identity,
		)
	}

	private suspend fun read(entryIds: List<String>, enforceFloor: Boolean): ImportedStepsRetainedRead {
		if (entryIds.isEmpty()) { return ImportedStepsRetainedRead.Ready(emptyList()) }
		if (entryIds.size > MAX_ENTRY_BATCH || entryIds.distinct().size != entryIds.size) {
			return failure(ImportedStepsReadFailure.DEPENDENCY_OVERFLOW)
		}
		val result = readWithoutAdaptiveSplit(entryIds, enforceFloor)
		val splittable = result is ImportedStepsRetainedRead.Unverifiable && result.reason in setOf(
			ImportedStepsReadFailure.DEPENDENCY_OVERFLOW, ImportedStepsReadFailure.INTEGRITY,
			ImportedStepsReadFailure.MISSING,
		)
		if (!splittable || entryIds.size == 1) { return result }
		val entries = mutableListOf<RetainedImportedStepsEntry>()
		val failures = linkedMapOf<String, ImportedStepsReadFailure>()
		for (batch in entryIds.chunked((entryIds.size + 1) / 2)) {
			when (val smaller = read(batch, enforceFloor)) {
				is ImportedStepsRetainedRead.Ready -> {
					entries += smaller.entries
					failures += smaller.unverifiableEntries
				}
				is ImportedStepsRetainedRead.Unverifiable -> batch.forEach {
					failures[it] = smaller.reason
				}
			}
		}
		return ImportedStepsRetainedRead.Ready(entries, failures)
	}

	private suspend fun readWithoutAdaptiveSplit(
		entryIds: List<String>,
		enforceFloor: Boolean,
	): ImportedStepsRetainedRead {
		return try {
			database.useReaderConnection { connection ->
				check(connection.inTransaction()) { "Imported Steps authentication requires one transaction" }
				readSnapshot(entryIds, enforceFloor)
			}
		} catch (_: IllegalArgumentException) {
			failure(ImportedStepsReadFailure.INTEGRITY)
		} catch (_: DateTimeException) {
			failure(ImportedStepsReadFailure.INTEGRITY)
		}
	}

	// Explicit fail-closed guards share one fixed dependency snapshot; no generic validation framework.
	@Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
	private suspend fun readSnapshot(entryIds: List<String>, enforceFloor: Boolean): ImportedStepsRetainedRead {
		val dao = database.importedStepsDao()
		val malformedEntryIds = dao.malformedReadEntryIds(entryIds).toSet()
		val entries = dao.entries(entryIds, entryIds.size + 1)
		if (entries.size != entryIds.size) { return failure(ImportedStepsReadFailure.MISSING) }
		val state = database.sourceEvidenceStateDao().get()
			?: return failure(ImportedStepsReadFailure.MISSING)
		val runs = dao.runsForEntries(entryIds, MAX_RUN_BATCH + 1)
		if (runs.size > MAX_RUN_BATCH) { return failure(ImportedStepsReadFailure.DEPENDENCY_OVERFLOW) }
		val runIds = runs.map(ImportedStepsRunEntity::identity)
		val manifests = dao.manifestsForRuns(runIds, MAX_MANIFEST_BATCH + 1)
		val rawFacts = database.stepFactRevisionDao().revisionsForImportedRuns(runIds, MAX_FACT_BATCH + 1)
		if (manifests.size > MAX_MANIFEST_BATCH || rawFacts.size > MAX_FACT_BATCH) {
			return failure(ImportedStepsReadFailure.DEPENDENCY_OVERFLOW)
		}
		val facts = rawFacts.map { requireNotNull(it.validatedOrNull()) }
		val factsByIdentity = facts.associateBy(StepFactRevisionEntity::logicalFactId)
		if (factsByIdentity.size != facts.size) { return failure(ImportedStepsReadFailure.INTEGRITY) }
		// A redacted later revision can have no run identity. Check the global identity lineage too.
		for (ids in facts.map(StepFactRevisionEntity::logicalFactId).chunked(SQLITE_ID_BATCH)) {
			val lineage = database.stepFactRevisionDao().revisionsForFactIdentities(ids, ids.size + 1)
				.map { requireNotNull(it.validatedOrNull()) }
			if (lineage.size != ids.size || lineage.toSet() != ids.map { factsByIdentity.getValue(it) }.toSet()) {
				return failure(ImportedStepsReadFailure.INTEGRITY)
			}
		}
		val history = database.trackingHistoryReadDao()
		val segments = history.segments(runs.mapNotNull(ImportedStepsRunEntity::sessionSegmentId)).associateBy { it.id }
		if (history.serviceRuns(runIds).isNotEmpty()) { return failure(ImportedStepsReadFailure.INTEGRITY) }
		val fences = history.deletionFences(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			runs.map(ImportedStepsRunEntity::deletionScopeDigest),
		)
		val truncationFences = history.deletionFences(
			SourceDestinationOwnerEntity.SOURCE_STEPS, StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, runs.map(ImportedStepsRunEntity::deletionScopeDigest),
		)
		if (truncationFences.any { it.collectedDataEpoch != state.collectedDataEpoch || it.fenceGeneration != 1L }) {
			return failure(ImportedStepsReadFailure.INTEGRITY)
		}
		val truncatedScopes = truncationFences.mapTo(hashSetOf()) { it.scopeIdentityDigest }
		val deletedScopes = fences.mapTo(hashSetOf()) { it.scopeIdentityDigest }
		val failures = linkedMapOf<String, ImportedStepsReadFailure>()
		val manifestsByRun = manifests.groupBy(ImportedStepsManifestEntity::runIdentity)
		val factsByRun = facts.groupBy { requireNotNull(it.serviceRunId) }
		val result = entries.mapNotNull { entry ->
			if (entry.identity in malformedEntryIds) {
				failures[entry.identity] = ImportedStepsReadFailure.INTEGRITY
				return@mapNotNull null
			}
			val entryRuns = runs.filter { it.entryIdentity == entry.identity }
			if (entryRuns.any { it.deletionScopeDigest in deletedScopes }) {
				failures[entry.identity] = ImportedStepsReadFailure.DELETION
				return@mapNotNull null
			}
			val verified = try {
				require(entry.collectedDataEpoch == state.collectedDataEpoch)
				ImportedStepsRetainedValidator.validate(
				entry, runs.filter { it.entryIdentity == entry.identity }, segments,
				manifestsByRun, factsByRun,
			)
			} catch (_: IllegalArgumentException) {
				failures[entry.identity] = ImportedStepsReadFailure.INTEGRITY
				return@mapNotNull null
			} catch (_: DateTimeException) {
				failures[entry.identity] = ImportedStepsReadFailure.INTEGRITY
				return@mapNotNull null
			}
			val truncatedIds = verified.runs.filter { it.deletionScopeDigest in truncatedScopes }
				.mapTo(hashSetOf()) { it.identity }
			val floor = state.retainedFromMs
			if (enforceFloor && floor != null && violatesFloor(verified, truncatedIds, floor)) {
				failures[entry.identity] = ImportedStepsReadFailure.RETENTION
				return@mapNotNull null
			}
			verified.copy(retentionTruncatedRunIds = truncatedIds,
				portable = verified.portable.takeIf { truncatedIds.isEmpty() })
		}
		if (entryIds.size == 1 && failures.isNotEmpty()) { return failure(failures.values.single()) }
		return ImportedStepsRetainedRead.Ready(result, failures)
	}

	private fun violatesFloor(entry: RetainedImportedStepsEntry, truncatedIds: Set<String>, floor: Long): Boolean {
		val unmarkedRun = entry.runs.any { it.startTimeMs < floor && it.identity !in truncatedIds }
		val expiredFact = entry.factsByRun.values.flatten().any { requireNotNull(it.intervalEndTimeMs) < floor }
		val unmarkedStraddle = entry.factsByRun.any { (runId, facts) ->
			runId !in truncatedIds && facts.any { requireNotNull(it.intervalStartTimeMs) < floor }
		}
		return unmarkedRun || expiredFact || unmarkedStraddle
	}

	private fun failure(reason: ImportedStepsReadFailure) = ImportedStepsRetainedRead.Unverifiable(reason)

	/** Bounded source-local snapshot limits, adaptively split for otherwise legal aggregates. */
	companion object {
		/** Bounded batch, one complete portable entry is always within the dependency budget. */
		const val MAX_ENTRY_BATCH = 32
		private const val MAX_RUN_BATCH = StepsPortableFormatV1.MAX_RUNS_PER_ENTRY
		private const val MAX_MANIFEST_BATCH = MAX_RUN_BATCH * StepsPortableFormatV1.MAX_MANIFESTS_PER_RUN
		private const val MAX_FACT_BATCH = MAX_RUN_BATCH * StepsPortableFormatV1.MAX_FACTS_PER_RUN
		private const val SQLITE_ID_BATCH = 400
		private const val SINGLE_ENTRY_PAGE_SIZE = 1
	}
}

/** Pure source-specific validation; transaction loading and current fences remain the reader's job. */
object ImportedStepsRetainedValidator {
	/** Throws only for invalid retained hierarchy; independent surviving members need no deleted payload. */
	fun validate(
		entry: ImportedStepsEntryEntity,
		runs: List<ImportedStepsRunEntity>,
		segments: Map<Long, SessionSegment>,
		manifestsByRun: Map<String, List<ImportedStepsManifestEntity>>,
		factsByRun: Map<String, List<StepFactRevisionEntity>>,
	): RetainedImportedStepsEntry {
		require(entry.writerOwnerGeneration != null)
		require(runs.isNotEmpty() && runs.size <= StepsPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(runs.map { it.identity }.distinct().size == runs.size)
		require(runs.map { it.deletionScopeDigest }.distinct().size == runs.size)
		val portableRuns = runs.associate { run ->
			require(run.entryIdentity == entry.identity)
			val segment = requireNotNull(segments[run.sessionSegmentId])
			requireExactSegment(entry, run, segment)
			val portable = portableRun(entry, run, manifestsByRun[run.identity].orEmpty(), factsByRun[run.identity].orEmpty())
			require(run.retainedChecksum == ImportedStepsRetainedIntegrity.runChecksum(entry, run, portable))
			run.identity to portable
		}
		val portable = try {
			PortableStepsEntryV1(
				PortableStepsOpaqueIdentity(entry.identity), PortableStepsDigest(entry.contentChecksum),
				PortableStepsSessionMode.valueOf(entry.sessionMode), entry.startTimeMs, entry.endTimeMs,
				portableRuns.values.sortedWith(PORTABLE_STEPS_RUN_ORDER),
			)
		} catch (_: IllegalArgumentException) { null }
		return RetainedImportedStepsEntry(
			entry, runs, runs.associate { it.identity to requireNotNull(segments[it.sessionSegmentId]) },
			runs.associate { it.identity to factsByRun[it.identity].orEmpty() }, portableRuns, portable,
		)
	}

	private fun requireExactSegment(
		entry: ImportedStepsEntryEntity, run: ImportedStepsRunEntity, segment: SessionSegment,
	) {
		require(segment.id > 0L && segment.id == run.sessionSegmentId)
		require(segment.logicalTrackingId == entry.identity && segment.serviceRunId == run.identity)
		require(segment.startTimeMs == run.startTimeMs && segment.endTimeMs == run.endTimeMs)
		require(segment.source == SegmentSource.PORTABLE_STEPS_IMPORT)
		require(segment.sampleCount == 0)
		require(segment.steps == null)
		require(segment.distanceM == 0f)
		require(segment.primaryActivity == null)
		require(segment.activityConfidence == null)
		require(!segment.hasDistanceAnomaly)
		require(segment.inferenceVersion == null)
	}

	private fun portableRun(
		entry: ImportedStepsEntryEntity,
		run: ImportedStepsRunEntity,
		manifests: List<ImportedStepsManifestEntity>,
		facts: List<StepFactRevisionEntity>,
	): PortableStepsRunV1 {
		val manifestsByRevision = manifests.associateBy { it.revision }
		val portableFacts = facts.map { fact ->
			require(StepFactRevisionIntegrity.hasValidPortableImportFact(fact))
			require(fact.logicalTrackingId == entry.identity && fact.serviceRunId == run.identity)
			require(fact.collectedDataEpoch == entry.collectedDataEpoch &&
				fact.writerBindingGeneration == StepFactRevisionIntegrity.PORTABLE_IMPORT_BINDING_GENERATION)
			val manifest = requireNotNull(manifestsByRevision[fact.manifestRevision])
			require(manifest.runIdentity == run.identity && fact.sourcePolicyRevision == manifest.originSourcePolicyRevision &&
				fact.captureConsentEpoch == manifest.captureConsentEpoch)
			PortableStepsFactV1.create(
				PortableStepsOpaqueIdentity(fact.logicalFactId), requireNotNull(fact.manifestRevision),
				requireNotNull(fact.intervalStartTimeMs), requireNotNull(fact.intervalEndTimeMs),
				requireNotNull(fact.wallTimeUncertaintyMs), PortableStepsFactCoverage.valueOf(requireNotNull(fact.coverageKind)),
				fact.effectiveStepCount,
			)
		}
		require(manifests.all { it.runIdentity == run.identity })
		return PortableStepsRunV1(
			PortableStepsOpaqueIdentity(run.identity), PortableStepsDeletionScopeDigest(run.deletionScopeDigest),
			run.startTimeMs, run.endTimeMs, run.storedZoneId,
			manifests.map {
				PortableStepsManifestV1(it.revision, it.effectiveWallTimeMs,
					it.originSourcePolicyRevision, it.captureConsentEpoch)
			},
			PortableStepsCompletenessV1(PortableStepsCaptureCoverage.valueOf(run.captureCoverage),
				PortableStepsProviderCoverage.valueOf(run.providerCoverage), run.appDrainComplete, run.stopComplete,
				run.hasUnresolvedProviderRange),
			portableFacts.sortedWith(PORTABLE_STEPS_FACT_ORDER),
		)
	}
}
