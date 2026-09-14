@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
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
import com.adsamcik.tracker.stats.api.repository.WifiCapturedPortableFormatV1
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
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
) : ExportPortableCapturedWifi {
	override suspend fun export(
		request: ExportPortableCapturedWifiRequest,
		sink: PortableCapturedWifiSink,
	): ExportPortableCapturedWifiResult = withContext(ioDispatcher) {
		val snapshot = try {
			database.withTransaction { readInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (blocked: WifiCapturedRetentionBlockedException) {
			return@withContext ExportPortableCapturedWifiResult.Unverifiable(
				when (blocked.reason) {
					WifiCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
						PortableWifiUnverifiableReason.DEPENDENCY_OVERFLOW
					WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
						PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE
					else -> PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE
				},
			)
		} catch (_: WifiCapturedMaintenanceLimitExceeded) {
			return@withContext ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		} catch (_: IllegalArgumentException) {
			return@withContext ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		} catch (_: ArithmeticException) {
			return@withContext ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		} catch (_: Exception) {
			return@withContext ExportPortableCapturedWifiResult.RetryableFailure(
				PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
			)
		}

		when (snapshot) {
			is PortableWifiRead.Entry -> {
				currentCoroutineContext().ensureActive()
				sink.emit(snapshot.value)
				ExportPortableCapturedWifiResult.Exported(
					snapshot.value.identity,
					snapshot.value.contentChecksum,
					snapshot.value.runs.size,
					snapshot.value.runs.sumOf { it.observations.size },
				)
			}
			is PortableWifiRead.Outcome -> snapshot.value
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readInTransaction(
		request: ExportPortableCapturedWifiRequest,
	): PortableWifiRead {
		currentCoroutineContext().ensureActive()
		val evidence = database.sourceEvidenceStateDao().get()
			?: return outcome(unverifiable(PortableWifiUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING))
		if (!evidence.hasValidPortableShape()) return outcome(unverifiableFact())

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
		if (runs.size > WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) return outcome(overflow())
		if (runs.any { it.logicalTrackingId != request.logicalTrackingId } ||
			runs.distinctBy(SourceServiceRunEntity::serviceRunId).size != runs.size ||
			runs != runs.sortedWith(compareBy(SourceServiceRunEntity::startedAtMs, SourceServiceRunEntity::serviceRunId))
		) return outcome(unverifiableMembership())

		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId)
		if (segmentIds.size != runs.size || segmentIds.distinct().size != segmentIds.size) {
			return outcome(unverifiableMembership())
		}
		val segments = readDao.rawLogicalEntrySegments(
			request.logicalTrackingId,
			runs.map(SourceServiceRunEntity::serviceRunId),
			WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 1,
		)
		if (segments.size > WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) return outcome(overflow())
		if (segments.size != runs.size || segments.distinctBy(SessionSegment::id).size != segments.size ||
			segments.mapTo(mutableSetOf(), SessionSegment::id) != segmentIds.toSet()
		) return outcome(unverifiableMembership())
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
		if (!session.hasPortableShape(runs)) return outcome(unverifiableAttribution())
		val hierarchyMaterializing = session.completedAtMs != null && runs.any { run ->
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
		) return outcome(overflow())
		if (completeness.any { row ->
				(row.logicalTrackingId == request.logicalTrackingId) != (row.serviceRunId in runIds)
			}
		) return outcome(unverifiableMembership())
		val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
		val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
		if (sources.any { source ->
			source.sourceKind !in VALID_SOURCE_CODES ||
				source.purpose !in SessionManifestPurposeCode.ALL ||
				(source.purpose == CAPTURE_PURPOSE && !source.persistenceEligible)
		}) return outcome(unverifiableAttribution())
		if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				manifestsByRun.values.map { revisions ->
					revisions.map(SessionManifestVersionEntity::manifestRevision)
				},
			) || runs.any { run ->
				val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
				!SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests) ||
					runManifests.any { manifest ->
						manifest.logicalTrackingId != request.logicalTrackingId ||
							!SessionManifestIntegrity.verify(
								manifest,
								sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision].orEmpty(),
							)
					}
			}
		) return outcome(unverifiableAttribution())
		if (manifests.any { !hasValidZone(it.zoneId) || it.sessionMode != session.sessionMode }) {
			return outcome(unverifiableAttribution())
		}
		val latestByRun = runs.associateWith { run ->
			manifestsByRun[run.serviceRunId].orEmpty().maxByOrNull(
				SessionManifestVersionEntity::manifestRevision,
			) ?: return outcome(unverifiableAttribution())
		}
		val latestLogical = latestByRun.values.maxByOrNull(SessionManifestVersionEntity::manifestRevision)
			?: return outcome(unverifiableAttribution())
		if (latestByRun.any { (run, manifest) ->
			run.desiredPlanRevision != manifest.acquisitionPlanRevision ||
				run.rolloutRevision != manifest.rolloutRevision
		} || session.currentManifestRevision != latestLogical.manifestRevision ||
			session.desiredPlanRevision != latestLogical.acquisitionPlanRevision ||
			session.rolloutRevision != latestLogical.rolloutRevision
		) return outcome(unverifiableAttribution())

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
				) return outcome(unverifiableAttribution())
				capturedWifi.singleOrNull()?.let { binding ->
					if (!binding.isExactPortableWifiWriter()) return outcome(unverifiableWriter())
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
		val audit = maintenance.auditPortableInTransaction(
			evidence,
			request.logicalTrackingId,
			runIds,
			PORTABLE_LIMITS,
		)

		val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
		val consentEpochs = captureByRun.values.flatten().map(SessionManifestSourceEntity::consentEpoch).distinct()
		val policies = database.wifiCapturedFactDao().historyPolicies(
			WIFI_SOURCE,
			runIds,
			WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
		)
		val consents = if (consentEpochs.isEmpty()) emptyList() else
			database.wifiCapturedFactDao().historyConsentEpochs(
				WIFI_SOURCE,
				CAPTURE_PURPOSE,
				consentEpochs,
				WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
			)
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
			desiredPlans.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
			policies.distinctBy { it.policyRevision }.size != policies.size ||
			consents.distinctBy { it.epoch }.size != consents.size ||
			planHeaders.distinctBy { it.revision }.size != planHeaders.size ||
			desiredPlans.distinctBy { it.revision }.size != desiredPlans.size
		) return outcome(overflow())
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
		}) return outcome(unverifiableAttribution())

		val lane = readDao.productLanesForServiceRuns(WIFI_SOURCE, CAPTURE_PURPOSE, runIds)
			.singleOrNull() ?: return outcome(unverifiableWriter())
		val requiredModeMask = when (sessionMode) {
			PortableWifiSessionMode.MANUAL -> MANUAL_CAPTURE_MASK
			PortableWifiSessionMode.AUTOMATIC -> AUTOMATIC_CAPTURE_MASK
		}
		if (!lane.isExactPortableWifiLane() || !laneExecutionAuthority.owns(lane) ||
			lane.captureModeMask and requiredModeMask == 0L ||
			manifests.any { lane.activatedRolloutRevision > it.rolloutRevision }
		) return outcome(unverifiableWriter())

		val selectedCapturedRunIds = captureByRun.filterValues { it.isNotEmpty() }.keys
		val selectedLineages = audit.lineages.filter {
			it.scope.logicalTrackingId == request.logicalTrackingId && it.scope.serviceRunId in runIds
		}
		val selectedWal = audit.wal.captureByEventId.values.filter {
			it.scope.logicalTrackingId == request.logicalTrackingId && it.scope.serviceRunId in runIds
		}
		val closureEventIds = audit.lineages.mapTo(mutableSetOf()) { it.revisions.last().sourceEventId }
		if (audit.lineages.any { lineage ->
			(lineage.scope.logicalTrackingId == request.logicalTrackingId) !=
				(lineage.scope.serviceRunId in runIds)
		} || audit.wal.captureByEventId.values.any { wal ->
			(wal.scope.logicalTrackingId == request.logicalTrackingId) !=
				(wal.scope.serviceRunId in runIds) ||
				(wal !in selectedWal && wal.evidence.sourceEventId.value !in closureEventIds)
		} || selectedLineages.any { it.scope.serviceRunId !in selectedCapturedRunIds } ||
			selectedWal.any { it.scope.serviceRunId !in selectedCapturedRunIds }
		) return outcome(unverifiableFact())

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
		) return outcome(unverifiableFact())
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
		}) return outcome(unverifiableFact())
		val fencedRuns = scopeDigestByRun.filterValues { digest ->
			fences.any { it.scopeIdentityDigest == digest }
		}.keys
		if (audit.generationByScope.keys.any { scope ->
			scope.logicalTrackingId != request.logicalTrackingId ||
				scope.serviceRunId !in runIds || scope.serviceRunId !in selectedCapturedRunIds
		}) return outcome(unverifiableFact())
		val generatedRuns = audit.generationByScope.keys.mapNotNull { scope ->
			if (scope.logicalTrackingId == request.logicalTrackingId && scope.serviceRunId in runIds) {
				scope.serviceRunId
			} else null
		}.toSet()
		if (fencedRuns != generatedRuns) return outcome(unverifiableFact())
		if (generatedRuns.isNotEmpty()) {
			return outcome(
				if (generatedRuns == selectedCapturedRunIds && selectedLineages.isEmpty()) {
					ExportPortableCapturedWifiResult.Deleted
				} else {
					unverifiableFact()
				},
			)
		}

		if (session.completedAtMs == null || runs.any { it.completedAtMs == null }) {
			return outcome(ExportPortableCapturedWifiResult.Active)
		}

		val wifiCompleteness = completeness.filter { row ->
			row.sourceKind == WIFI_SOURCE && row.serviceRunId in selectedCapturedRunIds
		}
		if (!wifiCompleteness.hasValidPortableWifiShape(request.logicalTrackingId, selectedCapturedRunIds) ||
			selectedCapturedRunIds.any { runId -> wifiCompleteness.none { it.serviceRunId == runId } }
		) return outcome(unverifiableWriter())
		val walRegistrationKeys = selectedWal.mapTo(mutableSetOf()) { wal ->
			wal.evidence.registrationGeneration to wal.evidence.sourceInstanceId.value
		}
		val unbackedCompleteness = wifiCompleteness.filter { row ->
			row.registrationGeneration > 0L &&
				(row.registrationGeneration to row.sourceInstanceId) !in walRegistrationKeys
		}
		if (unbackedCompleteness.isNotEmpty()) {
			val generations = unbackedCompleteness.map(SourceSessionCompletenessEntity::registrationGeneration)
				.distinct()
			val registrations = database.wifiCapturedFactDao().historyProviderRegistrations(
				WIFI_SOURCE,
				generations,
				WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS + 1,
			)
			if (registrations.size > WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS ||
				registrations.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration).size !=
					registrations.size || registrations.mapTo(mutableSetOf()) { it.registrationGeneration } !=
					generations.toSet()
			) return outcome(overflow())
			val registrationByGeneration = registrations.associateBy {
				it.registrationGeneration
			}
			val authorizationRows = database.wifiCapturedFactDao().historyAuthorizations(
				WIFI_SOURCE,
				generations,
				WifiCapturedPortableFormatV1.MAX_AUTHORIZATION_ROWS + 1,
			)
			if (authorizationRows.size > WifiCapturedPortableFormatV1.MAX_AUTHORIZATION_ROWS ||
				authorizationRows.any { it.registrationGeneration !in generations }
			) return outcome(overflow())
			val authorizationSnapshots = authorizationRows
				.groupBy { it.registrationGeneration to it.authorizationRevision }
				.mapNotNull { (_, rows) -> runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull() }
			if (authorizationSnapshots.size != authorizationRows.distinctBy {
				it.registrationGeneration to it.authorizationRevision
			}.size || authorizationSnapshots.distinctBy(SourceAuthorizationSnapshot::authorizationRevision).size !=
				authorizationSnapshots.size || authorizationSnapshots.any { snapshot ->
					selectedWal.any { wal ->
						wal.evidence.registrationGeneration != snapshot.members.first().registrationGeneration &&
							wal.evidence.authorizationRevision == snapshot.authorizationRevision
					}
				}
			) return outcome(unverifiableWriter())
			val demandIds = authorizationRows.mapNotNull(SourceAuthorizationEntity::demandId).distinct()
			if (demandIds.size > WifiCapturedPortableFormatV1.MAX_DEMANDS) return outcome(overflow())
			val demands = mutableListOf<SourceDemandEntity>()
			for (demandIdBatch in demandIds.chunked(PORTABLE_QUERY_BATCH_SIZE)) {
				currentCoroutineContext().ensureActive()
				val batch = database.wifiCapturedFactDao().historyDemands(
					demandIdBatch,
					demandIdBatch.size + 1,
				)
				if (batch.size != demandIdBatch.size) return outcome(unverifiableWriter())
				demands += batch
			}
			if (demands.size != demandIds.size || demands.size > WifiCapturedPortableFormatV1.MAX_DEMANDS ||
				demands.distinctBy(SourceDemandEntity::demandId).size != demands.size
			) return outcome(unverifiableWriter())
			val demandsById = demands.associateBy(SourceDemandEntity::demandId)
			if (authorizationRows.any { row -> !row.hasExactPortableMember(demandsById[row.demandId]) } ||
				authorizationSnapshots.any { snapshot ->
					!snapshot.hasExactPortableFingerprint(demandsById)
				}
			) return outcome(unverifiableWriter())
			if (unbackedCompleteness.any { row ->
				val registration = registrationByGeneration[row.registrationGeneration]
					?: return@any true
				val run = runs.singleOrNull { it.serviceRunId == row.serviceRunId }
					?: return@any true
				val registrationAuthorizations = authorizationSnapshots.filter { snapshot ->
					snapshot.members.first().registrationGeneration == row.registrationGeneration
				}
				!registration.hasPortableRegistrationShape(
					row,
					runs,
					manifestsByRun,
					captureByRun,
					desiredByRevision,
					planCodec,
				) || registrationAuthorizations.none { snapshot ->
					snapshot.hasExactPortableCaptureAuthority(
						registration,
						run,
						captureByRun[run.serviceRunId].orEmpty(),
						demandsById,
					)
				} || !registrationAuthorizations.hasExactPortableCaptureRetirement(
					registration,
					run,
					demandsById,
				)
			}) return outcome(unverifiableWriter())
			val allSelectedInstances = selectedWal.map { wal -> wal.evidence.sourceInstanceId.value } +
				registrations.map(ProviderRegistrationGenerationEntity::sourceInstanceId)
			if (allSelectedInstances.distinct().size != 1) return outcome(unverifiableWriter())
			val epochRelations = registrations.map { registration ->
				registration.collectedDataEpoch.compareTo(evidence.collectedDataEpoch)
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
			val actions = readDao.historyStartActions(
				WIFI_SOURCE,
				runIds,
				WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
			)
			if (actions.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
				actions.distinctBy(LifecycleDesiredActionEntity::actionId).size != actions.size ||
				unbackedCompleteness.any { row ->
					val run = runs.single { it.serviceRunId == row.serviceRunId }
					val registration = registrationByGeneration.getValue(row.registrationGeneration)
					actions.count { action ->
						action.authenticatesPortableCompleteness(
							run,
							manifestsByRun[run.serviceRunId].orEmpty(),
							captureByRun[run.serviceRunId].orEmpty(),
							registration,
						)
					} != 1
				}
			) return outcome(unverifiableWriter())
		}

		val targetOrdinal = listOfNotNull(
			selectedLineages.maxOfOrNull { it.revisions.last().sourceAdmissionOrdinal },
			wifiCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
			selectedWal.maxOfOrNull { it.evidence.sourceAdmissionOrdinal },
		).maxOrNull()
		if (targetOrdinal != null && (session.finalAdmissionOrdinal?.let { targetOrdinal > it } == true ||
			targetOrdinal < lane.activationOrdinal ||
			lane.captureAdmissionCutoffOrdinal?.let { it < targetOrdinal } == true)
		) return outcome(unverifiableWriter())
		if (selectedLineages.any { it.revisions.last().sourceAdmissionOrdinal < lane.activationOrdinal }) {
			return outcome(unverifiableWriter())
		}
		if (selectedLineages.any { lineage ->
			val fact = lineage.revisions.last()
			wifiCompleteness.none { row ->
				row.serviceRunId == fact.serviceRunId && row.sourceInstanceId == fact.sourceInstanceId &&
					row.registrationGeneration == fact.registrationGeneration &&
					row.lastAdmissionOrdinal?.let { it >= fact.sourceAdmissionOrdinal } == true
			}
		}) return outcome(unverifiableWriter())
		if (selectedWal.any { wal ->
			wifiCompleteness.none { row ->
				row.serviceRunId == wal.scope.serviceRunId &&
					row.sourceInstanceId == wal.evidence.sourceInstanceId.value &&
					row.registrationGeneration == wal.evidence.registrationGeneration &&
					row.lastAdmissionOrdinal?.let { it >= wal.evidence.sourceAdmissionOrdinal } == true
			}
		}) return outcome(unverifiableWriter())

		val failures = if (targetOrdinal == null || lane.activationOrdinal > targetOrdinal) emptyList() else
			readDao.terminalFailuresForServiceRuns(
				WIFI_SOURCE,
				CAPTURE_PURPOSE,
				runIds,
				lane.activationOrdinal - 1L,
				targetOrdinal,
				WifiCapturedPortableFormatV1.MAX_TERMINAL_FAILURES + 1,
			)
		if (failures.size > WifiCapturedPortableFormatV1.MAX_TERMINAL_FAILURES) return outcome(overflow())
		if (failures.isNotEmpty()) return outcome(unverifiableWriter())
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
		val runsOut = runs.map { run ->
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
				startTimeMs = segment.startTimeMs,
				endTimeMs = segment.endTimeMs,
				storedZoneIds = runManifests.map(SessionManifestVersionEntity::zoneId).distinct().sorted(),
				captureCoverage = when {
					!captured -> PortableWifiCaptureCoverage.NOT_CAPTURED
					bindings.size == runManifests.size -> PortableWifiCaptureCoverage.WHOLE_RUN
					else -> PortableWifiCaptureCoverage.PARTIAL_RUN
				},
				availability = when {
					!captured -> PortableWifiRunAvailability.NOT_CAPTURED
					observations.isNotEmpty() -> PortableWifiRunAvailability.RETAINED
					else -> PortableWifiRunAvailability.NO_RETAINED_OBSERVATION
				},
				acquisitionCompleteness = wifiCompleteness
					.filter { it.serviceRunId == run.serviceRunId }
					.toPortableCompleteness(captured),
				hasUnresolvedProviderRange = wifiCompleteness.any {
					it.serviceRunId == run.serviceRunId && it.unresolvedSequenceStart != null
				},
				retentionLoss = retentionLoss,
				observations = observations,
			)
		}.sortedWith(PORTABLE_WIFI_RUN_ORDER)

		if (runsOut.all { it.observations.isEmpty() && !it.retentionLoss } &&
			wifiCompleteness.any {
				it.registrationGeneration == 0L || it.stopStatus in PROVIDER_UNAVAILABLE_STATUSES
			}
		) return outcome(ExportPortableCapturedWifiResult.Unavailable(
			PortableWifiUnavailableReason.PROVIDER_UNAVAILABLE,
		))
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
		return PortableWifiRead.Entry(entry)
	}
}

