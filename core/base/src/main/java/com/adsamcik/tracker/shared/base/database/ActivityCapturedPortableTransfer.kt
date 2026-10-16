package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
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
	const val MAX_FILE_BYTES = 64L * 1_024L * 1_024L

	const val MAX_ENTRIES = 4_096
	const val MAX_RUNS = 16_384
	const val MAX_ZONE_EPOCHS = 65_536
	const val MAX_WINDOWS = 65_536
	const val MAX_FRAGMENTS = 524_288
	const val MAX_RUNS_PER_ENTRY = 64
	const val MAX_WINDOWS_PER_RUN = 2_048
	const val MAX_FRAGMENTS_PER_WINDOW = 1_024
	const val MAX_ZONE_EPOCHS_PER_RUN = 256
	const val MAX_ORIGIN_IDENTITY_LENGTH = 4_096
	const val MAX_IMPORT_RECEIPT_FIELD_LENGTH = 4_096
	const val MAX_TEXT_VALUE_LENGTH = 128
}

/** Local identifiers are irreversibly namespaced before crossing the source boundary. */
enum class PortableActivityIdentityKind {
	LOGICAL_ENTRY,
	PHYSICAL_RUN,
	CAPTURE_WINDOW,
}

/**
 * Lowercase SHA-256 Activity member identity derived in an explicit source/type namespace without
 * exposing the local key.
 */
