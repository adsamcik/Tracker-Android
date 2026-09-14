@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId
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
}

enum class PortableCellIdentityKind {
	LOGICAL_ENTRY,
	PHYSICAL_RUN,
	OBSERVATION,
}

@JvmInline
value class PortableCellOpaqueIdentity(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}

	companion object {
		fun derive(kind: PortableCellIdentityKind, localIdentity: String): PortableCellOpaqueIdentity {
			require(localIdentity.isNotBlank() && localIdentity.length <= 4_096)
			return PortableCellOpaqueIdentity(
				CellCapturedPortableIntegrity.digest(
					"tracker-portable-cell-identity-v1",
					listOf(kind.name, localIdentity),
				),
			)
		}
	}
}

@JvmInline
value class PortableCellDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}
}

@JvmInline
value class PortableCellDeletionScopeDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}

	companion object {
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

enum class PortableCellSessionMode { MANUAL, AUTOMATIC }
enum class PortableCellCaptureCoverage { WHOLE_RUN, PARTIAL_RUN, NOT_CAPTURED }
enum class PortableCellRunAvailability { RETAINED, NO_RETAINED_OBSERVATION, NOT_CAPTURED }
enum class PortableCellAcquisitionCompleteness { COMPLETE, PARTIAL, UNKNOWN }
enum class PortableCellChildCompleteness { COMPLETE, PARTIAL }
enum class PortableCellSubscriptionGrouping { UNKNOWN }

/** No tower, subscription, provider, hardware, authorization, or local database identity crosses this type. */
@Suppress("LongParameterList")
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
		require(weakObservationCount <= knownQualityObservationCount &&
			knownQualityObservationCount <= observationCount)
		require(allKnownQualityIsWeak ==
			(knownQualityObservationCount > 0 && weakObservationCount == knownQualityObservationCount))
		require(qualityFlags >= 0L)
		require(qualityConfidence?.let { it.isFinite() && it in 0.0..1.0 } != false)
		require(CellCapturedPortableIntegrity.observationChecksum(this) == contentChecksum)
	}
}

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

data class PortableCapturedCellEntryV1(
	val format: String = CellCapturedPortableFormatV1.FORMAT,
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
		require(runs.any { it.availability == PortableCellRunAvailability.RETAINED })
		require(CellCapturedPortableIntegrity.entryChecksum(this) == contentChecksum)
	}
}

data class ExportPortableCapturedCellRequest(val logicalTrackingId: String) {
	init {
		require(logicalTrackingId.isNotBlank() && logicalTrackingId.length <= 4_096)
	}
}

fun interface PortableCapturedCellSink {
	/** Invoked only after the authenticated immutable Room snapshot has ended. */
	suspend fun emit(entry: PortableCapturedCellEntryV1)
}

interface ExportPortableCapturedCell {
	suspend fun export(
		request: ExportPortableCapturedCellRequest,
		sink: PortableCapturedCellSink,
	): ExportPortableCapturedCellResult
}

sealed interface ExportPortableCapturedCellResult {
	data class Exported(
		val entryIdentity: PortableCellOpaqueIdentity,
		val contentChecksum: PortableCellDigest,
		val physicalRunCount: Int,
		val observationCount: Int,
	) : ExportPortableCapturedCellResult {
		init {
			require(physicalRunCount > 0 && observationCount > 0)
		}
	}

	data class Unavailable(val reason: PortableCellUnavailableReason) : ExportPortableCapturedCellResult
	data object Materializing : ExportPortableCapturedCellResult
	data object Deleted : ExportPortableCapturedCellResult
	data class Unverifiable(val reason: PortableCellUnverifiableReason) : ExportPortableCapturedCellResult
	data class RetryableFailure(val reason: PortableCellRetryableReason) : ExportPortableCapturedCellResult
}

enum class PortableCellUnavailableReason {
	ENTRY_NOT_FOUND,
	SOURCE_NOT_CAPTURED,
	NO_QUALIFIED_FACTS,
	RETENTION_LIMIT,
	PROVIDER_UNAVAILABLE,
}

