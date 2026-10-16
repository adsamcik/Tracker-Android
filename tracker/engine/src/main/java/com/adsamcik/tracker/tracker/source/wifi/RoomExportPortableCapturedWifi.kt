@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_OBSERVATION_ORDER
import com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_RUN_ORDER
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiSink
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnavailableReason
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiCapturedPortableFormatV1
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import java.time.DateTimeException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local captured Wi-Fi exporter; no provider or demand path is reachable from this class. */
@Singleton
internal class RoomExportPortableCapturedWifi @Inject constructor(
	private val database: AppDatabase,
	private val maintenance: WifiCapturedFactMaintenance,
	private val planCodec: SourcePlanCodec,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableCapturedWifi, ReadLocalPortableCapturedWifi {
	override suspend fun export(
		request: ExportPortableCapturedWifiRequest,
		sink: PortableCapturedWifiSink,
	): ExportPortableCapturedWifiResult = withContext(ioDispatcher) {
		val snapshot = readExportSnapshot(request)

		when (snapshot) {
			is ReadLocalPortableCapturedWifiResult.Ready -> {
				currentCoroutineContext().ensureActive()
				sink.emit(snapshot.entry)
				ExportPortableCapturedWifiResult.Exported(
					snapshot.entry.identity,
					snapshot.entry.contentChecksum,
					snapshot.entry.runs.size,
					snapshot.entry.runs.sumOf { it.observations.size },
				)
			}
			is ReadLocalPortableCapturedWifiResult.Outcome -> snapshot.result
		}
	}

	private suspend fun readExportSnapshot(
		request: ExportPortableCapturedWifiRequest,
	): ReadLocalPortableCapturedWifiResult = try {
		database.withTransaction { readInTransaction(request) }
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (blocked: WifiCapturedRetentionBlockedException) {
		outcome(ExportPortableCapturedWifiResult.Unverifiable(
			when (blocked.reason) {
				WifiCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
					PortableWifiUnverifiableReason.DEPENDENCY_OVERFLOW
				WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
					PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE
				else -> PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE
			},
		))
	} catch (_: WifiCapturedMaintenanceLimitExceeded) {
		outcome(ExportPortableCapturedWifiResult.Unverifiable(
			PortableWifiUnverifiableReason.DEPENDENCY_OVERFLOW,
		))
	} catch (_: IllegalArgumentException) {
		outcome(ExportPortableCapturedWifiResult.Unverifiable(
			PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		))
	} catch (_: ArithmeticException) {
		outcome(ExportPortableCapturedWifiResult.Unverifiable(
			PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		))
	} catch (_: Exception) {
		outcome(ExportPortableCapturedWifiResult.RetryableFailure(
			PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
		))
	}

	override suspend fun readInTransaction(
		request: ExportPortableCapturedWifiRequest,
	): ReadLocalPortableCapturedWifiResult = readPortableWifiInTransaction(
		database, maintenance, planCodec, laneExecutionAuthority, request,
	)
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
private suspend fun readPortableWifiInTransaction(
	database: AppDatabase,
	maintenance: WifiCapturedFactMaintenance,
	planCodec: SourcePlanCodec,
	laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	request: ExportPortableCapturedWifiRequest,
): ReadLocalPortableCapturedWifiResult {
	currentCoroutineContext().ensureActive()
	val evidence = database.sourceEvidenceStateDao().get()
		?: return outcome(unverifiable(PortableWifiUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING))
	if (!evidence.hasValidPortableShape()) {
		return outcome(unverifiableFact())
	}

	val readDao = database.trackingHistoryReadDao()
	val runs = readDao.logicalEntryServiceRunPage(
		listOf(request.logicalTrackingId),
		WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 1,
		null,
		null,
		null,
	)
	if (runs.isEmpty()) {
		return outcome(
			if (database.sourceSessionDao().session(request.logicalTrackingId) == null) {
				ExportPortableCapturedWifiResult.Unavailable(PortableWifiUnavailableReason.ENTRY_NOT_FOUND)
			} else {
				unverifiableMembership()
			},
		)
	}
	if (runs.size > WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
		return outcome(overflow())
	}
	if (runs.any { it.logicalTrackingId != request.logicalTrackingId } ||
		runs.distinctBy(SourceServiceRunEntity::serviceRunId).size != runs.size ||
		runs != runs.sortedWith(compareBy(SourceServiceRunEntity::startedAtMs,
                SourceServiceRunEntity::serviceRunId))
	) {
		return outcome(unverifiableMembership())
	}

	val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId)
	if (segmentIds.size != runs.size || segmentIds.distinct().size != segmentIds.size) {
		return outcome(unverifiableMembership())
	}
	val segments = readDao.rawLogicalEntrySegments(
		request.logicalTrackingId,
		runs.map(SourceServiceRunEntity::serviceRunId),
		WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 1,
	)
	if (segments.size > WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
		return outcome(overflow())
	}
	if (segments.size != runs.size || segments.distinctBy(SessionSegment::id).size != segments.size ||
		segments.mapTo(mutableSetOf(), SessionSegment::id) != segmentIds.toSet()
	) {
		return outcome(unverifiableMembership())
	}
	val segmentsById = segments.associateBy(SessionSegment::id)
	if (runs.any { run -> !run.hasExactPortableMembership(request.logicalTrackingId, segmentsById) }) {
		return outcome(unverifiableMembership())
	}
	val runIds = runs.map(SourceServiceRunEntity::serviceRunId)

	val session = database.sourceSessionDao().session(request.logicalTrackingId)
		?: return outcome(unverifiableMembership())
	val sessionMode = when (session.sessionMode) {
		"MANUAL" -> PortableWifiSessionMode.MANUAL
		"AUTOMATIC" -> PortableWifiSessionMode.AUTOMATIC
		else -> return outcome(unverifiableAttribution())
	}
	val interrupted = session.state == "FAILED" && session.failureCode == "RESTORED_INTERRUPTED_SESSION"
	val interruptedRunIds = runs.filter { it.state == "FAILED" &&
		it.completionReason == "RESTORED_INTERRUPTED_SESSION" && it.completedAtMs == null }
		.mapTo(mutableSetOf()) { it.serviceRunId }
	if (!session.hasPortableShape(runs, interrupted)) {
		return outcome(unverifiableAttribution())
	}
	val hierarchyMaterializing = !interrupted && session.completedAtMs != null && runs.any { run ->
		run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING
	}

	val manifests = readDao.manifests(runIds, WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1)
	val sources = readDao.manifestSources(runIds, WifiCapturedPortableFormatV1.MAX_MANIFEST_SOURCES + 1)
	val completeness = readDao.completenessForSource(
		WIFI_SOURCE,
		request.logicalTrackingId,
		runIds,
		WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS + 1,
	)
	if (manifests.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
		sources.size > WifiCapturedPortableFormatV1.MAX_MANIFEST_SOURCES ||
		completeness.size > WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS
	) {
		return outcome(overflow())
	}
	if (completeness.any { row ->
			(row.logicalTrackingId == request.logicalTrackingId) != (row.serviceRunId in runIds)
		}
	) {
		return outcome(unverifiableMembership())
	}
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
	if (sources.any { source ->
		source.sourceKind !in VALID_SOURCE_CODES ||
			source.purpose !in SessionManifestPurposeCode.ALL ||
			(source.purpose == CAPTURE_PURPOSE && !source.persistenceEligible)
	}) {
		return outcome(unverifiableAttribution())
	}
	if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
			manifestsByRun.values.map { revisions ->
				revisions.map(SessionManifestVersionEntity::manifestRevision)
			},
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
		}
	) {
		return outcome(unverifiableAttribution())
	}
	if (manifests.any { !hasValidPortableWifiZone(it.zoneId) || it.sessionMode != session.sessionMode }) {
		return outcome(unverifiableAttribution())
	}
	val latestByRun = runs.associateWith { run ->
		manifestsByRun[run.serviceRunId].orEmpty().maxByOrNull(
			SessionManifestVersionEntity::manifestRevision,
		) ?: return outcome(unverifiableAttribution())
	}
	val latestLogical = latestByRun.values.maxByOrNull(SessionManifestVersionEntity::manifestRevision)
		?: return outcome(unverifiableAttribution())
	if (latestByRun.any { (run, manifest) -> !run.matchesPortableDesiredManifest(manifest) } ||
		!session.matchesPortableDesiredManifest(latestLogical)
	) {
		return outcome(unverifiableAttribution())
	}

	val captureByRun = linkedMapOf<String, List<SessionManifestSourceEntity>>()
	for (run in runs) {
		val bindings = mutableListOf<SessionManifestSourceEntity>()
		for (manifest in manifestsByRun[run.serviceRunId].orEmpty()) {
			val membership = sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision].orEmpty()
			val captured = membership.filter { source -> source.purpose == CAPTURE_PURPOSE }
			val capturedWifi = captured.filter { source -> source.sourceKind == WIFI_SOURCE }
			if (capturedWifi.size > 1 || capturedWifi.isNotEmpty() && membership.any { source ->
					source.sourceKind == WIFI_SOURCE && source.purpose == SessionManifestPurposeCode.CONTROL
				}
			) {
				return outcome(unverifiableAttribution())
			}
			capturedWifi.singleOrNull()?.let { binding ->
				if (!binding.isExactPortableWifiWriter()) {
					return outcome(unverifiableWriter())
				}
				bindings += binding
			}
		}
		captureByRun[run.serviceRunId] = bindings
	}
	if (captureByRun.values.all { it.isEmpty() }) {
		return outcome(ExportPortableCapturedWifiResult.Unavailable(
			PortableWifiUnavailableReason.SOURCE_NOT_CAPTURED,
		))
	}
	if (!interrupted && (session.completedAtMs == null || runs.any { it.completedAtMs == null })) {
		return outcome(ExportPortableCapturedWifiResult.Active)
	}
	val audit = maintenance.auditPortableInTransaction(
		evidence,
		request.logicalTrackingId,
		runIds,
		completeness,
		PORTABLE_LIMITS,
	)

	val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
	val consentEpochs = captureByRun.values.flatten().map(SessionManifestSourceEntity::consentEpoch).distinct()
	val policies = database.wifiCapturedFactDao().historyPolicies(
		WIFI_SOURCE,
		runIds,
		WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
	)
	val consents = if (consentEpochs.isEmpty()) {
		emptyList()
	} else
		{
		database.wifiCapturedFactDao().historyConsentEpochs(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			consentEpochs,
			WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
		)
	}
	val planHeaders = database.wifiCapturedFactDao().historyAcquisitionPlanRevisions(
		planRevisions,
		WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
	)
	val desiredPlans = database.wifiCapturedFactDao().historyDesiredPlans(
		WIFI_SOURCE,
		planRevisions,
		WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
	)
	if (policies.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
		consents.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
		planHeaders.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
		desiredPlans.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS
	) {
		return outcome(overflow())
	}
	if (policies.distinctBy { it.policyRevision }.size != policies.size ||
		consents.distinctBy { it.epoch }.size != consents.size ||
		planHeaders.distinctBy { it.revision }.size != planHeaders.size ||
		desiredPlans.distinctBy { it.revision }.size != desiredPlans.size
	) {
		return outcome(overflow())
	}
	val policyByRevision = policies.associateBy { it.policyRevision }
	val consentByEpoch = consents.associateBy { it.epoch }
	val headerByRevision = planHeaders.associateBy { it.revision }
	val desiredByRevision = desiredPlans.associateBy { it.revision }
	if (manifests.any { manifest ->
		val bindings = sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision]
			.orEmpty().filter(::isPortableWifiCaptureMembership)
		when (bindings.size) {
			0 -> !manifest.hasExactPortableHeader(headerByRevision[manifest.acquisitionPlanRevision])
			1 -> !manifest.hasExactPortableWifiIntent(
				bindings.single(),
				policyByRevision[manifest.sourcePolicyRevision],
				consentByEpoch[bindings.single().consentEpoch],
				headerByRevision[manifest.acquisitionPlanRevision],
				desiredByRevision[manifest.acquisitionPlanRevision],
				planCodec,
			)
			else -> true
		}
	}) {
		return outcome(unverifiableAttribution())
	}

	val lane = readDao.productLanesForServiceRuns(WIFI_SOURCE, CAPTURE_PURPOSE, runIds)
		.singleOrNull() ?: return outcome(unverifiableWriter())
	val requiredModeMask = when (sessionMode) {
		PortableWifiSessionMode.MANUAL -> MANUAL_CAPTURE_MASK
		PortableWifiSessionMode.AUTOMATIC -> AUTOMATIC_CAPTURE_MASK
	}
	if (!lane.isExactPortableWifiLane() || !laneExecutionAuthority.owns(lane) ||
		lane.captureModeMask and requiredModeMask == 0L ||
		manifests.any { lane.activatedRolloutRevision > it.rolloutRevision }
	) {
		return outcome(unverifiableWriter())
	}

	val selectedCapturedRunIds = captureByRun.filterValues { it.isNotEmpty() }.keys
	val selectedLineages = audit.lineages.filter {
		it.scope.logicalTrackingId == request.logicalTrackingId && it.scope.serviceRunId in runIds
	}
	val selectedWal = audit.wal.captureByEventId.values.flatten().filter {
		it.scope.logicalTrackingId == request.logicalTrackingId && it.scope.serviceRunId in runIds
	}
	val closureEventIds = audit.lineages.mapTo(mutableSetOf()) { it.revisions.last().sourceEventId }
	if (audit.lineages.any { !it.scope.hasPortableSelectedMembership(request.logicalTrackingId, runIds) } ||
		audit.wal.captureByEventId.values.flatten().any { wal ->
			!wal.scope.hasPortableSelectedMembership(request.logicalTrackingId, runIds) ||
				(wal !in selectedWal && wal.evidence.sourceEventId.value !in closureEventIds)
		}
	) {
		return outcome(unverifiableFact())
	}
	if (selectedLineages.any { it.scope.serviceRunId !in selectedCapturedRunIds } ||
		selectedWal.any { it.scope.serviceRunId !in selectedCapturedRunIds }
	) {
		return outcome(unverifiableFact())
	}

	val scopeDigestByRun = runIds.associateWith { runId ->
		SourceDeletionFenceEntity.logicalServiceRunIdentity(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			request.logicalTrackingId,
			runId,
		)
	}
	val fences = readDao.deletionFences(
		WIFI_SOURCE,
		CAPTURE_PURPOSE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeDigestByRun.values.toList(),
	)
	if (fences.distinctBy { it.scopeIdentityDigest }.size != fences.size ||
		fences.any { it.scopeIdentityDigest !in scopeDigestByRun.values }
	) {
		return outcome(unverifiableFact())
	}
	val runByScopeDigest = scopeDigestByRun.entries.associate { (runId, digest) -> digest to runId }
	if (fences.any { fence ->
		val runId = runByScopeDigest[fence.scopeIdentityDigest] ?: return@any true
		val generation = audit.generationByScope[
			WifiCapturedRunScope(request.logicalTrackingId, runId)
		] ?: return@any true
		fence != SourceDeletionFenceEntity.createLogicalServiceRun(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			request.logicalTrackingId,
			runId,
			generation.generation,
			generation.collectedDataEpoch,
			generation.updatedAtMs,
		)
	}) {
		return outcome(unverifiableFact())
	}
	val fencedRuns = scopeDigestByRun.filterValues { digest ->
		fences.any { it.scopeIdentityDigest == digest }
	}.keys
	if (audit.generationByScope.keys.any { scope ->
		scope.logicalTrackingId != request.logicalTrackingId ||
			scope.serviceRunId !in runIds || scope.serviceRunId !in selectedCapturedRunIds
	}) {
		return outcome(unverifiableFact())
	}
	val generatedRuns = audit.generationByScope.keys.mapNotNull { scope ->
		if (scope.logicalTrackingId == request.logicalTrackingId && scope.serviceRunId in runIds) {
			scope.serviceRunId
		} else {
			null
		}
	}.toSet()
	if (fencedRuns != generatedRuns) {
		return outcome(unverifiableFact())
	}
	if (generatedRuns == selectedCapturedRunIds) {
		return outcome(ExportPortableCapturedWifiResult.Deleted)
	}
	if (selectedLineages.any { it.scope.serviceRunId in generatedRuns }) {
		return outcome(unverifiableFact())
	}

	val wifiCompleteness = completeness.filter { row ->
		row.sourceKind == WIFI_SOURCE && row.serviceRunId in selectedCapturedRunIds
	}
	if (!wifiCompleteness.hasValidPortableWifiShape(request.logicalTrackingId, selectedCapturedRunIds) ||
		selectedCapturedRunIds.any { runId -> runId !in interruptedRunIds &&
			runId !in generatedRuns && wifiCompleteness.none { it.serviceRunId == runId } }
	) {
		return outcome(unverifiableWriter())
	}
	val positiveCompleteness = wifiCompleteness.filter { it.registrationGeneration > 0L &&
		it.serviceRunId !in interruptedRunIds && it.serviceRunId !in generatedRuns }
	var portableAuthorityQueryCount = audit.wal.portableAuthorityQueryCount
	if (portableAuthorityQueryCount > WIFI_PORTABLE_AUTHORITY_QUERY_LIMIT) {
		return outcome(overflow())
	}
	val portableAuthority = audit.wal.portableAuthority ?: return outcome(unverifiableWriter())
	if (positiveCompleteness.any { row ->
			val registration = portableAuthority.registrationsByGeneration[row.registrationGeneration]
				?: return@any true
			val run = runs.singleOrNull { it.serviceRunId == row.serviceRunId } ?: return@any true
			val authorizations = portableAuthority.authorizationsByGeneration[row.registrationGeneration]
				.orEmpty()
			val perReading = selectedWal.all { it.evidence.payloadVersion == WIFI_WAL_PAYLOAD_VERSION }
			// A shared owner stays ACTIVE after End; only this run's acknowledged drain closes its prefix.
			val sharedRetirement = if (perReading &&
                    registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE) {
				if (++portableAuthorityQueryCount > WIFI_PORTABLE_AUTHORITY_QUERY_LIMIT) {
					return outcome(overflow())
				}
				database.sourceSessionDao().rawRunRetirement(
					row.logicalTrackingId, row.serviceRunId, WIFI_SOURCE,
					row.sourceInstanceId, row.registrationGeneration,
				).singleOrNull()?.validatedOrNull()?.takeIf {
					it.matchesPortableWifiSharedSettlement(row, run) &&
						registration.acceptedAtMs?.let { accepted -> accepted <= it.cutoffWallTimeMs } == true &&
						registration.acceptedElapsedRealtimeNanos?.let { accepted ->
                                accepted <= it.cutoffElapsedRealtimeNanos } == true
				}
					?: return@any true
			} else {
				null
			}
			!registration.hasPortableRegistrationShape(
				row,
				run,
				portableAuthority,
			) || !authorizations.hasExactPortableCaptureRetirement(
				registration,
				run,
				portableAuthority,
				perReading = perReading,
				sharedRetirement = sharedRetirement,
			) ||
				!row.hasExactPortableTail(audit.wal.settlementByAdmissionOrdinal)
		}
	) {
		return outcome(unverifiableWriter())
	}
	val epochRelations = positiveCompleteness.map { row ->
		portableAuthority.registrationsByGeneration.getValue(row.registrationGeneration)
			.collectedDataEpoch.let { if (evidence.acceptsRetainedEpoch(it)) {
				0
			} else {
				it.compareTo(evidence.collectedDataEpoch)
			} }
	}
	if (epochRelations.any { it != 0 }) {
		return outcome(
			if (epochRelations.all { it < 0 } && selectedLineages.isEmpty() && selectedWal.isEmpty()) {
				ExportPortableCapturedWifiResult.Deleted
			} else {
				unverifiableFact()
			},
		)
	}

	val targetOrdinal = listOfNotNull(
		selectedLineages.maxOfOrNull { it.revisions.last().sourceAdmissionOrdinal },
		wifiCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		selectedWal.maxOfOrNull { it.evidence.sourceAdmissionOrdinal },
	).maxOrNull()
	if (targetOrdinal != null && (!session.permitsPortableTail(targetOrdinal, selectedWal) ||
		!lane.containsPortableTail(targetOrdinal))
	) {
		return outcome(unverifiableWriter())
	}
	if (selectedLineages.any { it.revisions.last().sourceAdmissionOrdinal < lane.activationOrdinal }) {
		return outcome(unverifiableWriter())
	}
	if (selectedLineages.any { lineage ->
		val fact = lineage.revisions.last()
		fact.serviceRunId !in interruptedRunIds && wifiCompleteness.none { it.coversPortableFact(fact) }
	}) {
		return outcome(unverifiableWriter())
	}
	if (selectedWal.any { wal ->
		wal.scope.serviceRunId !in interruptedRunIds && wal.scope.serviceRunId !in generatedRuns &&
			wifiCompleteness.none { it.coversPortableWal(wal) }
	}) {
		return outcome(unverifiableWriter())
	}

	val failures = if (targetOrdinal == null || lane.activationOrdinal > targetOrdinal) {
		emptyList()
	} else
		{
		readDao.terminalFailuresForServiceRuns(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			runIds,
			lane.activationOrdinal - 1L,
			targetOrdinal,
			WifiCapturedPortableFormatV1.MAX_TERMINAL_FAILURES + 1,
		)
	}
	if (failures.size > WifiCapturedPortableFormatV1.MAX_TERMINAL_FAILURES) {
		return outcome(overflow())
	}
	if (failures.isNotEmpty()) {
		return outcome(unverifiableWriter())
	}
	if (hierarchyMaterializing ||
		(targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal)
	) {
		return outcome(ExportPortableCapturedWifiResult.Materializing)
	}
	val factEventIds = selectedLineages.mapTo(mutableSetOf()) { it.revisions.last().sourceEventId }
	val retainedFloor = evidence.retainedFromMs
	val retainedWal = selectedWal.filter { wal ->
		retainedFloor == null || wal.earliestPossibleWallTimeMs() >= retainedFloor
	}
	if (retainedWal.any { wal ->
		wal.scope.serviceRunId !in interruptedRunIds && wal.scope.serviceRunId !in generatedRuns &&
			wal.derivedAggregate != null && wal.evidence.sourceEventId.value !in factEventIds
	}) {
		return outcome(unverifiableWriter())
	}

	val retainedClosureIds = audit.lineages.filter { lineage ->
		retainedFloor == null || lineage.earliestPossibleWallTimeMs >= retainedFloor
	}.mapTo(mutableSetOf(), WifiCapturedLineage::logicalFactId)
	val timeRetained = selectedLineages.filter { lineage ->
		retainedFloor == null || lineage.earliestPossibleWallTimeMs >= retainedFloor
	}
	val retained = timeRetained.filter { lineage ->
		lineage.aggregateOwnerLogicalFactId == null ||
			lineage.aggregateOwnerLogicalFactId in retainedClosureIds
	}
	if (retained.isEmpty() &&
		(selectedLineages.isNotEmpty() || selectedWal.size != retainedWal.size)
	) {
		return outcome(ExportPortableCapturedWifiResult.Unavailable(
			PortableWifiUnavailableReason.RETENTION_LIMIT,
		))
	}

	val retainedByRun = retained.groupBy { it.scope.serviceRunId }
	val selectedFactIds = selectedLineages.mapTo(mutableSetOf(), WifiCapturedLineage::logicalFactId)
	val runsOut = runs.mapNotNull { run ->
		if (run.serviceRunId in generatedRuns) {
			return@mapNotNull null
		}
		val segment = requireNotNull(segmentsById[run.sessionSegmentId])
		val runManifests = manifestsByRun.getValue(run.serviceRunId)
		val bindings = captureByRun.getValue(run.serviceRunId)
		val captured = bindings.isNotEmpty()
		val runLineages = retainedByRun[run.serviceRunId].orEmpty()
		val observations = runLineages.map { lineage ->
			lineage.toPortableObservation(
				audit.aggregateById[lineage.logicalFactId]
					?: throw IllegalArgumentException("Authenticated Wi-Fi aggregate missing"),
				selectedFactIds,
			)
		}.sortedWith(PORTABLE_WIFI_OBSERVATION_ORDER)
		val unfinished = run.serviceRunId in interruptedRunIds
		if (unfinished && observations.isEmpty()) {
			return@mapNotNull null
		}
		val retentionLoss = captured && (
			runLineages.size != selectedLineages.count { it.scope.serviceRunId == run.serviceRunId } ||
			selectedWal.any { wal -> wal.scope.serviceRunId == run.serviceRunId && wal !in retainedWal }
		)
		PortableWifiIntegrity.createRun(
			identity = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.PHYSICAL_RUN,
				run.serviceRunId,
			),
			deletionScopeDigest = PortableWifiDeletionScopeDigest(
				scopeDigestByRun.getValue(run.serviceRunId),
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
			storedZoneIds = runManifests.map(SessionManifestVersionEntity::zoneId).distinct().sorted(),
			captureCoverage = when {
				!captured -> PortableWifiCaptureCoverage.NOT_CAPTURED
				unfinished -> PortableWifiCaptureCoverage.PARTIAL_RUN
				bindings.size == runManifests.size -> PortableWifiCaptureCoverage.WHOLE_RUN
				else -> PortableWifiCaptureCoverage.PARTIAL_RUN
			},
			availability = when {
				!captured -> PortableWifiRunAvailability.NOT_CAPTURED
				observations.isNotEmpty() -> PortableWifiRunAvailability.RETAINED
				else -> PortableWifiRunAvailability.NO_RETAINED_OBSERVATION
			},
			acquisitionCompleteness = if (unfinished) {
				PortableWifiAcquisitionCompleteness.PARTIAL
			} else
				{
				wifiCompleteness.filter { it.serviceRunId == run.serviceRunId }.toPortableCompleteness(captured)
			},
			hasUnresolvedProviderRange = wifiCompleteness.any {
				it.serviceRunId == run.serviceRunId && it.unresolvedSequenceStart != null
			},
			retentionLoss = retentionLoss,
			observations = observations,
		)
	}.sortedWith(PORTABLE_WIFI_RUN_ORDER)

	if (runsOut.isEmpty()) {
		return outcome(ExportPortableCapturedWifiResult.Unavailable(PortableWifiUnavailableReason.ENTRY_NOT_FOUND))
	}
	if (runsOut.all { it.observations.isEmpty() && !it.retentionLoss } &&
		wifiCompleteness.any {
			it.registrationGeneration == 0L || it.stopStatus in PROVIDER_UNAVAILABLE_STATUSES
		}
	) {
		return outcome(ExportPortableCapturedWifiResult.Unavailable(
		PortableWifiUnavailableReason.PROVIDER_UNAVAILABLE,
	))
	}
	val entry = PortableWifiIntegrity.createEntry(
		identity = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.LOGICAL_ENTRY,
			request.logicalTrackingId,
		),
		sessionMode = sessionMode,
		startTimeMs = runsOut.minOf(PortableCapturedWifiRunV1::startTimeMs),
		endTimeMs = runsOut.maxOf(PortableCapturedWifiRunV1::endTimeMs),
		runs = runsOut,
	)
	return ReadLocalPortableCapturedWifiResult.Ready(entry)
}