@JvmInline
value class PortableActivityOpaqueIdentity(val value: String) {
	init {
		require(ACTIVITY_PORTABLE_SHA_256_HEX.matches(value))
	}

	/**
	 * Derives opaque Activity member identities within the explicit logical/run/window namespace.
	 */
	companion object {
		/**
		 * Hashes the bounded nonblank local identity with its Activity member namespace.
		 */
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

/**
 * Lowercase SHA-256 checksum of canonical Activity wire content; it is not recording permission.
 */
@JvmInline
value class PortableActivityDigest(val value: String) {
	init {
		require(ACTIVITY_PORTABLE_SHA_256_HEX.matches(value))
	}
}

/** Exact payload-free key used by durable Activity no-resurrection fences. */
@JvmInline
value class PortableActivityDeletionScopeDigest(val value: String) {
	init {
		require(ACTIVITY_PORTABLE_SHA_256_HEX.matches(value))
	}

	/**
	 * Derives stable original Activity capture deletion scopes.
	 */
	companion object {
		/**
		 * Derives the original Activity capture deletion scope from the logical tracking and service-run
		 * identities.
		 */
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

/**
 * Original manual or automatic session mode retained by the portable Activity entry.
 */
enum class PortableActivitySessionMode {
	MANUAL,
	AUTOMATIC,
}

/**
 * Whether Activity capture represented the whole physical run, a partial run or no captured scope.
 */
enum class PortableActivityCaptureCoverage {
	WHOLE_RUN,
	PARTIAL_RUN,
	NOT_CAPTURED,
}

/**
 * Whether a saved Activity window has no, partial or complete observed elapsed coverage.
 */
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

/**
 * Original elapsed nanosecond offsets of a classified band or known gap within an Activity window.
 */
sealed interface PortableActivityFragmentV1 {
	val startOffsetNanos: Long
	val endOffsetNanos: Long

	/**
	 * Original known missing Activity interval in elapsed nanosecond offsets with its saved reason.
	 */
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

	/**
	 * Original classified Activity interval with elapsed offsets, wall-clock uncertainty, mechanism
	 * and optional sampled confidence.
	 */
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

/**
 * Original Activity physical run with UTC millisecond bounds, zone changes and ordered saved
 * windows.
 */
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

/**
 * Logical Activity entry preserving original identity, session mode and complete ordered
 * replacement-run membership.
 */
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

/**
 * Bounded v1 Activity file envelope with canonical checksum and ordered logical entries.
 */
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

/**
 * Nonempty half-open UTC millisecond selection for retained Activity entries.
 */
data class ExportPortableCapturedActivityRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
	}
}

/** Exact saved records for a bundle; each native seed retains its original replacement group. */
data class ExportSelectedPortableCapturedActivityRequest(
	val nativeSegmentIds: List<Long>,
	val importedEntryIdentities: List<PortableActivityOpaqueIdentity>,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(nativeSegmentIds.all { it > 0L })
		require(nativeSegmentIds.size <= ActivityCapturedPortableFormatV1.MAX_RUNS)
		require(importedEntryIdentities.size <= ActivityCapturedPortableFormatV1.MAX_ENTRIES)
		require(nativeSegmentIds.isNotEmpty() || importedEntryIdentities.isNotEmpty())
	}
}

/**
 * Source-specific structural verifier for already-authenticated portable Activity entries.
 * It exposes no identifiers and grants no live, deletion, or writer authority.
 */
class PortableActivityOpaqueOwnershipVerifier private constructor(
	private val identityOwners: MutableMap<String, PortableActivityOpaqueOwner>,
	private val deletionScopeOwners: MutableMap<String, PortableActivityOpaqueOwner>,
) {
	/** Atomically adds one compatible hierarchy; false leaves the verifier unchanged. */
	fun tryInclude(entry: PortableActivityEntryV1): Boolean {
		val candidate = ownershipOf(entry) ?: return false
		return tryInclude(candidate)
	}

	/** Atomically adds one authenticated payload-free retained hierarchy. */
	fun tryInclude(
		entryIdentity: PortableActivityOpaqueIdentity,
		retainedIdentities: List<RetainedImportedActivityIdentity>,
	): Boolean {
		val candidate = ownershipOf(entryIdentity, retainedIdentities) ?: return false
		return tryInclude(candidate)
	}

	@Suppress("ComplexCondition")
	private fun tryInclude(candidate: PortableActivityOpaqueOwnershipVerifier): Boolean {
		if (candidate.identityOwners.keys.any { it in deletionScopeOwners } ||
			candidate.deletionScopeOwners.keys.any { it in identityOwners } ||
			candidate.identityOwners.any { (identity, owner) ->
				identityOwners[identity]?.let { it != owner } == true
			} || candidate.deletionScopeOwners.any { (scope, owner) ->
				deletionScopeOwners[scope]?.let { it != owner } == true
			}
		) {
			return false
		}
		identityOwners.putAll(candidate.identityOwners)
		deletionScopeOwners.putAll(candidate.deletionScopeOwners)
		return true
	}

	/**
	 * Builds a verifier only from compatible complete Activity hierarchies.
	 */
	companion object {
		/**
		 * Returns a verifier when every entry/member/deletion scope has compatible ownership, or null on
		 * any collision.
		 */
		fun fromEntries(entries: Collection<PortableActivityEntryV1>):
			PortableActivityOpaqueOwnershipVerifier? {
			val verifier = PortableActivityOpaqueOwnershipVerifier(linkedMapOf(), linkedMapOf())
			return verifier.takeIf { entries.all(verifier::tryInclude) }
		}

		private fun ownershipOf(entry: PortableActivityEntryV1): PortableActivityOpaqueOwnershipVerifier? {
			val verifier = PortableActivityOpaqueOwnershipVerifier(linkedMapOf(), linkedMapOf())
			val entryIdentity = entry.identity.value
			if (!verifier.bindIdentity(
				entryIdentity,
				PortableActivityOpaqueOwner(PortableActivityIdentityKind.LOGICAL_ENTRY, entryIdentity),
			)) {
				return null
			}
			if (!entry.runs.all { run -> verifier.includeRun(entryIdentity, run) }) {
				return null
			}
			if (verifier.identityOwners.keys.any { it in verifier.deletionScopeOwners }) {
				return null
			}
			return verifier
		}

		private fun ownershipOf(
			entryIdentity: PortableActivityOpaqueIdentity,
			retainedIdentities: List<RetainedImportedActivityIdentity>,
		): PortableActivityOpaqueOwnershipVerifier? {
			if (retainedIdentities.map { it.value }.distinct().size != retainedIdentities.size ||
				retainedIdentities.filterIsInstance<RetainedImportedActivityIdentity.Entry>()
					.singleOrNull()?.identity != entryIdentity
			) {
				return null
			}
			val verifier = PortableActivityOpaqueOwnershipVerifier(linkedMapOf(), linkedMapOf())
			if (!retainedIdentities.all { retained -> verifier.bindRetainedIdentity(entryIdentity, retained) }) {
				return null
			}
			if (verifier.identityOwners.keys.any { it in verifier.deletionScopeOwners }) {
				return null
			}
			return verifier
		}
	}

	private fun includeRun(entryIdentity: String, run: PortableActivityRunV1): Boolean {
		val runIdentity = run.identity.value
		val scope = run.deletionScopeDigest.value
		val runOwner = PortableActivityOpaqueOwner(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			entryIdentity,
			runIdentity,
		)
		return bindIdentity(runIdentity, runOwner) && bindScope(scope, runOwner) &&
			run.windows.all { window ->
				bindIdentity(
					window.identity.value,
					PortableActivityOpaqueOwner(
						PortableActivityIdentityKind.CAPTURE_WINDOW,
						entryIdentity,
						runIdentity,
					),
				)
			}
	}

	private fun bindRetainedIdentity(
		entryIdentity: PortableActivityOpaqueIdentity,
		retained: RetainedImportedActivityIdentity,
	): Boolean {
		val entry = entryIdentity.value
		return when (retained) {
			is RetainedImportedActivityIdentity.Entry -> retained.identity == entryIdentity && bindIdentity(
				retained.identity.value,
				PortableActivityOpaqueOwner(PortableActivityIdentityKind.LOGICAL_ENTRY, entry),
			)
			is RetainedImportedActivityIdentity.Run -> bindIdentity(
				retained.identity.value,
				PortableActivityOpaqueOwner(PortableActivityIdentityKind.PHYSICAL_RUN, entry, retained.identity.value),
			)
			is RetainedImportedActivityIdentity.Window -> bindIdentity(
				retained.identity.value,
				PortableActivityOpaqueOwner(PortableActivityIdentityKind.CAPTURE_WINDOW, entry),
			)
			is RetainedImportedActivityIdentity.DeletionScope -> bindScope(
				retained.digest.value,
				PortableActivityOpaqueOwner(PortableActivityIdentityKind.PHYSICAL_RUN, entry),
			)
		}
	}

	private fun bindIdentity(identity: String, owner: PortableActivityOpaqueOwner): Boolean {
		val previous = identityOwners[identity]
		if (previous == null) {
			identityOwners[identity] = owner
		}
		return previous == null || previous == owner
	}

	private fun bindScope(scope: String, owner: PortableActivityOpaqueOwner): Boolean {
		val previous = deletionScopeOwners[scope]
		if (previous == null) {
			deletionScopeOwners[scope] = owner
		}
		return previous == null || previous == owner
	}
}

private data class PortableActivityOpaqueOwner(
	val kind: PortableActivityIdentityKind,
	val entryIdentity: String,
	val runIdentity: String? = null,
)

/** One authenticated semantic identity retained after imported Activity payload removal. */
sealed interface RetainedImportedActivityIdentity {
	val value: String

	/**
	 * Original logical imported Activity identity retained after its payload expires.
	 */
	data class Entry(val identity: PortableActivityOpaqueIdentity) : RetainedImportedActivityIdentity {
		override val value: String get() = identity.value
	}

	/**
	 * Original imported physical-run identity retained for replay/deletion qualification.
	 */
	data class Run(val identity: PortableActivityOpaqueIdentity) : RetainedImportedActivityIdentity {
		override val value: String get() = identity.value
	}

	/**
	 * Original imported Activity window identity retained independently of live capture.
	 */
	data class Window(val identity: PortableActivityOpaqueIdentity) : RetainedImportedActivityIdentity {
		override val value: String get() = identity.value
	}

	/**
	 * Original imported physical-run deletion-scope digest retained after payload removal.
	 */
	data class DeletionScope(
		val digest: PortableActivityDeletionScopeDigest,
	) : RetainedImportedActivityIdentity {
		override val value: String get() = digest.value
	}
}

/**
 * Receives one complete prepared Activity file envelope after its database snapshot closes.
 */
fun interface PortableActivityEnvelopeSink {
	/** Receives one complete immutable envelope after its Room snapshot has ended. */
	suspend fun emit(envelope: PortableActivityEnvelopeV1)
}

/**
 * Read-only Activity export from saved native/imported products without acquiring provider demand.
 */
interface ExportPortableCapturedActivity {
	/**
	 * Prepares a bounded saved Activity envelope before sink I/O; collection and provider demand
	 * remain unchanged.
	 */
	suspend fun export(
		request: ExportPortableCapturedActivityRequest,
		sink: PortableActivityEnvelopeSink,
	): ExportPortableCapturedActivityResult

	/** Exact selected contents, with no date fallback or borrowed native counterpart. */
	suspend fun export(
		request: ExportSelectedPortableCapturedActivityRequest,
		sink: PortableActivityEnvelopeSink,
	): ExportPortableCapturedActivityResult
}

/** Bounded provenance copied into Activity-local immutable import authority. */
data class PortableActivityImportReceipt(
	val jobId: String,
	val entryKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, entryKey, sourceName).forEach { value ->
			require(value.isNotBlank())
			require(value.length <= ActivityCapturedPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/** One Activity-only admission transaction over an already-decoded captured-product entry. */
data class ImportPortableCapturedActivityRequest(
	val entry: PortableActivityEntryV1,
	val receipt: PortableActivityImportReceipt,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/** Admission stores imported product evidence only and never grants live capture authority. */
interface ImportPortableCapturedActivity {
	/**
	 * Admits decoded Activity product and original file provenance under the expected job data epoch,
	 * without native capture authority.
	 */
	suspend fun importEntry(
		request: ImportPortableCapturedActivityRequest,
	): ImportPortableCapturedActivityResult
}

/** Exact physical-run and source-deletion scope selected from an authenticated product read. */
data class SelectedImportedActivityRunDeletionScope(
	val runIdentity: PortableActivityOpaqueIdentity,
	val deletionScopeDigest: PortableActivityDeletionScopeDigest,
)

/** Exact latest imported Activity revision selected from an authenticated product read. */
data class SelectedImportedActivityIdentity(
	val entryIdentity: PortableActivityOpaqueIdentity,
	val importRevision: Long,
	val contentChecksum: PortableActivityDigest,
	val runDeletionScopes: List<SelectedImportedActivityRunDeletionScope>,
	val windowIdentities: List<PortableActivityOpaqueIdentity>,
) {
	init {
		require(importRevision > 0L)
		require(runDeletionScopes.isNotEmpty())
		require(runDeletionScopes.size <= ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(runDeletionScopes.map { it.runIdentity }.distinct().size == runDeletionScopes.size)
		require(
			runDeletionScopes.map { it.deletionScopeDigest }.distinct().size == runDeletionScopes.size,
		)
		require(windowIdentities.isNotEmpty())
		require(windowIdentities.size <= MAX_SELECTED_IMPORTED_WINDOWS)
		require(windowIdentities.distinct().size == windowIdentities.size)
		val allIdentities = buildSet {
			add(entryIdentity.value)
			runDeletionScopes.forEach { run -> add(run.runIdentity.value) }
			windowIdentities.forEach { window -> add(window.value) }
		}
		require(allIdentities.size == runDeletionScopes.size + windowIdentities.size + 1)
		require(runDeletionScopes.none { it.deletionScopeDigest.value in allIdentities })
	}

	private companion object {
		const val MAX_SELECTED_IMPORTED_WINDOWS = 16_384
	}
}

/**
 * One selected imported-origin deletion attempt; it grants no live source authority.
 *
 * [expectedSourceEvidenceRevision] is supplied by page-bound selections. Source-wide maintenance
 * may omit it while retaining the existing collected-data epoch guard.
 */
data class DeleteSelectedImportedActivityRequest(
	val selected: SelectedImportedActivityIdentity,
	val expectedCollectedDataEpoch: Long,
	val deletedAtMs: Long,
	val expectedSourceEvidenceRevision: Long? = null,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(deletedAtMs >= 0L)
		require(expectedSourceEvidenceRevision == null || expectedSourceEvidenceRevision >= 0L)
	}
}

/**
 * Deletes only the exact imported Activity selection after rechecking its revision, checksum and
 * original data epoch.
 */
interface DeleteSelectedImportedActivity {
	/**
	 * Rechecks the source-issued imported identity/revision/checksum, then atomically fences and
	 * removes that lineage.
	 */
	suspend fun delete(
		request: DeleteSelectedImportedActivityRequest,
	): DeleteSelectedImportedActivityResult
}

/**
 * Exact Activity-import retention snapshot. The global floor is established separately; this
 * request may consume that exact monotonic state but cannot create, lower, or widen it.
 */
data class TruncateImportedActivityRetentionRequest(
	val expectedCollectedDataEpoch: Long,
	val expectedSourceEvidenceRevision: Long,
	val retainedFromMs: Long,
	val retainedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedSourceEvidenceRevision >= 0L)
		require(retainedFromMs >= 0L)
		require(retainedAtMs >= 0L)
	}
}

/**
 * Compacts imported Activity payload affected by the already-published global retention floor.
 */
interface TruncateImportedActivityRetention {
	/**
	 * Compacts imported Activity below the already-published floor while preserving the verified
	 * remaining hierarchy and replay fences.
	 */
	suspend fun truncate(
		request: TruncateImportedActivityRetentionRequest,
	): TruncateImportedActivityRetentionResult
}

/**
 * Imported Activity retention outcome with removed entry/revision/run/window/fragment counts or
 * explicit failure.
 */
sealed interface TruncateImportedActivityRetentionResult {
	/**
	 * Reports removed entry, revision, run, window and fragment rows after imported retention
	 * compaction.
	 */
	data class Truncated(
		val entryCount: Int,
		val revisionCount: Int,
		val runRowCount: Int,
		val windowRowCount: Int,
		val fragmentRowCount: Int,
	) : TruncateImportedActivityRetentionResult {
		init {
			require(entryCount > 0 && revisionCount >= entryCount)
			require(runRowCount >= revisionCount)
			require(windowRowCount >= revisionCount)
			require(fragmentRowCount >= windowRowCount)
		}
	}

	/**
	 * No saved payload in this source scope required removal for the requested cutoff.
	 */
	data object NoChange : TruncateImportedActivityRetentionResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: ImportedActivityRetentionBlockedReason,
	) : TruncateImportedActivityRetentionResult

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : TruncateImportedActivityRetentionResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(
		val reason: PortableActivityTransferRetryableReason,
	) : TruncateImportedActivityRetentionResult
}

/**
 * Changed source evidence or stale published-retention request preventing imported Activity
 * compaction.
 */
enum class ImportedActivityRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	STALE_REQUEST,
}

/**
 * Exact imported Activity deletion outcome, distinguishing prior deletion, missing selection and
 * incomplete authority.
 */
sealed interface DeleteSelectedImportedActivityResult {
	/**
	 * The exact selected payload was fenced and removed; reports original removed scope counts.
	 */
	data class Deleted(
		val importRevisionCount: Int,
		val physicalRunCount: Int,
	) : DeleteSelectedImportedActivityResult {
		init {
			require(importRevisionCount > 0)
			require(physicalRunCount > 0)
		}
	}

	/**
	 * The exact selected source scope was already deleted and remains represented by its original
	 * deletion authority.
	 */
	data class AlreadyDeleted(val deletedImportRevision: Long) : DeleteSelectedImportedActivityResult {
		init {
			require(deletedImportRevision > 0L)
		}
	}

	/**
	 * No matching saved source entry or retained original deletion selection exists.
	 */
	data object NotFound : DeleteSelectedImportedActivityResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: SelectedImportedActivityDeletionBlockedReason,
	) : DeleteSelectedImportedActivityResult

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : DeleteSelectedImportedActivityResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(
		val reason: PortableActivityTransferRetryableReason,
	) : DeleteSelectedImportedActivityResult
}