enum class PortableCellUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
	WRITER_AUTHORITY_UNVERIFIABLE,
	FACT_AUTHORITY_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

enum class PortableCellRetryableReason { STORAGE_UNAVAILABLE }

/** Source-owned export; database authentication and checksum construction stay off the caller thread. */
class RoomExportPortableCapturedCell(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableCapturedCell {
	override suspend fun export(
		request: ExportPortableCapturedCellRequest,
		sink: PortableCapturedCellSink,
	): ExportPortableCapturedCellResult = withContext(ioDispatcher) {
		val snapshot = try {
			database.withTransaction {
				readPortableCapturedCellEntry(request, laneExecutionAuthority)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (blocked: CellCapturedRetentionBlockedException) {
			return@withContext ExportPortableCapturedCellResult.Unverifiable(
				when (blocked.reason) {
					CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
						PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW
					else -> PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE
				},
			)
		} catch (@Suppress("SwallowedException") _: CellCapturedMaintenanceLimitExceeded) {
			return@withContext ExportPortableCapturedCellResult.Unverifiable(
				PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
			return@withContext ExportPortableCapturedCellResult.Unverifiable(
				PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		} catch (@Suppress("SwallowedException") _: ArithmeticException) {
			return@withContext ExportPortableCapturedCellResult.Unverifiable(
				PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		} catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception) {
			return@withContext ExportPortableCapturedCellResult.RetryableFailure(
				PortableCellRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (snapshot) {
			is CellPortableReadResult.Entry -> {
				currentCoroutineContext().ensureActive()
				sink.emit(snapshot.value)
				ExportPortableCapturedCellResult.Exported(
					snapshot.value.identity,
					snapshot.value.contentChecksum,
					snapshot.value.runs.size,
					snapshot.value.runs.sumOf { it.observations.size },
				)
			}
			is CellPortableReadResult.Result -> snapshot.value
		}
	}
}

private sealed interface CellPortableReadResult {
	data class Entry(val value: PortableCapturedCellEntryV1) : CellPortableReadResult
	data class Result(val value: ExportPortableCapturedCellResult) : CellPortableReadResult
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
private suspend fun AppDatabase.readPortableCapturedCellEntry(
	request: ExportPortableCapturedCellRequest,
	laneExecutionAuthority: SourceProductLaneExecutionAuthority,
): CellPortableReadResult {
	currentCoroutineContext().ensureActive()
	val evidence = sourceEvidenceStateDao().get() ?: return result(
		ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		),
	)
	if (!evidence.hasValidPortableCellShape()) return result(unverifiableFact())

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
	if (runs.size > CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) return result(overflow())
	if (runs.any { it.logicalTrackingId != request.logicalTrackingId } ||
		runs.distinctBy { it.serviceRunId }.size != runs.size ||
		runs != runs.sortedWith(compareBy(SourceServiceRunEntity::startedAtMs, SourceServiceRunEntity::serviceRunId))
	) return result(unverifiableMembership())

	val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId)
	if (segmentIds.size != runs.size || segmentIds.distinct().size != segmentIds.size) {
		return result(unverifiableMembership())
	}
	val segments = readDao.rawLogicalEntrySegments(
		request.logicalTrackingId,
		CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 1,
	)
	if (segments.size > CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) return result(overflow())
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
	if (!session.hasPortableCellShape(runs)) return result(unverifiableAttribution())
	val hierarchyMaterializing = session.completedAtMs == null || runs.any { run ->
		run.completedAtMs == null ||
			run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING
	}
	if (runs.any { run ->
		run.presentationAcknowledgement !in setOf(
			SourceServiceRunEntity.PRESENTATION_PENDING,
			SourceServiceRunEntity.PRESENTATION_QUIESCED,
		) || (run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED &&
			run.presentationAcknowledgedAtMs == null)
	}) return result(unverifiableWriter())

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
	) return result(overflow())
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
	if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
		manifestsByRun.values.map { revisions -> revisions.map(SessionManifestVersionEntity::manifestRevision) },
	) || runs.any { run ->
		val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
		!SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests) || runManifests.any { manifest ->
			manifest.logicalTrackingId != request.logicalTrackingId ||
				!SessionManifestIntegrity.verify(
					manifest,
					sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision].orEmpty(),
				)
		}
	}) return result(unverifiableAttribution())
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
	} || manifests.any { it.sessionMode != session.sessionMode } ||
		session.currentManifestRevision != latestLogicalManifest.manifestRevision ||
		session.desiredPlanRevision != latestLogicalManifest.acquisitionPlanRevision ||
		session.rolloutRevision != latestLogicalManifest.rolloutRevision
	) return result(unverifiableAttribution())

	val captureByRun = linkedMapOf<String, List<SessionManifestSourceEntity>>()
	for (run in runs) {
		val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
		if (runManifests.isEmpty()) return result(unverifiableAttribution())
		val bindings = runManifests.mapNotNull { manifest ->
			val membership = sourcesByManifest[
				manifest.logicalTrackingId to manifest.manifestRevision
			].orEmpty()
			val matches = membership
				.filter(::isPortableCellCaptureMembership)
			if (matches.size > 1 || (matches.isNotEmpty() && membership.any { source ->
				source.sourceKind == CELL_SOURCE_KIND &&
					source.purpose == SessionManifestPurposeCode.CONTROL
			})) return result(unverifiableAttribution())
			matches.singleOrNull()
		}
		if (bindings.any { !it.isExactPortableCellWriter() }) return result(unverifiableWriter())
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
		desiredPlans.size > CellCapturedPortableFormatV1.MAX_PLANS ||
		policies.distinctBy { it.policyRevision }.size != policies.size ||
		consents.distinctBy { it.epoch }.size != consents.size ||
		planHeaders.distinctBy { it.revision }.size != planHeaders.size ||
		desiredPlans.distinctBy { it.revision }.size != desiredPlans.size
	) return result(overflow())
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
	}) return result(unverifiableAttribution())

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
	) return result(unverifiableWriter())
	val cellCompleteness = completeness.filter { row -> row.serviceRunId in selectedCapturedRunIds }
	if (!cellCompleteness.hasValidPortableCellShape(request.logicalTrackingId, runIds)) {
		return result(unverifiableWriter())
	}
	if (selectedCapturedRunIds.any { runId ->
		cellCompleteness.none { it.serviceRunId == runId }
	}) return result(unverifiableWriter())
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
		selectedWal.any { it.scope.serviceRunId !in runIds } ||
		audit.lineages.any { lineage ->
			lineage.scope.serviceRunId in runIds && lineage.scope.logicalTrackingId != request.logicalTrackingId
		} || walAudit.carriers.any { carrier ->
			carrier.scope.serviceRunId in runIds &&
				carrier.scope.logicalTrackingId != request.logicalTrackingId
		}
	) return result(unverifiableFact())
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
	) return result(unverifiableFact())
	val fenceDigests = retainedFences.mapTo(mutableSetOf()) { it.scopeIdentityDigest }
	if (runIds.any { runId ->
		val hasLocalGeneration = audit.generationByScope.containsKey(
			CellCapturedRunScope(request.logicalTrackingId, runId),
		)
		val hasGlobalFence = scopeDigestByRun.getValue(runId) in fenceDigests
		hasLocalGeneration != hasGlobalFence
	}) return result(unverifiableFact())
	if (audit.generationByScope.keys.any { scope ->
		scope.logicalTrackingId != request.logicalTrackingId || scope.serviceRunId !in runIds ||
			scope.serviceRunId !in selectedCapturedRunIds
	}) return result(unverifiableFact())
	val selectedDeleted = selectedCapturedRunIds.any { runId ->
		audit.generationByScope.containsKey(CellCapturedRunScope(request.logicalTrackingId, runId))
	}
	if (selectedDeleted) {
		return result(if (selectedLineages.isEmpty()) ExportPortableCapturedCellResult.Deleted else unverifiableFact())
	}
	val retainedFloor = evidence.retainedFromMs
	val retainedWal = selectedWal.filter { carrier ->
		retainedFloor == null || carrier.earliestPossibleWallTimeMs >= retainedFloor
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
		if (row.registrationGeneration == 0L || PortableCellSettlementIdentity(
				row.serviceRunId,
				row.sourceInstanceId,
				row.registrationGeneration,
			) in evidenceBackedSettlements
		) continue
		val run = runs.singleOrNull { it.serviceRunId == row.serviceRunId }
			?: return result(unverifiableWriter())
		if (!authenticatePortableCellSettlement(
			row = row,
			run = run,
			manifests = manifestsByRun[row.serviceRunId].orEmpty(),
			sourcesByManifest = sourcesByManifest,
			desiredPlanByRevision = desiredPlanByRevision,
			evidenceState = evidence,
		)) return result(unverifiableWriter())
	}
	val targetOrdinal = listOfNotNull(
		selectedLineages.maxOfOrNull { it.revisions.last().sourceAdmissionOrdinal },
		cellCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		selectedWal.maxOfOrNull(CellCapturedWalCarrier::admissionOrdinal),
	).maxOrNull()
	if (targetOrdinal != null && session.finalAdmissionOrdinal?.let { targetOrdinal > it } == true) {
		return result(unverifiableWriter())
	}
	if (targetOrdinal != null && (targetOrdinal < lane.activationOrdinal ||
		lane.captureAdmissionCutoffOrdinal?.let { it < targetOrdinal } == true)
	) return result(unverifiableWriter())
	if (selectedLineages.any { it.revisions.last().sourceAdmissionOrdinal < lane.activationOrdinal }) {
		return result(unverifiableWriter())
	}
	if (selectedLineages.any { lineage ->
		val fact = lineage.revisions.last()
		cellCompleteness.none { row ->
			fact.serviceRunId == row.serviceRunId && fact.sourceInstanceId == row.sourceInstanceId &&
				fact.registrationGeneration == row.registrationGeneration &&
				row.lastAdmissionOrdinal?.let { it >= fact.sourceAdmissionOrdinal } == true
		}
	}) return result(unverifiableWriter())
	val failures = if (targetOrdinal == null || lane.activationOrdinal > targetOrdinal) emptyList() else {
		readDao.terminalFailuresForServiceRuns(
			CELL_SOURCE_KIND,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			runIds,
			lane.activationOrdinal - 1L,
			targetOrdinal,
			CellCapturedPortableFormatV1.MAX_TERMINAL_FAILURES + 1,
		)
	}
	if (failures.size > CellCapturedPortableFormatV1.MAX_TERMINAL_FAILURES) return result(overflow())
	if (failures.isNotEmpty()) return result(unverifiableWriter())
	if (hierarchyMaterializing ||
		(targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal)
	) return result(ExportPortableCapturedCellResult.Materializing)
	val factEventIds = selectedLineages.mapTo(mutableSetOf()) { it.revisions.last().sourceEventId }
	if (retainedWal.any { it.eventId !in factEventIds }) return result(unverifiableWriter())

	val timeRetainedLineages = selectedLineages.filter { lineage ->
		retainedFloor == null || lineage.earliestPossibleWallTimeMs >= retainedFloor
	}
	val timeRetainedIds = timeRetainedLineages.mapTo(mutableSetOf(), CellCapturedLineage::logicalFactId)
	// Never export a coverage-only correction whose authenticated aggregate owner was lost at the
	// retention boundary. Keeping the dependent without its owner would make a later importer invent
	// aggregate authority that this database no longer retains.
	val retainedLineages = timeRetainedLineages.filter { lineage ->
		lineage.aggregateOwnerLogicalFactId == null || lineage.aggregateOwnerLogicalFactId in timeRetainedIds
	}
	if (retainedLineages.isEmpty()) {
		return result(
			if (selectedLineages.isNotEmpty() || selectedWal.size != retainedWal.size) {
				ExportPortableCapturedCellResult.Unavailable(PortableCellUnavailableReason.RETENTION_LIMIT)
			} else if (cellCompleteness.any {
				it.stopStatus == "PERMISSION_LOST" || it.stopStatus == "PROVIDER_FAILED"
			}) {
				ExportPortableCapturedCellResult.Unavailable(
					PortableCellUnavailableReason.PROVIDER_UNAVAILABLE,
				)
			} else {
				ExportPortableCapturedCellResult.Unavailable(PortableCellUnavailableReason.NO_QUALIFIED_FACTS)
			},
		)
	}

	val retainedByRun = retainedLineages.groupBy { it.scope.serviceRunId }
	val runsOut = runs.map { run ->
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
			}
		val observations = lineages.map { lineage ->
			val fact = lineage.revisions.last()
			val aggregate = audit.aggregateById[fact.logicalFactId]
				?: throw IllegalArgumentException("Authenticated Cell aggregate missing")
			fact.toPortableObservation(aggregate)
		}.sortedWith(PORTABLE_CELL_OBSERVATION_ORDER)
		val availability = when {
			!captured -> PortableCellRunAvailability.NOT_CAPTURED
			observations.isNotEmpty() -> PortableCellRunAvailability.RETAINED
			else -> PortableCellRunAvailability.NO_RETAINED_OBSERVATION
		}
		val acquisition = cellCompleteness.filter { it.serviceRunId == run.serviceRunId }
			.toPortableAcquisitionCompleteness(captured)
		val payload = PortableCellRunPayload(
			identity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				run.serviceRunId,
			),
			deletionScopeDigest = PortableCellDeletionScopeDigest.derive(
				request.logicalTrackingId,
				run.serviceRunId,
			),
			startTimeMs = segment.startTimeMs,
			endTimeMs = segment.endTimeMs,
			captureCoverage = captureCoverage,
			availability = availability,
			acquisitionCompleteness = acquisition,
			retentionLoss = retentionLoss,
			subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
			observations = observations,
		)
		payload.toValue(CellCapturedPortableIntegrity.runChecksum(payload))
	}.sortedWith(PORTABLE_CELL_RUN_ORDER)
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
	return CellPortableReadResult.Entry(
		entryPayload.toValue(CellCapturedPortableIntegrity.entryChecksum(entryPayload)),
	)
}