private sealed interface PortableWifiRead {
	data class Entry(val value: PortableCapturedWifiEntryV1) : PortableWifiRead
	data class Outcome(val value: ExportPortableCapturedWifiResult) : PortableWifiRead
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

private fun LogicalTrackingSessionEntity.hasPortableShape(runs: List<SourceServiceRunEntity>): Boolean {
	if (state !in ALL_SESSION_STATES) return false
	val terminal = state in TERMINAL_STATES
	if ((completedAtMs != null) != terminal || logicalTrackingId.isBlank() || lifecycleRevision <= 0L ||
		desiredPlanRevision <= 0L || rolloutRevision <= 0L || startOrigin.isBlank() ||
		clockDomainId.isBlank() || lifecycleBootId != clockDomainId || lifecycleLeaseGeneration <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L ||
		(cutoffAtMs == null) != (cutoffElapsedNanos == null)
	) return false
	if (terminal && (cutoffAtMs == null || cutoffElapsedNanos == null || currentServiceRunId != null ||
		finalAdmissionOrdinal == null || requireNotNull(finalAdmissionOrdinal) < 0L ||
		requireNotNull(completedAtMs) < requireNotNull(cutoffAtMs))
	) return false
	if (!terminal && (currentServiceRunId.isNullOrBlank() || finalAdmissionOrdinal != null)) return false
	if (!runs.all { run ->
		if (run.state !in ALL_RUN_STATES) return@all false
		val runTerminal = run.state in TERMINAL_STATES
		run.logicalTrackingId == logicalTrackingId && run.bootId == clockDomainId &&
			run.leaseGeneration > 0L && run.leaseGeneration <= lifecycleLeaseGeneration &&
			run.desiredPlanRevision > 0L && run.rolloutRevision > 0L && run.startedAtMs >= 0L &&
			run.startedElapsedNanos >= 0L && run.startOrigin.isNotBlank() &&
			!run.startDeliveryToken.isNullOrBlank() && run.startCommandGeneration > 0L &&
			run.preparedManifestRevision > 0L && run.preparedIntentRevision > 0L &&
			run.runtimeAcknowledgement.isNotBlank() && run.androidDeliveryState.isNotBlank() &&
			run.androidDeliveryUpdatedAtMs != null && (run.completedAtMs != null) == runTerminal &&
			(run.completedAtMs != null) == !run.completionReason.isNullOrBlank() &&
			run.completedAtMs?.let { it >= run.startedAtMs } != false &&
			run.presentationAcknowledgement in setOf(
				SourceServiceRunEntity.PRESENTATION_PENDING,
				SourceServiceRunEntity.PRESENTATION_QUIESCED,
			) && (run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_QUIESCED ||
				run.presentationAcknowledgedAtMs != null)
	}) return false
	val openRuns = runs.filter { it.completedAtMs == null }
	if (terminal) return openRuns.isEmpty() && runs.all { it.state in TERMINAL_STATES }
	val activeRun = openRuns.singleOrNull() ?: return false
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
	runs: List<SourceServiceRunEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	captureByRun: Map<String, List<SessionManifestSourceEntity>>,
	desiredByRevision: Map<Long, com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity>,
	planCodec: SourcePlanCodec,
): Boolean {
	if (row.lastAdmissionOrdinal != null || row.lastSourceSequence != null) return false
	val run = runs.singleOrNull { it.serviceRunId == row.serviceRunId } ?: return false
	val capturedRevisions = captureByRun[run.serviceRunId].orEmpty()
		.mapTo(mutableSetOf(), SessionManifestSourceEntity::manifestRevision)
	val fingerprints = manifestsByRun[run.serviceRunId].orEmpty()
		.filter { it.manifestRevision in capturedRevisions }
		.mapNotNull { manifest ->
			val desired = desiredByRevision[manifest.acquisitionPlanRevision] ?: return@mapNotNull null
			(runCatching { planCodec.decode(desired.payload) as? WifiPlan }.getOrNull())
				?.physicalConfigurationFingerprint()
		}.toSet()
	val acceptedWall = acceptedAtMs ?: return false
	val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
	val statusShape = status == ProviderRegistrationGenerationEntity.STATUS_RETIRED &&
		retiredAtMs != null && retiredElapsedRealtimeNanos != null &&
		!failureCode.isNullOrBlank()
	return sourceKind == WIFI_SOURCE && registrationGeneration == row.registrationGeneration &&
		sourceInstanceId == row.sourceInstanceId && ownerScope == "source-broker:$WIFI_SOURCE" &&
		clockDomainId == run.bootId &&
		providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
		!providerProcessIncarnationId.isNullOrBlank() && physicalConfigurationFingerprint in fingerprints &&
		captureCallbackBarrierAuthorizationRevision > 0L &&
		statusShape && reservedAtMs <= acceptedWall && reservedElapsedRealtimeNanos <= acceptedElapsed &&
		retiredAtMs?.let { it >= acceptedWall } != false &&
		retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false
}

private fun SourceAuthorizationEntity.hasExactPortableMember(demand: SourceDemandEntity?): Boolean {
	if (isDenyAll) return demand == null && memberId == SourceBrokerAuthorization.DENY_ALL_MEMBER_ID &&
		purposeEligibilityMask == 0L && !persistenceEligible
	if (demand == null) return false
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
	if (members.distinctBy(SourceAuthorizationEntity::memberId).size != members.size) return false
	val demands = authorizedMembers.mapNotNull { member -> member.demandId?.let(demandsById::get) }
	if (demands.size != authorizedMembers.size || demands.distinctBy(SourceDemandEntity::demandId).size != demands.size) {
		return false
	}
	return authorizationFingerprint == SourceBrokerAuthorization.fingerprint(demands) &&
		purposeEligibilityMask == SourceBrokerAuthorization.purposeMask(demands)
}

private fun SourceAuthorizationSnapshot.hasExactPortableCaptureAuthority(
	registration: ProviderRegistrationGenerationEntity,
	run: SourceServiceRunEntity,
	bindings: List<SessionManifestSourceEntity>,
	demandsById: Map<String, SourceDemandEntity>,
): Boolean {
	val acceptedElapsed = registration.acceptedElapsedRealtimeNanos ?: return false
	val retiredElapsed = registration.retiredElapsedRealtimeNanos ?: return false
	if (members.first().registrationGeneration != registration.registrationGeneration ||
		effectiveBootId != registration.clockDomainId || effectiveBootId != run.bootId ||
		effectiveElapsedRealtimeNanos !in acceptedElapsed..retiredElapsed ||
		purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
	) return false
	return authorizedMembers.any { member ->
		val demand = member.demandId?.let(demandsById::get) ?: return@any false
		val binding = bindings.singleOrNull { it.manifestRevision == demand.manifestRevision }
		val expectedContract = runCatching {
			SourceDemandContractFactory.forQos(
				SourceKind.WIFI,
				demand.qosCode,
				DirectSourceDemandPurpose.SESSION_CAPTURE,
			)
		}.getOrNull()
		val actualContract = runCatching { demand.toSourceDemandContract() }.getOrNull()
		binding != null && demand.purpose == CAPTURE_PURPOSE && demand.persistenceEligible &&
			demand.consumerId == "session:${run.logicalTrackingId}" && expectedContract == actualContract &&
			demand.logicalTrackingId == run.logicalTrackingId && demand.serviceRunId == run.serviceRunId &&
			demand.lifecycleLeaseGeneration == run.leaseGeneration &&
			demand.sourcePolicyRevision == member.sourcePolicyRevision &&
			demand.sourcePolicyRevision > 0L && demand.consentEpoch == binding.consentEpoch &&
			demand.qosCode == binding.qosCode && demand.status == SourceDemandEntity.STATUS_RETIRED &&
			demand.retireBootId == run.bootId &&
			registration.captureCallbackBarrierAuthorizationRevision == authorizationRevision
	}
}

private fun List<SourceAuthorizationSnapshot>.hasExactPortableCaptureRetirement(
	registration: ProviderRegistrationGenerationEntity,
	run: SourceServiceRunEntity,
	demandsById: Map<String, SourceDemandEntity>,
): Boolean {
	if (isEmpty() || any { snapshot ->
			snapshot.members.first().registrationGeneration != registration.registrationGeneration ||
				snapshot.effectiveBootId != registration.clockDomainId
		}
	) return false
	val ordered = sortedWith(compareBy(
		SourceAuthorizationSnapshot::effectiveElapsedRealtimeNanos,
		SourceAuthorizationSnapshot::authorizationRevision,
	))
	if (ordered.map(SourceAuthorizationSnapshot::authorizationRevision).distinct().size != ordered.size) return false
	val selectedDemandIds = demandsById.values.filter { demand ->
		demand.sourceKind == WIFI_SOURCE && demand.purpose == CAPTURE_PURPOSE &&
			demand.logicalTrackingId == run.logicalTrackingId && demand.serviceRunId == run.serviceRunId
	}.mapTo(mutableSetOf(), SourceDemandEntity::demandId)
	if (selectedDemandIds.isEmpty()) return false
	val captureSnapshots = ordered.filter { snapshot ->
		snapshot.authorizedMembers.any { member -> member.demandId in selectedDemandIds }
	}
	val lastCapture = captureSnapshots.lastOrNull() ?: return false
	if (lastCapture.authorizationRevision != registration.captureCallbackBarrierAuthorizationRevision) return false
	val retirementElapsed = selectedDemandIds.map { demandId ->
		demandsById.getValue(demandId).retireElapsedRealtimeNanos ?: return false
	}.maxOrNull() ?: return false
	val retirementWall = selectedDemandIds.map { demandId ->
		demandsById.getValue(demandId).retiredAtMs ?: return false
	}.maxOrNull() ?: return false
	val closing = ordered.firstOrNull { snapshot ->
		(snapshot.effectiveElapsedRealtimeNanos > lastCapture.effectiveElapsedRealtimeNanos ||
			snapshot.effectiveElapsedRealtimeNanos == lastCapture.effectiveElapsedRealtimeNanos &&
				snapshot.authorizationRevision > lastCapture.authorizationRevision) &&
			snapshot.authorizedMembers.none { member -> member.demandId in selectedDemandIds }
	} ?: return false
	return closing.effectiveElapsedRealtimeNanos == retirementElapsed &&
		closing.members.first().effectiveWallTimeMs == retirementWall &&
		ordered.dropWhile { it != closing }.none { snapshot ->
			snapshot.authorizedMembers.any { member -> member.demandId in selectedDemandIds }
	}
}

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
	return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
		desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
		failureCode == null && retryTrigger == null && logicalTrackingId == run.logicalTrackingId &&
		serviceRunId == run.serviceRunId && desiredPlanRevision == manifest.acquisitionPlanRevision &&
		sourcePolicyRevision == manifest.sourcePolicyRevision && consentEpoch == binding.consentEpoch &&
		startOrigin == manifest.startOrigin && startOrigin == run.startOrigin && bootId == run.bootId &&
		leaseGeneration == run.leaseGeneration && sourceInstanceId == registration.sourceInstanceId &&
		registrationGeneration == registration.registrationGeneration &&
		requestedAtMs == manifest.effectiveWallTimeMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		acknowledgedWall >= requestedAtMs && acknowledgedElapsed >= requestedElapsedRealtimeNanos &&
		acceptedWall <= acknowledgedWall && acceptedElapsed <= acknowledgedElapsed
}

