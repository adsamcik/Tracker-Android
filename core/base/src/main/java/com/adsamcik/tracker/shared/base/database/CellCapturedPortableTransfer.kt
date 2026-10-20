@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Stable, source-local v1 boundary for identity-free captured Cell product evidence. */
object CellCapturedPortableFormatV1 {
	const val FORMAT = "tracker-portable-captured-cell"
	const val SCHEMA_VERSION = 1
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.captured-cell+json"
	const val FILE_EXTENSION = "trackercell"

	const val MAX_RUNS_PER_ENTRY = 64
	const val MAX_MANIFESTS = 256
	const val MAX_MANIFEST_SOURCES = 4_096
	const val MAX_COMPLETENESS_ROWS = 256
	const val MAX_TERMINAL_FAILURES = 256
	const val MAX_POLICIES = 256
	const val MAX_CONSENTS = 256
	const val MAX_PLANS = 256
	const val MAX_OBSERVATIONS_PER_RUN = 4_096
	const val MAX_OBSERVATIONS_PER_ENTRY = 4_096
	const val MAX_TEXT_LENGTH = 128
	const val MAX_ORIGIN_IDENTITY_LENGTH = 4_096
	const val MAX_IMPORT_RECEIPT_FIELD_LENGTH = 4_096
}

/**
 * Distinct namespaces for portable logical Cell entries, physical runs and observations.
 */
enum class PortableCellIdentityKind {
	LOGICAL_ENTRY,
	PHYSICAL_RUN,
	OBSERVATION,
}

/**
 * Lowercase SHA-256 portable Cell identity retaining stable membership without the local database
 * key.
 */
@JvmInline
@Serializable
value class PortableCellOpaqueIdentity(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}

	/**
	 * Derives opaque Cell member identities within their explicit logical/run/observation namespace.
	 */
	companion object {
		/**
		 * Hashes the bounded nonblank local identity with its Cell member namespace.
		 */
		fun derive(kind: PortableCellIdentityKind, localIdentity: String): PortableCellOpaqueIdentity {
			require(
				localIdentity.isNotBlank() &&
					localIdentity.length <= CellCapturedPortableFormatV1.MAX_ORIGIN_IDENTITY_LENGTH,
			)
			return PortableCellOpaqueIdentity(
				CellCapturedPortableIntegrity.digest(
					"tracker-portable-cell-identity-v1",
					listOf(kind.name, localIdentity),
				),
			)
		}
	}
}

/**
 * Lowercase SHA-256 checksum of canonical Cell wire content; it grants no collection authority.
 */
@JvmInline
@Serializable
value class PortableCellDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}
}

/**
 * Lowercase SHA-256 identifier of the original Cell physical-run deletion scope.
 */
@JvmInline
@Serializable
value class PortableCellDeletionScopeDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}

	/**
	 * Derives stable original Cell capture deletion scopes.
	 */
	companion object {
		/**
		 * Derives the original Cell capture deletion scope from logical tracking and service-run
		 * identities.
		 */
		fun derive(logicalTrackingId: String, serviceRunId: String) =
			PortableCellDeletionScopeDigest(
				SourceDeletionFenceEntity.logicalServiceRunIdentity(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					logicalTrackingId,
					serviceRunId,
				),
			)
	}
}

/**
 * Original manual or automatic session mode retained by a portable Cell entry.
 */
@Serializable
enum class PortableCellSessionMode { MANUAL, AUTOMATIC }
/**
 * Whether the file represents the whole Cell run, a selected partial run or no captured scope.
 */
@Serializable
enum class PortableCellCaptureCoverage { WHOLE_RUN, PARTIAL_RUN, NOT_CAPTURED }
/**
 * Retained Cell observations, captured scope with no retained observations, or no captured Cell
 * scope.
 */
@Serializable
enum class PortableCellRunAvailability { RETAINED, NO_RETAINED_OBSERVATION, NOT_CAPTURED }
/**
 * Provider acquisition coverage of the represented Cell run, retaining complete, partial and
 * unknown states.
 */
@Serializable
enum class PortableCellAcquisitionCompleteness { COMPLETE, PARTIAL, UNKNOWN }
/**
 * Whether all represented Cell children qualified or the child set is partial.
 */
@Serializable
enum class PortableCellChildCompleteness { COMPLETE, PARTIAL }
/**
 * Explicitly unknown subscription grouping in the portable Cell file; no SIM identity is inferred.
 */
@Serializable
enum class PortableCellSubscriptionGrouping { UNKNOWN }

/** No tower, subscription, provider, hardware, authorization, or local database identity crosses this type. */
@Suppress("LongParameterList")
@Serializable
data class PortableCapturedCellObservationV1(
	val identity: PortableCellOpaqueIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
	val aggregateOwnerIdentity: PortableCellOpaqueIdentity?,
	val aggregateOwnerSemanticRevision: Long?,
	val contentChecksum: PortableCellDigest,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val storedZoneId: String,
	val childCompleteness: PortableCellChildCompleteness,
	val subscriptionGrouping: PortableCellSubscriptionGrouping,
	val submittedChildCount: Int,
	val acceptedChildCount: Int,
	val staleChildCount: Int,
	val futureTimeChildCount: Int,
	val missingTimeChildCount: Int,
	val clockUnverifiableChildCount: Int,
	val authorityMismatchChildCount: Int,
	val unsupportedTechnologyChildCount: Int,
	val observationCount: Int,
	val registeredObservationCount: Int,
	val gsmCount: Int,
	val cdmaCount: Int,
	val wcdmaCount: Int,
	val tdscdmaCount: Int,
	val lteCount: Int,
	val nrCount: Int,
	val qualityUnknownCount: Int,
	val qualityNoneOrUnknownCount: Int,
	val qualityPoorCount: Int,
	val qualityModerateCount: Int,
	val qualityGoodCount: Int,
	val qualityGreatCount: Int,
	val weakObservationCount: Int,
	val knownQualityObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
	val qualityFlags: Long,
	val qualityConfidence: Double?,
) {
	init {
		require(semanticRevision > 0L)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require((aggregateOwnerIdentity == null) == (aggregateOwnerSemanticRevision == null))
		require(aggregateOwnerSemanticRevision?.let { it > 0L } != false)
		require(coverageStartTimeMs >= 0L && observedTimeMs >= coverageStartTimeMs)
		require(latestPossibleTimeMs >= observedTimeMs && wallTimeUncertaintyMs >= 0L)
		// Native coverage can start earlier than the observation, but never later than its
		// earliest possible wall time. The exporter uses checked subtraction without a clamp.
		require(coverageStartTimeMs <= Math.subtractExact(observedTimeMs, wallTimeUncertaintyMs))
		require(storedZoneId.isNotBlank() && storedZoneId.length <= CellCapturedPortableFormatV1.MAX_TEXT_LENGTH)
		requireValidZone(storedZoneId)
		val counts = listOf(
			submittedChildCount, acceptedChildCount, staleChildCount, futureTimeChildCount,
			missingTimeChildCount, clockUnverifiableChildCount, authorityMismatchChildCount,
			unsupportedTechnologyChildCount, observationCount, registeredObservationCount,
			gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount,
			qualityUnknownCount, qualityNoneOrUnknownCount, qualityPoorCount,
			qualityModerateCount, qualityGoodCount, qualityGreatCount, weakObservationCount,
			knownQualityObservationCount,
		)
		require(counts.all { it >= 0 })
		val classifiedChildCount = sumExact(
			acceptedChildCount, staleChildCount, futureTimeChildCount, missingTimeChildCount,
			clockUnverifiableChildCount, authorityMismatchChildCount,
			unsupportedTechnologyChildCount,
		)
		require(submittedChildCount > 0 && submittedChildCount == classifiedChildCount)
		require((childCompleteness == PortableCellChildCompleteness.COMPLETE) ==
			(submittedChildCount == acceptedChildCount))
		require(observationCount > 0 && acceptedChildCount == observationCount)
		require(registeredObservationCount <= observationCount)
		require(sumExact(gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount) == observationCount)
		require(sumExact(
			qualityUnknownCount, qualityNoneOrUnknownCount, qualityPoorCount,
			qualityModerateCount, qualityGoodCount, qualityGreatCount,
		) == observationCount)
		require(knownQualityObservationCount == Math.subtractExact(observationCount, qualityUnknownCount))
		require(weakObservationCount == Math.addExact(qualityNoneOrUnknownCount, qualityPoorCount))
		require(allKnownQualityIsWeak ==
			(knownQualityObservationCount > 0 && weakObservationCount == knownQualityObservationCount))
		require(qualityFlags >= 0L)
		require(qualityConfidence?.let { it.isFinite() && it in 0.0..1.0 } != false)
		require(CellCapturedPortableIntegrity.observationChecksum(this) == contentChecksum)
	}
}

/**
 * One original Cell run with UTC millisecond bounds, capture/retention coverage and ordered unique
 * observations.
 */
@Serializable
data class PortableCapturedCellRunV1(
	val identity: PortableCellOpaqueIdentity,
	val deletionScopeDigest: PortableCellDeletionScopeDigest,
	val contentChecksum: PortableCellDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val captureCoverage: PortableCellCaptureCoverage,
	val availability: PortableCellRunAvailability,
	val acquisitionCompleteness: PortableCellAcquisitionCompleteness,
	val retentionLoss: Boolean,
	val subscriptionGrouping: PortableCellSubscriptionGrouping,
	val observations: List<PortableCapturedCellObservationV1>,
) {
	init {
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(observations.size <= CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_RUN)
		require(observations == observations.sortedWith(PORTABLE_CELL_OBSERVATION_ORDER))
		require(observations.map { it.identity }.distinct().size == observations.size)
		when (availability) {
			PortableCellRunAvailability.RETAINED -> require(
				captureCoverage != PortableCellCaptureCoverage.NOT_CAPTURED && observations.isNotEmpty(),
			)
			PortableCellRunAvailability.NO_RETAINED_OBSERVATION -> require(
				captureCoverage != PortableCellCaptureCoverage.NOT_CAPTURED && observations.isEmpty(),
			)
			PortableCellRunAvailability.NOT_CAPTURED -> require(
				captureCoverage == PortableCellCaptureCoverage.NOT_CAPTURED && observations.isEmpty() &&
					!retentionLoss,
			)
		}
		require(CellCapturedPortableIntegrity.runChecksum(this) == contentChecksum)
	}
}

/**
 * Bounded logical Cell entry retaining original identity, session mode and ordered physical-run
 * membership.
 */