private fun WifiCapturedLineage.toPortableObservation(
	aggregate: WifiIdentityFreeAggregate,
	selectedFactIds: Set<String>,
): PortableCapturedWifiObservationV1 {
	val fact = revisions.last()
	val portableOwnerId = fact.aggregateOwnerLogicalFactId?.takeIf { it in selectedFactIds }
	val coverageStart = fact.earliestPossibleWallTimeMs()
	val latest = Math.addExact(fact.observedWallTimeMs, fact.wallTimeUncertaintyMs)
	return PortableWifiIntegrity.createObservation(
		identity = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION, fact.logicalFactId),
		semanticRevision = fact.semanticRevision,
		supersedesSemanticRevision = fact.supersedesSemanticRevision,
		aggregateOwnerIdentity = portableOwnerId?.let { ownerId ->
			PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION, ownerId)
		},
		aggregateOwnerSemanticRevision = fact.aggregateOwnerSemanticRevision.takeIf { portableOwnerId != null },
		coverageStartTimeMs = coverageStart,
		observedTimeMs = fact.observedWallTimeMs,
		latestPossibleTimeMs = latest,
		wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
		storedZoneId = fact.storedZoneId,
		availability = PortableWifiAvailability.AVAILABLE,
		resultCompleteness = PortableWifiResultCompleteness.valueOf(fact.coverageCompleteness),
		submittedResultCount = fact.submittedResultCount,
		acceptedResultCount = fact.acceptedResultCount,
		staleResultCount = fact.staleResultCount,
		clockUnverifiableResultCount = fact.clockUnverifiableResultCount,
		malformedResultCount = fact.malformedResultCount,
		observationCount = aggregate.observationCount,
		twoPointFourGhzCount = aggregate.bandMix.twoPointFourGhzCount,
		fiveGhzCount = aggregate.bandMix.fiveGhzCount,
		sixGhzCount = aggregate.bandMix.sixGhzCount,
		otherBandCount = aggregate.bandMix.otherCount,
		strongestSignalDbm = requireNotNull(aggregate.signalQuality).strongestSignalLevelDbm,
		weakestSignalDbm = aggregate.signalQuality.weakestSignalLevelDbm,
		meanSignalDbm = aggregate.signalQuality.signalLevelSumDbm.toDouble() / aggregate.observationCount,
		sourceQualityFlags = fact.qualityFlags,
		sourceQualityConfidence = fact.qualityConfidence,
	)
}

