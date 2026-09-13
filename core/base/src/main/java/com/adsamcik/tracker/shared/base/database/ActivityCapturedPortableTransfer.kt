package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Stable, source-specific interchange limits for captured Activity history. */
object ActivityCapturedPortableFormatV1 {
	const val FORMAT = "tracker-portable-captured-activity"
	const val SCHEMA_VERSION = 1
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.captured-activity+json"
	const val FILE_EXTENSION = "trackeractivity"

	const val MAX_ENTRIES = 4_096
	const val MAX_RUNS = 16_384
	const val MAX_RUNS_PER_ENTRY = 64
	const val MAX_WINDOWS_PER_RUN = 2_048
	const val MAX_FRAGMENTS_PER_WINDOW = 1_024
	const val MAX_ZONE_EPOCHS_PER_RUN = 256
	const val MAX_ORIGIN_IDENTITY_LENGTH = 4_096
	const val MAX_TEXT_VALUE_LENGTH = 128
}

/** Local identifiers are irreversibly namespaced before crossing the source boundary. */
enum class PortableActivityIdentityKind {
	LOGICAL_ENTRY,
	PHYSICAL_RUN,
	CAPTURE_WINDOW,
}

@JvmInline
value class PortableActivityOpaqueIdentity(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}

	companion object {
		fun derive(
			kind: PortableActivityIdentityKind,
			localIdentity: String,
		): PortableActivityOpaqueIdentity {
			require(localIdentity.isNotBlank())
			require(localIdentity.length <= ActivityCapturedPortableFormatV1.MAX_ORIGIN_IDENTITY_LENGTH)
			return PortableActivityOpaqueIdentity(
				ActivityCapturedPortableIntegrity.digest(
					"tracker-portable-activity-identity-v1",
					listOf(kind.name, localIdentity),
				),
			)
		}
	}
}

@JvmInline
value class PortableActivityDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}
}

/** Exact payload-free key used by durable Activity no-resurrection fences. */
@JvmInline
value class PortableActivityDeletionScopeDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}

	companion object {
		fun derive(
			logicalTrackingId: String,
			serviceRunId: String,
		): PortableActivityDeletionScopeDigest = PortableActivityDeletionScopeDigest(
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			),
		)
	}
}

enum class PortableActivitySessionMode {
	MANUAL,
	AUTOMATIC,
}

enum class PortableActivityCaptureCoverage {
	WHOLE_RUN,
	PARTIAL_RUN,
	NOT_CAPTURED,
}

enum class PortableActivityWindowCoverage {
	NONE,
	PARTIAL,
	COMPLETE,
}

/** Stored civil-time authority, without manifest, policy, or provider identity. */
data class PortableActivityZoneEpochV1(
	val effectiveWallTimeMs: Long,
	val zoneId: String,
) {
	init {
		require(effectiveWallTimeMs >= 0L)
		requireBoundedText(zoneId)
		requireValidZone(zoneId)
	}
}

sealed interface PortableActivityFragmentV1 {
	val startOffsetNanos: Long
	val endOffsetNanos: Long

	data class Gap(
		override val startOffsetNanos: Long,
		override val endOffsetNanos: Long,
		val reason: String,
	) : PortableActivityFragmentV1 {
		init {
			requireValidOffsets(startOffsetNanos, endOffsetNanos)
			requireBoundedText(reason)
			require(reason in ACTIVITY_GAP_REASONS)
		}
	}

	@Suppress("LongParameterList")
	data class Band(
		override val startOffsetNanos: Long,
		override val endOffsetNanos: Long,
		val activity: String,
		val mechanism: String,
		val refinedTransitionActivity: String?,
		val confidenceKind: String,
		val confidenceMinimumPercent: Int?,
		val confidenceMaximumPercent: Int?,
		val confidenceObservationCount: Int?,
		val startWallTimeMs: Long,
		val startWallTimeUncertaintyMs: Long,
		val startBoundaryKind: String,
		val endWallTimeMs: Long,
		val endWallTimeUncertaintyMs: Long,
		val endBoundaryKind: String,
		val wallTimeContinuity: String,
	) : PortableActivityFragmentV1 {
		init {
			requireValidOffsets(startOffsetNanos, endOffsetNanos)
			listOf(
				activity,
				mechanism,
				confidenceKind,
				startBoundaryKind,
				endBoundaryKind,
				wallTimeContinuity,
			).forEach(::requireBoundedText)
			refinedTransitionActivity?.let(::requireBoundedText)
			require(activity in CAPTURED_ACTIVITY_TYPES)
			require(mechanism in ACTIVITY_BAND_MECHANISMS)
			require(refinedTransitionActivity == null ||
				refinedTransitionActivity in CAPTURED_ACTIVITY_TYPES)
			require(startBoundaryKind in ACTIVITY_BOUNDARY_KINDS)
			require(endBoundaryKind in ACTIVITY_BOUNDARY_KINDS)
			require(wallTimeContinuity in ACTIVITY_WALL_CONTINUITIES)
			require(startWallTimeMs >= 0L && endWallTimeMs >= 0L)
			require(startWallTimeUncertaintyMs >= 0L && endWallTimeUncertaintyMs >= 0L)
			if (confidenceMinimumPercent == null || confidenceMaximumPercent == null) {
				require(confidenceKind == ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION)
				require(mechanism == "TRANSITION" && refinedTransitionActivity == null)
				require(confidenceMinimumPercent == null && confidenceMaximumPercent == null)
				require(confidenceObservationCount == null)
			} else {
				require(confidenceKind == ActivityCapturedFragmentEntity.CONFIDENCE_SAMPLED)
				require(mechanism != "TRANSITION")
				require((mechanism == "SAMPLED_REFINEMENT") == (refinedTransitionActivity != null))
				if (refinedTransitionActivity != null) {
					require(isPortableCompatibleRefinement(refinedTransitionActivity, activity))
				}
				require(confidenceMinimumPercent in 0..100)
				require(confidenceMaximumPercent in confidenceMinimumPercent..100)
				require(confidenceObservationCount != null && confidenceObservationCount > 0)
			}
		}
	}
}