@Serializable
data class PortableCapturedCellEntryV1(
	@Required
	val format: String = CellCapturedPortableFormatV1.FORMAT,
	@Required
	val schemaVersion: Int = CellCapturedPortableFormatV1.SCHEMA_VERSION,
	val identity: PortableCellOpaqueIdentity,
	val contentChecksum: PortableCellDigest,
	val sessionMode: PortableCellSessionMode,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val subscriptionGrouping: PortableCellSubscriptionGrouping,
	val runs: List<PortableCapturedCellRunV1>,
) {
	init {
		require(format == CellCapturedPortableFormatV1.FORMAT)
		require(schemaVersion == CellCapturedPortableFormatV1.SCHEMA_VERSION)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(runs.isNotEmpty() && runs.size <= CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(runs == runs.sortedWith(PORTABLE_CELL_RUN_ORDER))
		require(runs.map { it.identity }.distinct().size == runs.size)
		require(runs.sumOf { it.observations.size } <=
			CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY)
		require(startTimeMs == runs.minOf { it.startTimeMs } && endTimeMs == runs.maxOf { it.endTimeMs })
		require(runs.any { it.captureCoverage != PortableCellCaptureCoverage.NOT_CAPTURED })
		require(CellCapturedPortableIntegrity.entryChecksum(this) == contentChecksum)
	}
}

/**
 * Structural verifier for already-authenticated Cell-v1 hierarchies. It grants no live, writer,
 * deletion, or provider authority.
 */
class PortableCellOpaqueOwnershipVerifier private constructor(
	private val identityOwners: MutableMap<String, PortableCellOpaqueOwner>,
	private val deletionScopeOwners: MutableMap<String, PortableCellOpaqueOwner>,
) {
	/** Atomically includes one compatible hierarchy; false leaves the verifier unchanged. */
	fun tryInclude(entry: PortableCapturedCellEntryV1): Boolean {
		val candidate = ownershipOf(entry) ?: return false
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
	 * Builds a verifier only from compatible complete Cell hierarchies.
	 */
	companion object {
		/**
		 * Returns a verifier when every Cell entry/member/deletion scope has compatible ownership, or
		 * null on collision.
		 */
		fun fromEntries(entries: Collection<PortableCapturedCellEntryV1>):
			PortableCellOpaqueOwnershipVerifier? {
			val verifier = PortableCellOpaqueOwnershipVerifier(linkedMapOf(), linkedMapOf())
			return verifier.takeIf { entries.all(verifier::tryInclude) }
		}

		private fun ownershipOf(entry: PortableCapturedCellEntryV1):
			PortableCellOpaqueOwnershipVerifier? {
			val verifier = PortableCellOpaqueOwnershipVerifier(linkedMapOf(), linkedMapOf())
			val entryIdentity = entry.identity.value
			if (!verifier.bindIdentity(
				entryIdentity,
				PortableCellOpaqueOwner(PortableCellIdentityKind.LOGICAL_ENTRY, entryIdentity),
			)) {
				return null
			}
			entry.runs.forEach { run ->
				val runIdentity = run.identity.value
				val runOwner = PortableCellOpaqueOwner(
					PortableCellIdentityKind.PHYSICAL_RUN,
					entryIdentity,
					runIdentity,
				)
				if (!verifier.bindIdentity(runIdentity, runOwner) ||
					!verifier.bindScope(run.deletionScopeDigest.value, runOwner)
				) {
					return null
				}
				run.observations.forEach { observation ->
					if (!verifier.bindIdentity(
						observation.identity.value,
						PortableCellOpaqueOwner(
							kind = PortableCellIdentityKind.OBSERVATION,
							entryIdentity = entryIdentity,
							runIdentity = runIdentity,
							aggregateOwnerIdentity = observation.aggregateOwnerIdentity?.value,
						),
					)) {
						return null
					}
				}
			}
			return verifier.takeUnless { current ->
				current.identityOwners.keys.any { it in current.deletionScopeOwners }
			}
		}
	}

	private fun bindIdentity(identity: String, owner: PortableCellOpaqueOwner): Boolean {
		val previous = identityOwners[identity]
		if (previous == null) {
			identityOwners[identity] = owner
		}
		return previous == null || previous == owner
	}

	private fun bindScope(scope: String, owner: PortableCellOpaqueOwner): Boolean {
		val previous = deletionScopeOwners[scope]
		if (previous == null) {
			deletionScopeOwners[scope] = owner
		}
		return previous == null || previous == owner
	}
}

/** Exact source-specific duplicate rule shared by local/imported Cell composition tests. */
object PortableCellOriginComparison {
	/**
	 * Checks identical full v1 Cell hierarchies with the required complete capture representation
	 * before treating origins as duplicates.
	 */
	fun areExactFullV1Duplicates(
		local: PortableCapturedCellEntryV1,
		imported: PortableCapturedCellEntryV1,
	): Boolean = local == imported &&
		PortableCellOpaqueOwnershipVerifier.fromEntries(listOf(local, imported)) != null
}

private data class PortableCellOpaqueOwner(
	val kind: PortableCellIdentityKind,
	val entryIdentity: String,
	val runIdentity: String? = null,
	val aggregateOwnerIdentity: String? = null,
)

/**
 * One nonblank bounded logical recording identity selecting native Cell export.
 */
data class ExportPortableCapturedCellRequest(val logicalTrackingId: String) {
	init {
		require(
			logicalTrackingId.isNotBlank() &&
				logicalTrackingId.length <= CellCapturedPortableFormatV1.MAX_ORIGIN_IDENTITY_LENGTH,
		)
	}
}

/**
 * Receives one complete prepared Cell entry after its bounded source snapshot closes.
 */
fun interface PortableCapturedCellSink {
	/** Invoked only after the authenticated immutable Room snapshot has ended. */
	suspend fun emit(entry: PortableCapturedCellEntryV1)
}

/**
 * Read-only native Cell export with no provider acquisition or consent changes.
 */
interface ExportPortableCapturedCell {
	/**
	 * Prepares the exact saved native Cell entry without provider demand and emits only a complete
	 * snapshot.
	 */
	suspend fun export(
		request: ExportPortableCapturedCellRequest,
		sink: PortableCapturedCellSink,
	): ExportPortableCapturedCellResult
}

/** Exact imported Cell lineage selected for authenticated latest-v1 re-export. */
data class ReexportImportedPortableCapturedCellRequest(
	val identity: PortableCellOpaqueIdentity,
	val expectedImportRevision: Long,
	val expectedContentChecksum: PortableCellDigest,
) {
	init {
		require(expectedImportRevision > 0L)
	}
}

/**
 * Read-only re-export of the exact selected imported Cell revision and content.
 */
interface ReexportImportedPortableCapturedCell {
	/**
	 * Rechecks the selected imported revision/checksum and data epoch before emitting its retained
	 * Cell hierarchy.
	 */
	suspend fun reexport(
		request: ReexportImportedPortableCapturedCellRequest,
		sink: PortableCapturedCellSink,
	): ExportPortableCapturedCellResult
}

/** Bounded provenance copied into Cell-local immutable imported-product authority. */
data class PortableCellImportReceipt(
	val jobId: String,
	val entryKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, entryKey, sourceName).forEach { value ->
			require(value.isNotBlank())
			require(value.length <= CellCapturedPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/** One Cell-only admission transaction over an already-decoded captured-product entry. */
data class ImportPortableCapturedCellRequest(
	val entry: PortableCapturedCellEntryV1,
	val receipt: PortableCellImportReceipt,
	val expectedCollectedDataEpoch: Long,
	val mergeSelectedReadings: Boolean = false,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/** Admission stores imported product evidence only and never grants live Cell authority. */
interface ImportPortableCapturedCell {
	/**
	 * Admits decoded Cell product and original file provenance under the job data epoch without
	 * creating native recording authority.
	 */
	suspend fun importEntry(request: ImportPortableCapturedCellRequest): ImportPortableCapturedCellResult
}

/**
 * Imported Cell admission outcome reporting saved revision/run/observation counts separately from
 * failures.
 */
sealed interface ImportPortableCapturedCellResult {
	/**
	 * The original imported product was saved at the reported revision with its represented hierarchy
	 * counts.
	 */
	data class Applied(
		val importRevision: Long,
		val physicalRunCount: Int,
		val observationCount: Int,
	) : ImportPortableCapturedCellResult {
		init {
			require(importRevision > 0L)
			require(physicalRunCount > 0)
			require(observationCount >= 0)
		}
	}

	/**
	 * Matching original imported content is already saved at the reported import revision.

	 */
	data class Duplicate(val importRevision: Long) : ImportPortableCapturedCellResult {
		init {
			require(importRevision > 0L)
		}
	}

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(val reason: PortableCellImportBlockedReason) : ImportPortableCapturedCellResult
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: PortableCellImportUnverifiableReason,
	) : ImportPortableCapturedCellResult
	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(
		val reason: PortableCellImportRetryableReason,
	) : ImportPortableCapturedCellResult
}

/**
 * Data-epoch, retention, receipt, correction, identity or deletion boundaries preventing Cell
 * import.
 */
enum class PortableCellImportBlockedReason {
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
 * Invalid entry or stored evidence and exceeded run/observation/revision limits during Cell
 * import.
 */
enum class PortableCellImportUnverifiableReason {
	ENTRY_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	RUN_OVERFLOW,
	OBSERVATION_OVERFLOW,
	TOTAL_OBSERVATION_OVERFLOW,
	REVISION_OVERFLOW,
}

/**
 * Concurrent-state or storage failures permitting retry of Cell admission.
 */
enum class PortableCellImportRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}

/**
 * Prepared Cell output or specific missing, materializing, deleted, unverifiable or storage state.
 */
sealed interface ExportPortableCapturedCellResult {
	/**
	 * The complete prepared source payload was emitted; reports its original identity/checksum or
	 * represented entry counts.
	 */
	data class Exported(
		val entryIdentity: PortableCellOpaqueIdentity,
		val contentChecksum: PortableCellDigest,
		val physicalRunCount: Int,
		val observationCount: Int,
	) : ExportPortableCapturedCellResult {
		init {
			require(physicalRunCount > 0 && observationCount >= 0)
		}
	}

	/**
	 * The requested original scope or retention authority is unavailable for the stated reason.
	 */
	data class Unavailable(val reason: PortableCellUnavailableReason) : ExportPortableCapturedCellResult
	/**
	 * The source's saved product is still settling and cannot provide a complete export.
	 */
	data object Materializing : ExportPortableCapturedCellResult
	/**
	 * The selected source payload has been deleted and is unavailable for a complete export.
	 */
	data object Deleted : ExportPortableCapturedCellResult
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(val reason: PortableCellUnverifiableReason) : ExportPortableCapturedCellResult
	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(val reason: PortableCellRetryableReason) : ExportPortableCapturedCellResult
}

/**
 * Missing entry/capture/facts, retention loss or provider unavailability preventing the complete
 * Cell export.
 */
enum class PortableCellUnavailableReason {
	ENTRY_NOT_FOUND,
	SOURCE_NOT_CAPTURED,
	NO_QUALIFIED_FACTS,
	RETENTION_LIMIT,
	PROVIDER_UNAVAILABLE,
}

/**
 * Source, membership, writer/fact/import evidence, stale selection, collision or dependency

 * failure preventing Cell export.
 */
enum class PortableCellUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
	WRITER_AUTHORITY_UNVERIFIABLE,
	FACT_AUTHORITY_UNVERIFIABLE,
	IMPORTED_EVIDENCE_UNVERIFIABLE,
	IMPORTED_SELECTION_STALE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

/**
 * Storage failure permitting retry of the Cell transfer.
 */
enum class PortableCellRetryableReason { STORAGE_UNAVAILABLE }

/** Source-owned export; database authentication and checksum construction stay off the caller thread. */
class RoomExportPortableCapturedCell(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableCapturedCell {
	/**
	 * Prepares the selected native Cell hierarchy on the disk dispatcher and emits outside the
	 * transaction; cancellation propagates.
	 */
	override suspend fun export(
		request: ExportPortableCapturedCellRequest,
		sink: PortableCapturedCellSink,
	): ExportPortableCapturedCellResult = withContext(ioDispatcher) {
		when (val snapshot = readSnapshot(request)) {
			is ReadLocalPortableCapturedCellResult.Ready -> emitPortableCellEntry(snapshot.entry, sink)
			is ReadLocalPortableCapturedCellResult.Outcome -> snapshot.result
		}
	}

	private suspend fun readSnapshot(
		request: ExportPortableCapturedCellRequest,
	): ReadLocalPortableCapturedCellResult = try {
		database.withTransaction {
			database.readPortableCapturedCellEntry(request, laneExecutionAuthority)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (blocked: CellCapturedRetentionBlockedException) {
		result(ExportPortableCapturedCellResult.Unverifiable(
			when (blocked.reason) {
				CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
					PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW
				else -> PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE
			},
		))
	} catch (@Suppress("SwallowedException") _: CellCapturedMaintenanceLimitExceeded) {
		result(ExportPortableCapturedCellResult.Unverifiable(PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW))
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		result(ExportPortableCapturedCellResult.Unverifiable(PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE))
	} catch (@Suppress("SwallowedException") _: ArithmeticException) {
		result(ExportPortableCapturedCellResult.Unverifiable(PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE))
	} catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception) {
		result(ExportPortableCapturedCellResult.RetryableFailure(PortableCellRetryableReason.STORAGE_UNAVAILABLE))
	}
}

/** Re-exports only the authenticated latest undeleted imported Cell-v1 revision. */
@Singleton
class RoomReexportImportedPortableCapturedCell @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ReexportImportedPortableCapturedCell {
	/**
	 * Rechecks the exact imported selection and emits its prepared hierarchy after snapshot closure;
	 * cancellation propagates.
	 */
	override suspend fun reexport(
		request: ReexportImportedPortableCapturedCellRequest,
		sink: PortableCapturedCellSink,
	): ExportPortableCapturedCellResult = withContext(ioDispatcher) {
		when (val snapshot = readSnapshot(request)) {
			is ReadLocalPortableCapturedCellResult.Ready -> emitPortableCellEntry(snapshot.entry, sink)
			is ReadLocalPortableCapturedCellResult.Outcome -> snapshot.result
		}
	}

	private suspend fun readSnapshot(
		request: ReexportImportedPortableCapturedCellRequest,
	): ReadLocalPortableCapturedCellResult {
		val snapshot = try {
			database.withTransaction { loadSnapshotInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception) {
			return result(ExportPortableCapturedCellResult.RetryableFailure(PortableCellRetryableReason.STORAGE_UNAVAILABLE))
		}
		return when (snapshot) {
			ImportedCellReexportSnapshot.Missing -> result(
				ExportPortableCapturedCellResult.Unavailable(PortableCellUnavailableReason.ENTRY_NOT_FOUND),
			)
			ImportedCellReexportSnapshot.Deleted -> result(ExportPortableCapturedCellResult.Deleted)
			ImportedCellReexportSnapshot.Stale -> result(
				ExportPortableCapturedCellResult.Unverifiable(PortableCellUnverifiableReason.IMPORTED_SELECTION_STALE),
			)
			ImportedCellReexportSnapshot.Unverifiable -> result(
				ExportPortableCapturedCellResult.Unverifiable(PortableCellUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE),
			)
			is ImportedCellReexportSnapshot.Readable -> prepareSelection(snapshot.evaluation, request)
		}
	}

	private suspend fun loadSnapshotInTransaction(
		request: ReexportImportedPortableCapturedCellRequest,
	): ImportedCellReexportSnapshot {
		val selected = ImportedCellProductReader(database).selectIdentityInTransaction(request.identity)
		if (selected != null) {
			return ImportedCellReexportSnapshot.Readable(selected)
		}
		return when (database.authenticateDeletedImportedCellSelectionInTransaction(
			request.identity,
			request.expectedImportRevision,
			request.expectedContentChecksum,
		)) {
			DeletedImportedCellSelectionAuthentication.Absent -> ImportedCellReexportSnapshot.Missing
			is DeletedImportedCellSelectionAuthentication.Exact -> ImportedCellReexportSnapshot.Deleted
			DeletedImportedCellSelectionAuthentication.Stale -> ImportedCellReexportSnapshot.Stale
			DeletedImportedCellSelectionAuthentication.Unverifiable -> ImportedCellReexportSnapshot.Unverifiable
		}
	}

	private fun prepareSelection(
		selected: ImportedCellProductEvaluation,
		request: ReexportImportedPortableCapturedCellRequest,
	): ReadLocalPortableCapturedCellResult {
		val candidate = selected.candidate
		if (candidate.importRevision != request.expectedImportRevision ||
			candidate.contentChecksum != request.expectedContentChecksum.value
		) {
			return result(ExportPortableCapturedCellResult.Unverifiable(PortableCellUnverifiableReason.IMPORTED_SELECTION_STALE))
		}
		return when (selected) {
			is ImportedCellProductEvaluation.Unverifiable -> result(
				ExportPortableCapturedCellResult.Unverifiable(selected.reason.toPortableCellUnverifiableReason()),
			)
			is ImportedCellProductEvaluation.Readable -> prepareReadableEntry(selected)
		}
	}

	private fun prepareReadableEntry(
		selected: ImportedCellProductEvaluation.Readable,
	): ReadLocalPortableCapturedCellResult = when {
		selected.entryDeleted || selected.deletedRunIdentities.isNotEmpty() ->
			result(ExportPortableCapturedCellResult.Deleted)
		selected.retentionLimited -> result(
			ExportPortableCapturedCellResult.Unavailable(PortableCellUnavailableReason.RETENTION_LIMIT),
		)
		!selected.isReExportable -> result(
			ExportPortableCapturedCellResult.Unverifiable(PortableCellUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE),
		)
		else -> ReadLocalPortableCapturedCellResult.Ready(selected.entry)
	}
}

/** Prepared entries leave their Room snapshot before any destination or user callback runs. */
private suspend fun emitPortableCellEntry(
	entry: PortableCapturedCellEntryV1,
	sink: PortableCapturedCellSink,
): ExportPortableCapturedCellResult {
	currentCoroutineContext().ensureActive()
	sink.emit(entry)
	return ExportPortableCapturedCellResult.Exported(
		entryIdentity = entry.identity,
		contentChecksum = entry.contentChecksum,
		physicalRunCount = entry.runs.size,
		observationCount = entry.runs.sumOf { it.observations.size },
	)
}

private sealed interface ImportedCellReexportSnapshot {
	data class Readable(
		val evaluation: ImportedCellProductEvaluation,
	) : ImportedCellReexportSnapshot
	data object Missing : ImportedCellReexportSnapshot
	/**
	 * The selected source payload has been deleted and is unavailable for a complete export.
	 */
	data object Deleted : ImportedCellReexportSnapshot
	data object Stale : ImportedCellReexportSnapshot
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data object Unverifiable : ImportedCellReexportSnapshot
}

/**
 * Prepared saved Cell hierarchy or the exact export outcome preventing the in-transaction
 * snapshot.
 */
sealed interface ReadLocalPortableCapturedCellResult {
	/**
	 * Contains the complete prepared source hierarchy or verified saved authority for this exact
	 * read.
	 */
	data class Ready(val entry: PortableCapturedCellEntryV1) : ReadLocalPortableCapturedCellResult
	/**
	 * The saved Cell hierarchy could not be prepared; preserves the exact export outcome.
	 */
	data class Outcome(val result: ExportPortableCapturedCellResult) : ReadLocalPortableCapturedCellResult
}

private fun ImportedCellProductFailure.toPortableCellUnverifiableReason():
	PortableCellUnverifiableReason = when (this) {
	ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING ->
		PortableCellUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING
	ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		PortableCellUnverifiableReason.ORIGIN_IDENTITY_CONFLICT
	ImportedCellProductFailure.DEPENDENCY_OVERFLOW ->
		PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH,
	ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	ImportedCellProductFailure.VALUE_OVERFLOW,
	-> PortableCellUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE
}

/** Local Cell-v1 reader used only inside a caller-owned Room snapshot. */
class RoomReadLocalPortableCapturedCell(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
) {
	/**
	 * Reads the named native Cell entry inside the caller's snapshot, preserving the exact
	 * unavailable/export outcome.
	 */
	suspend fun readInTransaction(
		logicalTrackingId: String,
	): ReadLocalPortableCapturedCellResult = database.readPortableCapturedCellEntry(
		ExportPortableCapturedCellRequest(logicalTrackingId),
		laneExecutionAuthority,
	)
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
private suspend fun AppDatabase.readPortableCapturedCellEntry(
	request: ExportPortableCapturedCellRequest,
	laneExecutionAuthority: SourceProductLaneExecutionAuthority,
): ReadLocalPortableCapturedCellResult {
	currentCoroutineContext().ensureActive()
	val evidence = sourceEvidenceStateDao().get() ?: return result(
		ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		),
	)
	if (!evidence.hasValidPortableCellShape()) {
		return result(unverifiableFact())
	}

	val readDao = trackingHistoryReadDao()
	val runs = readDao.logicalEntryServiceRunPage(
		listOf(request.logicalTrackingId),
		CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 1,
		null,
		null,
		null,
	)
	if (runs.isEmpty()) {
		return result(
			if (sourceSessionDao().session(request.logicalTrackingId) == null) {
				ExportPortableCapturedCellResult.Unavailable(PortableCellUnavailableReason.ENTRY_NOT_FOUND)
			} else {
				ExportPortableCapturedCellResult.Unverifiable(
					PortableCellUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
				)
			},
		)
	}
	if (runs.size > CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
		return result(overflow())
	}
	if (runs.any { it.logicalTrackingId != request.logicalTrackingId } ||
		runs.distinctBy { it.serviceRunId }.size != runs.size ||
		runs != runs.sortedWith(compareBy(SourceServiceRunEntity::startedAtMs, SourceServiceRunEntity::serviceRunId))
	) {
		return result(unverifiableMembership())
	}

	val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId)
	if (segmentIds.size != runs.size || segmentIds.distinct().size != segmentIds.size) {
		return result(unverifiableMembership())
	}
	val segments = readDao.rawLogicalEntrySegments(
		request.logicalTrackingId,
		CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 1,
	)
	if (segments.size > CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
		return result(overflow())
	}
	if (segments.size != runs.size || segments.distinctBy(SessionSegment::id).size != segments.size ||
		segments.mapTo(mutableSetOf(), SessionSegment::id) != segmentIds.toSet()
	) {
		return result(unverifiableMembership())
	}
	val segmentsById = segments.associateBy(SessionSegment::id)
	if (runs.any { run -> !run.hasExactPortableMembership(request.logicalTrackingId, segmentsById) }) {
		return result(unverifiableMembership())
	}
	val runIds = runs.map(SourceServiceRunEntity::serviceRunId)

	val session = sourceSessionDao().session(request.logicalTrackingId)
		?: return result(unverifiableMembership())
	val sessionMode = when (session.sessionMode) {
		"MANUAL" -> PortableCellSessionMode.MANUAL
		"AUTOMATIC" -> PortableCellSessionMode.AUTOMATIC
		else -> return result(unverifiableAttribution())
	}
	val interrupted = session.state == "FAILED" && session.failureCode == "RESTORED_INTERRUPTED_SESSION"
	val interruptedRunIds = runs.filter { it.state == "FAILED" &&
		it.completionReason == "RESTORED_INTERRUPTED_SESSION" && it.completedAtMs == null }
		.mapTo(mutableSetOf()) { it.serviceRunId }
	if (!session.hasPortableCellShape(runs, interrupted)) {
		return result(unverifiableAttribution())
	}
	val hierarchyMaterializing = !interrupted && (session.completedAtMs == null || runs.any { run ->
		run.completedAtMs == null ||
			run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING
	})
	if (runs.any { run ->
		run.presentationAcknowledgement !in setOf(
			SourceServiceRunEntity.PRESENTATION_PENDING,
			SourceServiceRunEntity.PRESENTATION_QUIESCED,
		) || (run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED &&
			run.presentationAcknowledgedAtMs == null)
	}) {
		return result(unverifiableWriter())
	}

	val manifests = readDao.manifests(runIds, CellCapturedPortableFormatV1.MAX_MANIFESTS + 1)
	val sources = readDao.manifestSources(runIds, CellCapturedPortableFormatV1.MAX_MANIFEST_SOURCES + 1)
	val completeness = readDao.sourceCompleteness(
		CELL_SOURCE_KIND,
		runIds,
		CellCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS + 1,
	)
	if (manifests.size > CellCapturedPortableFormatV1.MAX_MANIFESTS ||
		sources.size > CellCapturedPortableFormatV1.MAX_MANIFEST_SOURCES ||
		completeness.size > CellCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS
	) {
		return result(overflow())
	}
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
	if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
		manifestsByRun.values.map { revisions -> revisions.map(SessionManifestVersionEntity::manifestRevision) },
	) || runs.any { run ->
		val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
		!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, runManifests) ||
            runManifests.any { manifest ->
			manifest.logicalTrackingId != request.logicalTrackingId ||
				!SessionManifestIntegrity.verify(
					manifest,
					sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision].orEmpty(),
				)
		}
	}) {
		return result(unverifiableAttribution())
	}
	val latestManifestByRun = runs.associateWith { run ->
		manifestsByRun[run.serviceRunId].orEmpty().maxByOrNull(
			SessionManifestVersionEntity::manifestRevision,
		) ?: return result(unverifiableAttribution())
	}
	val latestLogicalManifest = latestManifestByRun.values.maxByOrNull(
		SessionManifestVersionEntity::manifestRevision,
	) ?: return result(unverifiableAttribution())
	if (latestManifestByRun.any { (run, manifest) ->
		run.desiredPlanRevision != manifest.acquisitionPlanRevision ||
			run.rolloutRevision != manifest.rolloutRevision
	}) {
		return result(unverifiableAttribution())
	}
	if (manifests.any { it.sessionMode != session.sessionMode } ||
		session.currentManifestRevision != latestLogicalManifest.manifestRevision ||
		session.desiredPlanRevision != latestLogicalManifest.acquisitionPlanRevision ||
		session.rolloutRevision != latestLogicalManifest.rolloutRevision
	) {
		return result(unverifiableAttribution())
	}

	val captureByRun = linkedMapOf<String, List<SessionManifestSourceEntity>>()
	for (run in runs) {
		val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
		if (runManifests.isEmpty()) {
			return result(unverifiableAttribution())
		}
		val bindings = runManifests.mapNotNull { manifest ->
			val membership = sourcesByManifest[
				manifest.logicalTrackingId to manifest.manifestRevision
			].orEmpty()
			val matches = membership
				.filter(::isPortableCellCaptureMembership)
			if (matches.size > 1 || (matches.isNotEmpty() && membership.any { source ->
				source.sourceKind == CELL_SOURCE_KIND &&
					source.purpose == SessionManifestPurposeCode.CONTROL
			})) {
				return result(unverifiableAttribution())
			}
			matches.singleOrNull()
		}
		if (bindings.any { !it.isExactPortableCellWriter() }) {
			return result(unverifiableWriter())
		}
		captureByRun[run.serviceRunId] = bindings
	}
	if (captureByRun.values.all { it.isEmpty() }) {
		return result(ExportPortableCapturedCellResult.Unavailable(
			PortableCellUnavailableReason.SOURCE_NOT_CAPTURED,
		))
	}
	val selectedCapturedRunIds = captureByRun.filterValues { it.isNotEmpty() }.keys
	val selectedCapturedRunIdList = selectedCapturedRunIds.sorted()
	val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
	val consentEpochs = captureByRun.values.flatten().map(SessionManifestSourceEntity::consentEpoch).distinct()
	val policies = readDao.policiesForServiceRuns(CELL_SOURCE_KIND, runIds)
	val consents = sourcePolicyDao().consentEpochs(
		CELL_SOURCE_KIND,
		SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpochs,
	)
	val planHeaders = readDao.acquisitionPlanRevisions(
		planRevisions,
		CellCapturedPortableFormatV1.MAX_PLANS + 1,
	)
	val desiredPlans = readDao.desiredPlans(
		CELL_SOURCE_KIND,
		planRevisions,
		CellCapturedPortableFormatV1.MAX_PLANS + 1,
	)
	if (policies.size > CellCapturedPortableFormatV1.MAX_POLICIES ||
		consents.size > CellCapturedPortableFormatV1.MAX_CONSENTS ||
		planHeaders.size > CellCapturedPortableFormatV1.MAX_PLANS ||
		desiredPlans.size > CellCapturedPortableFormatV1.MAX_PLANS
	) {
		return result(overflow())
	}
	if (policies.distinctBy { it.policyRevision }.size != policies.size ||
		consents.distinctBy { it.epoch }.size != consents.size ||
		planHeaders.distinctBy { it.revision }.size != planHeaders.size ||
		desiredPlans.distinctBy { it.revision }.size != desiredPlans.size
	) {
		return result(overflow())
	}
	val policyByRevision = policies.associateBy { it.policyRevision }
	val consentByEpoch = consents.associateBy { it.epoch }
	val planHeaderByRevision = planHeaders.associateBy { it.revision }
	val desiredPlanByRevision = desiredPlans.associateBy { it.revision }
	if (manifests.any { manifest ->
		val binding = sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision]
			.orEmpty().singleOrNull(::isPortableCellCaptureMembership) ?: return@any false
		!manifest.hasExactPortableCellIntent(
			binding,
			policyByRevision[manifest.sourcePolicyRevision],
			consentByEpoch[binding.consentEpoch],
			planHeaderByRevision[manifest.acquisitionPlanRevision],
			desiredPlanByRevision[manifest.acquisitionPlanRevision],
		)
	}) {
		return result(unverifiableAttribution())
	}

	val lane = readDao.productLanesForServiceRuns(
		CELL_SOURCE_KIND,
		SessionManifestPurposeCode.SESSION_CAPTURE,
		runIds,
	).singleOrNull() ?: return result(unverifiableWriter())
	val requiredModeMask = when (sessionMode) {
		PortableCellSessionMode.MANUAL -> MANUAL_CAPTURE_MASK
		PortableCellSessionMode.AUTOMATIC -> AUTOMATIC_CAPTURE_MASK
	}
	if (!lane.isExactPortableCellLane() || !laneExecutionAuthority.owns(lane) ||
		lane.captureModeMask and requiredModeMask == 0L ||
		manifests.any { lane.activatedRolloutRevision > it.rolloutRevision }
	) {
		return result(unverifiableWriter())
	}
	val cellCompleteness = completeness.filter { row -> row.serviceRunId in selectedCapturedRunIds }
	if (!cellCompleteness.hasValidPortableCellShape(request.logicalTrackingId, runIds)) {
		return result(unverifiableWriter())
	}
	val activationFloorOrdinal = Math.subtractExact(lane.activationOrdinal, 1L)
	val audit = auditPortableCapturedCellFacts(
		evidence,
		request.logicalTrackingId,
		runIds,
		selectedCapturedRunIdList,
		activationFloorOrdinal,
		lane.contiguousAdmissionOrdinal,
		PORTABLE_CELL_AUDIT_LIMITS,
	) { currentCoroutineContext().ensureActive() }
	val walAudit = loadPortableCapturedCellWalScopes(
		evidence,
		request.logicalTrackingId,
		runIds,
		selectedCapturedRunIdList,
		activationFloorOrdinal,
		lane.contiguousAdmissionOrdinal,
		PORTABLE_CELL_AUDIT_LIMITS,
	)
	val selectedWal = walAudit.carriers.filter {
		it.scope.logicalTrackingId == request.logicalTrackingId
	}

	val selectedLineages = audit.lineages.filter { it.scope.logicalTrackingId == request.logicalTrackingId }
	if (selectedLineages.any { it.scope.serviceRunId !in runIds } ||
		selectedWal.any { it.scope.serviceRunId !in runIds }
	) {
		return result(unverifiableFact())
	}
	if (audit.lineages.any { lineage ->
			lineage.scope.serviceRunId in runIds && lineage.scope.logicalTrackingId != request.logicalTrackingId
		} || walAudit.carriers.any { carrier ->
			carrier.scope.serviceRunId in runIds &&
				carrier.scope.logicalTrackingId != request.logicalTrackingId
		}
	) {
		return result(unverifiableFact())
	}
	if (selectedLineages.any { it.scope.serviceRunId !in selectedCapturedRunIds } ||
		selectedWal.any { it.scope.serviceRunId !in selectedCapturedRunIds }
	) {
		return result(unverifiableFact())
	}
	val scopeDigestByRun = runIds.associateWith { runId ->
		SourceDeletionFenceEntity.logicalServiceRunIdentity(
			CELL_SOURCE_KIND,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			request.logicalTrackingId,
			runId,
		)
	}
	val retainedFences = readDao.deletionFences(
		CELL_SOURCE_KIND,
		SessionManifestPurposeCode.SESSION_CAPTURE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeDigestByRun.values.toList(),
	)
	if (retainedFences.distinctBy { it.scopeIdentityDigest }.size != retainedFences.size ||
		retainedFences.any { fence -> fence.scopeIdentityDigest !in scopeDigestByRun.values }
	) {
		return result(unverifiableFact())
	}
	val fenceDigests = retainedFences.mapTo(mutableSetOf()) { it.scopeIdentityDigest }
	if (runIds.any { runId ->
		val hasLocalGeneration = audit.generationByScope.containsKey(
			CellCapturedRunScope(request.logicalTrackingId, runId),
		)
		val hasGlobalFence = scopeDigestByRun.getValue(runId) in fenceDigests
		hasLocalGeneration != hasGlobalFence
	}) {
		return result(unverifiableFact())
	}
	if (audit.generationByScope.keys.any { scope ->
		scope.logicalTrackingId != request.logicalTrackingId || scope.serviceRunId !in runIds ||
			scope.serviceRunId !in selectedCapturedRunIds
	}) {
		return result(unverifiableFact())
	}
	val deletedRunIds = selectedCapturedRunIds.filterTo(mutableSetOf()) { runId ->
		audit.generationByScope.containsKey(CellCapturedRunScope(request.logicalTrackingId, runId))
	}
	if (deletedRunIds == selectedCapturedRunIds) {
		return result(ExportPortableCapturedCellResult.Deleted)
	}
	// Deletion is scoped to one physical run; surviving sibling readings remain exportable.
	if (selectedLineages.any { it.scope.serviceRunId in deletedRunIds }) {
		return result(unverifiableFact())
	}
	if (selectedCapturedRunIds.any { runId ->
		runId !in interruptedRunIds && runId !in deletedRunIds &&
			cellCompleteness.none { it.serviceRunId == runId }
	}) {
		return result(unverifiableWriter())
	}
	val retainedFloor = evidence.retainedFromMs
	val retainedFactProofRunIds = mutableSetOf<String>()
	if (retainedFloor != null) {
		val lastAdmissionOrdinal = minOf(
			lane.contiguousAdmissionOrdinal,
			lane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
		)
		if (lastAdmissionOrdinal >= lane.activationOrdinal) {
			for (runId in selectedCapturedRunIdList) {
				currentCoroutineContext().ensureActive()
				val proof = capturedRadioFactRetentionProofDao().firstForCapturedRun(
					CELL_SOURCE_KIND,
					request.logicalTrackingId,
					runId,
					evidence.collectedDataEpoch,
					lane.activationOrdinal,
					lastAdmissionOrdinal,
					retainedFloor,
				) ?: continue
				val scope = proof.authenticatedCaptureScope(
					CELL_SOURCE_KIND,
					evidence.collectedDataEpoch,
					retainedFloor,
					evidence.updatedAtMs,
				)
				val matchingManifest = manifestsByRun[runId].orEmpty().any { manifest ->
					manifest.sourcePolicyRevision == proof.sourcePolicyRevision &&
						sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision]
							.orEmpty().any { source ->
								isPortableCellCaptureMembership(source) &&
									source.consentEpoch == proof.captureConsentEpoch
							}
				}
				if (scope == null || scope.logicalTrackingId != request.logicalTrackingId ||
					scope.serviceRunId != runId
				) {
					return result(unverifiableWriter())
				}
				if (scope.scopeDeletionGeneration != 0L ||
					scope.logicalRunScopeDigest != scopeDigestByRun[runId] || !matchingManifest
				) {
					return result(unverifiableWriter())
				}
				retainedFactProofRunIds += runId
			}
		}
	}
	val authenticatedCoverageByEventId = selectedLineages.groupBy { lineage ->
		lineage.revisions.last().sourceEventId
	}.mapValues { (_, lineages) ->
		lineages.minOf { lineage ->
			audit.canonicalRetentionLowerBoundById.getValue(lineage.logicalFactId)
		}
	}
	val retainedWal = selectedWal.filter { carrier ->
		retainedFloor == null ||
			(authenticatedCoverageByEventId[carrier.eventId] ?: carrier.earliestPossibleWallTimeMs) >=
			retainedFloor
	}

	val evidenceBackedSettlements = buildSet {
		selectedLineages.forEach { lineage ->
			val fact = lineage.revisions.last()
			add(PortableCellSettlementIdentity(
				fact.serviceRunId,
				fact.sourceInstanceId,
				fact.registrationGeneration,
			))
		}
		selectedWal.forEach { carrier ->
			add(PortableCellSettlementIdentity(
				carrier.scope.serviceRunId,
				carrier.sourceInstanceId,
				carrier.registrationGeneration,
			))
		}
	}
	for (row in cellCompleteness) {
		currentCoroutineContext().ensureActive()
		if (row.serviceRunId in interruptedRunIds || row.serviceRunId in deletedRunIds) {
			continue
		}
		if (row.registrationGeneration == 0L || PortableCellSettlementIdentity(
				row.serviceRunId,
				row.sourceInstanceId,
				row.registrationGeneration,
			) in evidenceBackedSettlements
		) {
			continue
		}
		val run = runs.singleOrNull { it.serviceRunId == row.serviceRunId }
			?: return result(unverifiableWriter())
		if (!authenticatePortableCellSettlement(
			row = row,
			run = run,
			manifests = manifestsByRun[row.serviceRunId].orEmpty(),
			sourcesByManifest = sourcesByManifest,
			desiredPlanByRevision = desiredPlanByRevision,
			evidenceState = evidence,
		)) {
			return result(unverifiableWriter())
		}
	}
	val targetOrdinal = listOfNotNull(
		selectedLineages.maxOfOrNull { it.revisions.last().sourceAdmissionOrdinal },
		cellCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		selectedWal.maxOfOrNull(CellCapturedWalCarrier::admissionOrdinal),
	).maxOrNull()
	val legacyTarget = listOfNotNull(
		selectedLineages.filter { it.revisions.last().payloadVersion != CELL_WAL_PAYLOAD_VERSION }
			.maxOfOrNull { it.revisions.last().sourceAdmissionOrdinal },
		selectedWal.filter { it.payloadVersion != CELL_WAL_PAYLOAD_VERSION }
			.maxOfOrNull(CellCapturedWalCarrier::admissionOrdinal),
		cellCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
	).maxOrNull()
	if (legacyTarget != null && session.finalAdmissionOrdinal?.let { legacyTarget > it } == true) {
		return result(unverifiableWriter())
	}
	if (targetOrdinal != null && (targetOrdinal < lane.activationOrdinal ||
		lane.captureAdmissionCutoffOrdinal?.let { it < targetOrdinal } == true)
	) {
		return result(unverifiableWriter())
	}
	if (selectedLineages.any { it.revisions.last().sourceAdmissionOrdinal < lane.activationOrdinal }) {
		return result(unverifiableWriter())
	}
	if (selectedLineages.any { lineage ->
		val fact = lineage.revisions.last()
		fact.serviceRunId !in interruptedRunIds && cellCompleteness.none { row ->
			row.coversPortableCellFact(fact)
		}
	}) {
		return result(unverifiableWriter())
	}
	val failures = if (targetOrdinal == null || lane.activationOrdinal > targetOrdinal) {
		emptyList()
	} else {
		readDao.terminalFailuresForServiceRuns(
			CELL_SOURCE_KIND,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			runIds,
			lane.activationOrdinal - 1L,
			targetOrdinal,
			CellCapturedPortableFormatV1.MAX_TERMINAL_FAILURES + 1,
		)
	}
	if (failures.size > CellCapturedPortableFormatV1.MAX_TERMINAL_FAILURES) {
		return result(overflow())
	}
	if (failures.isNotEmpty()) {
		return result(unverifiableWriter())
	}
	if (hierarchyMaterializing ||
		(targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal)
	) {
		return result(ExportPortableCapturedCellResult.Materializing)
	}
	val factEventIds = selectedLineages.mapTo(mutableSetOf()) { it.revisions.last().sourceEventId }
	if (retainedWal.any { it.scope.serviceRunId !in interruptedRunIds &&
		it.scope.serviceRunId !in deletedRunIds && it.eventId !in factEventIds }) {
		return result(unverifiableWriter())
	}

	val timeRetainedLineages = selectedLineages.filter { lineage ->
		retainedFloor == null || audit.canonicalRetentionLowerBoundById.getValue(lineage.logicalFactId) >= retainedFloor
	}
	val timeRetainedIds = timeRetainedLineages.mapTo(mutableSetOf(), CellCapturedLineage::logicalFactId)
	// Never export a coverage-only correction whose authenticated aggregate owner was lost at the
	// retention boundary. Keeping the dependent without its owner would make a later importer invent
	// aggregate authority that this database no longer retains.
	val retainedLineages = timeRetainedLineages.filter { lineage ->
		lineage.aggregateOwnerLogicalFactId == null || lineage.aggregateOwnerLogicalFactId in timeRetainedIds
	}
	val retainedByRun = retainedLineages.groupBy { it.scope.serviceRunId }
	val runsOut = runs.mapNotNull { run ->
		if (run.serviceRunId in deletedRunIds) {
			return@mapNotNull null
		}
		val segment = requireNotNull(segmentsById[run.sessionSegmentId])
		val runManifests = manifestsByRun.getValue(run.serviceRunId)
		val bindings = captureByRun.getValue(run.serviceRunId)
		val captured = bindings.isNotEmpty()
		val captureCoverage = when {
			!captured -> PortableCellCaptureCoverage.NOT_CAPTURED
			bindings.size == runManifests.size -> PortableCellCaptureCoverage.WHOLE_RUN
			else -> PortableCellCaptureCoverage.PARTIAL_RUN
		}
		val lineages = retainedByRun[run.serviceRunId].orEmpty()
		val retentionLoss =
			lineages.size != selectedLineages.count { it.scope.serviceRunId == run.serviceRunId } ||
			selectedWal.any { carrier ->
				carrier.scope.serviceRunId == run.serviceRunId && carrier !in retainedWal
			} || run.serviceRunId in retainedFactProofRunIds
		val observations = lineages.map { lineage ->
			val fact = lineage.revisions.last()
			val aggregate = audit.aggregateById[fact.logicalFactId]
				?: throw IllegalArgumentException("Authenticated Cell aggregate missing")
			fact.toPortableObservation(aggregate,
				audit.canonicalRetentionLowerBoundById.getValue(lineage.logicalFactId))
		}.sortedWith(PORTABLE_CELL_OBSERVATION_ORDER)
		val availability = when {
			!captured -> PortableCellRunAvailability.NOT_CAPTURED
			observations.isNotEmpty() -> PortableCellRunAvailability.RETAINED
			else -> PortableCellRunAvailability.NO_RETAINED_OBSERVATION
		}
		val unfinished = run.serviceRunId in interruptedRunIds
		if (unfinished && observations.isEmpty()) {
			return@mapNotNull null
		}
		val acquisition = if (unfinished) {
			PortableCellAcquisitionCompleteness.PARTIAL
		} else
			{
			cellCompleteness.filter { it.serviceRunId == run.serviceRunId }.toPortableAcquisitionCompleteness(captured)
		}
		val payload = PortableCellRunPayload(
			identity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				run.serviceRunId,
			),
			deletionScopeDigest = PortableCellDeletionScopeDigest.derive(
				request.logicalTrackingId,
				run.serviceRunId,
			),
			startTimeMs = if (unfinished) {
				observations.minOf { it.coverageStartTimeMs }
			} else {
				segment.startTimeMs
			},
			endTimeMs = if (unfinished) {
				observations.maxOf { it.latestPossibleTimeMs }
			} else {
				segment.endTimeMs
			},
			captureCoverage = if (unfinished) {
				PortableCellCaptureCoverage.PARTIAL_RUN
			} else {
				captureCoverage
			},
			availability = availability,
			acquisitionCompleteness = acquisition,
			retentionLoss = retentionLoss,
			subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
			observations = observations,
		)
		payload.toValue(CellCapturedPortableIntegrity.runChecksum(payload))
	}.sortedWith(PORTABLE_CELL_RUN_ORDER)
	if (runsOut.isEmpty()) {
		return result(ExportPortableCapturedCellResult.Unavailable(PortableCellUnavailableReason.ENTRY_NOT_FOUND))
	}
	val entryPayload = PortableCellEntryPayload(
		identity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			request.logicalTrackingId,
		),
		sessionMode = sessionMode,
		startTimeMs = runsOut.minOf { it.startTimeMs },
		endTimeMs = runsOut.maxOf { it.endTimeMs },
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		runs = runsOut,
	)
	return ReadLocalPortableCapturedCellResult.Ready(
		entryPayload.toValue(CellCapturedPortableIntegrity.entryChecksum(entryPayload)),
	)
}