private fun SourceServiceRunEntity.hasExactPortableMembership(
	logicalTrackingId: String,
	segmentsById: Map<Long, SessionSegment>,
): Boolean {
	val segmentId = sessionSegmentId ?: return false
	val segment = segmentsById[segmentId] ?: return false
	return this.logicalTrackingId == logicalTrackingId && segment.logicalTrackingId == logicalTrackingId &&
		segment.serviceRunId == serviceRunId && segment.startTimeMs >= 0L &&
		segment.endTimeMs >= segment.startTimeMs &&
		presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
}

// Malformed saved headers/runs and the selected open-run absence keep their ordered rejection exits.
@Suppress("ReturnCount")
private fun LogicalTrackingSessionEntity.hasPortableShape(
	runs: List<SourceServiceRunEntity>,
	interrupted: Boolean,
): Boolean {
	if (state !in ALL_SESSION_STATES) {
		return false
	}
	val terminal = state in TERMINAL_STATES && !interrupted
	if (!hasPortableSessionHeader(terminal, interrupted)) {
		return false
	}
	if (!runs.all { it.hasPortableRunShape(this, interrupted) }) {
		return false
	}
	if (interrupted) {
		return runs.all { it.state in TERMINAL_STATES }
	}
	val openRuns = runs.filter { it.completedAtMs == null }
	if (terminal) {
		return openRuns.isEmpty() && runs.all { it.state in TERMINAL_STATES }
	}
	val activeRun = openRuns.singleOrNull() ?: return false
	return hasPortableActiveRunPair(activeRun)
}