private fun CellCapturedFactRevisionEntity.toPortableObservation(
	aggregate: CellHistoricalIdentityFreeAggregate,
): PortableCapturedCellObservationV1 {
	val coverageStart = Math.subtractExact(
		Math.subtractExact(
			observedWallTimeMs,
			Math.subtractExact(observedElapsedNanos, coverageIntervalStartNanos) / NANOS_PER_MILLISECOND,
		),
		wallTimeUncertaintyMs,
	)
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
		qualityConfidence = qualityConfidence,
	)
	return payload.toValue(CellCapturedPortableIntegrity.observationChecksum(payload))
}

/** Canonical binary hashing kept next to the only Cell v1 format. */
object CellCapturedPortableIntegrity {
	internal fun digest(namespace: String, parts: List<Any?>): String = canonicalDigest(namespace) {
		writeInt(parts.size)
		parts.forEach { part -> writeString(part?.toString()) }
	}

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

private fun LogicalTrackingSessionEntity.hasPortableCellShape(runs: List<SourceServiceRunEntity>): Boolean {
	val terminal = state in TERMINAL_SESSION_STATES
	if ((completedAtMs != null) != terminal || logicalTrackingId.isBlank() || lifecycleRevision <= 0L ||
		desiredPlanRevision <= 0L || rolloutRevision <= 0L || startOrigin.isBlank() ||
		clockDomainId.isBlank() || lifecycleBootId != clockDomainId || lifecycleLeaseGeneration <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L
	) return false
	if ((cutoffAtMs == null) != (cutoffElapsedNanos == null)) return false
	if (terminal && (cutoffAtMs == null || cutoffElapsedNanos == null || currentServiceRunId != null ||
		finalAdmissionOrdinal == null || finalAdmissionOrdinal < 0L ||
		requireNotNull(completedAtMs) < requireNotNull(cutoffAtMs))
	) return false
	if (runs.any { run ->
		run.logicalTrackingId != logicalTrackingId || run.bootId != clockDomainId ||
			run.leaseGeneration <= 0L || run.leaseGeneration > lifecycleLeaseGeneration ||
			run.desiredPlanRevision <= 0L || run.rolloutRevision <= 0L ||
			run.startedAtMs < 0L || run.startedElapsedNanos < 0L || run.startOrigin.isBlank() ||
			run.startDeliveryToken.isNullOrBlank() || run.startCommandGeneration <= 0L ||
			run.preparedManifestRevision <= 0L || run.preparedIntentRevision <= 0L ||
			run.runtimeAcknowledgement.isBlank() || run.androidDeliveryState.isBlank() ||
			run.androidDeliveryUpdatedAtMs == null ||
			(run.completedAtMs != null) != (run.state in TERMINAL_SESSION_STATES) ||
			(run.completedAtMs != null) != !run.completionReason.isNullOrBlank() ||
			run.completedAtMs?.let { it < run.startedAtMs } == true
	}) return false
	return true
}

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
		planHeader == null || desiredPlan == null || desiredPlan.payload.size > MAX_CELL_PLAN_PAYLOAD_BYTES ||
		desiredPlan.payloadVersion != 1 || desiredPlan.payloadChecksum != sha256Portable(desiredPlan.payload) ||
		!hasValidPortableZone(zoneId)
	) return false
	val plan = decodeCanonicalCellPlan(desiredPlan.payload) ?: return false
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
		consent.effectiveElapsedRealtimeNanos <= effectiveElapsedRealtimeNanos &&
		planHeader.revision == acquisitionPlanRevision &&
		planHeader.sourcePolicyRevision == sourcePolicyRevision && planHeader.planId.isNotBlank() &&
		planHeader.createdAtMs >= 0L && desiredPlan.revision == acquisitionPlanRevision &&
		desiredPlan.sourceKind == CELL_SOURCE_KIND && plan.revision == acquisitionPlanRevision &&
		plan.hasSupportedHistoricalShape()
}

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