private fun CellCapturedFactRevisionEntity.toPortableObservation(
	aggregate: CellHistoricalIdentityFreeAggregate,
	coverageStart: Long,
): PortableCapturedCellObservationV1 {
	val latest = Math.addExact(observedWallTimeMs, wallTimeUncertaintyMs)
	val payload = PortableCellObservationPayload(
		identity = PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, logicalFactId),
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = supersedesSemanticRevision,
		aggregateOwnerIdentity = aggregateOwnerLogicalFactId?.let {
			PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, it)
		},
		aggregateOwnerSemanticRevision = aggregateOwnerSemanticRevision,
		coverageStartTimeMs = coverageStart,
		observedTimeMs = observedWallTimeMs,
		latestPossibleTimeMs = latest,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		storedZoneId = storedZoneId,
		childCompleteness = when (childCompleteness) {
			CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE -> PortableCellChildCompleteness.COMPLETE
			CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL -> PortableCellChildCompleteness.PARTIAL
			else -> throw IllegalArgumentException("Unsupported Cell child completeness")
		},
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		submittedChildCount = submittedChildCount,
		acceptedChildCount = acceptedChildCount,
		staleChildCount = staleChildCount,
		futureTimeChildCount = futureTimeChildCount,
		missingTimeChildCount = missingTimeChildCount,
		clockUnverifiableChildCount = clockUnverifiableChildCount,
		authorityMismatchChildCount = authorityMismatchChildCount,
		unsupportedTechnologyChildCount = unsupportedTechnologyChildCount,
		observationCount = aggregate.observationCount,
		registeredObservationCount = aggregate.registeredObservationCount,
		gsmCount = aggregate.gsmCount,
		cdmaCount = aggregate.cdmaCount,
		wcdmaCount = aggregate.wcdmaCount,
		tdscdmaCount = aggregate.tdscdmaCount,
		lteCount = aggregate.lteCount,
		nrCount = aggregate.nrCount,
		qualityUnknownCount = aggregate.qualityUnknownCount,
		qualityNoneOrUnknownCount = aggregate.qualityNoneOrUnknownCount,
		qualityPoorCount = aggregate.qualityPoorCount,
		qualityModerateCount = aggregate.qualityModerateCount,
		qualityGoodCount = aggregate.qualityGoodCount,
		qualityGreatCount = aggregate.qualityGreatCount,
		weakObservationCount = aggregate.weakObservationCount,
		knownQualityObservationCount = aggregate.knownQualityObservationCount,
		allKnownQualityIsWeak = aggregate.allKnownQualityIsWeak,
		qualityFlags = qualityFlags,
		qualityConfidence = qualityConfidence?.toDouble(),
	)
	return payload.toValue(CellCapturedPortableIntegrity.observationChecksum(payload))
}