// One saved session header: scalar/null/state rules only, before any run traversal.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private fun LogicalTrackingSessionEntity.hasPortableSessionHeader(
	terminal: Boolean,
	interrupted: Boolean,
): Boolean {
	if ((!interrupted && (completedAtMs != null) != terminal) || logicalTrackingId.isBlank() ||
        lifecycleRevision <= 0L ||
		desiredPlanRevision <= 0L || rolloutRevision <= 0L || startOrigin.isBlank() ||
		clockDomainId.isBlank() || lifecycleBootId.isNullOrBlank() || lifecycleLeaseGeneration <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L ||
		(cutoffAtMs == null) != (cutoffElapsedNanos == null)
	) {
		return false
	}
	if (terminal && (cutoffAtMs == null || cutoffElapsedNanos == null || currentServiceRunId != null ||
		finalAdmissionOrdinal == null || requireNotNull(finalAdmissionOrdinal) < 0L ||
		requireNotNull(completedAtMs) < requireNotNull(cutoffAtMs))
	) {
		return false
	}
	if (!terminal && !interrupted && (currentServiceRunId.isNullOrBlank() || finalAdmissionOrdinal != null)) {
		return false
	}
	return true
}

// One saved run and its parent stamp; keeping the complete immutable field contract avoids field helpers.
@Suppress("CyclomaticComplexMethod")
private fun SourceServiceRunEntity.hasPortableRunShape(
	session: LogicalTrackingSessionEntity,
	interrupted: Boolean,
): Boolean {
	if (state !in ALL_RUN_STATES) {
		return false
	}
	val restoredRun = interrupted && state == "FAILED" &&
		completionReason == "RESTORED_INTERRUPTED_SESSION"
	val runTerminal = state in TERMINAL_STATES && !restoredRun
	return logicalTrackingId == session.logicalTrackingId && bootId.isNotBlank() &&
		leaseGeneration > 0L && leaseGeneration <= session.lifecycleLeaseGeneration &&
		desiredPlanRevision > 0L && rolloutRevision > 0L && startedAtMs >= 0L &&
		startedElapsedNanos >= 0L && startOrigin.isNotBlank() &&
		!startDeliveryToken.isNullOrBlank() && startCommandGeneration > 0L &&
		preparedManifestRevision > 0L && preparedIntentRevision > 0L &&
		runtimeAcknowledgement.isNotBlank() && androidDeliveryState.isNotBlank() &&
		androidDeliveryUpdatedAtMs != null &&
		(restoredRun || ((completedAtMs != null) == runTerminal &&
			(completedAtMs != null) == !completionReason.isNullOrBlank())) &&
		completedAtMs?.let { it >= startedAtMs } != false &&
		presentationAcknowledgement in setOf(
			SourceServiceRunEntity.PRESENTATION_PENDING,
			SourceServiceRunEntity.PRESENTATION_QUIESCED,
		) && (presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_QUIESCED ||
			presentationAcknowledgedAtMs != null)
}

// The finite saved session/run state pair and its cutoff fields are one immutable correspondence.
@Suppress("CyclomaticComplexMethod")
private fun LogicalTrackingSessionEntity.hasPortableActiveRunPair(
	activeRun: SourceServiceRunEntity,
): Boolean {
	val pairValid = state in ADMISSION_SESSION_STATES && activeRun.state in ADMISSION_RUN_STATES ||
		state == "ACTIVE" && activeRun.state == "STOPPING" ||
		state == "STOPPING" && activeRun.state == "STOPPING"
	val cutoffValid = if (state == "STOPPING" && activeRun.state == "STOPPING") {
		cutoffAtMs != null && cutoffElapsedNanos != null
	} else {
		cutoffAtMs == null && cutoffElapsedNanos == null
	}
	return pairValid && cutoffValid && currentServiceRunId == activeRun.serviceRunId &&
		lifecycleLeaseGeneration == activeRun.leaseGeneration && lifecycleBootId == activeRun.bootId
}

private fun isPortableWifiCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == WIFI_SOURCE && source.purpose == CAPTURE_PURPOSE && source.persistenceEligible

private fun SessionManifestSourceEntity.isExactPortableWifiWriter(): Boolean =
	isPortableWifiCaptureMembership(this) &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS &&
		writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
		writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
		writerBindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION

@Suppress("LongParameterList")
private fun ProviderRegistrationGenerationEntity.hasPortableRegistrationShape(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	authority: WifiPortableAuthoritySnapshot,
): Boolean {
	val acceptedWall = acceptedAtMs ?: return false
	val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
	val recordedBootId = authority.manifestsByRun[run.serviceRunId].orEmpty()
		.singleOrNull { it.manifestRevision == run.preparedManifestRevision }?.effectiveBootId ?: return false
	return authority.hasExactPortableRegistrationPlans(this, run) &&
		hasPortableRegistrationIdentity(row, recordedBootId) &&
		hasPortableRegistrationStatus() && hasPortableRegistrationTimes(acceptedWall, acceptedElapsed)
}

private fun WifiPortableAuthoritySnapshot.hasExactPortableRegistrationPlans(
	registration: ProviderRegistrationGenerationEntity,
	run: SourceServiceRunEntity,
): Boolean {
	val actions = exactPortableActions(registration, run) ?: return false
	val plans = actions.mapNotNull { plansByRevision[it.desiredPlanRevision] }
	return plans.size == actions.size && plans.none {
		it.physicalConfigurationFingerprint() != registration.physicalConfigurationFingerprint
	}
}

private fun ProviderRegistrationGenerationEntity.hasPortableRegistrationIdentity(
	row: SourceSessionCompletenessEntity,
	recordedBootId: String,
): Boolean = sourceKind == WIFI_SOURCE && registrationGeneration == row.registrationGeneration &&
	sourceInstanceId == row.sourceInstanceId && ownerScope == "source-broker:$WIFI_SOURCE" &&
	clockDomainId == recordedBootId && providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
	!providerProcessIncarnationId.isNullOrBlank() && captureCallbackBarrierAuthorizationRevision > 0L