/**
 * Changed data epoch, stale selection/action time or retention floor preventing imported Activity
 * deletion.
 */
enum class SelectedImportedActivityDeletionBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_SELECTION,
	STALE_REQUEST,
	RETENTION_BOUNDARY,
}

/**
 * Activity import outcome with saved revision and hierarchy counts separate from conflicts and
 * failures.
 */
sealed interface ImportPortableCapturedActivityResult {
	/**
	 * The original imported product was saved at the reported revision with its represented hierarchy
	 * counts.
	 */
	data class Applied(
		val importRevision: Long,
		val physicalRunCount: Int,
		val windowCount: Int,
		val fragmentCount: Int,
	) : ImportPortableCapturedActivityResult {
		init {
			require(importRevision > 0L)
			require(physicalRunCount > 0)
			require(windowCount > 0)
			require(fragmentCount > 0)
		}
	}

	/**
	 * Matching original imported content is already saved at the reported import revision.
	 */
	data class Duplicate(val importRevision: Long) : ImportPortableCapturedActivityResult {
		init {
			require(importRevision > 0L)
		}
	}

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: PortableActivityImportBlockedReason,
	) : ImportPortableCapturedActivityResult

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: PortableActivityImportUnverifiableReason,
	) : ImportPortableCapturedActivityResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(
		val reason: PortableActivityTransferRetryableReason,
	) : ImportPortableCapturedActivityResult
}