/** Canonical binary hashing kept next to the only Cell v1 format. */
object CellCapturedPortableIntegrity {
	internal fun digest(namespace: String, parts: List<Any?>): String = canonicalDigest(namespace) {
		writeInt(parts.size)
		parts.forEach { part -> writeString(part?.toString()) }
	}

	/**
	 * Hashes the original Cell observation payload, identities, revision, clocks and
	 * child/technology/quality fields.
	 */
	fun observationChecksum(value: PortableCapturedCellObservationV1) =
		observationChecksum(value.toPayload())

	internal fun observationChecksum(value: PortableCellObservationPayload) = PortableCellDigest(
		canonicalDigest("tracker-portable-cell-observation-v1") {
			writeString(value.identity.value)
			writeLong(value.semanticRevision)
			writeNullableLong(value.supersedesSemanticRevision)
			writeString(value.aggregateOwnerIdentity?.value)
			writeNullableLong(value.aggregateOwnerSemanticRevision)
			writeLong(value.coverageStartTimeMs)
			writeLong(value.observedTimeMs)
			writeLong(value.latestPossibleTimeMs)
			writeLong(value.wallTimeUncertaintyMs)
			writeString(value.storedZoneId)
			writeString(value.childCompleteness.name)
			writeString(value.subscriptionGrouping.name)
			listOf(
				value.submittedChildCount, value.acceptedChildCount, value.staleChildCount,
				value.futureTimeChildCount, value.missingTimeChildCount,
				value.clockUnverifiableChildCount, value.authorityMismatchChildCount,
				value.unsupportedTechnologyChildCount, value.observationCount,
				value.registeredObservationCount, value.gsmCount, value.cdmaCount,
				value.wcdmaCount, value.tdscdmaCount, value.lteCount, value.nrCount,
				value.qualityUnknownCount, value.qualityNoneOrUnknownCount,
				value.qualityPoorCount, value.qualityModerateCount, value.qualityGoodCount,
				value.qualityGreatCount, value.weakObservationCount,
				value.knownQualityObservationCount,
			).forEach { count -> writeInt(count) }
			writeBoolean(value.allKnownQualityIsWeak)
			writeLong(value.qualityFlags)
			writeNullableDouble(value.qualityConfidence)
		},
	)