/** Latest effective state of one fully authenticated append-only correction lineage. */
@Suppress("LongParameterList")
data class PortableActivityWindowV1(
	val identity: PortableActivityOpaqueIdentity,
	val contentChecksum: PortableActivityDigest,
	val startOffsetNanos: Long,
	val endOffsetNanos: Long,
	val storedZoneId: String,
	val coverage: PortableActivityWindowCoverage,
	val knownActiveDurationNanos: Long,
	val knownInactiveDurationNanos: Long,
	val unknownActivityDurationNanos: Long,
	val unobservedDurationNanos: Long,
	val fragments: List<PortableActivityFragmentV1>,
) {
	init {
		requireValidOffsets(startOffsetNanos, endOffsetNanos)
		requireBoundedText(storedZoneId)
		requireValidZone(storedZoneId)
		listOf(
			knownActiveDurationNanos,
			knownInactiveDurationNanos,
			unknownActivityDurationNanos,
			unobservedDurationNanos,
		).forEach { duration -> require(duration >= 0L) }
		val duration = endOffsetNanos - startOffsetNanos
		require(
			Math.addExact(
				Math.addExact(knownActiveDurationNanos, knownInactiveDurationNanos),
				Math.addExact(unknownActivityDurationNanos, unobservedDurationNanos),
			) == duration,
		)
		require(fragments.isNotEmpty())
		require(fragments.size <= ActivityCapturedPortableFormatV1.MAX_FRAGMENTS_PER_WINDOW)
		require(fragments.first().startOffsetNanos == 0L)
		require(fragments.last().endOffsetNanos == duration)
		require(fragments.zipWithNext().all { (left, right) ->
			left.endOffsetNanos == right.startOffsetNanos
		})
		val bands = fragments.filterIsInstance<PortableActivityFragmentV1.Band>()
		val gaps = fragments.filterIsInstance<PortableActivityFragmentV1.Gap>()
		require(
			knownActiveDurationNanos == bands.filter { band -> band.activity in KNOWN_ACTIVE_TYPES }
				.sumExactDurations(),
		)
		require(
			knownInactiveDurationNanos == bands.filter { band -> band.activity in KNOWN_INACTIVE_TYPES }
				.sumExactDurations(),
		)
		require(
			unknownActivityDurationNanos == bands.filter { band ->
				band.activity !in KNOWN_ACTIVE_TYPES && band.activity !in KNOWN_INACTIVE_TYPES
			}.sumExactDurations(),
		)
		require(unobservedDurationNanos == gaps.sumExactDurations())
		when (coverage) {
			PortableActivityWindowCoverage.NONE -> require(
				bands.isEmpty() && gaps.isNotEmpty() && knownActiveDurationNanos == 0L &&
					knownInactiveDurationNanos == 0L && unknownActivityDurationNanos == 0L &&
					unobservedDurationNanos == duration,
			)
			PortableActivityWindowCoverage.PARTIAL -> require(
				bands.isNotEmpty() && gaps.isNotEmpty() && unobservedDurationNanos > 0L,
			)
			PortableActivityWindowCoverage.COMPLETE -> require(
				bands.isNotEmpty() && gaps.isEmpty() && unobservedDurationNanos == 0L,
			)
		}
		require(ActivityCapturedPortableIntegrity.windowChecksum(this) == contentChecksum)
	}
}

@Suppress("LongParameterList")
data class PortableActivityRunV1(
	val identity: PortableActivityOpaqueIdentity,
	val deletionScopeDigest: PortableActivityDeletionScopeDigest,
	val contentChecksum: PortableActivityDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val captureCoverage: PortableActivityCaptureCoverage,
	val zoneEpochs: List<PortableActivityZoneEpochV1>,
	val windows: List<PortableActivityWindowV1>,
) {
	init {
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(zoneEpochs.isNotEmpty())
		require(zoneEpochs.size <= ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS_PER_RUN)
		require(zoneEpochs == zoneEpochs.sortedWith(ZONE_EPOCH_ORDER))
		require(windows.size <= ActivityCapturedPortableFormatV1.MAX_WINDOWS_PER_RUN)
		require(windows == windows.sortedWith(WINDOW_ORDER))
		require(windows.map(PortableActivityWindowV1::identity).distinct().size == windows.size)
		when (captureCoverage) {
			PortableActivityCaptureCoverage.NOT_CAPTURED -> require(windows.isEmpty())
			PortableActivityCaptureCoverage.WHOLE_RUN,
			PortableActivityCaptureCoverage.PARTIAL_RUN,
			-> require(windows.isNotEmpty())
		}
		require(ActivityCapturedPortableIntegrity.runChecksum(this) == contentChecksum)
	}
}