private fun List<SourceSessionCompletenessEntity>.hasValidPortableCellShape(
	logicalTrackingId: String,
	runIds: List<String>,
): Boolean = groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, unorderedRows) ->
	val rows = unorderedRows.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
	runId in runIds && rows.distinctBy(SourceSessionCompletenessEntity::registrationGeneration).size == rows.size &&
		rows.all { row ->
			row.logicalTrackingId == logicalTrackingId && row.sourceInstanceId.isNotBlank() &&
				row.registrationGeneration >= 0L && row.hasValidPortableCellStopShape() &&
				(row.lastAdmissionOrdinal == null) == (row.lastSourceSequence == null) &&
				row.lastAdmissionOrdinal?.let { it > 0L } != false &&
				row.lastSourceSequence?.let { it > 0L } != false &&
				(row.unresolvedSequenceStart == null) == (row.unresolvedSequenceEnd == null) &&
				row.unresolvedSequenceStart?.let { start ->
					start > 0L && requireNotNull(row.unresolvedSequenceEnd) >= start
				} != false &&
				row.providerCoverage == CELL_PROVIDER_COVERAGE &&
				row.updatedAtMs >= 0L &&
				if (row.registrationGeneration == 0L) {
					rows.size == 1
				} else true
		}
}

private fun SourceSessionCompletenessEntity.hasValidPortableCellStopShape(): Boolean {
	val hasGap = unresolvedSequenceStart != null
	if (hasGap && (appDrainComplete || stopStatus != "TIMED_OUT")) return false
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

@Suppress("ComplexCondition", "LongMethod")
private suspend fun AppDatabase.authenticatePortableCellSettlement(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
	sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
	desiredPlanByRevision: Map<Long, SourceDesiredPlanEntity>,
	evidenceState: SourceEvidenceState,
): Boolean {
	val brokerDao = sourceBrokerDao()
	val registration = brokerDao.registration(CELL_SOURCE_KIND, row.registrationGeneration)
		?: return false
	if (!registration.hasExactPortableCellSettlementShape(row, run, evidenceState)) return false
	val retiredElapsedNanos = requireNotNull(registration.retiredElapsedRealtimeNanos)
	if (brokerDao.hasConflictingAcceptedRegistrationBefore(
			sourceKind = CELL_SOURCE_KIND,
			registrationGeneration = row.registrationGeneration,
			clockDomainId = registration.clockDomainId,
			collectedDataEpoch = registration.collectedDataEpoch,
			sourceInstanceId = registration.sourceInstanceId,
			reservedElapsedRealtimeNanos = registration.reservedElapsedRealtimeNanos,
		) || brokerDao.hasConflictingAcceptedRegistrationAfter(
			sourceKind = CELL_SOURCE_KIND,
			registrationGeneration = row.registrationGeneration,
			clockDomainId = registration.clockDomainId,
			collectedDataEpoch = registration.collectedDataEpoch,
			sourceInstanceId = registration.sourceInstanceId,
			retiredElapsedRealtimeNanos = retiredElapsedNanos,
		)
	) return false

	val authorizationRevisions = trackingHistoryReadDao().registrationAuthorizationRevisions(
		CELL_SOURCE_KIND,
		row.registrationGeneration,
		MAX_CELL_SETTLEMENT_AUTHORIZATION_REVISIONS + 1,
	)
	if (authorizationRevisions.size > MAX_CELL_SETTLEMENT_AUTHORIZATION_REVISIONS) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	if (authorizationRevisions.isEmpty()) return false
	val previousMaximumRevision = brokerDao.maximumAuthorizationRevisionBeforeRegistration(
		CELL_SOURCE_KIND,
		row.registrationGeneration,
	)
	val nextMinimumRevision = brokerDao.minimumAuthorizationRevisionAfterRegistration(
		CELL_SOURCE_KIND,
		row.registrationGeneration,
	)
	val maximumCaptureRevision = brokerDao.maximumCaptureAuthorizationRevision(
		CELL_SOURCE_KIND,
		row.registrationGeneration,
	)
	if (registration.captureCallbackBarrierAuthorizationRevision > maximumCaptureRevision) return false

	var selectedCaptureRevisionFound = false
	for (revision in authorizationRevisions) {
		currentCoroutineContext().ensureActive()
		if (revision <= previousMaximumRevision ||
			(nextMinimumRevision > 0L && revision >= nextMinimumRevision) ||
			brokerDao.authorizationRevisionRegistrationCount(CELL_SOURCE_KIND, revision) != 1
		) return false
		val rows = cellCapturedFactDao().maintenanceAuthorizationMembers(
			CELL_SOURCE_KIND,
			row.registrationGeneration,
			revision,
			PORTABLE_CELL_AUDIT_LIMITS.maximumAuthorizationMembers + 1,
		)
		if (rows.size > PORTABLE_CELL_AUDIT_LIMITS.maximumAuthorizationMembers) {
			throw CellCapturedMaintenanceLimitExceeded()
		}
		val authorization = runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull()
			?: return false
		val demandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
		if (demandIds.distinct().size != authorization.authorizedMembers.size) return false
		val demands = brokerDao.demandsByIds(demandIds)
		if (demands.size != demandIds.size) return false
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
			authorization.effectiveElapsedRealtimeNanos !in
				registration.reservedElapsedRealtimeNanos..retiredElapsedNanos
		) return false
		val selectedDemands = demands.filter { candidate ->
			candidate.logicalTrackingId == row.logicalTrackingId &&
				candidate.serviceRunId == row.serviceRunId &&
				candidate.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
				candidate.persistenceEligible
		}
		if (selectedDemands.size > 1) return false
		val demand = selectedDemands.singleOrNull() ?: continue
		val manifest = manifests.singleOrNull { it.manifestRevision == demand.manifestRevision }
			?: return false
		val binding = sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision]
			.orEmpty().singleOrNull(::isPortableCellCaptureMembership) ?: return false
		val desiredPlan = desiredPlanByRevision[manifest.acquisitionPlanRevision] ?: return false
		val plan = decodeCanonicalCellPlan(desiredPlan.payload) ?: return false
		if (!demand.hasExactPortableCellSettlementShape(row, run, manifest, binding) ||
			registration.physicalConfigurationFingerprint != plan.physicalConfigurationFingerprint() ||
			authorization.effectiveElapsedRealtimeNanos < demand.requestedElapsedRealtimeNanos ||
			authorization.effectiveElapsedRealtimeNanos > requireNotNull(demand.retireElapsedRealtimeNanos)
		) return false
		selectedCaptureRevisionFound = true
	}
	return selectedCaptureRevisionFound
}