	/**
	 * Hashes original Cell run bounds, deletion scope, coverage and ordered child
	 * identities/checksums.
	 */
	fun runChecksum(value: PortableCapturedCellRunV1) = runChecksum(value.toPayload())

	internal fun runChecksum(value: PortableCellRunPayload) = PortableCellDigest(
		canonicalDigest("tracker-portable-cell-run-v1") {
			writeString(value.identity.value)
			writeString(value.deletionScopeDigest.value)
			writeLong(value.startTimeMs)
			writeLong(value.endTimeMs)
			writeString(value.captureCoverage.name)
			writeString(value.availability.name)
			writeString(value.acquisitionCompleteness.name)
			writeBoolean(value.retentionLoss)
			writeString(value.subscriptionGrouping.name)
			writeInt(value.observations.size)
			value.observations.forEach { observation ->
				writeString(observation.identity.value)
				writeString(observation.contentChecksum.value)
			}
		},
	)

	/**
	 * Hashes logical Cell identity, session mode, bounds, subscription grouping and ordered run
	 * digests.
	 */
	fun entryChecksum(value: PortableCapturedCellEntryV1) = entryChecksum(value.toPayload())

	internal fun entryChecksum(value: PortableCellEntryPayload) = PortableCellDigest(
		canonicalDigest("tracker-portable-cell-entry-v1") {
			writeString(CellCapturedPortableFormatV1.FORMAT)
			writeInt(CellCapturedPortableFormatV1.SCHEMA_VERSION)
			writeString(value.identity.value)
			writeString(value.sessionMode.name)
			writeLong(value.startTimeMs)
			writeLong(value.endTimeMs)
			writeString(value.subscriptionGrouping.name)
			writeInt(value.runs.size)
			value.runs.forEach { run ->
				writeString(run.identity.value)
				writeString(run.contentChecksum.value)
			}
		},
	)
}

@Suppress("LongParameterList")
internal data class PortableCellObservationPayload(
	val identity: PortableCellOpaqueIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
	val aggregateOwnerIdentity: PortableCellOpaqueIdentity?,
	val aggregateOwnerSemanticRevision: Long?,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val storedZoneId: String,
	val childCompleteness: PortableCellChildCompleteness,
	val subscriptionGrouping: PortableCellSubscriptionGrouping,
	val submittedChildCount: Int,
	val acceptedChildCount: Int,
	val staleChildCount: Int,
	val futureTimeChildCount: Int,
	val missingTimeChildCount: Int,
	val clockUnverifiableChildCount: Int,
	val authorityMismatchChildCount: Int,
	val unsupportedTechnologyChildCount: Int,
	val observationCount: Int,
	val registeredObservationCount: Int,
	val gsmCount: Int,
	val cdmaCount: Int,
	val wcdmaCount: Int,
	val tdscdmaCount: Int,
	val lteCount: Int,
	val nrCount: Int,
	val qualityUnknownCount: Int,
	val qualityNoneOrUnknownCount: Int,
	val qualityPoorCount: Int,
	val qualityModerateCount: Int,
	val qualityGoodCount: Int,
	val qualityGreatCount: Int,
	val weakObservationCount: Int,
	val knownQualityObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
	val qualityFlags: Long,
	val qualityConfidence: Double?,
)

internal data class PortableCellRunPayload(
	val identity: PortableCellOpaqueIdentity,
	val deletionScopeDigest: PortableCellDeletionScopeDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val captureCoverage: PortableCellCaptureCoverage,
	val availability: PortableCellRunAvailability,
	val acquisitionCompleteness: PortableCellAcquisitionCompleteness,
	val retentionLoss: Boolean,
	val subscriptionGrouping: PortableCellSubscriptionGrouping,
	val observations: List<PortableCapturedCellObservationV1>,
)

internal data class PortableCellEntryPayload(
	val identity: PortableCellOpaqueIdentity,
	val sessionMode: PortableCellSessionMode,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val subscriptionGrouping: PortableCellSubscriptionGrouping,
	val runs: List<PortableCapturedCellRunV1>,
)