private fun SessionManifestVersionEntity.hasExactPortableHeader(
	header: com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity?,
): Boolean = header != null && header.revision == acquisitionPlanRevision &&
	header.sourcePolicyRevision == sourcePolicyRevision && header.planId.isNotBlank() &&
	header.createdAtMs >= 0L

@Suppress("LongParameterList", "ComplexCondition")
private fun SessionManifestVersionEntity.hasExactPortableWifiIntent(
	binding: SessionManifestSourceEntity,
	policy: com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity?,
	consent: com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity?,
	header: com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity?,
	desired: com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity?,
	planCodec: SourcePlanCodec,
): Boolean {
	if (!binding.isExactPortableWifiWriter() || policy == null || consent == null || header == null ||
		desired == null || desired.payload.size > MAX_PLAN_PAYLOAD_BYTES || !hasValidZone(zoneId)
	) return false
	val plan = runCatching { planCodec.decode(desired.payload) as? WifiPlan }.getOrNull() ?: return false
	val encoded = runCatching { planCodec.encode(plan) }.getOrNull() ?: return false
	return desired.payloadVersion == PLAN_PAYLOAD_VERSION && desired.revision == acquisitionPlanRevision &&
		desired.sourceKind == WIFI_SOURCE && encoded.bytes.contentEquals(desired.payload) &&
		encoded.checksum == desired.payloadChecksum && plan.revision == acquisitionPlanRevision &&
		plan.hasPortableShape() && policy.sourceKind == WIFI_SOURCE &&
		policy.policyRevision == sourcePolicyRevision && policy.enabled &&
		policy.capturePersistenceEligible && policy.qosCode == binding.qosCode &&
		policy.captureConsentEpoch == binding.consentEpoch && policy.effectiveBootId == effectiveBootId &&
		policy.effectiveElapsedRealtimeNanos in 0L..effectiveElapsedRealtimeNanos &&
		policy.effectiveWallTimeMs >= 0L && policy.changeReason.isNotBlank() &&
		consent.sourceKind == WIFI_SOURCE && consent.purpose == CAPTURE_PURPOSE &&
		consent.epoch == binding.consentEpoch && consent.policyRevision <= policy.policyRevision &&
		consent.eligible && consent.persistenceEligible && consent.effectiveBootId == effectiveBootId &&
		consent.effectiveElapsedRealtimeNanos in 0L..effectiveElapsedRealtimeNanos &&
		consent.effectiveWallTimeMs >= 0L && consent.changeReason.isNotBlank() &&
		hasExactPortableHeader(header)
}