@Suppress("ComplexCondition")
private fun ProviderRegistrationGenerationEntity.hasExactPortableCellSettlementShape(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	evidenceState: SourceEvidenceState,
): Boolean {
	val acceptedWallTimeMs = acceptedAtMs ?: return false
	val acceptedElapsedNanos = acceptedElapsedRealtimeNanos ?: return false
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
	return sourceKind == CELL_SOURCE_KIND && registrationGeneration == row.registrationGeneration &&
		sourceInstanceId == row.sourceInstanceId && ownerScope == "source-broker:$CELL_SOURCE_KIND" &&
		clockDomainId == run.bootId && collectedDataEpoch == evidenceState.collectedDataEpoch &&
		providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
		!providerProcessIncarnationId.isNullOrBlank() && statusMatches && failureCode == "ORDERLY_STOP" &&
		reservedAtMs <= acceptedWallTimeMs && acceptedWallTimeMs <= retiredWallTimeMs &&
		reservedElapsedRealtimeNanos <= acceptedElapsedNanos && acceptedElapsedNanos <= retiredElapsedNanos
}

@Suppress("ComplexCondition")
private fun SourceDemandEntity.hasExactPortableCellSettlementShape(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
): Boolean {
	val retiredBootId = retireBootId ?: return false
	val retiredElapsedNanos = retireElapsedRealtimeNanos ?: return false
	val retiredWallTimeMs = retiredAtMs ?: return false
	val expectedMaximumAgeMs = when (qosCode) {
		1 -> 10 * 60_000L
		2 -> 5 * 60_000L
		3 -> 60_000L
		else -> return false
	}
	return demandId == portableCellDemandId(this) && consumerId == "session:${row.logicalTrackingId}" &&
		sourceKind == CELL_SOURCE_KIND && purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		logicalTrackingId == row.logicalTrackingId && serviceRunId == row.serviceRunId &&
		manifestRevision == manifest.manifestRevision &&
		lifecycleLeaseGeneration == run.leaseGeneration && sourcePolicyRevision == manifest.sourcePolicyRevision &&
		consentEpoch == binding.consentEpoch && persistenceEligible && qosCode == binding.qosCode &&
		minimumAcquisitionSpec == CELL_CAPTURE_ACQUISITION_FLOOR && adaptiveReductionAllowed &&
		maximumAgeMs == expectedMaximumAgeMs && desiredLatencyMs == Long.MAX_VALUE &&
		requestedDeliveryLatencyMs == null && requestedBootId == run.bootId &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedAtMs == manifest.effectiveWallTimeMs && status == SourceDemandEntity.STATUS_RETIRED &&
		retiredBootId == run.bootId && retiredElapsedNanos >= requestedElapsedRealtimeNanos &&
		retiredWallTimeMs >= requestedAtMs
}

private fun portableCellDemandId(demand: SourceDemandEntity): String = sha256Portable(
	listOf(
		demand.consumerId,
		demand.sourceKind,
		demand.purpose,
		demand.sourcePolicyRevision,
		demand.consentEpoch,
		demand.manifestRevision ?: 0L,
		demand.requestedBootId,
		demand.requestedElapsedRealtimeNanos,
	).joinToString("\u001f").toByteArray(Charsets.UTF_8),
)

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

private fun result(value: ExportPortableCapturedCellResult) = CellPortableReadResult.Result(value)
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
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun sha256Portable(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun DataOutputStream.writeString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeNullableDouble(value: Double?) {
	writeBoolean(value != null)
	if (value != null) writeDouble(value)
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