private fun PortableCellObservationPayload.toValue(
	checksum: PortableCellDigest,
) = PortableCapturedCellObservationV1(
	identity, semanticRevision, supersedesSemanticRevision, aggregateOwnerIdentity,
	aggregateOwnerSemanticRevision, checksum, coverageStartTimeMs, observedTimeMs,
	latestPossibleTimeMs, wallTimeUncertaintyMs, storedZoneId, childCompleteness,
	subscriptionGrouping, submittedChildCount, acceptedChildCount, staleChildCount,
	futureTimeChildCount, missingTimeChildCount, clockUnverifiableChildCount,
	authorityMismatchChildCount, unsupportedTechnologyChildCount, observationCount,
	registeredObservationCount, gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount,
	nrCount, qualityUnknownCount, qualityNoneOrUnknownCount, qualityPoorCount,
	qualityModerateCount, qualityGoodCount, qualityGreatCount, weakObservationCount,
	knownQualityObservationCount, allKnownQualityIsWeak, qualityFlags, qualityConfidence,
)

/** Compare correction content without constructing an observation with an invalid checksum. */
internal fun PortableCapturedCellObservationV1.hasMaterialDifferenceFrom(
	previous: PortableCapturedCellObservationV1,
): Boolean = toPayload().copy(
	semanticRevision = previous.semanticRevision,
	supersedesSemanticRevision = previous.supersedesSemanticRevision,
) != previous.toPayload()

private fun PortableCapturedCellObservationV1.toPayload() = PortableCellObservationPayload(
	identity, semanticRevision, supersedesSemanticRevision, aggregateOwnerIdentity,
	aggregateOwnerSemanticRevision, coverageStartTimeMs, observedTimeMs, latestPossibleTimeMs,
	wallTimeUncertaintyMs, storedZoneId, childCompleteness, subscriptionGrouping,
	submittedChildCount, acceptedChildCount, staleChildCount, futureTimeChildCount,
	missingTimeChildCount, clockUnverifiableChildCount, authorityMismatchChildCount,
	unsupportedTechnologyChildCount, observationCount, registeredObservationCount, gsmCount,
	cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount, qualityUnknownCount,
	qualityNoneOrUnknownCount, qualityPoorCount, qualityModerateCount, qualityGoodCount,
	qualityGreatCount, weakObservationCount, knownQualityObservationCount,
	allKnownQualityIsWeak, qualityFlags, qualityConfidence,
)

private fun PortableCellRunPayload.toValue(checksum: PortableCellDigest) = PortableCapturedCellRunV1(
	identity, deletionScopeDigest, checksum, startTimeMs, endTimeMs, captureCoverage,
	availability, acquisitionCompleteness, retentionLoss, subscriptionGrouping, observations,
)

private fun PortableCapturedCellRunV1.toPayload() = PortableCellRunPayload(
	identity, deletionScopeDigest, startTimeMs, endTimeMs, captureCoverage, availability,
	acquisitionCompleteness, retentionLoss, subscriptionGrouping, observations,
)

private fun PortableCellEntryPayload.toValue(checksum: PortableCellDigest) =
	PortableCapturedCellEntryV1(
		identity = identity,
		contentChecksum = checksum,
		sessionMode = sessionMode,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		subscriptionGrouping = subscriptionGrouping,
		runs = runs,
	)

private fun PortableCapturedCellEntryV1.toPayload() = PortableCellEntryPayload(
	identity, sessionMode, startTimeMs, endTimeMs, subscriptionGrouping, runs,
)

private fun SourceServiceRunEntity.hasExactPortableMembership(
	logicalTrackingId: String,
	segmentsById: Map<Long, SessionSegment>,
): Boolean {
	val segmentId = sessionSegmentId ?: return false
	val segment = segmentsById[segmentId] ?: return false
	return this.logicalTrackingId == logicalTrackingId &&
		segment.logicalTrackingId == logicalTrackingId && segment.serviceRunId == serviceRunId &&
		segment.startTimeMs >= 0L && segment.endTimeMs >= segment.startTimeMs &&
		presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
}

private fun LogicalTrackingSessionEntity.hasPortableCellShape(
	runs: List<SourceServiceRunEntity>,
	interrupted: Boolean,
): Boolean {
	val terminal = state in TERMINAL_SESSION_STATES && !interrupted
	if ((!interrupted && (completedAtMs != null) != terminal) || !hasPortableCellLifecycleIdentity()) {
		return false
	}
	if (startedAtMs < 0L || startedElapsedNanos < 0L || (cutoffAtMs == null) != (cutoffElapsedNanos == null)) {
		return false
	}
	if (terminal && !hasClosedPortableCellCutoff()) {
		return false
	}
	return runs.none { it.hasInvalidPortableCellRunShape(logicalTrackingId, lifecycleLeaseGeneration, interrupted) }
}

private fun LogicalTrackingSessionEntity.hasPortableCellLifecycleIdentity(): Boolean =
	logicalTrackingId.isNotBlank() && lifecycleRevision > 0L && desiredPlanRevision > 0L &&
		rolloutRevision > 0L && startOrigin.isNotBlank() && clockDomainId.isNotBlank() &&
		!lifecycleBootId.isNullOrBlank() && lifecycleLeaseGeneration > 0L

private fun LogicalTrackingSessionEntity.hasClosedPortableCellCutoff(): Boolean =
	cutoffAtMs != null && cutoffElapsedNanos != null && currentServiceRunId == null &&
		finalAdmissionOrdinal != null && finalAdmissionOrdinal >= 0L &&
		requireNotNull(completedAtMs) >= requireNotNull(cutoffAtMs)

private fun isPortableCellCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == CELL_SOURCE_KIND &&
		source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible

private fun SessionManifestSourceEntity.isExactPortableCellWriter(): Boolean =
	isPortableCellCaptureMembership(this) &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
		writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
		writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
		writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION

@Suppress("LongParameterList", "ComplexCondition")
private fun SessionManifestVersionEntity.hasExactPortableCellIntent(
	binding: SessionManifestSourceEntity,
	policy: com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity?,
	consent: com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity?,
	planHeader: com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity?,
	desiredPlan: com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity?,
): Boolean {
	if (!binding.isExactPortableCellWriter() || policy == null || consent == null ||
		planHeader == null || desiredPlan == null || !desiredPlan.hasValidPortableCellPayload() ||
		!hasValidPortableZone(zoneId)
	) {
		return false
	}
	val plan = decodeCanonicalCellPlan(desiredPlan.payload) ?: return false
	return hasExactPortableCellPolicyAndConsent(binding, policy, consent) &&
		matchesPortableCellAcquisitionPlan(planHeader, desiredPlan, plan)
}

private fun SourceDesiredPlanEntity.hasValidPortableCellPayload(): Boolean =
	payload.size <= MAX_CELL_PLAN_PAYLOAD_BYTES && payloadVersion == 1 &&
		payloadChecksum == sha256Portable(payload)

private fun SessionManifestVersionEntity.matchesPortableCellAcquisitionPlan(
	planHeader: com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity,
	desiredPlan: SourceDesiredPlanEntity,
	plan: CellHistoricalPlan,
): Boolean = planHeader.revision == acquisitionPlanRevision &&
	planHeader.sourcePolicyRevision == sourcePolicyRevision && planHeader.planId.isNotBlank() &&
	planHeader.createdAtMs >= 0L && desiredPlan.revision == acquisitionPlanRevision &&
	desiredPlan.sourceKind == CELL_SOURCE_KIND && plan.revision == acquisitionPlanRevision &&
	plan.hasSupportedHistoricalShape()

// Keep the closed saved lane shape together; live executable ownership is checked by the caller.
@Suppress("CyclomaticComplexMethod")
private fun SourceProductProjectionLaneEntity.isExactPortableCellLane(): Boolean =
	sourceKind == CELL_SOURCE_KIND &&
		bindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION &&
		projectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
		projectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
		captureModeMask > 0L && captureModeMask and ALL_CAPTURE_MASK.inv() == 0L &&
		productStage in setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		) && activatedRolloutRevision > 0L && activationOrdinal > 0L &&
		contiguousAdmissionOrdinal >= activationOrdinal - 1L && when (status) {
		SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
			captureAdmissionCutoffOrdinal == null && terminalDisposition == null &&
				terminalAtMs == null && retentionRequired
		SourceProductProjectionLaneEntity.STATUS_RETIRED ->
			captureAdmissionCutoffOrdinal != null &&
				contiguousAdmissionOrdinal >= requireNotNull(captureAdmissionCutoffOrdinal) &&
				terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
				terminalAtMs != null && !retentionRequired
		else -> false
	}

private fun SourceSessionCompletenessEntity.coversPortableCellFact(
	fact: CellCapturedFactRevisionEntity,
): Boolean {
	if (fact.serviceRunId != serviceRunId || fact.sourceInstanceId != sourceInstanceId ||
		fact.registrationGeneration != registrationGeneration
	) {
		return false
	}
	return fact.payloadVersion == CELL_WAL_PAYLOAD_VERSION ||
		lastAdmissionOrdinal?.let { it >= fact.sourceAdmissionOrdinal } == true
}

private fun List<SourceSessionCompletenessEntity>.hasValidPortableCellShape(
	logicalTrackingId: String,
	runIds: List<String>,
): Boolean = groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, unorderedRows) ->
	val rows = unorderedRows.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
	runId in runIds && rows.distinctBy(SourceSessionCompletenessEntity::registrationGeneration).size == rows.size &&
		rows.all { row -> row.hasExactPortableCellCompleteness(logicalTrackingId, rows.size) }
}

// One saved completeness row keeps its identity, cursor/gap null-pairs and stop shape visible together.
@Suppress("CyclomaticComplexMethod")
private fun SourceSessionCompletenessEntity.hasExactPortableCellCompleteness(
	logicalTrackingId: String,
	runRowCount: Int,
): Boolean = this.logicalTrackingId == logicalTrackingId && sourceInstanceId.isNotBlank() &&
	registrationGeneration >= 0L && hasValidPortableCellStopShape() &&
		(lastAdmissionOrdinal == null) == (lastSourceSequence == null) &&
		lastAdmissionOrdinal?.let { it > 0L } != false &&
		lastSourceSequence?.let { it >= 0L } != false &&
		(unresolvedSequenceStart == null) == (unresolvedSequenceEnd == null) &&
		unresolvedSequenceStart?.let { start ->
			start > 0L && requireNotNull(unresolvedSequenceEnd) >= start
		} != false &&
		providerCoverage == CELL_PROVIDER_COVERAGE && updatedAtMs >= 0L &&
		if (registrationGeneration == 0L) {
			runRowCount == 1
		} else {
			true
		}

// Keep the complete saved generation/status/drain truth table together; no live state is read.
@Suppress("CyclomaticComplexMethod")
private fun SourceSessionCompletenessEntity.hasValidPortableCellStopShape(): Boolean {
	val hasGap = unresolvedSequenceStart != null
	if (hasGap && (appDrainComplete || stopStatus != "TIMED_OUT")) {
		return false
	}
	return when {
		registrationGeneration == 0L -> {
			lastAdmissionOrdinal == null && lastSourceSequence == null && !hasGap &&
				when {
					sourceInstanceId == "not-owned-cell" && stopStatus == "COMPLETE" -> appDrainComplete
					sourceInstanceId == "unavailable-cell" && stopStatus in setOf(
						"COMPLETE",
						"PROVIDER_FAILED",
					) -> appDrainComplete
					sourceInstanceId == "unresolved-cell" && stopStatus in setOf(
						"TIMED_OUT",
						"PROVIDER_FAILED",
					) -> !appDrainComplete
					else -> false
				}
		}
		registrationGeneration > 0L -> when (stopStatus) {
			"COMPLETE" -> appDrainComplete && !hasGap
			"TIMED_OUT" -> !appDrainComplete
			"PROVIDER_FAILED" -> appDrainComplete && !hasGap
			else -> false
		}
		else -> false
	}
}

private suspend fun AppDatabase.authenticatePortableCellSettlement(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
	sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
	desiredPlanByRevision: Map<Long, SourceDesiredPlanEntity>,
	evidenceState: SourceEvidenceState,
): Boolean {
	val (registration, sharedRetirement) = loadPortableCellSettlementRegistration(row, run) ?: return false
	val recordedBootId = manifests.singleOrNull {
		it.manifestRevision == run.preparedManifestRevision
	}?.effectiveBootId ?: return false
	if (!registration.hasExactPortableCellSettlementShape(row, recordedBootId, evidenceState, sharedRetirement)) {
		return false
	}
	val settledElapsedNanos = registration.retiredElapsedRealtimeNanos
		?: requireNotNull(sharedRetirement).cutoffElapsedRealtimeNanos
	return sourceBrokerDao().hasPortableCellRegistrationWindow(row, registration, settledElapsedNanos) &&
		authenticatePortableCellSettlementRevisions(row, run, manifests, sourcesByManifest,
			desiredPlanByRevision, registration, sharedRetirement)
}