/**
 * Data-epoch, receipt, correction, identity, deletion or retention boundaries that prevent
 * Activity import.
 */
enum class PortableActivityImportBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RETENTION_BOUNDARY,
	RECEIPT_CONFLICT,
	CORRECTION_CONFLICT,
	OPAQUE_IDENTITY_CONFLICT,
	DELETED_ENTRY,
	DELETED_RUN,
	DELETED_SCOPE,
}

/**
 * Invalid entry, missing stored source evidence or exceeded run/window/fragment/zone/revision
 * bounds.
 */
enum class PortableActivityImportUnverifiableReason {
	ENTRY_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	RUN_OVERFLOW,
	WINDOW_OVERFLOW,
	FRAGMENT_OVERFLOW,
	ZONE_EPOCH_OVERFLOW,
	REVISION_OVERFLOW,
}

/**
 * Concurrent-state or storage failures permitting retry of the Activity transfer.
 */
enum class PortableActivityTransferRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}

/**
 * Complete Activity output count, empty selection or a qualification/storage failure.
 */
sealed interface ExportPortableCapturedActivityResult {
	/**
	 * The complete prepared source payload was emitted; reports its original identity/checksum or
	 * represented entry counts.
	 */
	data class Exported(val entryCount: Int) : ExportPortableCapturedActivityResult {
		init {
			require(entryCount > 0)
		}
	}

	/**
	 * The selected saved source scope contains no exportable product data.
	 */
	data object NoEntries : ExportPortableCapturedActivityResult

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: PortableActivityExportUnverifiableReason,
	) : ExportPortableCapturedActivityResult

	/**
	 * The bounded saved-data read could not complete; this is not an empty selection.
	 */
	data object StorageUnavailable : ExportPortableCapturedActivityResult
}

/**
 * Incomplete capture/projection, retention/deletion, conflicting origin or exceeded dependencies
 * preventing Activity export.
 */
enum class PortableActivityExportUnverifiableReason {
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	ENTRY_MATERIALIZING,
	RETENTION_CROSSES_ENTRY,
	DELETED_SCOPE,
	SOURCE_EVIDENCE_UNAVAILABLE,
	CONFLICTING_ORIGIN_IDENTITY,
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