private fun ProviderRegistrationGenerationEntity.hasPortableRegistrationStatus(): Boolean = when (status) {
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
		retiredAtMs == null && retiredElapsedRealtimeNanos == null && failureCode == null
	ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
		hasPortableRetirementBoundary() && !failureCode.isNullOrBlank()
	ProviderRegistrationGenerationEntity.STATUS_RETIRED ->
		hasPortableRetirementBoundary() && failureCode?.isNotBlank() != false
	else -> false
}

private fun ProviderRegistrationGenerationEntity.hasPortableRegistrationTimes(
	acceptedWall: Long,
	acceptedElapsed: Long,
): Boolean = reservedAtMs <= acceptedWall && reservedElapsedRealtimeNanos <= acceptedElapsed &&
	retiredAtMs?.let { it >= acceptedWall } != false &&
	retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false

private fun WifiPortableAuthoritySnapshot.exactPortableActions(
	registration: ProviderRegistrationGenerationEntity,
	run: SourceServiceRunEntity,
): List<LifecycleDesiredActionEntity>? {
	val manifests = manifestsByRun[run.serviceRunId].orEmpty()
	val bindings = manifests.flatMap { manifest ->
		sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision]
			.orEmpty().filter(::isPortableWifiCaptureMembership)
	}
	val captureMembers = authorizationsByGeneration[registration.registrationGeneration].orEmpty()
		.flatMap(SourceAuthorizationSnapshot::authorizedMembers)
		.filter { member ->
			member.purpose == CAPTURE_PURPOSE && member.logicalTrackingId == run.logicalTrackingId &&
				member.serviceRunId == run.serviceRunId
		}
	val captureManifestRevisions = captureMembers.mapNotNull(SourceAuthorizationEntity::manifestRevision).toSet()
	if (captureManifestRevisions.isEmpty()) {
		return null
	}
	val actions = actionsByRun[run.serviceRunId].orEmpty().filter { action ->
		action.authenticatesPortableCompleteness(run, manifests, bindings, registration)
	}
	if (actions.map(LifecycleDesiredActionEntity::manifestRevision).toSet() != captureManifestRevisions ||
		captureManifestRevisions.any { revision -> actions.count { it.manifestRevision == revision } != 1 } ||
		captureMembers.any { member -> actions.none { it.matchesPortableCaptureMember(member) } }
	) {
		return null
	}
	return actions
}

// One immutable authorization-member/demand correspondence, including its finite saved status/null shape.
@Suppress("CyclomaticComplexMethod")
private fun SourceAuthorizationEntity.hasExactPortableMember(demand: SourceDemandEntity?): Boolean {
	if (isDenyAll) {
		return demand == null && memberId == SourceBrokerAuthorization.DENY_ALL_MEMBER_ID &&
		purposeEligibilityMask == 0L && !persistenceEligible
	}
	if (demand == null) {
		return false
	}
	val demandLifecycleValid = when (demand.status) {
		SourceDemandEntity.STATUS_ACTIVE, SourceDemandEntity.STATUS_BLOCKED ->
			demand.retireBootId == null && demand.retireElapsedRealtimeNanos == null && demand.retiredAtMs == null
		SourceDemandEntity.STATUS_RETIRING, SourceDemandEntity.STATUS_RETIRED ->
			demand.retireBootId != null &&
				demand.retireElapsedRealtimeNanos?.let { it >= demand.requestedElapsedRealtimeNanos } == true &&
				demand.retiredAtMs?.let { it >= demand.requestedAtMs } == true
		else -> false
	}
	return memberId == SourceBrokerAuthorization.memberId(demand.demandId) &&
		sourceKind == demand.sourceKind && demand.sourceKind == WIFI_SOURCE &&
		demandId == demand.demandId && consumerId == demand.consumerId && purpose == demand.purpose &&
		sourcePolicyRevision == demand.sourcePolicyRevision && consentEpoch == demand.consentEpoch &&
		persistenceEligible == demand.persistenceEligible && logicalTrackingId == demand.logicalTrackingId &&
		serviceRunId == demand.serviceRunId && manifestRevision == demand.manifestRevision &&
		lifecycleLeaseGeneration == demand.lifecycleLeaseGeneration &&
		demand.requestedAtMs <= effectiveWallTimeMs && demand.requestedBootId == effectiveBootId &&
		demand.requestedElapsedRealtimeNanos <= effectiveElapsedRealtimeNanos && demandLifecycleValid
}

private fun SourceAuthorizationSnapshot.hasExactPortableFingerprint(
	demandsById: Map<String, SourceDemandEntity>,
): Boolean {
	if (members.distinctBy(SourceAuthorizationEntity::memberId).size != members.size) {
		return false
	}
	val demands = authorizedMembers.mapNotNull { member -> member.demandId?.let(demandsById::get) }
	if (demands.size != authorizedMembers.size ||
        demands.distinctBy(SourceDemandEntity::demandId).size != demands.size) {
		return false
	}
	val expected = runCatching {
		SourceBrokerAuthorization.rows(
			members.first().sourceKind,
			members.first().registrationGeneration,
			authorizationRevision,
			demands,
			effectiveBootId,
			effectiveElapsedRealtimeNanos,
			members.first().effectiveWallTimeMs,
		)
	}.getOrNull() ?: return false
	return expected.sortedBy(SourceAuthorizationEntity::memberId) ==
		members.sortedBy(SourceAuthorizationEntity::memberId) &&
		members.all { member -> member.hasExactPortableMember(member.demandId?.let(demandsById::get)) }
}

private fun List<SourceAuthorizationSnapshot>.hasExactPortableCaptureRetirement(
	registration: ProviderRegistrationGenerationEntity,
	run: SourceServiceRunEntity,
	authority: WifiPortableAuthoritySnapshot,
	perReading: Boolean = false,
	sharedRetirement: SourceRunRetirementEntity? = null,
): Boolean {
	if (!hasExactPortableAuthorizationHistory(registration, authority)) {
		return false
	}
	val ordered = sortedWith(compareBy(
		SourceAuthorizationSnapshot::effectiveElapsedRealtimeNanos,
		SourceAuthorizationSnapshot::authorizationRevision,
	))
	if (ordered.map(SourceAuthorizationSnapshot::authorizationRevision).distinct().size != ordered.size) {
		return false
	}
	val allCaptureDemands = ordered.selectedPortableCaptureDemands(registration, run, authority, perReading)
		?: return false
	return ordered.hasExactPortableDemandClosings(allCaptureDemands, registration) &&
		ordered.hasPortableSharedRetirementIfPresent(allCaptureDemands, sharedRetirement) &&
		ordered.hasExactPortableCallbackBarrier(registration, perReading) &&
		ordered.supportsPortableContinuationContracts(registration, authority) &&
		ordered.hasExactPortableActiveContinuation(registration, perReading, authority)
}

/** Authenticate each saved snapshot before its authorization timeline is ordered. */
private fun List<SourceAuthorizationSnapshot>.hasExactPortableAuthorizationHistory(
	registration: ProviderRegistrationGenerationEntity,
	authority: WifiPortableAuthoritySnapshot,
): Boolean {
	if (isEmpty() || any snapshotLoop@{ snapshot ->
			!snapshot.hasPortableRegistrationClock(registration) ||
				!snapshot.hasExactPortableFingerprint(authority.demandsById) ||
				snapshot.authorizedMembers.any memberLoop@{ member ->
					val demand = member.demandId?.let(authority.demandsById::get)
						?: return@memberLoop true
					!authority.authenticatesHistoricalDemand(demand, snapshot)
				}
		}
	) {
		return false
	}
	return true
}

/** Resolve the capture demands selected by this export before examining their closing snapshots. */
private fun List<SourceAuthorizationSnapshot>.selectedPortableCaptureDemands(
	registration: ProviderRegistrationGenerationEntity,
	run: SourceServiceRunEntity,
	authority: WifiPortableAuthoritySnapshot,
	perReading: Boolean,
): List<SourceDemandEntity>? {
	val allCaptureDemands = flatMap(SourceAuthorizationSnapshot::authorizedMembers)
		.filter { it.purpose == CAPTURE_PURPOSE && (!perReading ||
			it.logicalTrackingId == run.logicalTrackingId && it.serviceRunId == run.serviceRunId) }
		.mapNotNull { it.demandId?.let(authority.demandsById::get) }
		.distinctBy(SourceDemandEntity::demandId)
	if (allCaptureDemands.isEmpty() || allCaptureDemands.any { demand ->
		!demand.hasExactPortableCaptureBinding(registration, authority)
	}) {
		return null
	}
	if (allCaptureDemands.none { demand ->
		demand.logicalTrackingId == run.logicalTrackingId && demand.serviceRunId == run.serviceRunId
	}) {
		return null
	}
	return allCaptureDemands
}