private suspend fun AppDatabase.loadPortableCellSettlementRegistration(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
): Pair<ProviderRegistrationGenerationEntity, SourceRunRetirementEntity?>? {
	val registration = sourceBrokerDao().registration(CELL_SOURCE_KIND, row.registrationGeneration)
		?: return null
	// Ending a session drains its prefix; another purpose may keep this physical join alive.
	val sharedRetirement = if (registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE) {
		sourceSessionDao().rawRunRetirement(
			row.logicalTrackingId, row.serviceRunId, CELL_SOURCE_KIND,
			row.sourceInstanceId, row.registrationGeneration,
		).singleOrNull()?.validatedOrNull()?.takeIf { it.matchesPortableCellSharedSettlement(row, run) }
			?: return null
	} else {
		null
	}
	return registration to sharedRetirement
}

// These are the original immutable settlement inputs; no mutable context or second owner is needed.
@Suppress("LongParameterList")
private suspend fun AppDatabase.authenticatePortableCellSettlementRevisions(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
	sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
	desiredPlanByRevision: Map<Long, SourceDesiredPlanEntity>,
	registration: ProviderRegistrationGenerationEntity,
	sharedRetirement: SourceRunRetirementEntity?,
): Boolean {
	val (authorizationRevisions, previousMaximumRevision, nextMinimumRevision) =
		loadPortableCellSettlementRevisionBounds(row, registration) ?: return false
	var selectedCaptureRevisionFound = false
	var lastSelectedCaptureRevision = 0L
	var lastSelectedRetirementNanos = -1L
	var sharedClosingRevision = 0L
	for (revision in authorizationRevisions) {
		currentCoroutineContext().ensureActive()
		val (authorization, selectedDemands) = loadPortableCellSettlementCaptureDemands(
			row, registration, revision, previousMaximumRevision, nextMinimumRevision,
		) ?: return false
		val demand = selectedDemands.singleOrNull()
		if (demand == null) {
			if (authorization.matchesPortableCellSharedClosingRevision(sharedRetirement)) {
				sharedClosingRevision = maxOf(sharedClosingRevision, revision)
			}
			continue
		}
		if (!authenticatePortableCellSettlementDemand(demand, row, run, manifests,
			sourcesByManifest, desiredPlanByRevision, registration, authorization, sharedRetirement)
		) {
			return false
		}
		selectedCaptureRevisionFound = true
		lastSelectedCaptureRevision = maxOf(lastSelectedCaptureRevision, revision)
		lastSelectedRetirementNanos = maxOf(lastSelectedRetirementNanos,
			requireNotNull(demand.retireElapsedRealtimeNanos))
	}
	return sharedRetirement.hasCompletePortableCellCaptureSettlement(
		selectedCaptureRevisionFound, sharedClosingRevision, lastSelectedCaptureRevision, lastSelectedRetirementNanos,
	)
}

private fun SourceRunRetirementEntity?.hasCompletePortableCellCaptureSettlement(
	selectedCaptureRevisionFound: Boolean,
	sharedClosingRevision: Long,
	lastSelectedCaptureRevision: Long,
	lastSelectedRetirementNanos: Long,
): Boolean = selectedCaptureRevisionFound && (this == null ||
	(sharedClosingRevision > lastSelectedCaptureRevision &&
		lastSelectedRetirementNanos == this.cutoffElapsedRealtimeNanos))

private suspend fun AppDatabase.loadPortableCellSettlementRevisionBounds(
	row: SourceSessionCompletenessEntity,
	registration: ProviderRegistrationGenerationEntity,
): Triple<List<Long>, Long, Long>? {
	val brokerDao = sourceBrokerDao()
	val revisions = trackingHistoryReadDao().registrationAuthorizationRevisions(
		CELL_SOURCE_KIND, row.registrationGeneration, MAX_CELL_SETTLEMENT_AUTHORIZATION_REVISIONS + 1,
	)
	if (revisions.size > MAX_CELL_SETTLEMENT_AUTHORIZATION_REVISIONS) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	if (revisions.isEmpty()) {
		return null
	}
	val previousMaximumRevision = brokerDao.maximumAuthorizationRevisionBeforeRegistration(
		CELL_SOURCE_KIND, row.registrationGeneration,
	)
	val nextMinimumRevision = brokerDao.minimumAuthorizationRevisionAfterRegistration(
		CELL_SOURCE_KIND, row.registrationGeneration,
	)
	val maximumCaptureRevision = brokerDao.maximumCaptureAuthorizationRevision(
		CELL_SOURCE_KIND, row.registrationGeneration,
	)
	return Triple(revisions, previousMaximumRevision, nextMinimumRevision).takeIf {
		registration.captureCallbackBarrierAuthorizationRevision <= maximumCaptureRevision
	}
}

private suspend fun AppDatabase.loadPortableCellSettlementCaptureDemands(
	row: SourceSessionCompletenessEntity,
	registration: ProviderRegistrationGenerationEntity,
	revision: Long,
	previousMaximumRevision: Long,
	nextMinimumRevision: Long,
): Pair<SourceAuthorizationSnapshot, List<SourceDemandEntity>>? {
	val brokerDao = sourceBrokerDao()
	if (revision <= previousMaximumRevision ||
		(nextMinimumRevision > 0L && revision >= nextMinimumRevision) ||
		brokerDao.authorizationRevisionRegistrationCount(CELL_SOURCE_KIND, revision) != 1
	) {
		return null
	}
	val authorization = loadPortableCellSettlementAuthorization(row, revision) ?: return null
	val demandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
	if (demandIds.distinct().size != authorization.authorizedMembers.size) {
		return null
	}
	val demands = brokerDao.demandsByIds(demandIds)
	return if (demands.size == demandIds.size &&
		hasCanonicalPortableCellSettlementAuthorization(row, registration, revision, authorization, demands)
	) {
		val selectedDemands = demands.filter { it.isPortableCellSettlementCaptureDemand(row) }
		(authorization to selectedDemands).takeIf { selectedDemands.size <= 1 }
	} else {
		null
	}
}

private fun SourceDemandEntity.isPortableCellSettlementCaptureDemand(
	row: SourceSessionCompletenessEntity,
): Boolean = logicalTrackingId == row.logicalTrackingId && serviceRunId == row.serviceRunId &&
	purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible

private fun SourceAuthorizationSnapshot.matchesPortableCellSharedClosingRevision(
	sharedRetirement: SourceRunRetirementEntity?,
): Boolean = sharedRetirement != null && authorizedMembers.isNotEmpty() &&
	effectiveElapsedRealtimeNanos == sharedRetirement.cutoffElapsedRealtimeNanos &&
	members.first().effectiveWallTimeMs == sharedRetirement.cutoffWallTimeMs

// Keep the original immutable binding inputs explicit rather than introducing a transfer context.
@Suppress("LongParameterList")
private suspend fun AppDatabase.authenticatePortableCellSettlementDemand(
	demand: SourceDemandEntity,
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
	sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
	desiredPlanByRevision: Map<Long, SourceDesiredPlanEntity>,
	registration: ProviderRegistrationGenerationEntity,
	authorization: SourceAuthorizationSnapshot,
	sharedRetirement: SourceRunRetirementEntity?,
): Boolean {
	val (manifest, binding, desiredPlan) = demand.portableCellSettlementBinding(
		manifests, sourcesByManifest, desiredPlanByRevision,
	) ?: return false
	val plan = decodeCanonicalCellPlan(desiredPlan.payload) ?: return false
	val policy = sourcePolicyDao().policyAtRevision(manifest.sourcePolicyRevision, CELL_SOURCE_KIND) ?: return false
	return policy.qosCode == binding.qosCode && policy.captureConsentEpoch == binding.consentEpoch &&
		demand.hasExactPortableCellSettlementShape(row, run, manifest, binding, policy) &&
		registration.physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint() &&
		authorization.isWithinPortableCellSettlementDemand(demand, sharedRetirement)
}

private fun SourceDemandEntity.portableCellSettlementBinding(
	manifests: List<SessionManifestVersionEntity>,
	sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
	desiredPlanByRevision: Map<Long, SourceDesiredPlanEntity>,
): Triple<SessionManifestVersionEntity, SessionManifestSourceEntity, SourceDesiredPlanEntity>? {
	val manifest = manifests.singleOrNull { it.manifestRevision == manifestRevision } ?: return null
	val binding = sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision]
		.orEmpty().singleOrNull(::isPortableCellCaptureMembership) ?: return null
	val desiredPlan = desiredPlanByRevision[manifest.acquisitionPlanRevision] ?: return null
	return Triple(manifest, binding, desiredPlan)
}

private fun SourceAuthorizationSnapshot.isWithinPortableCellSettlementDemand(
	demand: SourceDemandEntity,
	sharedRetirement: SourceRunRetirementEntity?,
): Boolean = effectiveElapsedRealtimeNanos >= demand.requestedElapsedRealtimeNanos &&
	effectiveElapsedRealtimeNanos <= requireNotNull(demand.retireElapsedRealtimeNanos) &&
		(sharedRetirement == null || requireNotNull(demand.retireElapsedRealtimeNanos) <=
			sharedRetirement.cutoffElapsedRealtimeNanos) &&
		(sharedRetirement == null || requireNotNull(demand.retiredAtMs) <= sharedRetirement.cutoffWallTimeMs)

private suspend fun SourceBrokerDao.hasPortableCellRegistrationWindow(
	row: SourceSessionCompletenessEntity,
	registration: ProviderRegistrationGenerationEntity,
	settledElapsedNanos: Long,
): Boolean = !(hasConflictingAcceptedRegistrationBefore(
			sourceKind = CELL_SOURCE_KIND,
			registrationGeneration = row.registrationGeneration,
			clockDomainId = registration.clockDomainId,
			collectedDataEpoch = registration.collectedDataEpoch,
			sourceInstanceId = registration.sourceInstanceId,
			reservedElapsedRealtimeNanos = registration.reservedElapsedRealtimeNanos,
		) || hasConflictingAcceptedRegistrationAfter(
			sourceKind = CELL_SOURCE_KIND,
			registrationGeneration = row.registrationGeneration,
			clockDomainId = registration.clockDomainId,
			collectedDataEpoch = registration.collectedDataEpoch,
			sourceInstanceId = registration.sourceInstanceId,
			retiredElapsedRealtimeNanos = settledElapsedNanos,
		)
)

private suspend fun AppDatabase.loadPortableCellSettlementAuthorization(
	row: SourceSessionCompletenessEntity,
	revision: Long,
): SourceAuthorizationSnapshot? {
	val rows = cellCapturedFactDao().maintenanceAuthorizationMembers(
		CELL_SOURCE_KIND,
		row.registrationGeneration,
		revision,
		PORTABLE_CELL_AUDIT_LIMITS.maximumAuthorizationMembers + 1,
	)
	if (rows.size > PORTABLE_CELL_AUDIT_LIMITS.maximumAuthorizationMembers) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	return runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull()
}

private fun hasCanonicalPortableCellSettlementAuthorization(
	row: SourceSessionCompletenessEntity,
	registration: ProviderRegistrationGenerationEntity,
	revision: Long,
	authorization: SourceAuthorizationSnapshot,
	demands: List<SourceDemandEntity>,
): Boolean {
	val recomputed = runCatching {
		SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE_KIND,
			registrationGeneration = row.registrationGeneration,
			authorizationRevision = revision,
			demands = demands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	if (recomputed?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId } ||
		authorization.effectiveBootId != registration.clockDomainId ||
		authorization.effectiveElapsedRealtimeNanos < registration.reservedElapsedRealtimeNanos ||
		(registration.retiredElapsedRealtimeNanos?.let {
			authorization.effectiveElapsedRealtimeNanos > it
		} == true)
	) {
		return false
	}
	return true
}

// Keep the fixed saved receipt/completeness/run tuple together so every identity and time bound stays visible.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceRunRetirementEntity.matchesPortableCellSharedSettlement(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
): Boolean = logicalTrackingId == row.logicalTrackingId && serviceRunId == row.serviceRunId &&
	sourceKind == CELL_SOURCE_KIND && sourceInstanceId == row.sourceInstanceId &&
	registrationGeneration == row.registrationGeneration && leaseGeneration == run.leaseGeneration &&
	state == SourceRunRetirementEntity.STATE_ACKNOWLEDGED && stopStatus == "COMPLETE" &&
	row.stopStatus == stopStatus && appDrainComplete == true && row.appDrainComplete &&
	lastAdmissionOrdinal == row.lastAdmissionOrdinal && lastSourceSequence == row.lastSourceSequence &&
	providerCoverage == row.providerCoverage && failedAdmissionCount == 0L &&
	unresolvedSequenceStart == null && unresolvedSequenceEnd == null &&
	row.unresolvedSequenceStart == null && row.unresolvedSequenceEnd == null &&
	registrationRemovalOutcome == "NOT_REGISTERED" && providerFlushOutcome == "NOT_SUPPORTED" &&
	callbackEntryBarrierSequence?.let { it >= (lastSourceSequence ?: 0L) } == true &&
	cutoffElapsedRealtimeNanos >= run.startedElapsedNanos && cutoffWallTimeMs >= run.startedAtMs &&
	cutoffWallTimeMs <= row.updatedAtMs &&
	run.completedAtMs?.let { cutoffWallTimeMs <= it } == true && updatedAtMs >= cutoffWallTimeMs