data class PortableActivityEntryV1(
	val identity: PortableActivityOpaqueIdentity,
	val contentChecksum: PortableActivityDigest,
	val sessionMode: PortableActivitySessionMode,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val runs: List<PortableActivityRunV1>,
) {
	init {
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(runs.isNotEmpty())
		require(runs.size <= ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(runs == runs.sortedWith(RUN_ORDER))
		require(runs.map(PortableActivityRunV1::identity).distinct().size == runs.size)
		require(startTimeMs == runs.minOf(PortableActivityRunV1::startTimeMs))
		require(endTimeMs == runs.maxOf(PortableActivityRunV1::endTimeMs))
		require(runs.any { run -> run.captureCoverage != PortableActivityCaptureCoverage.NOT_CAPTURED })
		require(ActivityCapturedPortableIntegrity.entryChecksum(this) == contentChecksum)
	}
}

data class PortableActivityEnvelopeV1(
	val format: String = ActivityCapturedPortableFormatV1.FORMAT,
	val schemaVersion: Int = ActivityCapturedPortableFormatV1.SCHEMA_VERSION,
	val contentChecksum: PortableActivityDigest,
	val entries: List<PortableActivityEntryV1>,
) {
	init {
		require(format == ActivityCapturedPortableFormatV1.FORMAT)
		require(schemaVersion == ActivityCapturedPortableFormatV1.SCHEMA_VERSION)
		require(entries.isNotEmpty())
		require(entries.size <= ActivityCapturedPortableFormatV1.MAX_ENTRIES)
		require(entries == entries.sortedWith(ENTRY_ORDER))
		require(entries.map(PortableActivityEntryV1::identity).distinct().size == entries.size)
		require(ActivityCapturedPortableIntegrity.envelopeChecksum(entries) == contentChecksum)
	}
}

data class ExportPortableCapturedActivityRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
	}
}

fun interface PortableActivityEnvelopeSink {
	/** Receives one complete immutable envelope after its Room snapshot has ended. */
	suspend fun emit(envelope: PortableActivityEnvelopeV1)
}

interface ExportPortableCapturedActivity {
	suspend fun export(
		request: ExportPortableCapturedActivityRequest,
		sink: PortableActivityEnvelopeSink,
	): ExportPortableCapturedActivityResult
}

sealed interface ExportPortableCapturedActivityResult {
	data class Exported(val entryCount: Int) : ExportPortableCapturedActivityResult {
		init {
			require(entryCount > 0)
		}
	}

	data object NoEntries : ExportPortableCapturedActivityResult

	data class Unverifiable(
		val reason: PortableActivityExportUnverifiableReason,
	) : ExportPortableCapturedActivityResult

	data object StorageUnavailable : ExportPortableCapturedActivityResult
}

enum class PortableActivityExportUnverifiableReason {
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	ENTRY_MATERIALIZING,
	RETENTION_CROSSES_ENTRY,
	DELETED_SCOPE,
	DEPENDENCY_OVERFLOW,
}

/**
 * Source-owned exporter. The external sink is unreachable until the complete read-only snapshot
 * has committed and every selected replacement member has passed authentication.
 */