/** Each selected demand closes once and never reappears in a later saved snapshot. */
private fun List<SourceAuthorizationSnapshot>.hasExactPortableDemandClosings(
	allCaptureDemands: List<SourceDemandEntity>,
	registration: ProviderRegistrationGenerationEntity,
): Boolean {
	if (allCaptureDemands.any { demand ->
		val lastCapture = this.lastOrNull { snapshot ->
			snapshot.authorizedMembers.any { it.demandId == demand.demandId }
		} ?: return@any true
		val closing = this.firstOrNull { snapshot ->
			PORTABLE_WIFI_AUTHORIZATION_ORDER.compare(snapshot, lastCapture) > 0 &&
				snapshot.authorizedMembers.none { it.demandId == demand.demandId }
		} ?: return@any true
		!demand.closedAtPortableSnapshot(registration, closing) ||
			this.dropWhile { it != closing }.any { snapshot ->
				snapshot.authorizedMembers.any { it.demandId == demand.demandId }
			}
	}) {
		return false
	}
	return true
}

/** Compare the final selected closing with the acknowledged shared-owner drain cutoff. */
@Suppress("ReturnCount") // Missing retirement fields or either closing snapshot retain their ordered rejection exits.
private fun List<SourceAuthorizationSnapshot>.hasExactPortableSharedRetirement(
	allCaptureDemands: List<SourceDemandEntity>,
	sharedRetirement: SourceRunRetirementEntity,
): Boolean {
	// Earlier policy demands can close sooner; the final selected demand must close at the drain cutoff.
	if (allCaptureDemands.any { !it.retiredWithinPortableCutoff(sharedRetirement) }) {
		return false
	}
	val selectedDemandIds = allCaptureDemands.mapTo(mutableSetOf(), SourceDemandEntity::demandId)
	val lastSelectedCapture = this.lastOrNull { snapshot ->
		snapshot.authorizedMembers.any { member -> member.demandId in selectedDemandIds }
	} ?: return false
	val finalClosing = this.firstOrNull { snapshot ->
		PORTABLE_WIFI_AUTHORIZATION_ORDER.compare(snapshot, lastSelectedCapture) > 0 &&
			snapshot.authorizedMembers.none { member -> member.demandId in selectedDemandIds }
	} ?: return false
	if (finalClosing.effectiveElapsedRealtimeNanos != sharedRetirement.cutoffElapsedRealtimeNanos ||
		finalClosing.members.first().effectiveWallTimeMs != sharedRetirement.cutoffWallTimeMs ||
		allCaptureDemands.maxOf { requireNotNull(it.retireElapsedRealtimeNanos) } !=
			sharedRetirement.cutoffElapsedRealtimeNanos
	) {
		return false
	}
	return true
}

private fun List<SourceAuthorizationSnapshot>.hasExactPortableCallbackBarrier(
	registration: ProviderRegistrationGenerationEntity,
	perReading: Boolean,
): Boolean {
	val lastCapture = this.lastOrNull { snapshot ->
		snapshot.authorizedMembers.any { it.purpose == CAPTURE_PURPOSE }
	} ?: return false
	if (!perReading && lastCapture.authorizationRevision != registration.captureCallbackBarrierAuthorizationRevision) {
		return false
	}
	return true
}

/** Non-capture consumers must retain a compatible saved physical registration contract. */
private fun List<SourceAuthorizationSnapshot>.supportsPortableContinuationContracts(
	registration: ProviderRegistrationGenerationEntity,
	authority: WifiPortableAuthoritySnapshot,
): Boolean {
	val registrationPlans = authority.plansForRegistration(registration)
	if (registrationPlans.isEmpty() || this.flatMap(SourceAuthorizationSnapshot::authorizedMembers)
		.filter { it.purpose != CAPTURE_PURPOSE }
		.mapNotNull { it.demandId?.let(authority.demandsById::get) }
		.distinctBy(SourceDemandEntity::demandId)
		.any { demand ->
			registrationPlans.none { authority.planSatisfiesDemand(it, demand) }
		}
	) {
		return false
	}
	return true
}

/** An active legacy owner continues only for the explicitly supported surviving saved demands. */
private fun List<SourceAuthorizationSnapshot>.hasExactPortableActiveContinuation(
	registration: ProviderRegistrationGenerationEntity,
	perReading: Boolean,
	authority: WifiPortableAuthoritySnapshot,
): Boolean {
	if (!perReading && registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE) {
		val continuation = this.last().authorizedMembers
		if (continuation.isEmpty() || continuation.any { member ->
			member.purpose !in setOf(
				SourceBrokerPurpose.CONTROL_AUTOSTART,
				SourceBrokerPurpose.CONTROL_CONTINUATION,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
			) || member.demandId?.let(authority.demandsById::get)?.status != SourceDemandEntity.STATUS_ACTIVE
		}) {
			return false
		}
	}
	return true
}

// Compare one saved retirement/completeness/run stamp; no timeline is loaded or reconstructed here.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceRunRetirementEntity.matchesPortableWifiSharedSettlement(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
): Boolean = logicalTrackingId == row.logicalTrackingId && serviceRunId == row.serviceRunId &&
	sourceKind == WIFI_SOURCE && sourceInstanceId == row.sourceInstanceId &&
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

@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
private fun SourceDemandEntity.hasExactPortableCaptureBinding(
	registration: ProviderRegistrationGenerationEntity,
	authority: WifiPortableAuthoritySnapshot,
): Boolean {
	val runId = serviceRunId ?: return false
	val logicalId = logicalTrackingId ?: return false
	val manifestRevision = manifestRevision ?: return false
	val lease = lifecycleLeaseGeneration ?: return false
	return authority.authenticatesPortableCaptureDemand(this, runId, logicalId, manifestRevision, lease, registration)
}

private fun WifiPortableAuthoritySnapshot.authenticatesPortableCaptureDemand(
	demand: SourceDemandEntity,
	runId: String,
	logicalId: String,
	manifestRevision: Long,
	lease: Long,
	registration: ProviderRegistrationGenerationEntity,
): Boolean {
	val run = runsById[runId] ?: return false
	val manifest = manifestsByRun[runId].orEmpty()
		.singleOrNull { it.manifestRevision == manifestRevision } ?: return false
	val binding = sourcesByManifest[logicalId to manifestRevision].orEmpty()
		.singleOrNull { it.isExactPortableWifiWriter() } ?: return false
	return plansByRevision[manifest.acquisitionPlanRevision]?.let { plan ->
		demand.hasPortableCaptureIdentity(logicalId, session.logicalTrackingId) &&
			run.hasPortableDemandMembership(logicalId, lease) &&
			demand.hasPortableManifestBinding(manifest, binding, runId, logicalId, manifestRevision) &&
			plan.physicalConfigurationFingerprint() == registration.physicalConfigurationFingerprint &&
			planSatisfiesDemand(plan, demand)
	} == true
}

private fun SourceDemandEntity.hasPortableCaptureIdentity(logicalId: String, selectedLogicalId: String): Boolean =
	sourceKind == WIFI_SOURCE && purpose == CAPTURE_PURPOSE && persistenceEligible &&
		consumerId == "session:$logicalId" && logicalId == selectedLogicalId

private fun SourceServiceRunEntity.hasPortableDemandMembership(logicalId: String, lease: Long): Boolean =
	logicalTrackingId == logicalId && SessionManifestIntegrity.hasRetainedLeaseGeneration(this, lease)

private fun SourceDemandEntity.hasPortableManifestBinding(
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
	runId: String,
	logicalId: String,
	savedManifestRevision: Long,
): Boolean = manifest.logicalTrackingId == logicalId && manifest.serviceRunId == runId &&
	requestedBootId == manifest.effectiveBootId && binding.manifestRevision == savedManifestRevision &&
	binding.consentEpoch == consentEpoch && binding.qosCode == qosCode &&
	manifest.sourcePolicyRevision == sourcePolicyRevision

private fun SourceSessionCompletenessEntity.hasExactPortableTail(
	settlements: Map<Long, WifiPortableWalSettlement>,
): Boolean {
	val ordinal = lastAdmissionOrdinal
	if (ordinal == null) {
		return lastSourceSequence == null
	}
	if (lastSourceSequence == null) {
		return false
	}
	val settlement = settlements[ordinal] ?: return false
	val scopes = settlement.captureScopes
	return settlement.sourceInstanceId == sourceInstanceId &&
		settlement.registrationGeneration == registrationGeneration &&
		(scopes.isEmpty() || WifiCapturedRunScope(logicalTrackingId, serviceRunId) in scopes)
}

@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
private fun LifecycleDesiredActionEntity.authenticatesPortableCompleteness(
	run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
	bindings: List<SessionManifestSourceEntity>,
	registration: ProviderRegistrationGenerationEntity,
): Boolean {
	val manifest = manifests.singleOrNull { it.manifestRevision == manifestRevision } ?: return false
	val binding = bindings.singleOrNull { it.manifestRevision == manifestRevision } ?: return false
	val acknowledgedWall = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	val acceptedWall = registration.acceptedAtMs ?: return false
	val acceptedElapsed = registration.acceptedElapsedRealtimeNanos ?: return false
	return hasPortableAcceptedActionShape() && hasPortableActionMembership(run, manifest, binding) &&
		hasPortableActionRegistration(run, manifest, registration) &&
		hasPortableActionTimes(manifest, acknowledgedWall, acknowledgedElapsed, acceptedWall, acceptedElapsed)
}

private fun LifecycleDesiredActionEntity.hasPortableAcceptedActionShape(): Boolean =
	actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
		desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
		failureCode == null && retryTrigger == null