	/**
	 * Prepares the complete selected Activity envelope on the disk dispatcher, then emits it after
	 * snapshot closure; cancellation propagates.
	 */
	override suspend fun export(
		request: ExportPortableCapturedActivityRequest,
		sink: PortableActivityEnvelopeSink,
	): ExportPortableCapturedActivityResult = withContext(ioDispatcher) {
		val snapshot = try {
			reader.read(request)
		} catch (cancelled: CancellationException) {
			if (!currentCoroutineContext().isActive) {
				throw cancelled
			}
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

	/**
	 * Prepares the complete selected Activity envelope on the disk dispatcher, then emits it after
	 * snapshot closure; cancellation propagates.
	 */
	override suspend fun export(
		request: ExportSelectedPortableCapturedActivityRequest,
		sink: PortableActivityEnvelopeSink,
	): ExportPortableCapturedActivityResult = withContext(ioDispatcher) {
		val pinned = request.copy(
			nativeSegmentIds = request.nativeSegmentIds.toList(),
			importedEntryIdentities = request.importedEntryIdentities.toList(),
		)
		when (val selected = readSelected(pinned)) {
			is PortableCapturedActivitySnapshot.Outcome -> selected.result
			is PortableCapturedActivitySnapshot.Ready -> {
				currentCoroutineContext().ensureActive()
				sink.emit(selected.envelope)
				currentCoroutineContext().ensureActive()
				when (val current = readSelected(pinned)) {
					is PortableCapturedActivitySnapshot.Ready -> if (current == selected) {
						ExportPortableCapturedActivityResult.Exported(selected.envelope.entries.size)
					} else {
						ExportPortableCapturedActivityResult.Unverifiable(
						PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					)
					}
					is PortableCapturedActivitySnapshot.Outcome -> current.result
				}
			}
		}
	}

	private suspend fun readSelected(
		request: ExportSelectedPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot = try {
		reader.read(request)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		PortableCapturedActivitySnapshot.Outcome(ExportPortableCapturedActivityResult.StorageUnavailable)
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
	/**
	 * Contains the complete prepared source hierarchy or verified saved authority for this exact
	 * read.
	 */
	data class Ready(val envelope: PortableActivityEnvelopeV1) : PortableCapturedActivitySnapshot

	data class Outcome(
		val result: ExportPortableCapturedActivityResult,
	) : PortableCapturedActivitySnapshot {
		init {
			require(result !is ExportPortableCapturedActivityResult.Exported)
		}
	}
}

/** Exact local-origin portable facts for cross-origin product comparison; it grants no mutation authority. */
class RoomReadLocalPortableCapturedActivity(
	database: AppDatabase,
	laneExecutionAuthority: SourceProductLaneExecutionAuthority,
) {
	private val reader = PortableCapturedActivityRoomReader(database, laneExecutionAuthority)

	/**
	 * Reads a bounded native Activity envelope under the existing saved-source snapshot without
	 * external sink I/O.
	 */
	suspend fun read(
		request: ExportPortableCapturedActivityRequest,
	): ReadLocalPortableCapturedActivityResult = readSnapshot { reader.readLocal(request) }

	/** Compares only the selected native replacement groups, without date discovery. */
	suspend fun read(
		request: ExportSelectedPortableCapturedActivityRequest,
	): ReadLocalPortableCapturedActivityResult {
		require(request.importedEntryIdentities.isEmpty())
		return readSnapshot { reader.read(request) }
	}

	private suspend fun readSnapshot(
		read: suspend () -> PortableCapturedActivitySnapshot,
	): ReadLocalPortableCapturedActivityResult = try {
		when (val snapshot = read()) {
			is PortableCapturedActivitySnapshot.Ready ->
				ReadLocalPortableCapturedActivityResult.Ready(snapshot.envelope)
			is PortableCapturedActivitySnapshot.Outcome -> when (val result = snapshot.result) {
				ExportPortableCapturedActivityResult.NoEntries ->
					ReadLocalPortableCapturedActivityResult.NoEntries
				is ExportPortableCapturedActivityResult.Unverifiable ->
					ReadLocalPortableCapturedActivityResult.Unverifiable(result.reason)
				else -> ReadLocalPortableCapturedActivityResult.StorageUnavailable
			}
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		ReadLocalPortableCapturedActivityResult.StorageUnavailable
	}
}

/**
 * Prepared native Activity envelope or the reason the bounded local read cannot provide one.
 */
sealed interface ReadLocalPortableCapturedActivityResult {
	/**
	 * Contains the complete prepared source hierarchy or verified saved authority for this exact
	 * read.
	 */
	data class Ready(val envelope: PortableActivityEnvelopeV1) : ReadLocalPortableCapturedActivityResult
	/**
	 * The selected saved source scope contains no exportable product data.
	 */
	data object NoEntries : ReadLocalPortableCapturedActivityResult
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: PortableActivityExportUnverifiableReason,
	) : ReadLocalPortableCapturedActivityResult
	/**
	 * The bounded saved-data read could not complete; this is not an empty selection.
	 */
	data object StorageUnavailable : ReadLocalPortableCapturedActivityResult
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
	/**
	 * Prepares the exact selected Activity hierarchy, preserving source qualification and explicit
	 * empty/unverifiable states.
	 */
	suspend fun read(
		request: ExportSelectedPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot = try {
		database.withTransaction {
			readSelectedInTransaction(request)
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
		outcome(PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW)
	}

	/**
	 * Prepares the exact selected Activity hierarchy, preserving source qualification and explicit
	 * empty/unverifiable states.
	 */
	suspend fun read(
		request: ExportPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot = try {
		database.withTransaction {
			checkpoint(PortableActivityReadCheckpoint.TRANSACTION_STARTED)
			readCombinedInTransaction(request)
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

	internal suspend fun readLocal(
		request: ExportPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot = try {
		database.withTransaction {
			checkpoint(PortableActivityReadCheckpoint.TRANSACTION_STARTED)
			readLocalInTransaction(request)
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

	private suspend fun readSelectedInTransaction(
		request: ExportSelectedPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot {
		checkpoint(PortableActivityReadCheckpoint.TRANSACTION_STARTED)
		val state = database.sourceEvidenceStateDao().get()
		if (state?.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		val logicalIds = loadSelectedLogicalIds(request.nativeSegmentIds)
		val localEntries = if (logicalIds.isEmpty()) {
			emptyList()
		} else {
			when (val local = readLocalInTransaction(
				ExportPortableCapturedActivityRequest(0L, Long.MAX_VALUE), logicalIds,
			)) {
				is PortableCapturedActivitySnapshot.Outcome -> return local
				is PortableCapturedActivitySnapshot.Ready -> local.envelope.entries.also { entries ->
					val expected = logicalIds.mapTo(hashSetOf()) {
						PortableActivityOpaqueIdentity.derive(PortableActivityIdentityKind.LOGICAL_ENTRY, it)
					}
					if (entries.mapTo(hashSetOf(), PortableActivityEntryV1::identity) != expected) {
						abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
					}
				}
			}
		}
		val selection = EntrySelection(localEntries)
		includeSelectedImportedEntries(selection, request.importedEntryIdentities)
		return selection.snapshot()
	}

	private suspend fun loadSelectedLogicalIds(nativeSegmentIds: List<Long>): List<String> {
		val nativeSeeds = database.loadPortableActivitySegments(limits, nativeSegmentIds.distinct())
		val logicalIds = nativeSeeds.map { seed ->
			val logicalId = seed.logicalTrackingId?.takeIf(String::isNotBlank)
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val runId = seed.serviceRunId?.takeIf(String::isNotBlank)
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val run = database.sourceSessionDao().serviceRun(runId)
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			if (run.logicalTrackingId != logicalId || run.sessionSegmentId != seed.id) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			validatePortableActivitySegmentBinding(seed, logicalId, runId)
			logicalId
		}.distinct().sorted()
		if (logicalIds.size > minOf(limits.maximumEntries, ActivityCapturedPortableFormatV1.MAX_ENTRIES)) {
			portableActivityDependencyOverflow()
		}
		return logicalIds
	}

	private suspend fun includeSelectedImportedEntries(
		selection: EntrySelection,
		importedEntryIdentities: List<PortableActivityOpaqueIdentity>,
	) {
		val importedReader = ImportedActivityProductReader(database)
		importedEntryIdentities.distinct().forEach { identity ->
			currentCoroutineContext().ensureActive()
			val evaluation = importedReader.selectIdentityInTransaction(identity)
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			if (evaluation is ImportedActivityProductEvaluation.Readable && !evaluation.isReExportable) {
				abortPortableActivitySnapshot(if (evaluation.entryDeleted || evaluation.deletedRunIdentities.isNotEmpty()) {
					PortableActivityExportUnverifiableReason.DELETED_SCOPE
				} else {
					PortableActivityExportUnverifiableReason.RETENTION_CROSSES_ENTRY
				})
			}
			if (evaluation is ImportedActivityProductEvaluation.Retained) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.RETENTION_CROSSES_ENTRY)
			}
			selection.include(evaluation)
		}
	}

	private suspend fun readCombinedInTransaction(
		request: ExportPortableCapturedActivityRequest,
	): PortableCapturedActivitySnapshot {
		val local = readLocalInTransaction(request)
		val localEntries = when (local) {
			is PortableCapturedActivitySnapshot.Ready -> local.envelope.entries
			is PortableCapturedActivitySnapshot.Outcome -> when (local.result) {
				ExportPortableCapturedActivityResult.NoEntries -> emptyList()
				else -> return local
			}
		}
		val imported = ImportedActivityProductReader(database).selectForExportInTransaction(request)
		return combineEntries(localEntries, imported)
	}

	private suspend fun combineEntries(
		localEntries: List<PortableActivityEntryV1>,
		imported: List<ImportedActivityProductEvaluation>,
	): PortableCapturedActivitySnapshot {
		val selection = EntrySelection(localEntries)
		imported.forEach { evaluation ->
			currentCoroutineContext().ensureActive()
			selection.include(evaluation)
		}
		return selection.snapshot()
	}

	/** Bounds the deduplicated output before expanding ownership or retaining another hierarchy. */
	private inner class EntrySelection(localEntries: List<PortableActivityEntryV1>) {
		private val entries = linkedMapOf<PortableActivityOpaqueIdentity, PortableActivityEntryV1>()
		private val ownership = requireNotNull(PortableActivityOpaqueOwnershipVerifier.fromEntries(emptyList()))
		private var runCount = 0L
		private var windowCount = 0L
		private var fragmentCount = 0L
		private var zoneEpochCount = 0L

		init {
			localEntries.forEach(::includeEntry)
		}

		fun include(evaluation: ImportedActivityProductEvaluation) {
			when (evaluation) {
				is ImportedActivityProductEvaluation.Retained -> {
					val retainedEntryIdentity = PortableActivityOpaqueIdentity(evaluation.candidate.identity)
					if (retainedEntryIdentity in entries || !ownership.tryInclude(
							retainedEntryIdentity,
							evaluation.protectedIdentities,
						)
					) {
						abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY)
					}
				}
				is ImportedActivityProductEvaluation.Unverifiable -> abortPortableActivitySnapshot(
					when (evaluation.reason) {
						ImportedActivityProductFailure.DEPENDENCY_OVERFLOW ->
							PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW
						ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT ->
							PortableActivityExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY
						else -> PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE
					},
				)
				is ImportedActivityProductEvaluation.Readable -> if (evaluation.isReExportable) {
					includeEntry(evaluation.entry)
				}
			}
		}

		private fun includeEntry(entry: PortableActivityEntryV1) {
			entries[entry.identity]?.let { existing ->
				if (existing != entry) {
					abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY)
				}
				return
			}
			val addedRuns = entry.runs.size.toLong()
			val addedWindows = entry.runs.sumOf { it.windows.size.toLong() }
			val addedFragments = entry.runs.sumOf { run -> run.windows.sumOf { it.fragments.size.toLong() } }
			val addedZoneEpochs = entry.runs.sumOf { it.zoneEpochs.size.toLong() }
			if (entries.size >= minOf(limits.maximumEntries, ActivityCapturedPortableFormatV1.MAX_ENTRIES) ||
				runCount + addedRuns > minOf(limits.maximumRuns, ActivityCapturedPortableFormatV1.MAX_RUNS) ||
				windowCount + addedWindows > ActivityCapturedPortableFormatV1.MAX_WINDOWS
			) {
				portableActivityDependencyOverflow()
			}
			if (fragmentCount + addedFragments > ActivityCapturedPortableFormatV1.MAX_FRAGMENTS ||
				zoneEpochCount + addedZoneEpochs > ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS
			) {
				portableActivityDependencyOverflow()
			}
			if (!ownership.tryInclude(entry)) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY)
			}
			entries[entry.identity] = entry
			runCount += addedRuns
			windowCount += addedWindows
			fragmentCount += addedFragments
			zoneEpochCount += addedZoneEpochs
		}

		fun snapshot(): PortableCapturedActivitySnapshot {
			if (entries.isEmpty()) {
				return noEntries()
			}
			val ordered = entries.values.sortedWith(ENTRY_ORDER)
			return PortableCapturedActivitySnapshot.Ready(
				PortableActivityEnvelopeV1(
					contentChecksum = ActivityCapturedPortableIntegrity.envelopeChecksum(ordered),
					entries = ordered,
				),
			)
		}
	}

	private suspend fun readLocalInTransaction(
		request: ExportPortableCapturedActivityRequest,
		exactLogicalTrackingIds: List<String>? = null,
	): PortableCapturedActivitySnapshot {
		val exactRuns = exactLogicalTrackingIds?.let { database.loadPortableActivityReplacementRuns(limits, it) }
		val lineages = if (exactRuns == null) {
			database.auditCapturedActivityFacts(
				limits = limits.maintenance,
				checkpoint = { currentCoroutineContext().ensureActive() },
				requireOwnerWhenEmpty = false,
			).lineages
		} else {
			database.auditPortableActivitySelectedLineages(limits, requireNotNull(exactLogicalTrackingIds), exactRuns)
		}
		checkpoint(PortableActivityReadCheckpoint.FACT_AUDIT_COMPLETED)
		if (lineages.isEmpty()) {
			return noEntries()
		}
		val latestByWindow = lineages.associateWith { lineage -> lineage.revisions.last() }
		val carrierLogicalIds = readCarrierLogicalIds(latestByWindow)
		// Range is a property of the complete logical replacement group, never of the fact owner.
		val carrierRuns = exactRuns ?: database.loadPortableActivityReplacementRuns(limits, carrierLogicalIds)
		checkpoint(PortableActivityReadCheckpoint.REPLACEMENT_MEMBERS_LOADED)
		val carrierSegmentsById = readCarrierSegments(carrierRuns)
		val selectedLogicalIds = exactLogicalTrackingIds ?: carrierLogicalIds.filter { logicalTrackingId ->
			carrierRuns.asSequence()
				.filter { run -> run.logicalTrackingId == logicalTrackingId }
				.map { run -> carrierSegmentsById.getValue(requireNotNull(run.sessionSegmentId)) }
				.any { segment ->
					segment.startTimeMs < request.toExclusiveMs &&
						segment.endTimeMs > request.fromInclusiveMs
				}
		}
		if (selectedLogicalIds.isEmpty()) {
			return noEntries()
		}
		return readSelectedSnapshot(selectedLogicalIds, latestByWindow, carrierRuns, carrierSegmentsById)
	}

	private suspend fun readCarrierLogicalIds(
		latestByWindow: Map<ActivityCapturedLineage, ActivityCapturedPersistedRevision>,
	): List<String> {
		val factOwnerSegments = database.loadPortableActivitySegments(
			limits,
			latestByWindow.values.map { persisted -> persisted.revision.sessionSegmentId }.distinct(),
		)
		val factOwnerSegmentsById = factOwnerSegments.associateBy(SessionSegment::id)
		val carrierLogicalIds = latestByWindow.values.asSequence().map { persisted ->
			val revision = persisted.revision
			val segment = factOwnerSegmentsById[revision.sessionSegmentId]
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			validatePortableActivitySegmentBinding(segment, revision.logicalTrackingId, revision.serviceRunId)
			revision.logicalTrackingId
		}.distinct().sorted().toList()
		if (carrierLogicalIds.size > limits.maximumEntries) {
			portableActivityDependencyOverflow()
		}
		return carrierLogicalIds
	}

	private suspend fun readCarrierSegments(
		carrierRuns: List<SourceServiceRunEntity>,
	): Map<Long, SessionSegment> {
		val carrierSegments = database.loadPortableActivitySegments(limits, carrierRuns.map { run ->
			run.sessionSegmentId
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}.distinct())
		val carrierSegmentsById = carrierSegments.associateBy(SessionSegment::id)
		carrierRuns.forEach { run ->
			val segment = carrierSegmentsById[requireNotNull(run.sessionSegmentId)]
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			validatePortableActivitySegmentBinding(segment, run.logicalTrackingId, run.serviceRunId)
		}
		return carrierSegmentsById
	}

	private suspend fun readSelectedSnapshot(
		selectedLogicalIds: List<String>,
		latestByWindow: Map<ActivityCapturedLineage, ActivityCapturedPersistedRevision>,
		carrierRuns: List<SourceServiceRunEntity>,
		carrierSegmentsById: Map<Long, SessionSegment>,
	): PortableCapturedActivitySnapshot {
		val selectedLineages = latestByWindow.filterValues { persisted ->
			persisted.revision.logicalTrackingId in selectedLogicalIds
		}
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		selectedLineages.forEach { (lineage, persisted) ->
			if (!evidenceState.acceptsRetainedEpoch(persisted.revision.collectedDataEpoch)) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			if (evidenceState.retainedFromMs?.let { floor ->
				lineage.earliestPossibleWallTimeMs?.let { earliest -> earliest < floor } ?: true
			} == true) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.RETENTION_CROSSES_ENTRY)
			}
		}

		val runs = carrierRuns.filter { run -> run.logicalTrackingId in selectedLogicalIds }
		val sessions = database.loadPortableActivitySessions(selectedLogicalIds)
		val segmentsById = carrierSegmentsById.filterKeys { segmentId ->
			segmentId in runs.mapNotNullTo(hashSetOf(), SourceServiceRunEntity::sessionSegmentId)
		}
		val manifestDependencies = database.loadPortableActivityManifestDependencies(limits, runs)
		validatePortableActivityLogicalManifestUnions(
			selectedLogicalIds,
			runs,
			manifestDependencies.manifestsByRun,
		)
		val lineagesByRun = selectedLineages.keys.groupBy { lineage ->
			lineage.revisions.last().revision.serviceRunId
		}
		database.validatePortableActivityTerminalProjectionSettlement(
			limits, laneExecutionAuthority,
			sessions = sessions,
			runs = runs,
			lineagesByRun = lineagesByRun,
			manifestsByRun = manifestDependencies.manifestsByRun,
			sourcesByRun = manifestDependencies.sourcesByRun,
			collectedDataEpoch = evidenceState.collectedDataEpoch,
		)
		database.validatePortableActivityDeletionFences(runs)
		return buildSelectedSnapshot(
			selectedLogicalIds, sessions, runs, segmentsById, lineagesByRun, manifestDependencies,
		)
	}

	private suspend fun buildSelectedSnapshot(
		selectedLogicalIds: List<String>,
		sessions: Map<String, LogicalTrackingSessionEntity>,
		runs: List<SourceServiceRunEntity>,
		segmentsById: Map<Long, SessionSegment>,
		lineagesByRun: Map<String, List<ActivityCapturedLineage>>,
		manifestDependencies: ActivityPortableManifestDependencies,
	): PortableCapturedActivitySnapshot {
		val entries = selectedLogicalIds.map { logicalTrackingId ->
			currentCoroutineContext().ensureActive()
			PortableActivityEntryBuilder.buildEntry(
				logicalTrackingId = logicalTrackingId,
				session = sessions[logicalTrackingId],
				runs = runs.filter { run -> run.logicalTrackingId == logicalTrackingId },
				segmentsById = segmentsById,
				lineagesByRun = lineagesByRun,
				manifestsByRun = manifestDependencies.manifestsByRun,
				sourcesByRun = manifestDependencies.sourcesByRun,
				limits = limits,
			)
		}.sortedWith(ENTRY_ORDER)
		checkpoint(PortableActivityReadCheckpoint.SNAPSHOT_READY)
		val checksum = ActivityCapturedPortableIntegrity.envelopeChecksum(entries)
		return PortableCapturedActivitySnapshot.Ready(
			PortableActivityEnvelopeV1(contentChecksum = checksum, entries = entries),
		)
	}

}

/** Pure materialization of the already-audited saved Activity hierarchy; owns no database or runtime state. */
private object PortableActivityEntryBuilder {
	fun buildEntry(
		logicalTrackingId: String,
		session: LogicalTrackingSessionEntity?,
		runs: List<SourceServiceRunEntity>,
		segmentsById: Map<Long, SessionSegment>,
		lineagesByRun: Map<String, List<ActivityCapturedLineage>>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
		limits: PortableActivityReadLimits,
	): PortableActivityEntryV1 {
		val exactSession = validateEntrySession(session, runs, manifestsByRun, limits)
		val portableRuns = runs.map { run ->
			val segmentId = run.sessionSegmentId
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			val segment = segmentsById[segmentId]
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			validatePortableActivitySegmentBinding(segment, logicalTrackingId, run.serviceRunId)
			buildRun(
				run,
				segment,
				lineagesByRun[run.serviceRunId].orEmpty(),
				manifestsByRun[run.serviceRunId].orEmpty(),
				sourcesByRun[run.serviceRunId].orEmpty(),
				limits,
			)
		}.sortedWith(RUN_ORDER)
		val sessionMode = when (exactSession.sessionMode) {
			"MANUAL" -> PortableActivitySessionMode.MANUAL
			"AUTOMATIC" -> PortableActivitySessionMode.AUTOMATIC
			else -> abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
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

	private fun validateEntrySession(
		session: LogicalTrackingSessionEntity?,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		limits: PortableActivityReadLimits,
	): LogicalTrackingSessionEntity {
		val exactSession = session
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (!exactSession.hasTerminalPortableActivityBounds()) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		val currentManifestRevision = runs.asSequence()
			.flatMap { run -> manifestsByRun[run.serviceRunId].orEmpty().asSequence() }
			.maxOfOrNull(SessionManifestVersionEntity::manifestRevision)
		if (exactSession.currentManifestRevision != currentManifestRevision) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		if (runs.isEmpty() || runs.size > limits.maximumRunsPerEntry) {
			portableActivityDependencyOverflow()
		}
		return exactSession
	}

	private fun buildRun(
		run: SourceServiceRunEntity,
		segment: SessionSegment,
		lineages: List<ActivityCapturedLineage>,
		manifests: List<SessionManifestVersionEntity>,
		sources: List<SessionManifestSourceEntity>,
		limits: PortableActivityReadLimits,
	): PortableActivityRunV1 {
		val capturedManifestRevisions = validateRunCapture(run, lineages, manifests, sources, limits)
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

	private fun validateRunCapture(
		run: SourceServiceRunEntity,
		lineages: List<ActivityCapturedLineage>,
		manifests: List<SessionManifestVersionEntity>,
		sources: List<SessionManifestSourceEntity>,
		limits: PortableActivityReadLimits,
	): Set<Long> {
		if (!run.hasTerminalPortableActivityRun()) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		if (manifests.size > limits.maximumManifestsPerRun) {
			portableActivityDependencyOverflow()
		}
		if (sources.size > limits.maximumSourcesPerRun ||
			!run.hasValidPortableActivityManifests(manifests, sources)
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}

		val capturedManifestRevisions = manifests.filter { manifest ->
			sources.any { source -> source.isPortableCapturedActivityMember(manifest) }
		}.mapTo(linkedSetOf(), SessionManifestVersionEntity::manifestRevision)
		val factManifestRevisions = lineages.mapTo(linkedSetOf()) { lineage ->
			lineage.revisions.last().revision.manifestRevision
		}
		if (factManifestRevisions != capturedManifestRevisions) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		if (lineages.size > limits.maximumWindowsPerRun) {
			portableActivityDependencyOverflow()
		}
		return capturedManifestRevisions
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
			buildFragment(fragment, revision.windowStartElapsedRealtimeNanos)
		}
		val coverage = when (revision.coverage) {
			"NONE" -> PortableActivityWindowCoverage.NONE
			"PARTIAL" -> PortableActivityWindowCoverage.PARTIAL
			"COMPLETE" -> PortableActivityWindowCoverage.COMPLETE
			else -> abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
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

	private fun buildFragment(
		fragment: ActivityCapturedFragmentEntity,
		windowStartElapsedRealtimeNanos: Long,
	): PortableActivityFragmentV1 {
		val start = Math.subtractExact(fragment.intervalStartElapsedRealtimeNanos, windowStartElapsedRealtimeNanos)
		val end = Math.subtractExact(fragment.intervalEndElapsedRealtimeNanos, windowStartElapsedRealtimeNanos)
		return if (fragment.fragmentKind == ActivityCapturedFragmentEntity.KIND_GAP) {
			PortableActivityFragmentV1.Gap(start, end, requireNotNull(fragment.gapReason))
		} else {
			PortableActivityFragmentV1.Band(
				start,
				end,
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
}

/** Canonical semantic checksums deliberately exclude every local or provider identity. */
object ActivityCapturedPortableIntegrity {
	/**
	 * Hashes the original elapsed-offset window, saved zone, coverage, duration totals and ordered
	 * classified/gap fragments.
	 */
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

	/**
	 * Hashes the original elapsed-offset window, saved zone, coverage, duration totals and ordered
	 * classified/gap fragments.
	 */
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

	/**
	 * Hashes original UTC millisecond run bounds, deletion scope, coverage, zone epochs and ordered
	 * window digests.
	 */
	fun runChecksum(value: PortableActivityRunV1): PortableActivityDigest = runChecksum(
		value.identity,
		value.deletionScopeDigest,
		value.startTimeMs,
		value.endTimeMs,
		value.captureCoverage,
		value.zoneEpochs,
		value.windows,
	)

	/**
	 * Hashes original UTC millisecond run bounds, deletion scope, coverage, zone epochs and ordered
	 * window digests.
	 */
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

	/**
	 * Hashes logical identity, session mode, UTC millisecond envelope and ordered run
	 * identities/checksums.
	 */
	fun entryChecksum(value: PortableActivityEntryV1): PortableActivityDigest = entryChecksum(
		value.identity,
		value.sessionMode,
		value.startTimeMs,
		value.endTimeMs,
		value.runs,
	)

	/**
	 * Hashes logical identity, session mode, UTC millisecond envelope and ordered run
	 * identities/checksums.
	 */
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

	/**
	 * Hashes the ordered Activity entry identities and checksums under the v1 envelope namespace.
	 */
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

internal fun SessionManifestSourceEntity.belongsToPortableActivityManifest(
	manifest: SessionManifestVersionEntity,
): Boolean =
	logicalTrackingId == manifest.logicalTrackingId && manifestRevision == manifest.manifestRevision

internal fun SessionManifestSourceEntity.isPortableCapturedActivityMember(
	manifest: SessionManifestVersionEntity,
): Boolean = belongsToPortableActivityManifest(manifest) &&
	sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY &&
	purpose == SessionManifestPurposeCode.SESSION_CAPTURE && persistenceEligible &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
	writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
	writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
	writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION &&
	writerBindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION

private fun validatePortableActivitySegmentBinding(
	segment: SessionSegment,
	logicalTrackingId: String,
	serviceRunId: String,
) {
	if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId ||
		segment.startTimeMs < 0L || segment.endTimeMs < segment.startTimeMs
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
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

internal fun portableActivitySha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun outcome(
	reason: PortableActivityExportUnverifiableReason,
): PortableCapturedActivitySnapshot.Outcome = PortableCapturedActivitySnapshot.Outcome(
	ExportPortableCapturedActivityResult.Unverifiable(reason),
)

private fun noEntries(): PortableCapturedActivitySnapshot.Outcome =
	PortableCapturedActivitySnapshot.Outcome(ExportPortableCapturedActivityResult.NoEntries)

internal fun portableActivityDependencyOverflow(): Nothing =
	abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW)

internal fun abortPortableActivitySnapshot(reason: PortableActivityExportUnverifiableReason): Nothing =
	throw PortableActivitySnapshotAbort(reason)

internal class PortableActivitySnapshotAbort(
	val reason: PortableActivityExportUnverifiableReason,
) : IllegalStateException(reason.name)

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

internal const val ACTIVITY_PORTABLE_CAPTURED_PLAN_ATTRIBUTION = 0

internal val ACTIVITY_PORTABLE_SHA_256_HEX = Regex("[0-9a-f]{64}")