class RoomExportPortableCapturedActivity private constructor(
	private val reader: PortableCapturedActivityRoomReader,
	private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableCapturedActivity {
	constructor(
		database: AppDatabase,
		ioDispatcher: CoroutineDispatcher,
	) : this(PortableCapturedActivityRoomReader(database), ioDispatcher)

	override suspend fun export(
		request: ExportPortableCapturedActivityRequest,
		sink: PortableActivityEnvelopeSink,
	): ExportPortableCapturedActivityResult = withContext(ioDispatcher) {
		val snapshot = try {
			reader.read(request)
		} catch (cancelled: CancellationException) {
			if (!currentCoroutineContext().isActive) throw cancelled
			return@withContext ExportPortableCapturedActivityResult.StorageUnavailable
		} catch (_: Exception) {
			return@withContext ExportPortableCapturedActivityResult.StorageUnavailable
		}
		when (snapshot) {
			is PortableCapturedActivitySnapshot.Outcome -> snapshot.result
			is PortableCapturedActivitySnapshot.Ready -> {
				currentCoroutineContext().ensureActive()
				sink.emit(snapshot.envelope)
				ExportPortableCapturedActivityResult.Exported(snapshot.envelope.entries.size)
			}
		}
	}
}

internal enum class PortableActivityReadCheckpoint {
	TRANSACTION_STARTED,
	FACT_AUDIT_COMPLETED,
	REPLACEMENT_MEMBERS_LOADED,
	SNAPSHOT_READY,
}

internal data class PortableActivityReadLimits(
	val maintenance: ActivityCapturedMaintenanceLimits = ActivityCapturedMaintenanceLimits(),
	val maximumEntries: Int = ActivityCapturedPortableFormatV1.MAX_ENTRIES,
	val maximumRuns: Int = ActivityCapturedPortableFormatV1.MAX_RUNS,
	val maximumRunsPerEntry: Int = ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY,
	val maximumWindowsPerRun: Int = ActivityCapturedPortableFormatV1.MAX_WINDOWS_PER_RUN,
	val maximumManifestsPerRun: Int = ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS_PER_RUN,
	val maximumSourcesPerRun: Int = 3_072,
	val maximumTotalManifests: Int = 65_536,
	val maximumTotalSources: Int = 262_144,
) {
	init {
		listOf(
			maximumEntries,
			maximumRuns,
			maximumRunsPerEntry,
			maximumWindowsPerRun,
			maximumManifestsPerRun,
			maximumSourcesPerRun,
			maximumTotalManifests,
			maximumTotalSources,
		).forEach { value -> require(value in 1 until Int.MAX_VALUE) }
	}
}

internal sealed interface PortableCapturedActivitySnapshot {
	data class Ready(val envelope: PortableActivityEnvelopeV1) : PortableCapturedActivitySnapshot

	data class Outcome(
		val result: ExportPortableCapturedActivityResult,
	) : PortableCapturedActivitySnapshot {
		init {
			require(result !is ExportPortableCapturedActivityResult.Exported)
		}
	}
}

/** Bounded one-transaction reader; it owns no provider or projection lifecycle. */
internal class PortableCapturedActivityRoomReader(
	private val database: AppDatabase,
	private val limits: PortableActivityReadLimits = PortableActivityReadLimits(),
	private val checkpoint: suspend (PortableActivityReadCheckpoint) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
) {
	suspend fun read(
		request: ExportPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot = try {
		database.withTransaction {
			checkpoint(PortableActivityReadCheckpoint.TRANSACTION_STARTED)
			readInTransaction(request)
		}
	} catch (abort: PortableActivitySnapshotAbort) {
		outcome(abort.reason)
	} catch (_: ActivityCapturedMaintenanceLimitExceeded) {
		outcome(PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW)
	} catch (_: ActivityCapturedRetentionBlockedException) {
		outcome(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	} catch (_: IllegalArgumentException) {
		outcome(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	} catch (_: ArithmeticException) {
		outcome(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}

	private suspend fun readInTransaction(
		request: ExportPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot {
		val audit = database.auditCapturedActivityFacts(
			limits = limits.maintenance,
			checkpoint = { currentCoroutineContext().ensureActive() },
			requireOwnerWhenEmpty = false,
		)
		checkpoint(PortableActivityReadCheckpoint.FACT_AUDIT_COMPLETED)
		if (audit.lineages.isEmpty()) return noEntries()

		val latestByWindow = audit.lineages.associateWith { lineage -> lineage.revisions.last() }
		val factOwnerSegments = loadSegments(
			latestByWindow.values.map { persisted -> persisted.revision.sessionSegmentId }.distinct(),
		)
		val factOwnerSegmentsById = factOwnerSegments.associateBy(SessionSegment::id)
		val carrierLogicalIds = latestByWindow.values.asSequence().map { persisted ->
			val revision = persisted.revision
			val segment = factOwnerSegmentsById[revision.sessionSegmentId]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			validateSegmentBinding(segment, revision.logicalTrackingId, revision.serviceRunId)
			revision.logicalTrackingId
		}.distinct().sorted().toList()
		if (carrierLogicalIds.size > limits.maximumEntries) overflow()

		// Range is a property of the complete logical replacement group, never of the fact owner.
		val carrierRuns = loadReplacementRuns(carrierLogicalIds)
		checkpoint(PortableActivityReadCheckpoint.REPLACEMENT_MEMBERS_LOADED)
		val carrierSegments = loadSegments(carrierRuns.map { run ->
			run.sessionSegmentId
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}.distinct())
		val carrierSegmentsById = carrierSegments.associateBy(SessionSegment::id)
		carrierRuns.forEach { run ->
			val segment = carrierSegmentsById[requireNotNull(run.sessionSegmentId)]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			validateSegmentBinding(segment, run.logicalTrackingId, run.serviceRunId)
		}
		val selectedLogicalIds = carrierLogicalIds.filter { logicalTrackingId ->
			carrierRuns.asSequence()
				.filter { run -> run.logicalTrackingId == logicalTrackingId }
				.map { run -> carrierSegmentsById.getValue(requireNotNull(run.sessionSegmentId)) }
				.any { segment ->
					segment.startTimeMs < request.toExclusiveMs &&
						segment.endTimeMs > request.fromInclusiveMs
				}
		}
		if (selectedLogicalIds.isEmpty()) return noEntries()

		val selectedLineages = latestByWindow.filterValues { persisted ->
			persisted.revision.logicalTrackingId in selectedLogicalIds
		}
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		selectedLineages.forEach { (lineage, persisted) ->
			if (persisted.revision.collectedDataEpoch != evidenceState.collectedDataEpoch) {
				abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			if (evidenceState.retainedFromMs?.let { floor ->
				lineage.earliestPossibleWallTimeMs?.let { earliest -> earliest < floor } ?: true
			} == true) abort(PortableActivityExportUnverifiableReason.RETENTION_CROSSES_ENTRY)
		}

		val runs = carrierRuns.filter { run -> run.logicalTrackingId in selectedLogicalIds }
		val sessions = loadSessions(selectedLogicalIds)
		val segmentsById = carrierSegmentsById.filterKeys { segmentId ->
			segmentId in runs.mapNotNullTo(hashSetOf(), SourceServiceRunEntity::sessionSegmentId)
		}
		val manifestDependencies = loadManifestDependencies(runs)
		validateLogicalManifestUnions(
			selectedLogicalIds,
			runs,
			manifestDependencies.manifestsByRun,
		)
		val lineagesByRun = selectedLineages.keys.groupBy { lineage ->
			lineage.revisions.last().revision.serviceRunId
		}
		validateDeletionFences(runs)

		val entries = selectedLogicalIds.map { logicalTrackingId ->
			currentCoroutineContext().ensureActive()
			buildEntry(
				logicalTrackingId = logicalTrackingId,
				session = sessions[logicalTrackingId],
				runs = runs.filter { run -> run.logicalTrackingId == logicalTrackingId },
				segmentsById = segmentsById,
				lineagesByRun = lineagesByRun,
				manifestsByRun = manifestDependencies.manifestsByRun,
				sourcesByRun = manifestDependencies.sourcesByRun,
			)
		}.sortedWith(ENTRY_ORDER)
		checkpoint(PortableActivityReadCheckpoint.SNAPSHOT_READY)
		val checksum = ActivityCapturedPortableIntegrity.envelopeChecksum(entries)
		return PortableCapturedActivitySnapshot.Ready(
			PortableActivityEnvelopeV1(contentChecksum = checksum, entries = entries),
		)
	}

	private suspend fun loadReplacementRuns(
		logicalTrackingIds: List<String>,
	): List<SourceServiceRunEntity> {
		val result = mutableListOf<SourceServiceRunEntity>()
		logicalTrackingIds.chunked(SQLITE_BIND_BATCH).forEach { identities ->
			var afterLogicalId: String? = null
			var afterStartedAtMs: Long? = null
			var afterRunId: String? = null
			do {
				currentCoroutineContext().ensureActive()
				val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
					logicalTrackingIds = identities,
					limit = minOf(READ_PAGE_SIZE, limits.maximumRuns - result.size + 1),
					afterLogicalTrackingId = afterLogicalId,
					afterStartedAtMs = afterStartedAtMs,
					afterServiceRunId = afterRunId,
				)
				result += page
				if (result.size > limits.maximumRuns) overflow()
				val last = page.lastOrNull()
				afterLogicalId = last?.logicalTrackingId
				afterStartedAtMs = last?.startedAtMs
				afterRunId = last?.serviceRunId
			} while (page.size == READ_PAGE_SIZE)
		}
		if (result.map(SourceServiceRunEntity::serviceRunId).distinct().size != result.size ||
			result.map(SourceServiceRunEntity::logicalTrackingId).toSet() != logicalTrackingIds.toSet()
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		return result.sortedWith(RAW_RUN_ORDER)
	}

	private suspend fun loadSessions(
		logicalTrackingIds: List<String>,
	): Map<String, LogicalTrackingSessionEntity> {
		val sessions = logicalTrackingIds.chunked(SQLITE_BIND_BATCH).flatMap { identities ->
			database.sourceSessionDao().sessions(identities)
		}
		val result = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId)
		if (result.size != sessions.size || result.keys != logicalTrackingIds.toSet()) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return result
	}

	private suspend fun loadSegments(segmentIds: List<Long>): List<SessionSegment> {
		if (segmentIds.size > limits.maximumRuns) overflow()
		val rows = segmentIds.chunked(SQLITE_BIND_BATCH).flatMap { ids ->
			database.trackingHistoryReadDao().segments(ids)
		}
		if (rows.map(SessionSegment::id).distinct().size != rows.size ||
			rows.map(SessionSegment::id).toSet() != segmentIds.toSet()
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		return rows
	}

	private suspend fun validateDeletionFences(runs: List<SourceServiceRunEntity>) {
		val scopes = runs.associateWith { run ->
			PortableActivityDeletionScopeDigest.derive(
				run.logicalTrackingId,
				run.serviceRunId,
			).value
		}
		val fences = scopes.values.chunked(SQLITE_BIND_BATCH).flatMap { digests ->
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = digests,
			)
		}
		if (fences.isNotEmpty()) abort(PortableActivityExportUnverifiableReason.DELETED_SCOPE)
	}

	private suspend fun loadManifestDependencies(
		runs: List<SourceServiceRunEntity>,
	): ActivityPortableManifestDependencies {
		val manifests = mutableListOf<SessionManifestVersionEntity>()
		val sources = mutableListOf<SessionManifestSourceEntity>()
		runs.map(SourceServiceRunEntity::serviceRunId).chunked(SQLITE_BIND_BATCH).forEach { runIds ->
			currentCoroutineContext().ensureActive()
			val manifestPage = database.trackingHistoryReadDao().manifests(
				serviceRunIds = runIds,
				limit = limits.maximumTotalManifests - manifests.size + 1,
			)
			manifests += manifestPage
			if (manifests.size > limits.maximumTotalManifests) overflow()
			val sourcePage = database.trackingHistoryReadDao().manifestSources(
				serviceRunIds = runIds,
				limit = limits.maximumTotalSources - sources.size + 1,
			)
			sources += sourcePage
			if (sources.size > limits.maximumTotalSources) overflow()
		}
		val runIds = runs.mapTo(hashSetOf(), SourceServiceRunEntity::serviceRunId)
		val logicalIds = runs.mapTo(hashSetOf(), SourceServiceRunEntity::logicalTrackingId)
		if (manifests.any { manifest -> manifest.serviceRunId !in runIds } ||
			sources.any { source -> source.logicalTrackingId !in logicalIds }
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val manifestRunByKey = manifests.associate { manifest ->
			(manifest.logicalTrackingId to manifest.manifestRevision) to manifest.serviceRunId
		}
		if (manifestRunByKey.size != manifests.size) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return ActivityPortableManifestDependencies(
			manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByRun = sources.groupBy { source ->
				manifestRunByKey[source.logicalTrackingId to source.manifestRevision]
					?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			},
		)
	}

	private fun validateLogicalManifestUnions(
		logicalTrackingIds: List<String>,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	) {
		logicalTrackingIds.forEach { logicalTrackingId ->
			val revisionSlices = runs.filter { run -> run.logicalTrackingId == logicalTrackingId }
				.map { run ->
					manifestsByRun[run.serviceRunId].orEmpty()
						.map(SessionManifestVersionEntity::manifestRevision)
				}
			if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(revisionSlices)) {
				abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
	}

	private suspend fun buildEntry(
		logicalTrackingId: String,
		session: LogicalTrackingSessionEntity?,
		runs: List<SourceServiceRunEntity>,
		segmentsById: Map<Long, SessionSegment>,
		lineagesByRun: Map<String, List<ActivityCapturedLineage>>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
	): PortableActivityEntryV1 {
		val exactSession = session
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (exactSession.state !in TERMINAL_SESSION_STATES ||
			exactSession.currentServiceRunId != null || exactSession.completedAtMs == null ||
			exactSession.cutoffAtMs == null || exactSession.cutoffElapsedNanos == null
		) abort(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		val currentManifestRevision = runs.asSequence()
			.flatMap { run -> manifestsByRun[run.serviceRunId].orEmpty().asSequence() }
			.maxOfOrNull(SessionManifestVersionEntity::manifestRevision)
		if (exactSession.currentManifestRevision != currentManifestRevision) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		if (runs.isEmpty() || runs.size > limits.maximumRunsPerEntry) overflow()
		val portableRuns = runs.map { run ->
			val segmentId = run.sessionSegmentId
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val segment = segmentsById[segmentId]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			validateSegmentBinding(segment, logicalTrackingId, run.serviceRunId)
			buildRun(
				run,
				segment,
				lineagesByRun[run.serviceRunId].orEmpty(),
				manifestsByRun[run.serviceRunId].orEmpty(),
				sourcesByRun[run.serviceRunId].orEmpty(),
			)
		}.sortedWith(RUN_ORDER)
		val sessionMode = when (exactSession.sessionMode) {
			"MANUAL" -> PortableActivitySessionMode.MANUAL
			"AUTOMATIC" -> PortableActivitySessionMode.AUTOMATIC
			else -> abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			logicalTrackingId,
		)
		val start = portableRuns.minOf(PortableActivityRunV1::startTimeMs)
		val end = portableRuns.maxOf(PortableActivityRunV1::endTimeMs)
		val checksum = ActivityCapturedPortableIntegrity.entryChecksum(
			identity,
			sessionMode,
			start,
			end,
			portableRuns,
		)
		return PortableActivityEntryV1(identity, checksum, sessionMode, start, end, portableRuns)
	}

	private fun buildRun(
		run: SourceServiceRunEntity,
		segment: SessionSegment,
		lineages: List<ActivityCapturedLineage>,
		manifests: List<SessionManifestVersionEntity>,
		sources: List<SessionManifestSourceEntity>,
	): PortableActivityRunV1 {
		if (run.state !in TERMINAL_RUN_STATES || run.completedAtMs == null ||
			run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_QUIESCED
		) abort(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		if (manifests.size > limits.maximumManifestsPerRun) overflow()
		if (sources.size > limits.maximumSourcesPerRun ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) ||
			manifests.any { manifest ->
				!SessionManifestIntegrity.verify(
					manifest,
					sources.filter { source -> source.belongsTo(manifest) },
				)
			}
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)

		val capturedManifestRevisions = manifests.filter { manifest ->
			sources.any { source -> source.isCapturedActivityMember(manifest) }
		}.mapTo(linkedSetOf(), SessionManifestVersionEntity::manifestRevision)
		val factManifestRevisions = lineages.mapTo(linkedSetOf()) { lineage ->
			lineage.revisions.last().revision.manifestRevision
		}
		if (factManifestRevisions != capturedManifestRevisions) {
			abort(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		if (lineages.size > limits.maximumWindowsPerRun) overflow()
		val captureCoverage = when {
			capturedManifestRevisions.isEmpty() -> PortableActivityCaptureCoverage.NOT_CAPTURED
			capturedManifestRevisions.size == manifests.size ->
				PortableActivityCaptureCoverage.WHOLE_RUN
			else -> PortableActivityCaptureCoverage.PARTIAL_RUN
		}
		val zones = manifests.map { manifest ->
			PortableActivityZoneEpochV1(manifest.effectiveWallTimeMs, manifest.zoneId)
		}.sortedWith(ZONE_EPOCH_ORDER)
		val windows = lineages.map { lineage -> buildWindow(run, lineage) }.sortedWith(WINDOW_ORDER)
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			run.serviceRunId,
		)
		val deletionScope = PortableActivityDeletionScopeDigest.derive(
			run.logicalTrackingId,
			run.serviceRunId,
		)
		val checksum = ActivityCapturedPortableIntegrity.runChecksum(
			identity,
			deletionScope,
			segment.startTimeMs,
			segment.endTimeMs,
			captureCoverage,
			zones,
			windows,
		)
		return PortableActivityRunV1(
			identity,
			deletionScope,
			checksum,
			segment.startTimeMs,
			segment.endTimeMs,
			captureCoverage,
			zones,
			windows,
		)
	}

	private fun buildWindow(
		run: SourceServiceRunEntity,
		lineage: ActivityCapturedLineage,
	): PortableActivityWindowV1 {
		val current = lineage.revisions.last()
		val revision = current.revision
		val start = Math.subtractExact(
			revision.windowStartElapsedRealtimeNanos,
			run.startedElapsedNanos,
		)
		val end = Math.subtractExact(
			revision.windowEndElapsedRealtimeNanos,
			run.startedElapsedNanos,
		)
		val fragments = current.fragments.map { fragment ->
			val fragmentStart = Math.subtractExact(
				fragment.intervalStartElapsedRealtimeNanos,
				revision.windowStartElapsedRealtimeNanos,
			)
			val fragmentEnd = Math.subtractExact(
				fragment.intervalEndElapsedRealtimeNanos,
				revision.windowStartElapsedRealtimeNanos,
			)
			if (fragment.fragmentKind == ActivityCapturedFragmentEntity.KIND_GAP) {
				PortableActivityFragmentV1.Gap(
					fragmentStart,
					fragmentEnd,
					requireNotNull(fragment.gapReason),
				)
			} else {
				PortableActivityFragmentV1.Band(
					fragmentStart,
					fragmentEnd,
					requireNotNull(fragment.activity),
					requireNotNull(fragment.mechanism),
					fragment.refinedTransitionActivity,
					requireNotNull(fragment.confidenceKind),
					fragment.confidenceMinimumPercent,
					fragment.confidenceMaximumPercent,
					fragment.confidenceObservationCount,
					requireNotNull(fragment.startWallTimeMs),
					requireNotNull(fragment.startWallTimeUncertaintyMs),
					requireNotNull(fragment.startBoundaryKind),
					requireNotNull(fragment.endWallTimeMs),
					requireNotNull(fragment.endWallTimeUncertaintyMs),
					requireNotNull(fragment.endBoundaryKind),
					requireNotNull(fragment.wallTimeContinuity),
				)
			}
		}
		val coverage = when (revision.coverage) {
			"NONE" -> PortableActivityWindowCoverage.NONE
			"PARTIAL" -> PortableActivityWindowCoverage.PARTIAL
			"COMPLETE" -> PortableActivityWindowCoverage.COMPLETE
			else -> abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			revision.logicalWindowId,
		)
		val checksum = ActivityCapturedPortableIntegrity.windowChecksum(
			identity,
			start,
			end,
			revision.storedZoneId,
			coverage,
			revision.knownActiveDurationNanos,
			revision.knownInactiveDurationNanos,
			revision.unknownActivityDurationNanos,
			revision.unobservedDurationNanos,
			fragments,
		)
		return PortableActivityWindowV1(
			identity,
			checksum,
			start,
			end,
			revision.storedZoneId,
			coverage,
			revision.knownActiveDurationNanos,
			revision.knownInactiveDurationNanos,
			revision.unknownActivityDurationNanos,
			revision.unobservedDurationNanos,
			fragments,
		)
	}
}

/** Canonical semantic checksums deliberately exclude every local or provider identity. */
object ActivityCapturedPortableIntegrity {
	fun windowChecksum(value: PortableActivityWindowV1): PortableActivityDigest = windowChecksum(
		value.identity,
		value.startOffsetNanos,
		value.endOffsetNanos,
		value.storedZoneId,
		value.coverage,
		value.knownActiveDurationNanos,
		value.knownInactiveDurationNanos,
		value.unknownActivityDurationNanos,
		value.unobservedDurationNanos,
		value.fragments,
	)

	@Suppress("LongParameterList")
	fun windowChecksum(
		identity: PortableActivityOpaqueIdentity,
		startOffsetNanos: Long,
		endOffsetNanos: Long,
		storedZoneId: String,
		coverage: PortableActivityWindowCoverage,
		knownActiveDurationNanos: Long,
		knownInactiveDurationNanos: Long,
		unknownActivityDurationNanos: Long,
		unobservedDurationNanos: Long,
		fragments: List<PortableActivityFragmentV1>,
	): PortableActivityDigest = PortableActivityDigest(
		digest(
			"tracker-portable-activity-window-v1",
			listOf(
				identity.value,
				startOffsetNanos,
				endOffsetNanos,
				storedZoneId,
				coverage,
				knownActiveDurationNanos,
				knownInactiveDurationNanos,
				unknownActivityDurationNanos,
				unobservedDurationNanos,
			).map(Any::toString) + fragments.flatMap(::fragmentParts),
		),
	)

	fun runChecksum(value: PortableActivityRunV1): PortableActivityDigest = runChecksum(
		value.identity,
		value.deletionScopeDigest,
		value.startTimeMs,
		value.endTimeMs,
		value.captureCoverage,
		value.zoneEpochs,
		value.windows,
	)

	@Suppress("LongParameterList")
	fun runChecksum(
		identity: PortableActivityOpaqueIdentity,
		deletionScopeDigest: PortableActivityDeletionScopeDigest,
		startTimeMs: Long,
		endTimeMs: Long,
		captureCoverage: PortableActivityCaptureCoverage,
		zoneEpochs: List<PortableActivityZoneEpochV1>,
		windows: List<PortableActivityWindowV1>,
	): PortableActivityDigest = PortableActivityDigest(
		digest(
			"tracker-portable-activity-run-v1",
			listOf(
				identity.value,
				deletionScopeDigest.value,
				startTimeMs.toString(),
				endTimeMs.toString(),
				captureCoverage.name,
			) + zoneEpochs.flatMap { epoch -> listOf(epoch.effectiveWallTimeMs.toString(), epoch.zoneId) } +
				windows.flatMap { window -> listOf(window.identity.value, window.contentChecksum.value) },
		),
	)

	fun entryChecksum(value: PortableActivityEntryV1): PortableActivityDigest = entryChecksum(
		value.identity,
		value.sessionMode,
		value.startTimeMs,
		value.endTimeMs,
		value.runs,
	)

	fun entryChecksum(
		identity: PortableActivityOpaqueIdentity,
		sessionMode: PortableActivitySessionMode,
		startTimeMs: Long,
		endTimeMs: Long,
		runs: List<PortableActivityRunV1>,
	): PortableActivityDigest = PortableActivityDigest(
		digest(
			"tracker-portable-activity-entry-v1",
			listOf(
				identity.value,
				sessionMode.name,
				startTimeMs.toString(),
				endTimeMs.toString(),
			) + runs.flatMap { run -> listOf(run.identity.value, run.contentChecksum.value) },
		),
	)

	fun envelopeChecksum(entries: List<PortableActivityEntryV1>): PortableActivityDigest =
		PortableActivityDigest(
			digest(
				"tracker-portable-activity-envelope-v1",
				entries.flatMap { entry -> listOf(entry.identity.value, entry.contentChecksum.value) },
			),
		)

	internal fun digest(namespace: String, values: List<String>): String {
		val canonical = (listOf(namespace) + values).joinToString(separator = "") { value ->
			"${value.length}:$value"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private fun fragmentParts(fragment: PortableActivityFragmentV1): List<String> = when (fragment) {
		is PortableActivityFragmentV1.Gap -> listOf(
			"GAP",
			fragment.startOffsetNanos.toString(),
			fragment.endOffsetNanos.toString(),
			fragment.reason,
		)
		is PortableActivityFragmentV1.Band -> listOf(
			"BAND",
			fragment.startOffsetNanos,
			fragment.endOffsetNanos,
			fragment.activity,
			fragment.mechanism,
			fragment.refinedTransitionActivity,
			fragment.confidenceKind,
			fragment.confidenceMinimumPercent,
			fragment.confidenceMaximumPercent,
			fragment.confidenceObservationCount,
			fragment.startWallTimeMs,
			fragment.startWallTimeUncertaintyMs,
			fragment.startBoundaryKind,
			fragment.endWallTimeMs,
			fragment.endWallTimeUncertaintyMs,
			fragment.endBoundaryKind,
			fragment.wallTimeContinuity,
		).map { value -> value?.toString() ?: "null" }
	}
}

private fun SessionManifestSourceEntity.belongsTo(manifest: SessionManifestVersionEntity): Boolean =
	logicalTrackingId == manifest.logicalTrackingId && manifestRevision == manifest.manifestRevision

private fun SessionManifestSourceEntity.isCapturedActivityMember(
	manifest: SessionManifestVersionEntity,
): Boolean = belongsTo(manifest) &&
	sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY &&
	purpose == SessionManifestPurposeCode.SESSION_CAPTURE && persistenceEligible &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
	writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
	writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
	writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION &&
	writerBindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION

private fun validateSegmentBinding(
	segment: SessionSegment,
	logicalTrackingId: String,
	serviceRunId: String,
) {
	if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId ||
		segment.startTimeMs < 0L || segment.endTimeMs < segment.startTimeMs
	) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
}

private fun requireValidOffsets(start: Long, end: Long) {
	require(start >= 0L && end > start)
}

private fun Collection<PortableActivityFragmentV1>.sumExactDurations(): Long = fold(0L) { total, item ->
	Math.addExact(total, item.endOffsetNanos - item.startOffsetNanos)
}

private fun requireBoundedText(value: String) {
	require(value.isNotBlank())
	require(value.length <= ActivityCapturedPortableFormatV1.MAX_TEXT_VALUE_LENGTH)
}

private fun requireValidZone(zoneId: String) {
	try {
		ZoneId.of(zoneId)
	} catch (_: DateTimeException) {
		throw IllegalArgumentException("Invalid portable Activity zone")
	}
}

private fun isPortableCompatibleRefinement(coarse: String, detail: String): Boolean = when (coarse) {
	"ON_FOOT" -> detail == "WALKING" || detail == "RUNNING"
	"UNKNOWN" -> detail != "UNKNOWN"
	else -> false
}

private fun outcome(
	reason: PortableActivityExportUnverifiableReason,
): PortableCapturedActivitySnapshot.Outcome = PortableCapturedActivitySnapshot.Outcome(
	ExportPortableCapturedActivityResult.Unverifiable(reason),
)

private fun noEntries(): PortableCapturedActivitySnapshot.Outcome =
	PortableCapturedActivitySnapshot.Outcome(ExportPortableCapturedActivityResult.NoEntries)

private fun overflow(): Nothing =
	abort(PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW)

private fun abort(reason: PortableActivityExportUnverifiableReason): Nothing =
	throw PortableActivitySnapshotAbort(reason)

private class PortableActivitySnapshotAbort(
	val reason: PortableActivityExportUnverifiableReason,
) : IllegalStateException(reason.name)

private data class ActivityPortableManifestDependencies(
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
)

private val ZONE_EPOCH_ORDER = compareBy<PortableActivityZoneEpochV1>(
	PortableActivityZoneEpochV1::effectiveWallTimeMs,
).thenBy(PortableActivityZoneEpochV1::zoneId)

private val WINDOW_ORDER = compareBy<PortableActivityWindowV1>(
	PortableActivityWindowV1::startOffsetNanos,
).thenBy(PortableActivityWindowV1::endOffsetNanos)
	.thenBy { window -> window.identity.value }

private val RUN_ORDER = compareBy<PortableActivityRunV1>(PortableActivityRunV1::startTimeMs)
	.thenBy(PortableActivityRunV1::endTimeMs)
	.thenBy { run -> run.identity.value }

private val ENTRY_ORDER = compareBy<PortableActivityEntryV1>(PortableActivityEntryV1::startTimeMs)
	.thenBy(PortableActivityEntryV1::endTimeMs)
	.thenBy { entry -> entry.identity.value }

private val RAW_RUN_ORDER = compareBy<SourceServiceRunEntity>(SourceServiceRunEntity::logicalTrackingId)
	.thenBy(SourceServiceRunEntity::startedAtMs)
	.thenBy(SourceServiceRunEntity::serviceRunId)

private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "CLOSED", "FAILED")
private val TERMINAL_RUN_STATES = setOf("FINALIZED", "CLOSED", "FAILED")
private val CAPTURED_ACTIVITY_TYPES = setOf(
	"STILL", "WALKING", "RUNNING", "ON_BICYCLE", "IN_VEHICLE", "ON_FOOT", "TILTING", "UNKNOWN",
)
private val KNOWN_ACTIVE_TYPES = setOf(
	"WALKING", "RUNNING", "ON_BICYCLE", "IN_VEHICLE", "ON_FOOT",
)
private val KNOWN_INACTIVE_TYPES = setOf("STILL")
private val ACTIVITY_BAND_MECHANISMS = setOf(
	"TRANSITION", "SAMPLED_REFINEMENT", "SAMPLED_CLASSIFICATION",
)
private val ACTIVITY_GAP_REASONS = setOf(
	"NO_QUALIFIED_EVIDENCE",
	"PROVIDER_DISCONTINUITY",
	"AUTHORIZATION_DISCONTINUITY",
	"PROCESS_OR_REBOOT_DISCONTINUITY",
	"SOURCE_REJECTED_EVIDENCE",
)
private val ACTIVITY_BOUNDARY_KINDS = setOf(
	"EXACT_PROVIDER_OBSERVATION", "SAME_CLOCK_EXTRAPOLATION",
)
private val ACTIVITY_WALL_CONTINUITIES = setOf(
	"SAME_ANCHOR", "CONSISTENT_WITHIN_UNCERTAINTY", "DISCONTINUITY_DETECTED",
)
private const val READ_PAGE_SIZE = 256
private const val SQLITE_BIND_BATCH = 128
private val SHA_256_HEX = Regex("[0-9a-f]{64}")