private fun LifecycleDesiredActionEntity.hasPortableActionMembership(
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
): Boolean = logicalTrackingId == run.logicalTrackingId && serviceRunId == run.serviceRunId &&
	desiredPlanRevision == manifest.acquisitionPlanRevision && sourcePolicyRevision == manifest.sourcePolicyRevision &&
	consentEpoch == binding.consentEpoch && startOrigin == manifest.startOrigin &&
	(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin)

private fun LifecycleDesiredActionEntity.hasPortableActionRegistration(
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	registration: ProviderRegistrationGenerationEntity,
): Boolean = bootId == manifest.effectiveBootId && bootId == registration.clockDomainId &&
	SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) &&
	sourceInstanceId == registration.sourceInstanceId && registrationGeneration == registration.registrationGeneration

private fun LifecycleDesiredActionEntity.hasPortableActionTimes(
	manifest: SessionManifestVersionEntity,
	acknowledgedWall: Long,
	acknowledgedElapsed: Long,
	acceptedWall: Long,
	acceptedElapsed: Long,
): Boolean = requestedAtMs == manifest.effectiveWallTimeMs &&
	requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
	acknowledgedWall >= requestedAtMs && acknowledgedElapsed >= requestedElapsedRealtimeNanos &&
	acceptedWall <= acknowledgedWall && acceptedElapsed <= acknowledgedElapsed

private fun SessionManifestVersionEntity.hasExactPortableHeader(
	header: com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity?,
): Boolean = header != null && header.revision == acquisitionPlanRevision &&
	header.sourcePolicyRevision == sourcePolicyRevision && header.planId.isNotBlank() &&
	header.createdAtMs >= 0L

@Suppress("LongParameterList")
private fun SessionManifestVersionEntity.hasExactPortableWifiIntent(
	binding: SessionManifestSourceEntity,
	policy: com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity?,
	consent: com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity?,
	header: com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity?,
	desired: com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity?,
	planCodec: SourcePlanCodec,
): Boolean {
	if (policy == null || consent == null || header == null || desired == null) {
		return false
	}
	if (desired.portableWifiPlan(binding, this, planCodec) == null) {
		return false
	}
	return policy.matchesPortableWifiIntent(this, binding) &&
		consent.matchesPortableWifiIntent(this, binding, policy) && hasExactPortableHeader(header)
}

private fun com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity.portableWifiPlan(
	binding: SessionManifestSourceEntity,
	manifest: SessionManifestVersionEntity,
	planCodec: SourcePlanCodec,
): WifiPlan? {
	if (!binding.isExactPortableWifiWriter() || payload.size > MAX_PLAN_PAYLOAD_BYTES ||
		!hasValidPortableWifiZone(manifest.zoneId)
	) {
		return null
	}
	val plan = runCatching { planCodec.decode(payload) as? WifiPlan }.getOrNull() ?: return null
	val encoded = runCatching { planCodec.encode(plan) }.getOrNull() ?: return null
	return plan.takeIf { hasPortableEncodedPlan(manifest, plan, encoded) }
}

private fun com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity.hasPortableEncodedPlan(
	manifest: SessionManifestVersionEntity,
	plan: WifiPlan,
	encoded: com.adsamcik.tracker.tracker.source.coordinator.EncodedSourcePlan,
): Boolean = payloadVersion == PLAN_PAYLOAD_VERSION && revision == manifest.acquisitionPlanRevision &&
	sourceKind == WIFI_SOURCE && encoded.bytes.contentEquals(payload) && encoded.checksum == payloadChecksum &&
	plan.revision == manifest.acquisitionPlanRevision && plan.hasPortableShape()

private fun com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity.matchesPortableWifiIntent(
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
): Boolean = sourceKind == WIFI_SOURCE && policyRevision == manifest.sourcePolicyRevision && enabled &&
	capturePersistenceEligible && qosCode == binding.qosCode && captureConsentEpoch == binding.consentEpoch &&
	effectiveBootId == manifest.effectiveBootId &&
	effectiveElapsedRealtimeNanos in 0L..manifest.effectiveElapsedRealtimeNanos &&
	effectiveWallTimeMs >= 0L && changeReason.isNotBlank()

private fun com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity.matchesPortableWifiIntent(
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
	policy: com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity,
): Boolean = sourceKind == WIFI_SOURCE && purpose == CAPTURE_PURPOSE && epoch == binding.consentEpoch &&
	policyRevision <= policy.policyRevision && eligible && persistenceEligible &&
	effectiveBootId == manifest.effectiveBootId &&
	effectiveElapsedRealtimeNanos in 0L..manifest.effectiveElapsedRealtimeNanos &&
	effectiveWallTimeMs >= 0L && changeReason.isNotBlank()

private fun WifiPlan.hasPortableShape(): Boolean = revision > 0L && mode in setOf(
	WifiMode.BROADCAST_DRIVEN,
	WifiMode.ACTIVE_ATTEMPTS,
) && minimumAttemptIntervalMs >= 0L && maximumAcceptableResultAgeMs >= 0L &&
	unchangedResultDedupeWindowMs >= 0L && backoff.initialDelayMs >= 0L &&
	backoff.maximumDelayMs >= backoff.initialDelayMs && backoff.multiplier.isFinite() &&
	backoff.multiplier >= 1.0 && physicalConfigurationFingerprint().isNotBlank()

// One saved lane row: finite ACTIVE/RETIRED field shape without timeline reconstruction or current policy.
@Suppress("CyclomaticComplexMethod")
private fun SourceProductProjectionLaneEntity.isExactPortableWifiLane(): Boolean =
	sourceKind == WIFI_SOURCE && bindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION &&
		projectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
		projectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
		captureModeMask > 0L && captureModeMask and ALL_CAPTURE_MASK.inv() == 0L &&
		productStage in PRODUCT_STAGES && activatedRolloutRevision > 0L && activationOrdinal > 0L &&
		contiguousAdmissionOrdinal >= activationOrdinal - 1L && installedAtMs >= 0L &&
		updatedAtMs >= installedAtMs && (terminalDisposition == null) == (terminalAtMs == null) &&
		when (status) {
		SourceProductProjectionLaneEntity.STATUS_ACTIVE -> captureAdmissionCutoffOrdinal == null &&
			terminalDisposition == null && terminalAtMs == null && retentionRequired
		SourceProductProjectionLaneEntity.STATUS_RETIRED -> captureAdmissionCutoffOrdinal != null &&
			requireNotNull(captureAdmissionCutoffOrdinal) >= activationOrdinal - 1L &&
			contiguousAdmissionOrdinal == requireNotNull(captureAdmissionCutoffOrdinal) &&
			terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
			terminalAtMs?.let { it >= installedAtMs && updatedAtMs >= it } == true && !retentionRequired
		else -> false
	}

private fun List<SourceSessionCompletenessEntity>.hasValidPortableWifiShape(
	logicalTrackingId: String,
	capturedRunIds: Set<String>,
): Boolean = groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, unordered) ->
	val rows = unordered.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
	runId in capturedRunIds &&
		rows.distinctBy(SourceSessionCompletenessEntity::registrationGeneration).size == rows.size &&
		rows.all { it.hasPortableCompletenessIdentity(logicalTrackingId) && it.hasPortableSequenceBounds() &&
			it.hasPortableStopOutcome() && it.hasPortableZeroRegistration(rows.size) }
}

private fun SourceSessionCompletenessEntity.hasPortableCompletenessIdentity(logicalId: String): Boolean =
	logicalTrackingId == logicalId && sourceInstanceId.isNotBlank() && registrationGeneration >= 0L

private fun SourceSessionCompletenessEntity.hasPortableSequenceBounds(): Boolean =
	(lastAdmissionOrdinal == null) == (lastSourceSequence == null) &&
		lastAdmissionOrdinal?.let { it > 0L } != false && lastSourceSequence?.let { it >= 0L } != false &&
		(unresolvedSequenceStart == null) == (unresolvedSequenceEnd == null) &&
		unresolvedSequenceStart?.let { start -> start >= 0L && requireNotNull(unresolvedSequenceEnd) >= start } != false

private fun SourceSessionCompletenessEntity.hasPortableStopOutcome(): Boolean =
	providerCoverage in setOf(WIFI_PROVIDER_COVERAGE, COMPLETE_PROVIDER_COVERAGE) &&
		stopStatus in STOP_STATUS_VALUES && updatedAtMs >= 0L && hasValidStopShape()

private fun SourceSessionCompletenessEntity.hasPortableZeroRegistration(rowCount: Int): Boolean =
	registrationGeneration != 0L || rowCount == 1 && lastAdmissionOrdinal == null && lastSourceSequence == null &&
		hasPortableUnownedOutcome()

private fun SourceSessionCompletenessEntity.hasPortableUnownedOutcome(): Boolean = when (stopStatus) {
	"COMPLETE" -> sourceInstanceId == "not-owned-wifi" && appDrainComplete && unresolvedSequenceStart == null
	"PROVIDER_FAILED" -> unresolvedSequenceStart == null && hasPortableUnownedFailure()
	"TIMED_OUT" -> sourceInstanceId == "unresolved-wifi" && !appDrainComplete && unresolvedSequenceStart == null
	else -> false
}