private fun WifiPlan.hasPortableShape(): Boolean = revision > 0L && mode in setOf(
	WifiMode.CACHED_ONLY,
	WifiMode.BROADCAST_DRIVEN,
	WifiMode.ACTIVE_ATTEMPTS,
) && minimumAttemptIntervalMs >= 0L && maximumAcceptableResultAgeMs >= 0L &&
	unchangedResultDedupeWindowMs >= 0L && backoff.initialDelayMs >= 0L &&
	backoff.maximumDelayMs >= backoff.initialDelayMs && backoff.multiplier.isFinite() &&
	backoff.multiplier >= 1.0 && physicalConfigurationFingerprint().isNotBlank()

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
	runId in capturedRunIds && rows.distinctBy(SourceSessionCompletenessEntity::registrationGeneration).size ==
		rows.size && rows.all { row ->
			row.logicalTrackingId == logicalTrackingId && row.sourceInstanceId.isNotBlank() &&
				row.registrationGeneration >= 0L &&
				(row.lastAdmissionOrdinal == null) == (row.lastSourceSequence == null) &&
				row.lastAdmissionOrdinal?.let { it > 0L } != false &&
				row.lastSourceSequence?.let { it >= 0L } != false &&
				(row.unresolvedSequenceStart == null) == (row.unresolvedSequenceEnd == null) &&
				row.unresolvedSequenceStart?.let { start ->
					start >= 0L && requireNotNull(row.unresolvedSequenceEnd) >= start
				} != false && row.providerCoverage == WIFI_PROVIDER_COVERAGE &&
				row.stopStatus in STOP_STATUS_VALUES && row.updatedAtMs >= 0L && row.hasValidStopShape() &&
				if (row.registrationGeneration == 0L) {
					rows.size == 1 && row.lastAdmissionOrdinal == null && row.lastSourceSequence == null &&
					when (row.stopStatus) {
						"COMPLETE" -> row.sourceInstanceId == "not-owned-wifi" && row.appDrainComplete &&
							row.unresolvedSequenceStart == null
						"PROVIDER_FAILED" -> row.unresolvedSequenceStart == null &&
							((row.sourceInstanceId == "unresolved-wifi" && !row.appDrainComplete) ||
								(row.sourceInstanceId == "unavailable-wifi" && row.appDrainComplete))
						"TIMED_OUT" -> row.sourceInstanceId == "unresolved-wifi" &&
							!row.appDrainComplete && row.unresolvedSequenceStart == null
						else -> false
					}
				} else true
		}
}