// One saved registration/settlement tuple; retained-epoch and retirement checks retain their owners.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun ProviderRegistrationGenerationEntity.hasExactPortableCellSettlementShape(
	row: SourceSessionCompletenessEntity,
	recordedBootId: String,
	evidenceState: SourceEvidenceState,
	sharedRetirement: SourceRunRetirementEntity?,
): Boolean {
	val acceptedWallTimeMs = acceptedAtMs ?: return false
	val acceptedElapsedNanos = acceptedElapsedRealtimeNanos ?: return false
	val exactAcceptedIdentity = sourceKind == CELL_SOURCE_KIND &&
        registrationGeneration == row.registrationGeneration &&
		sourceInstanceId == row.sourceInstanceId && ownerScope == "source-broker:$CELL_SOURCE_KIND" &&
		clockDomainId == recordedBootId && evidenceState.acceptsRetainedEpoch(collectedDataEpoch) &&
		providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
		!providerProcessIncarnationId.isNullOrBlank() && reservedAtMs <= acceptedWallTimeMs &&
		reservedElapsedRealtimeNanos <= acceptedElapsedNanos
	if (!exactAcceptedIdentity) {
		return false
	}
	return hasExactPortableCellRetirementShape(row, acceptedWallTimeMs, acceptedElapsedNanos, sharedRetirement)
}

// Keep every saved demand/manifest/retirement field and ordered missing-value exit in one contract.
@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
private fun SourceDemandEntity.hasExactPortableCellSettlementShape(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
	policy: SourcePolicyEntity,
): Boolean {
	val retiredBootId = retireBootId ?: return false
	val retiredElapsedNanos = retireElapsedRealtimeNanos ?: return false
	val retiredWallTimeMs = retiredAtMs ?: return false
	val expectedMaximumAgeMs = policy.portableCellMaximumAgeMs(qosCode) ?: return false
	return (demandId == portableCellDemandId(this) ||
		demandId == portableCellDemandId(this, includeSessionIdentity = true)) &&
		consumerId == "session:${row.logicalTrackingId}" &&
		sourceKind == CELL_SOURCE_KIND && purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		logicalTrackingId == row.logicalTrackingId && serviceRunId == row.serviceRunId &&
		manifestRevision == manifest.manifestRevision &&
		SessionManifestIntegrity.hasRetainedLeaseGeneration(run, lifecycleLeaseGeneration) &&
		sourcePolicyRevision == manifest.sourcePolicyRevision &&
		consentEpoch == binding.consentEpoch && persistenceEligible && qosCode == binding.qosCode &&
		minimumAcquisitionSpec == CELL_CAPTURE_ACQUISITION_FLOOR && adaptiveReductionAllowed &&
		maximumAgeMs == expectedMaximumAgeMs && desiredLatencyMs == Long.MAX_VALUE &&
		requestedDeliveryLatencyMs == null && requestedBootId == manifest.effectiveBootId &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedAtMs == manifest.effectiveWallTimeMs && status == SourceDemandEntity.STATUS_RETIRED &&
		retiredBootId == requestedBootId && retiredElapsedNanos >= requestedElapsedRealtimeNanos &&
		retiredWallTimeMs >= requestedAtMs
}

// One supplied run/session shape: identity, delivery markers and terminal/null fields; no history scan.
@Suppress("CyclomaticComplexMethod")
private fun SourceServiceRunEntity.hasInvalidPortableCellRunShape(
	logicalTrackingId: String,
	lifecycleLeaseGeneration: Long,
	interrupted: Boolean,
): Boolean {
	return this.logicalTrackingId != logicalTrackingId || bootId.isBlank() ||
		leaseGeneration <= 0L || leaseGeneration > lifecycleLeaseGeneration ||
		desiredPlanRevision <= 0L || rolloutRevision <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L || startOrigin.isBlank() ||
		startDeliveryToken.isNullOrBlank() || startCommandGeneration <= 0L ||
		preparedManifestRevision <= 0L || preparedIntentRevision <= 0L ||
		runtimeAcknowledgement.isBlank() || androidDeliveryState.isBlank() ||
		androidDeliveryUpdatedAtMs == null ||
		(!interrupted || completionReason != "RESTORED_INTERRUPTED_SESSION") &&
			((completedAtMs != null) != (state in TERMINAL_SESSION_STATES) ||
				(completedAtMs != null) != !completionReason.isNullOrBlank()) ||
		completedAtMs?.let { it < startedAtMs } == true
}

// The original saved policy and consent must match this manifest/binding, including their scalar effective bounds.
@Suppress("CyclomaticComplexMethod")
private fun SessionManifestVersionEntity.hasExactPortableCellPolicyAndConsent(
	binding: SessionManifestSourceEntity,
	policy: SourcePolicyEntity,
	consent: com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity,
): Boolean {
	return policy.sourceKind == CELL_SOURCE_KIND && policy.policyRevision == sourcePolicyRevision &&
		policy.enabled && policy.capturePersistenceEligible && policy.qosCode == binding.qosCode &&
		policy.captureConsentEpoch == binding.consentEpoch && policy.effectiveBootId == effectiveBootId &&
		policy.effectiveElapsedRealtimeNanos >= 0L && policy.effectiveWallTimeMs >= 0L &&
		policy.changeReason.isNotBlank() &&
		policy.effectiveElapsedRealtimeNanos <= effectiveElapsedRealtimeNanos &&
		consent.sourceKind == CELL_SOURCE_KIND &&
		consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		consent.epoch == binding.consentEpoch && consent.policyRevision <= policy.policyRevision &&
		consent.eligible && consent.persistenceEligible && consent.effectiveBootId == effectiveBootId &&
		consent.effectiveElapsedRealtimeNanos >= 0L && consent.effectiveWallTimeMs >= 0L &&
		consent.changeReason.isNotBlank() &&
		consent.effectiveElapsedRealtimeNanos <= effectiveElapsedRealtimeNanos
}

// Closed accepted/retired field and stop-status table; retained-epoch parsing stays in the caller.
@Suppress("CyclomaticComplexMethod")
private fun ProviderRegistrationGenerationEntity.hasExactPortableCellRetirementShape(
	row: SourceSessionCompletenessEntity,
	acceptedWallTimeMs: Long,
	acceptedElapsedNanos: Long,
	sharedRetirement: SourceRunRetirementEntity?,
): Boolean {
	if (sharedRetirement != null) {
		return status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE && failureCode == null &&
			retiredAtMs == null && retiredElapsedRealtimeNanos == null &&
			acceptedWallTimeMs <= sharedRetirement.cutoffWallTimeMs &&
			acceptedElapsedNanos <= sharedRetirement.cutoffElapsedRealtimeNanos
	}
	val retiredWallTimeMs = retiredAtMs ?: return false
	val retiredElapsedNanos = retiredElapsedRealtimeNanos ?: return false
	val statusMatches = when (row.stopStatus) {
		"COMPLETE" -> status == ProviderRegistrationGenerationEntity.STATUS_RETIRED
		"TIMED_OUT" -> status in setOf(
			ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		)
		"PROVIDER_FAILED" -> status == ProviderRegistrationGenerationEntity.STATUS_RETIRING
		else -> false
	}
	return statusMatches && failureCode == "ORDERLY_STOP" && acceptedWallTimeMs <= retiredWallTimeMs &&
		acceptedElapsedNanos <= retiredElapsedNanos
}

// Closed saved v0/v1 profile shape and three legacy QoS codes; unknown versions retain rejection.
// Legacy QoS tags and millisecond age values retain their exact stored-format meaning.
@Suppress("CyclomaticComplexMethod", "ReturnCount", "MagicNumber")
private fun SourcePolicyEntity.portableCellMaximumAgeMs(qosCode: Int): Long? {
	return when (balanceProfileVersion) {
        null -> {
            if (balanceLevel != null || overallBalanceLevel != null ||
                sourceBalanceOverride != null) {
                return null
            }
            when (qosCode) { 1 -> 600_000L; 2 -> 300_000L; 3 -> 60_000L; else -> return null }
        }
        1 -> {
            val baseline = balanceLevel ?: return null
            if (baseline !in 1..5 || overallBalanceLevel?.let { it in 1..5 } != true ||
                baseline != (sourceBalanceOverride ?: overallBalanceLevel)) {
                return null
            }
            600_000L
        }
        else -> return null
    }
}

private fun portableCellDemandId(demand: SourceDemandEntity, includeSessionIdentity: Boolean = false): String {
	val components = listOf(
		demand.consumerId,
		demand.sourceKind,
		demand.purpose,
		demand.sourcePolicyRevision,
		demand.consentEpoch,
		demand.manifestRevision ?: 0L,
		demand.requestedBootId,
		demand.requestedElapsedRealtimeNanos,
	)
	val identityComponents = if (includeSessionIdentity) {
		components + "session:${demand.serviceRunId}:lease:${demand.lifecycleLeaseGeneration}"
	} else {
		components
	}
	return sha256Portable(identityComponents.joinToString("\u001f").toByteArray(Charsets.UTF_8))
}

private data class PortableCellSettlementIdentity(
	val serviceRunId: String,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
)

private fun List<SourceSessionCompletenessEntity>.toPortableAcquisitionCompleteness(
	captured: Boolean,
): PortableCellAcquisitionCompleteness = when {
	!captured || isEmpty() -> PortableCellAcquisitionCompleteness.UNKNOWN
	else -> PortableCellAcquisitionCompleteness.PARTIAL
}

private fun SourceEvidenceState.hasValidPortableCellShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		deletedSourceEventHighWaterOrdinal >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && updatedAtMs >= 0L

private fun result(value: ExportPortableCapturedCellResult) =
	ReadLocalPortableCapturedCellResult.Outcome(value)
private fun unverifiableFact() = ExportPortableCapturedCellResult.Unverifiable(
	PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
)
private fun unverifiableMembership() = ExportPortableCapturedCellResult.Unverifiable(
	PortableCellUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
)
private fun unverifiableAttribution() = ExportPortableCapturedCellResult.Unverifiable(
	PortableCellUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
)
private fun unverifiableWriter() = ExportPortableCapturedCellResult.Unverifiable(
	PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
)
private fun overflow() = ExportPortableCapturedCellResult.Unverifiable(
	PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW,
)

private fun sumExact(vararg values: Int): Int = values.fold(0) { sum, value ->
	Math.addExact(sum, value)
}

private fun requireValidZone(zoneId: String) {
	try {
		ZoneId.of(zoneId)
	} catch (_: DateTimeException) {
		throw IllegalArgumentException("Invalid stored Cell zone")
	}
}

private fun hasValidPortableZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private fun canonicalDigest(namespace: String, body: DataOutputStream.() -> Unit): String {
	val bytes = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeUTF(namespace)
			output.body()
		}
		buffer.toByteArray()
	}
	return MessageDigest.getInstance("SHA-256").digest(bytes)
		.toHexString()
}

private fun sha256Portable(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.toHexString()

private fun DataOutputStream.writeString(value: String?) {
	writeBoolean(value != null)
	if (value != null) {
		writeUTF(value)
	}
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) {
		writeLong(value)
	}
}

private fun DataOutputStream.writeNullableDouble(value: Double?) {
	writeBoolean(value != null)
	if (value != null) {
		writeDouble(value)
	}
}

private val PORTABLE_CELL_OBSERVATION_ORDER = compareBy<PortableCapturedCellObservationV1>(
	PortableCapturedCellObservationV1::coverageStartTimeMs,
	PortableCapturedCellObservationV1::observedTimeMs,
	{ it.identity.value },
)
private val PORTABLE_CELL_RUN_ORDER = compareBy<PortableCapturedCellRunV1>(
	PortableCapturedCellRunV1::startTimeMs,
	{ it.identity.value },
)
private val SHA_256_HEX = Regex("[0-9a-f]{64}")
private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
private const val CELL_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
private val PORTABLE_CELL_AUDIT_LIMITS = CellCapturedMaintenanceLimits(
	maximumRevisions = 4_096,
	maximumLogicalFacts = 4_096,
	maximumCursors = 4_096,
	maximumDeletionGenerations = 4_096,
)
private const val MANUAL_CAPTURE_MASK = 1L
private const val AUTOMATIC_CAPTURE_MASK = 2L
private const val ALL_CAPTURE_MASK = MANUAL_CAPTURE_MASK or AUTOMATIC_CAPTURE_MASK
private const val CELL_SOURCE_KIND = SourceDestinationOwnerEntity.SOURCE_CELL
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MAX_CELL_PLAN_PAYLOAD_BYTES = 64 * 1_024
private const val MAX_CELL_SETTLEMENT_AUTHORIZATION_REVISIONS = 256
private const val CELL_CAPTURE_ACQUISITION_FLOOR = "cell:v1:required=CHANGE_CALLBACK"