private fun SourceSessionCompletenessEntity.hasPortableUnownedFailure(): Boolean =
	(sourceInstanceId == "unresolved-wifi" && !appDrainComplete) ||
		(sourceInstanceId == "unavailable-wifi" && appDrainComplete)

private fun SourceSessionCompletenessEntity.hasValidStopShape(): Boolean {
	val gap = unresolvedSequenceStart != null
	if (gap && (appDrainComplete || stopStatus != "TIMED_OUT")) {
		return false
	}
	return when (stopStatus) {
		"COMPLETE" -> appDrainComplete && !gap
		"TIMED_OUT" -> !appDrainComplete
		"PERMISSION_LOST", "PROVIDER_FAILED", "PROCESS_RESTARTED" -> !gap
		else -> false
	}
}

private fun WifiCapturedFactRevisionEntity.earliestPossibleWallTimeMs(): Long {
	return requireNotNull(CanonicalRetentionBounds.wifiCoverage(
		coverageEndWallTimeMs = observedWallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		coverageStartElapsedRealtimeNanos = coverageIntervalStartNanos,
		coverageEndElapsedRealtimeNanos = coverageIntervalEndNanos,
	))
}

private fun AuthenticatedWifiWal.earliestPossibleWallTimeMs(): Long {
	val measured = derivedAggregate?.productEffect
	return requireNotNull(CanonicalRetentionBounds.wifiCoverage(
		coverageEndWallTimeMs = measured?.observedWallTimeMs ?: evidence.clock.observedWallTimeMs,
		wallTimeUncertaintyMs = measured?.wallTimeUncertaintyMs ?: evidence.clock.wallTimeUncertaintyMs,
		coverageStartElapsedRealtimeNanos = measured?.coverage?.providerIntervalStartElapsedRealtimeNanos
			?: evidence.clock.observedIntervalStartElapsedRealtimeNanos,
		coverageEndElapsedRealtimeNanos = measured?.coverage?.providerIntervalEndElapsedRealtimeNanos
			?: evidence.clock.observedElapsedRealtimeNanos,
	))
}

private fun List<SourceSessionCompletenessEntity>.toPortableCompleteness(
	captured: Boolean,
): PortableWifiAcquisitionCompleteness = when {
	!captured || isEmpty() -> PortableWifiAcquisitionCompleteness.UNKNOWN
	all { it.stopStatus == "COMPLETE" && it.appDrainComplete &&
		it.providerCoverage == COMPLETE_PROVIDER_COVERAGE && it.unresolvedSequenceStart == null } ->
		PortableWifiAcquisitionCompleteness.COMPLETE
	else -> PortableWifiAcquisitionCompleteness.PARTIAL
}

private fun SourceEvidenceState.hasValidPortableShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		deletedSourceEventHighWaterOrdinal >= 0L && retainedFromMs?.let { it >= 0L } != false &&
		updatedAtMs >= 0L

private fun hasValidPortableWifiZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private fun outcome(value: ExportPortableCapturedWifiResult) =
	ReadLocalPortableCapturedWifiResult.Outcome(value)
private fun unverifiable(reason: PortableWifiUnverifiableReason) =
	ExportPortableCapturedWifiResult.Unverifiable(reason)
private fun unverifiableFact() = unverifiable(PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE)
private fun unverifiableMembership() =
	unverifiable(PortableWifiUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE)
private fun unverifiableAttribution() =
	unverifiable(PortableWifiUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
private fun unverifiableWriter() =
	unverifiable(PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE)
private fun overflow() = unverifiable(PortableWifiUnverifiableReason.DEPENDENCY_OVERFLOW)

private val PORTABLE_LIMITS = WifiCapturedMaintenanceLimits(
	maximumRevisions = WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY * 4,
	maximumLogicalFacts = WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY,
	maximumCursors = WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY,
	maximumDeletionGenerations = WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY,
	maximumWalEvents = WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY,
)
private val PORTABLE_WIFI_AUTHORIZATION_ORDER = compareBy<SourceAuthorizationSnapshot>(
	SourceAuthorizationSnapshot::effectiveElapsedRealtimeNanos,
).thenBy(SourceAuthorizationSnapshot::authorizationRevision)
private const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
private const val MANUAL_CAPTURE_MASK = 1L
private const val AUTOMATIC_CAPTURE_MASK = 2L
private const val ALL_CAPTURE_MASK = MANUAL_CAPTURE_MASK or AUTOMATIC_CAPTURE_MASK
private const val PLAN_PAYLOAD_VERSION = 1
private const val MAX_PLAN_PAYLOAD_BYTES = 1_024
private val TERMINAL_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
private val ALL_SESSION_STATES = TERMINAL_STATES + setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
private val ALL_RUN_STATES = ALL_SESSION_STATES
private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private val PROVIDER_UNAVAILABLE_STATUSES = setOf("PERMISSION_LOST", "PROVIDER_FAILED")
private val VALID_SOURCE_CODES = SourceKind.entries.mapTo(mutableSetOf(), SourceKind::stableCode)
private const val WIFI_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
private const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
private val STOP_STATUS_VALUES = setOf(
	"COMPLETE",
	"TIMED_OUT",
	"PERMISSION_LOST",
	"PROVIDER_FAILED",
	"PROCESS_RESTARTED",
)
private val PRODUCT_STAGES = setOf(
	SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
	SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
)

private fun SourceServiceRunEntity.matchesPortableDesiredManifest(manifest: SessionManifestVersionEntity): Boolean =
	desiredPlanRevision == manifest.acquisitionPlanRevision && rolloutRevision == manifest.rolloutRevision

private fun LogicalTrackingSessionEntity.matchesPortableDesiredManifest(
	manifest: SessionManifestVersionEntity,
): Boolean =
	currentManifestRevision == manifest.manifestRevision && desiredPlanRevision == manifest.acquisitionPlanRevision &&
		rolloutRevision == manifest.rolloutRevision

private fun WifiCapturedRunScope.hasPortableSelectedMembership(logicalId: String, runIds: List<String>): Boolean =
	(logicalTrackingId == logicalId) == (serviceRunId in runIds)

private fun LogicalTrackingSessionEntity.permitsPortableTail(
	ordinal: Long,
	wal: List<AuthenticatedWifiWal>,
): Boolean = !(wal.any { it.evidence.payloadVersion != WIFI_WAL_PAYLOAD_VERSION } &&
	finalAdmissionOrdinal?.let { ordinal > it } == true)

private fun SourceProductProjectionLaneEntity.containsPortableTail(ordinal: Long): Boolean =
	ordinal >= activationOrdinal && captureAdmissionCutoffOrdinal?.let { it < ordinal } != true

private fun SourceSessionCompletenessEntity.coversPortableFact(fact: WifiCapturedFactRevisionEntity): Boolean =
	serviceRunId == fact.serviceRunId && sourceInstanceId == fact.sourceInstanceId &&
	registrationGeneration == fact.registrationGeneration && (fact.payloadVersion == WIFI_WAL_PAYLOAD_VERSION ||
		lastAdmissionOrdinal?.let { it >= fact.sourceAdmissionOrdinal } == true)

private fun SourceSessionCompletenessEntity.coversPortableWal(wal: AuthenticatedWifiWal): Boolean =
	serviceRunId == wal.scope.serviceRunId && sourceInstanceId == wal.evidence.sourceInstanceId.value &&
	registrationGeneration == wal.evidence.registrationGeneration &&
	(wal.evidence.payloadVersion == WIFI_WAL_PAYLOAD_VERSION ||
		lastAdmissionOrdinal?.let { it >= wal.evidence.sourceAdmissionOrdinal } == true)

private fun List<SourceAuthorizationSnapshot>.hasPortableSharedRetirementIfPresent(
	demands: List<SourceDemandEntity>,
	retirement: SourceRunRetirementEntity?,
): Boolean = retirement == null || hasExactPortableSharedRetirement(demands, retirement)

private fun SourceDemandEntity.retiredWithinPortableCutoff(retirement: SourceRunRetirementEntity): Boolean {
	val retiredElapsed = retireElapsedRealtimeNanos ?: return false
	val retiredWall = retiredAtMs ?: return false
	return retiredElapsed <= retirement.cutoffElapsedRealtimeNanos && retiredWall <= retirement.cutoffWallTimeMs
}

private fun LifecycleDesiredActionEntity.matchesPortableCaptureMember(member: SourceAuthorizationEntity): Boolean =
	manifestRevision == member.manifestRevision && leaseGeneration == member.lifecycleLeaseGeneration &&
		bootId == member.effectiveBootId

private fun SourceDemandEntity.closedAtPortableSnapshot(
	registration: ProviderRegistrationGenerationEntity,
	closing: SourceAuthorizationSnapshot,
): Boolean = status == SourceDemandEntity.STATUS_RETIRED && retireBootId == registration.clockDomainId &&
	retireElapsedRealtimeNanos == closing.effectiveElapsedRealtimeNanos &&
	retiredAtMs == closing.members.first().effectiveWallTimeMs

private fun ProviderRegistrationGenerationEntity.hasPortableRetirementBoundary(): Boolean =
	retiredAtMs != null && retiredElapsedRealtimeNanos != null

private fun SourceAuthorizationSnapshot.hasPortableRegistrationClock(
	registration: ProviderRegistrationGenerationEntity,
): Boolean = members.first().registrationGeneration == registration.registrationGeneration &&
	effectiveBootId == registration.clockDomainId
