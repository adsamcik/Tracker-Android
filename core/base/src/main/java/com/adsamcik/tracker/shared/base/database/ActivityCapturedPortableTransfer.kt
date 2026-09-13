package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedPortableWalTargetRow
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		ioDispatcher: CoroutineDispatcher,
	) : this(PortableCapturedActivityRoomReader(database, laneExecutionAuthority), ioDispatcher)

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
	val maximumCompletenessRows: Int = 65_536,
	val maximumCapturedWalRows: Int = 65_536,
	val maximumLifecycleActions: Int = 65_536,
	val maximumCaptureAuthorizations: Int = 65_536,
	val maximumTerminalFailures: Int = 4_096,
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
			maximumCompletenessRows,
			maximumCapturedWalRows,
			maximumLifecycleActions,
			maximumCaptureAuthorizations,
			maximumTerminalFailures,
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
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
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
		validateTerminalProjectionSettlement(
			sessions = sessions,
			runs = runs,
			lineagesByRun = lineagesByRun,
			manifestsByRun = manifestDependencies.manifestsByRun,
			sourcesByRun = manifestDependencies.sourcesByRun,
			collectedDataEpoch = evidenceState.collectedDataEpoch,
		)
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

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun validateTerminalProjectionSettlement(
		sessions: Map<String, LogicalTrackingSessionEntity>,
		runs: List<SourceServiceRunEntity>,
		lineagesByRun: Map<String, List<ActivityCapturedLineage>>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
		collectedDataEpoch: Long,
	) {
		if (sessions.values.any { session ->
			session.state !in TERMINAL_SESSION_STATES || session.currentServiceRunId != null ||
				session.completedAtMs == null || session.cutoffAtMs == null ||
				session.cutoffElapsedNanos == null
		}) abort(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)

		val runIds = runs.map(SourceServiceRunEntity::serviceRunId)
		val capturedRuns = linkedSetOf<String>()
		runs.forEach { run ->
			val manifests = manifestsByRun[run.serviceRunId].orEmpty()
			val sources = sourcesByRun[run.serviceRunId].orEmpty()
			if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) ||
				manifests.any { manifest ->
					!SessionManifestIntegrity.verify(
						manifest,
						sources.filter { source -> source.belongsTo(manifest) },
					)
				} || sources.any { source ->
					source.sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY &&
						source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
						source.persistenceEligible &&
						manifests.singleOrNull { manifest -> source.belongsTo(manifest) }
							?.let(source::isCapturedActivityMember) != true
				}
			) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			if (manifests.any { manifest ->
				sources.any { source -> source.isCapturedActivityMember(manifest) }
			}) capturedRuns += run.serviceRunId
		}

		val completeness = loadPortableCompleteness(runIds)
		if (!hasValidPortableCompleteness(completeness, runs) ||
			completeness.mapTo(hashSetOf(), SourceSessionCompletenessEntity::serviceRunId) != runIds.toSet()
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (completeness.any { row -> !row.appDrainComplete }) {
			abort(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		validatePortableCompletenessAuthority(
			rows = completeness,
			runs = runs,
			manifestsByRun = manifestsByRun,
			sourcesByRun = sourcesByRun,
			collectedDataEpoch = collectedDataEpoch,
		)
		val completenessTargetByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId)
			.mapValues { (_, rows) ->
				rows.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull()
			}
		if (capturedRuns.any { runId -> completenessTargetByRun[runId] == null }) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val retainedWalTargetByRun = loadPortableCapturedWalTargets(
			runs = runs,
			manifestsByRun = manifestsByRun,
			sourcesByRun = sourcesByRun,
			collectedDataEpoch = collectedDataEpoch,
		)
		if (retainedWalTargetByRun.any { (runId, retainedTarget) ->
				retainedTarget > (completenessTargetByRun[runId] ?: 0L)
			}
		) abort(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		val targetByRun = completenessTargetByRun.mapValues { (runId, completenessTarget) ->
			completenessTarget?.let { target ->
				maxOf(target, retainedWalTargetByRun[runId] ?: 0L)
			}
		}
		val groupTarget = targetByRun.values.filterNotNull().maxOrNull()
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)

		val lane = database.sourceProjectionStateDao().productLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			bindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
		) ?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (!laneExecutionAuthority.owns(lane) || !hasValidPortableActivityLane(lane)) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val requiredModeMask = sessions.values.fold(0L) { mask, session ->
			mask or when (session.sessionMode) {
				"MANUAL" -> MANUAL_SESSION_CAPTURE_MASK
				"AUTOMATIC" -> AUTOMATIC_SESSION_CAPTURE_MASK
				else -> abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
		if (requiredModeMask == 0L || lane.captureModeMask and requiredModeMask != requiredModeMask ||
			manifestsByRun.values.flatten().any { manifest ->
				lane.activatedRolloutRevision > manifest.rolloutRevision
			} || groupTarget < lane.activationOrdinal ||
			lane.captureAdmissionCutoffOrdinal?.let { cutoff -> groupTarget > cutoff } == true
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)

		sessions.values.forEach { session ->
			val sessionTarget = runs.asSequence()
				.filter { run -> run.logicalTrackingId == session.logicalTrackingId }
				.mapNotNull { run -> targetByRun[run.serviceRunId] }
				.maxOrNull()
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			if (session.finalAdmissionOrdinal?.let { final -> final >= sessionTarget } != true) {
				abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
		lineagesByRun.forEach { (runId, lineages) ->
			val target = targetByRun[runId]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			if (lineages.asSequence().flatMap { lineage -> lineage.revisions.asSequence() }
				.flatMap { revision -> revision.evidence.asSequence() }
				.any { evidence ->
					evidence.sourceAdmissionOrdinal < lane.activationOrdinal ||
						evidence.sourceAdmissionOrdinal > target
				}
			) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}

		val failures = database.trackingHistoryReadDao().terminalFailuresForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			serviceRunIds = runIds,
			afterOrdinal = lane.activationOrdinal - 1L,
			throughOrdinal = groupTarget,
			limit = limits.maximumTerminalFailures + 1,
		)
		if (failures.size > limits.maximumTerminalFailures) overflow()
		if (failures.isNotEmpty()) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		if (lane.contiguousAdmissionOrdinal < groupTarget) {
			abort(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
	}

	private suspend fun loadPortableCompleteness(
		runIds: List<String>,
	): List<SourceSessionCompletenessEntity> {
		val result = mutableListOf<SourceSessionCompletenessEntity>()
		runIds.chunked(SQLITE_BIND_BATCH).forEach { batch ->
			currentCoroutineContext().ensureActive()
			val page = database.activityCapturedFactDao().portableCompleteness(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				serviceRunIds = batch,
				limit = limits.maximumCompletenessRows - result.size + 1,
			)
			result += page
			if (result.size > limits.maximumCompletenessRows) overflow()
		}
		return result
	}

	private suspend fun loadPortableCapturedWalTargets(
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
		collectedDataEpoch: Long,
	): Map<String, Long> {
		val rows = mutableListOf<ActivityCapturedPortableWalTargetRow>()
		runs.map(SourceServiceRunEntity::serviceRunId).chunked(SQLITE_BIND_BATCH).forEach { runIds ->
			currentCoroutineContext().ensureActive()
			rows += database.activityCapturedFactDao().portableCapturedWalTargets(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				serviceRunIds = runIds,
				capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				limit = limits.maximumCapturedWalRows - rows.size + 1,
			)
			if (rows.size > limits.maximumCapturedWalRows) overflow()
		}
		val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
		rows.forEach { row ->
			val runId = row.serviceRunId
			val run = runId?.let(runsById::get)
			val manifestRevision = row.sessionManifestRevision
			val manifest = manifestsByRun[runId].orEmpty().singleOrNull { candidate ->
				candidate.manifestRevision == manifestRevision
			}
			val source = sourcesByRun[runId].orEmpty().singleOrNull { candidate ->
				manifest != null && candidate.isCapturedActivityMember(manifest)
			}
			if (run == null || manifest == null || source == null ||
				row.logicalTrackingId != run.logicalTrackingId || row.admissionOrdinal <= 0L ||
				row.sourceSequence <= 0L || row.sourceInstanceId.isBlank() ||
				row.registrationGeneration <= 0L ||
				row.physicalConfigurationFingerprint.isNullOrBlank() ||
				row.authorizationRevision?.let { revision -> revision > 0L } != true ||
				row.authorizationFingerprint.isNullOrBlank() ||
				row.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
				row.configRevision != manifest.acquisitionPlanRevision ||
				row.clockDomainId != run.bootId || row.clockDomainId != manifest.effectiveBootId ||
				row.observedElapsedNanos < manifest.effectiveElapsedRealtimeNanos ||
				row.receivedElapsedNanos < row.observedElapsedNanos ||
				row.capturedCollectedDataEpoch != collectedDataEpoch ||
				row.sourcePolicyRevision != manifest.sourcePolicyRevision ||
				row.captureConsentEpoch != source.consentEpoch ||
				row.lifecycleLeaseGeneration != run.leaseGeneration ||
				!SHA_256_HEX.matches(row.integrityIdentity)
			) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return rows.groupBy { row -> requireNotNull(row.serviceRunId) }
			.mapValues { (_, values) -> values.maxOf { row -> row.admissionOrdinal } }
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun validatePortableCompletenessAuthority(
		rows: List<SourceSessionCompletenessEntity>,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
		collectedDataEpoch: Long,
	) {
		val positiveRows = rows.filter { row -> row.registrationGeneration > 0L }
		if (positiveRows.isEmpty()) return
		val generations = positiveRows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct()
		val plans = loadPortableRegistrationPlans(generations)
		val expectedPlanKeys = positiveRows.mapTo(hashSetOf()) { row ->
			row.sourceInstanceId to row.registrationGeneration
		}
		val plansByKey = plans.associateBy { plan -> plan.sourceInstanceId to plan.registrationGeneration }
		if (plansByKey.size != plans.size || plansByKey.keys != expectedPlanKeys) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val desiredPlans = loadPortableDesiredPlans(
			plans.map(ActivityCapturedRegistrationPlanEntity::configurationRevision).distinct(),
		)
		val desiredByRevision = desiredPlans.associateBy(SourceDesiredPlanEntity::revision)
		if (desiredByRevision.size != desiredPlans.size ||
			desiredByRevision.keys != plans.mapTo(hashSetOf(), ActivityCapturedRegistrationPlanEntity::configurationRevision)
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val providers = loadPortableProviderRegistrations(generations)
		val providersByGeneration = providers.associateBy(ProviderRegistrationGenerationEntity::registrationGeneration)
		if (providersByGeneration.size != providers.size || providersByGeneration.keys != generations.toSet()) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val runIds = runs.map(SourceServiceRunEntity::serviceRunId)
		val actions = loadPortableLifecycleActions(runIds)
		val authorizations = loadPortableCaptureAuthorizations(generations, runIds)
		val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
		positiveRows.forEach { row ->
			val plan = plansByKey[row.sourceInstanceId to row.registrationGeneration]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val desired = desiredByRevision[plan.configurationRevision]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val provider = providersByGeneration[row.registrationGeneration]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val decodedPlan = PortableActivityPlanIntegrity.decode(desired)
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val manifests = manifestsByRun[row.serviceRunId].orEmpty()
			val sources = sourcesByRun[row.serviceRunId].orEmpty()
			val run = runsById[row.serviceRunId]
				?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val matchingManifests = manifests.filter { manifest ->
				manifest.acquisitionPlanRevision == plan.configurationRevision &&
					sources.any { source -> source.isCapturedActivityMember(manifest) }
			}
			val acceptedActions = actions.filter { action ->
				action.serviceRunId == row.serviceRunId &&
					action.sourceInstanceId == row.sourceInstanceId &&
					action.registrationGeneration == row.registrationGeneration &&
					action.status == PORTABLE_START_ACCEPTED &&
					action.desiredState == PORTABLE_ACTION_STARTED
			}
			val capturedAuthorizations = authorizations.filter { authorization ->
				authorization.serviceRunId == row.serviceRunId &&
					authorization.registrationGeneration == row.registrationGeneration
			}
			if (plan.desiredPlanPayloadVersion != desired.payloadVersion ||
				!plan.desiredPlanPayload.contentEquals(desired.payload) ||
				plan.desiredPlanPayloadChecksum != desired.payloadChecksum ||
				plan.desiredPlanPayloadChecksum != sha256(plan.desiredPlanPayload) ||
				plan.bindingIdentity != plan.calculatedBindingIdentity() || !decodedPlan.enabled ||
				plan.physicalConfigurationFingerprint != decodedPlan.physicalConfigurationFingerprint ||
				matchingManifests.isEmpty() || acceptedActions.isEmpty() ||
				capturedAuthorizations.isEmpty() ||
				provider.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
				provider.sourceInstanceId != row.sourceInstanceId ||
				provider.registrationGeneration != row.registrationGeneration ||
				provider.ownerScope != PORTABLE_ACTIVITY_PROVIDER_OWNER_SCOPE ||
				provider.clockDomainId != run.bootId ||
				provider.physicalConfigurationFingerprint != plan.physicalConfigurationFingerprint ||
				provider.collectedDataEpoch != collectedDataEpoch || provider.acceptedAtMs == null ||
				provider.acceptedElapsedRealtimeNanos == null ||
				provider.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
				provider.providerProcessIncarnationId.isNullOrBlank() ||
				provider.status !in PORTABLE_ACCEPTED_PROVIDER_STATUSES ||
				!provider.hasValidPortableChronology(plan) ||
				provider.captureCallbackBarrierAuthorizationRevision <
					capturedAuthorizations.maxOf(SourceAuthorizationEntity::authorizationRevision)
			) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			acceptedActions.forEach { action ->
				validatePortableAcceptedAction(action, run, matchingManifests, sources, provider, plan)
			}
			capturedAuthorizations.forEach { authorization ->
				validatePortableCaptureAuthorization(
					authorization,
					run,
					manifests,
					sources,
					provider,
					acceptedActions,
				)
			}
		}
	}

	private suspend fun loadPortableLifecycleActions(
		runIds: List<String>,
	): List<LifecycleDesiredActionEntity> {
		val result = mutableListOf<LifecycleDesiredActionEntity>()
		runIds.chunked(SQLITE_BIND_BATCH).forEach { batch ->
			currentCoroutineContext().ensureActive()
			result += database.activityCapturedFactDao().portableLifecycleActions(
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				batch,
				limits.maximumLifecycleActions - result.size + 1,
			)
			if (result.size > limits.maximumLifecycleActions) overflow()
		}
		return result
	}

	private suspend fun loadPortableCaptureAuthorizations(
		generations: List<Long>,
		runIds: List<String>,
	): List<SourceAuthorizationEntity> {
		val result = mutableListOf<SourceAuthorizationEntity>()
		generations.chunked(SQLITE_BIND_BATCH).forEach { generationBatch ->
			runIds.chunked(SQLITE_BIND_BATCH).forEach { runBatch ->
				currentCoroutineContext().ensureActive()
				result += database.activityCapturedFactDao().portableCaptureAuthorizations(
					SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
					generationBatch,
					runBatch,
					SourceBrokerPurpose.SESSION_CAPTURE,
					limits.maximumCaptureAuthorizations - result.size + 1,
				)
				if (result.size > limits.maximumCaptureAuthorizations) overflow()
			}
		}
		return result
	}

	@Suppress("ComplexCondition")
	private fun ProviderRegistrationGenerationEntity.hasValidPortableChronology(
		plan: ActivityCapturedRegistrationPlanEntity,
	): Boolean {
		val acceptedWall = acceptedAtMs ?: return false
		val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
		if (reservedAtMs > acceptedWall || reservedElapsedRealtimeNanos > acceptedElapsed ||
			plan.appliedAtElapsedRealtimeNanos != acceptedElapsed
		) return false
		return when (status) {
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
				retiredAtMs == null && retiredElapsedRealtimeNanos == null && failureCode == null
			ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			-> retiredAtMs?.let { retiredWall -> retiredWall >= acceptedWall } == true &&
				retiredElapsedRealtimeNanos?.let { retiredElapsed -> retiredElapsed >= acceptedElapsed } == true
			else -> false
		}
	}

	@Suppress("ComplexCondition")
	private fun validatePortableAcceptedAction(
		action: LifecycleDesiredActionEntity,
		run: SourceServiceRunEntity,
		matchingManifests: List<SessionManifestVersionEntity>,
		sources: List<SessionManifestSourceEntity>,
		provider: ProviderRegistrationGenerationEntity,
		plan: ActivityCapturedRegistrationPlanEntity,
	) {
		val manifest = matchingManifests.singleOrNull { candidate ->
			candidate.manifestRevision == action.manifestRevision
		} ?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val source = sources.singleOrNull { candidate -> candidate.isCapturedActivityMember(manifest) }
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val acknowledgedWall = action.acknowledgedAtMs
		val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos
		if (action.actionId.isBlank() || action.logicalTrackingId != run.logicalTrackingId ||
			action.serviceRunId != run.serviceRunId || action.actionRevision <= 0L ||
			action.actionFamily != PORTABLE_SOURCE_RUNTIME_ACTION ||
			action.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
			action.desiredState != PORTABLE_ACTION_STARTED ||
			action.desiredPlanRevision != plan.configurationRevision ||
			action.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			action.consentEpoch != source.consentEpoch || action.startOrigin != manifest.startOrigin ||
			action.bootId != run.bootId || action.bootId != manifest.effectiveBootId ||
			action.leaseGeneration != run.leaseGeneration || action.attemptCount <= 0 ||
			action.status != PORTABLE_START_ACCEPTED || action.failureCode != null ||
			action.retryTrigger != null || action.sourceInstanceId != provider.sourceInstanceId ||
			action.registrationGeneration != provider.registrationGeneration ||
			action.requestedAtMs < run.startedAtMs ||
			action.requestedAtMs < manifest.effectiveWallTimeMs ||
			action.requestedElapsedRealtimeNanos < run.startedElapsedNanos ||
			action.requestedElapsedRealtimeNanos < manifest.effectiveElapsedRealtimeNanos ||
			acknowledgedWall == null || acknowledgedElapsed == null ||
			acknowledgedWall < action.requestedAtMs ||
			acknowledgedElapsed < action.requestedElapsedRealtimeNanos ||
			acknowledgedWall < requireNotNull(provider.acceptedAtMs) ||
			acknowledgedElapsed < requireNotNull(provider.acceptedElapsedRealtimeNanos) ||
			acknowledgedElapsed < plan.appliedAtElapsedRealtimeNanos ||
			acknowledgedWall > requireNotNull(run.completedAtMs) ||
			provider.retiredAtMs?.let { retired -> acknowledgedWall > retired } == true ||
			provider.retiredElapsedRealtimeNanos?.let { retired ->
				acknowledgedElapsed > retired
			} == true
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}

	@Suppress("ComplexCondition")
	private fun validatePortableCaptureAuthorization(
		authorization: SourceAuthorizationEntity,
		run: SourceServiceRunEntity,
		manifests: List<SessionManifestVersionEntity>,
		sources: List<SessionManifestSourceEntity>,
		provider: ProviderRegistrationGenerationEntity,
		acceptedActions: List<LifecycleDesiredActionEntity>,
	) {
		val manifestRevision = authorization.manifestRevision
		val manifest = manifests.singleOrNull { candidate ->
			candidate.manifestRevision == manifestRevision
		} ?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val source = sources.singleOrNull { candidate -> candidate.isCapturedActivityMember(manifest) }
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val acceptedElapsed = requireNotNull(provider.acceptedElapsedRealtimeNanos)
		val acceptedWall = requireNotNull(provider.acceptedAtMs)
		if (authorization.isDenyAll || authorization.memberId.isBlank() ||
			authorization.authorizationRevision <= 0L ||
			authorization.authorizationFingerprint.isBlank() ||
			authorization.purpose != SourceBrokerPurpose.SESSION_CAPTURE ||
			authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			authorization.logicalTrackingId != run.logicalTrackingId ||
			authorization.serviceRunId != run.serviceRunId ||
			authorization.lifecycleLeaseGeneration != run.leaseGeneration ||
			authorization.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			authorization.consentEpoch != source.consentEpoch || !authorization.persistenceEligible ||
			authorization.effectiveBootId != run.bootId ||
			authorization.effectiveBootId != manifest.effectiveBootId ||
			authorization.effectiveElapsedRealtimeNanos < run.startedElapsedNanos ||
			authorization.effectiveElapsedRealtimeNanos < manifest.effectiveElapsedRealtimeNanos ||
			authorization.effectiveElapsedRealtimeNanos < acceptedElapsed ||
			authorization.effectiveWallTimeMs < run.startedAtMs ||
			authorization.effectiveWallTimeMs < manifest.effectiveWallTimeMs ||
			authorization.effectiveWallTimeMs < acceptedWall ||
			authorization.effectiveWallTimeMs > requireNotNull(run.completedAtMs) ||
			provider.retiredElapsedRealtimeNanos?.let { retired ->
				authorization.effectiveElapsedRealtimeNanos > retired
			} == true || provider.retiredAtMs?.let { retired ->
				authorization.effectiveWallTimeMs > retired
			} == true || acceptedActions.none { action ->
				action.manifestRevision == manifest.manifestRevision
			}
		) abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}

	private suspend fun loadPortableRegistrationPlans(
		generations: List<Long>,
	): List<ActivityCapturedRegistrationPlanEntity> = loadBoundedDependencies(generations) { batch, limit ->
		database.activityCapturedFactDao().portableRegistrationPlans(batch, limit)
	}

	private suspend fun loadPortableDesiredPlans(
		revisions: List<Long>,
	): List<SourceDesiredPlanEntity> = loadBoundedDependencies(revisions) { batch, limit ->
		database.activityCapturedFactDao().portableDesiredPlans(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			batch,
			limit,
		)
	}

	private suspend fun loadPortableProviderRegistrations(
		generations: List<Long>,
	): List<ProviderRegistrationGenerationEntity> = loadBoundedDependencies(generations) { batch, limit ->
		database.activityCapturedFactDao().portableProviderRegistrations(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			batch,
			limit,
		)
	}

	private suspend fun <T> loadBoundedDependencies(
		identities: List<Long>,
		load: suspend (List<Long>, Int) -> List<T>,
	): List<T> {
		val result = mutableListOf<T>()
		identities.chunked(SQLITE_BIND_BATCH).forEach { batch ->
			currentCoroutineContext().ensureActive()
			result += load(batch, limits.maximumCompletenessRows - result.size + 1)
			if (result.size > limits.maximumCompletenessRows) overflow()
		}
		return result
	}

	private fun hasValidPortableCompleteness(
		rows: List<SourceSessionCompletenessEntity>,
		runs: List<SourceServiceRunEntity>,
	): Boolean {
		val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
		return runsById.size == runs.size && rows.groupBy(SourceSessionCompletenessEntity::serviceRunId)
			.all { (runId, runRows) ->
				val run = runsById[runId] ?: return@all false
				val generations = runRows.map(SourceSessionCompletenessEntity::registrationGeneration)
				if (generations.distinct().size != generations.size) return@all false
				val highWaters = runRows.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
					.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal)
				if (!highWaters.zipWithNext().all { (left, right) -> right > left }) return@all false
				runRows.all { row -> row.hasValidPortableShape(run) }
			}
	}

	@Suppress("ComplexCondition")
	private fun SourceSessionCompletenessEntity.hasValidPortableShape(
		run: SourceServiceRunEntity,
	): Boolean {
		val unresolvedStart = unresolvedSequenceStart
		val unresolvedEnd = unresolvedSequenceEnd
		if (logicalTrackingId != run.logicalTrackingId || serviceRunId != run.serviceRunId ||
			sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY || sourceInstanceId.isBlank() ||
			registrationGeneration < 0L || lastAdmissionOrdinal?.let { ordinal -> ordinal <= 0L } == true ||
			lastSourceSequence?.let { sequence -> sequence < 0L } == true ||
			(lastAdmissionOrdinal == null) != (lastSourceSequence == null) ||
			(unresolvedStart == null) != (unresolvedEnd == null) ||
			unresolvedStart?.let { start -> start <= 0L || start > requireNotNull(unresolvedEnd) } == true ||
			providerCoverage !in PORTABLE_PROVIDER_COVERAGES || stopStatus !in PORTABLE_STOP_STATUSES ||
			(stopStatus == COMPLETE_STOP_STATUS && !appDrainComplete) || updatedAtMs < 0L
		) return false
		if (registrationGeneration > 0L) return sourceInstanceId !in SYNTHETIC_ACTIVITY_INSTANCES
		if (lastAdmissionOrdinal != null || lastSourceSequence != null || unresolvedStart != null ||
			unresolvedEnd != null || providerCoverage != UNOBSERVABLE_PROVIDER_COVERAGE
		) return false
		return when (sourceInstanceId) {
			NOT_OWNED_ACTIVITY_INSTANCE -> stopStatus == COMPLETE_STOP_STATUS && appDrainComplete
			UNRESOLVED_ACTIVITY_INSTANCE ->
				stopStatus in PROVIDER_UNAVAILABLE_STOP_STATUSES && !appDrainComplete
			UNREGISTERED_ACTIVITY_INSTANCE ->
				stopStatus in setOf(COMPLETE_STOP_STATUS, "PROVIDER_FAILED") && appDrainComplete
			else -> false
		}
	}

	private fun hasValidPortableActivityLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
			lane.bindingGeneration != SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION ||
			lane.projectionId != SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID ||
			lane.projectionVersion != SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION ||
			lane.captureModeMask <= 0L ||
			lane.captureModeMask and PORTABLE_SESSION_CAPTURE_MASK.inv() != 0L ||
			lane.productStage !in PORTABLE_ACTIVITY_PRODUCT_STAGES || lane.activatedRolloutRevision <= 0L ||
			lane.activationOrdinal <= 0L || lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs
		) return false
		val minimumCursor = lane.activationOrdinal - 1L
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (lane.contiguousAdmissionOrdinal < minimumCursor ||
			(cutoff != null && (cutoff < minimumCursor || lane.contiguousAdmissionOrdinal > cutoff))
		) return false
		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
				lane.retentionRequired && cutoff == null && lane.terminalDisposition == null &&
					lane.terminalAtMs == null
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
				val terminalAt = lane.terminalAtMs ?: return false
				!lane.retentionRequired &&
					lane.terminalDisposition ==
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
					cutoff != null && lane.contiguousAdmissionOrdinal == cutoff &&
					terminalAt >= lane.installedAtMs && lane.updatedAtMs >= terminalAt
			}
			else -> false
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

private data class PortableDecodedActivityPlan(
	val revision: Long,
	val mode: String,
	val desiredDetectionLatencyMs: Long,
	val confidenceThresholdPercent: Int,
	val transitionTypes: Set<Int>,
	val physicalConfigurationFingerprint: String,
) {
	val enabled: Boolean
		get() = mode != "OFF"
}

/** Canonical source-plan-v1 check kept local to the source-specific portable selector. */
private object PortableActivityPlanIntegrity {
	fun decode(row: SourceDesiredPlanEntity): PortableDecodedActivityPlan? = runCatching {
		require(row.sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
		require(row.payloadVersion == 1 && row.payloadChecksum == sha256(row.payload))
		val decoded = DataInputStream(ByteArrayInputStream(row.payload)).use { input ->
			require(input.readInt() == 1)
			require(input.readUTF() == "ACTIVITY")
			val revision = input.readLong()
			val mode = input.readUTF()
			val latency = input.readLong()
			val confidence = input.readInt()
			val transitionCount = input.readInt()
			requireCount(transitionCount)
			val transitions = buildSet(transitionCount) {
				repeat(transitionCount) { add(input.readInt()) }
			}
			require(input.available() == 0 && revision == row.revision && revision > 0L)
			require(transitions.size == transitionCount)
			require(mode in PORTABLE_ACTIVITY_PLAN_MODES && latency >= 0L && confidence in 0..100)
			PortableDecodedActivityPlan(
				revision = revision,
				mode = mode,
				desiredDetectionLatencyMs = latency,
				confidenceThresholdPercent = confidence,
				transitionTypes = transitions,
				physicalConfigurationFingerprint = sha256(
					listOf(
						"ACTIVITY",
						mode,
						latency,
						confidence,
						transitions.sorted().joinToString(","),
					).joinToString("\u001f").toByteArray(Charsets.UTF_8),
				),
			)
		}
		require(row.payload.contentEquals(encode(decoded)))
		decoded
	}.getOrNull()

	private fun requireCount(value: Int) {
		require(value in 0..MAX_ACTIVITY_PLAN_TRANSITIONS)
	}

	private fun encode(plan: PortableDecodedActivityPlan): ByteArray =
		ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(1)
				output.writeUTF("ACTIVITY")
				output.writeLong(plan.revision)
				output.writeUTF(plan.mode)
				output.writeLong(plan.desiredDetectionLatencyMs)
				output.writeInt(plan.confidenceThresholdPercent)
				output.writeInt(plan.transitionTypes.size)
				plan.transitionTypes.sorted().forEach(output::writeInt)
			}
			buffer.toByteArray()
		}
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

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
private const val MANUAL_SESSION_CAPTURE_MASK = 1L
private const val AUTOMATIC_SESSION_CAPTURE_MASK = 2L
private const val PORTABLE_SESSION_CAPTURE_MASK =
	MANUAL_SESSION_CAPTURE_MASK or AUTOMATIC_SESSION_CAPTURE_MASK
private const val PORTABLE_SOURCE_RUNTIME_ACTION = "SOURCE_RUNTIME"
private const val PORTABLE_ACTION_STARTED = "STARTED"
private const val PORTABLE_START_ACCEPTED = "START_ACCEPTED"
private val PORTABLE_ACTIVITY_PROVIDER_OWNER_SCOPE =
	"source-broker:${SourceDestinationOwnerEntity.SOURCE_ACTIVITY}"
private const val COMPLETE_STOP_STATUS = "COMPLETE"
private const val UNOBSERVABLE_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
private const val NOT_OWNED_ACTIVITY_INSTANCE = "not-owned-activity"
private const val UNRESOLVED_ACTIVITY_INSTANCE = "unresolved-activity"
private const val UNREGISTERED_ACTIVITY_INSTANCE = "activity-unregistered"
private val SYNTHETIC_ACTIVITY_INSTANCES = setOf(
	NOT_OWNED_ACTIVITY_INSTANCE,
	UNRESOLVED_ACTIVITY_INSTANCE,
	UNREGISTERED_ACTIVITY_INSTANCE,
)
private val PORTABLE_ACTIVITY_PRODUCT_STAGES = setOf(
	SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
	SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
)
private val PORTABLE_PROVIDER_COVERAGES = setOf(
	"CALLBACKS_ENTERED_BEFORE_BARRIER",
	UNOBSERVABLE_PROVIDER_COVERAGE,
)
private val PORTABLE_STOP_STATUSES = setOf(
	COMPLETE_STOP_STATUS,
	"TIMED_OUT",
	"PERMISSION_LOST",
	"PROVIDER_FAILED",
	"PROCESS_RESTARTED",
)
private val PROVIDER_UNAVAILABLE_STOP_STATUSES = setOf(
	"TIMED_OUT",
	"PERMISSION_LOST",
	"PROVIDER_FAILED",
)
private const val MAX_ACTIVITY_PLAN_TRANSITIONS = 10_000
private val PORTABLE_ACTIVITY_PLAN_MODES = setOf("OFF", "TRANSITIONS_ONLY", "CONTINUOUS_RECOGNITION")
private val PORTABLE_ACCEPTED_PROVIDER_STATUSES = setOf(
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	ProviderRegistrationGenerationEntity.STATUS_RETIRING,
	ProviderRegistrationGenerationEntity.STATUS_RETIRED,
)
private val SHA_256_HEX = Regex("[0-9a-f]{64}")