private fun SourceSessionCompletenessEntity.hasValidStopShape(): Boolean {
	val gap = unresolvedSequenceStart != null
	if (gap && (appDrainComplete || stopStatus != "TIMED_OUT")) return false
	return when (stopStatus) {
		"COMPLETE" -> appDrainComplete && !gap
		"TIMED_OUT" -> !appDrainComplete
		"PERMISSION_LOST", "PROVIDER_FAILED", "PROCESS_RESTARTED" -> !gap
		else -> false
	}
}

private fun WifiCapturedFactRevisionEntity.earliestPossibleWallTimeMs(): Long {
	val elapsedSpanNanos = Math.subtractExact(observedElapsedNanos, coverageIntervalStartNanos)
	val spanMs = elapsedSpanNanos / NANOS_PER_MILLISECOND
	val roundingMs = if (elapsedSpanNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
	return Math.subtractExact(
		Math.subtractExact(observedWallTimeMs, spanMs),
		Math.addExact(wallTimeUncertaintyMs, roundingMs),
	).also { require(it >= 0L) }
}

private fun AuthenticatedWifiWal.earliestPossibleWallTimeMs(): Long {
	val elapsedSpanNanos = Math.subtractExact(
		evidence.clock.observedElapsedRealtimeNanos,
		evidence.clock.observedIntervalStartElapsedRealtimeNanos,
	)
	val spanMs = elapsedSpanNanos / NANOS_PER_MILLISECOND
	val roundingMs = if (elapsedSpanNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
	return Math.subtractExact(
		Math.subtractExact(evidence.clock.observedWallTimeMs, spanMs),
		Math.addExact(evidence.clock.wallTimeUncertaintyMs, roundingMs),
	).also { require(it >= 0L) }
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

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private fun outcome(value: ExportPortableCapturedWifiResult) = PortableWifiRead.Outcome(value)
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
private const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
private const val MANUAL_CAPTURE_MASK = 1L
private const val AUTOMATIC_CAPTURE_MASK = 2L
private const val ALL_CAPTURE_MASK = MANUAL_CAPTURE_MASK or AUTOMATIC_CAPTURE_MASK
private const val PLAN_PAYLOAD_VERSION = 1
private const val MAX_PLAN_PAYLOAD_BYTES = 1_024
private const val PORTABLE_QUERY_BATCH_SIZE = 256
private const val NANOS_PER_MILLISECOND = 1_000_000L
private val TERMINAL_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
private val ALL_SESSION_STATES = TERMINAL_STATES + setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
private val ALL_RUN_STATES = ALL_SESSION_STATES
private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private val PROVIDER_UNAVAILABLE_STATUSES = setOf("PERMISSION_LOST", "PROVIDER_FAILED")
private val VALID_SOURCE_CODES = SourceKind.entries.mapTo(mutableSetOf(), SourceKind::stableCode)
private const val WIFI_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
private const val COMPLETE_PROVIDER_COVERAGE = WIFI_PROVIDER_COVERAGE
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
