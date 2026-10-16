package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsTerminalProductAuthentication
import com.adsamcik.tracker.shared.base.database.dao.UnvalidatedStepFactRevision
import com.adsamcik.tracker.shared.base.database.dao.RawSourceRunWalManifestRevisionEvidence
import com.adsamcik.tracker.shared.base.database.dao.RawSourceRunWalGenerationEvidence
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalDao
import com.adsamcik.tracker.shared.base.database.dao.SourceSessionDao
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedFactDrainResult
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedFactProjectionLane
import com.adsamcik.tracker.tracker.source.cell.CellSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.cell.CellSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalDrainResult
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalHandoff
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.PressureSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.PressureSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.wifi.WifiSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.wifi.WifiSessionFactProjectionLane
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Exact durable membership carried from provider retirement into one source-product settlement.
 *
 * This is acquisition completeness only. It does not assert product queryability.
 */
data class SourceDrainMembership(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val lastAdmissionOrdinal: Long?,
	val lastSourceSequence: Long?,
	val appDrainComplete: Boolean,
	val providerCoverage: String,
	val stopStatus: String,
	val unresolvedSequenceStart: Long?,
	val unresolvedSequenceEndInclusive: Long?,
) {
	init {
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration >= 0L)
		require(lastAdmissionOrdinal == null || lastAdmissionOrdinal >= 0L)
		require(lastSourceSequence == null || lastSourceSequence >= 0L)
		require((unresolvedSequenceStart == null) == (unresolvedSequenceEndInclusive == null))
		require(unresolvedSequenceStart == null ||
			requireNotNull(unresolvedSequenceEndInclusive) >= unresolvedSequenceStart)
		require(providerCoverage.isNotBlank())
		require(stopStatus.isNotBlank())
	}
}

/** Exact provider/action identity and whether retirement produced product completeness. */
data class SourceDrainRetirementClaim(
	val source: SourceKind,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val actionId: String,
	val attemptCount: Int,
	val leaseGeneration: Long,
	val cleanupOnly: Boolean,
) {
	init {
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration > 0L)
		require(actionId.isNotBlank())
		require(attemptCount > 0)
		require(leaseGeneration > 0L)
	}
}

/** Identifies the exact source-local writer or protected Location owner responsible for settlement. */
sealed interface SourceProductDrainTarget {
	/**
	 * Exact source writer, owner generation and projection binding to settle before a lifecycle
	 * transition.
	 */
	data class SourceLocalWriter(
		val destination: String,
		val writerOwner: String,
		val writerOwnerGeneration: Long,
		val projectionId: String,
		val projectionVersion: Int,
		val bindingGeneration: Long,
	) : SourceProductDrainTarget {
		init {
			require(destination.isNotBlank())
			require(writerOwner.isNotBlank())
			require(writerOwnerGeneration > 0L)
			require(projectionId.isNotBlank())
			require(projectionVersion > 0)
			require(bindingGeneration > 0L)
		}
	}

	/** Existing protected Location writer; its implementation is supplied by the Location owner. */
	data object ProtectedLocationWriter : SourceProductDrainTarget
}

/**
 * Captured writer-settlement boundary using boot elapsed nanoseconds, UTC milliseconds and admission
 * high-water ordinals.
 */
data class SourceProductDrainRequest(
	val source: SourceKind,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val cutoffElapsedRealtimeNanos: Long,
	val cutoffWallTimeMs: Long,
	val settlementHighWaterAdmissionOrdinal: Long,
	val sourceHighWaterAdmissionOrdinal: Long,
	val memberships: List<SourceDrainMembership>,
	val target: SourceProductDrainTarget,
	val runManifestRevisions: List<Long>,
	val retirementClaims: List<SourceDrainRetirementClaim> = emptyList(),
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(cutoffElapsedRealtimeNanos >= 0L)
		require(cutoffWallTimeMs >= 0L)
		require(settlementHighWaterAdmissionOrdinal >= 0L)
		require(sourceHighWaterAdmissionOrdinal in 0L..settlementHighWaterAdmissionOrdinal)
		require(memberships.isNotEmpty())
		require(memberships.distinctBy {
			it.sourceInstanceId to it.registrationGeneration
		}.size == memberships.size)
		require(retirementClaims.all { claim -> claim.source == source })
		require(retirementClaims.distinct().size == retirementClaims.size)
		require(retirementClaims.filter(SourceDrainRetirementClaim::cleanupOnly).none { claim ->
			memberships.any { membership ->
				membership.sourceInstanceId == claim.sourceInstanceId &&
					membership.registrationGeneration == claim.registrationGeneration
			}
		})
		require(runManifestRevisions.isNotEmpty())
		require(runManifestRevisions.all { it > 0L })
		require(runManifestRevisions == runManifestRevisions.distinct().sorted())
		require(source == SourceKind.LOCATION || target is SourceProductDrainTarget.SourceLocalWriter)
		require(source != SourceKind.LOCATION || target == SourceProductDrainTarget.ProtectedLocationWriter)
	}
}

/**
 * Writer-settlement outcome tied to the original request, with progress retained for retry or failure
 * handling.
 */
sealed interface SourceProductDrainResult {
	val request: SourceProductDrainRequest

	/**
	 * The exact source-local cursor reached the requested high-water.
	 *
	 * This proves materialization settlement only; it deliberately makes no QUERYABLE claim.
	 */
	data class Complete(
		override val request: SourceProductDrainRequest,
		val lastMaterializedAdmissionOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : SourceProductDrainResult {
		init {
			require(lastMaterializedAdmissionOrdinal >= request.sourceHighWaterAdmissionOrdinal)
			require(factsInserted >= 0)
			require(eventsValidated >= 0)
		}
	}

	/**
	 * The writer has not reached the requested boundary; progress and the blocking admission remain
	 * available for retry.
	 */
	data class Deferred(
		override val request: SourceProductDrainRequest,
		val lastMaterializedAdmissionOrdinal: Long,
		val blockedAdmissionOrdinal: Long?,
		val reason: String,
	) : SourceProductDrainResult

	/** No active collaborator can settle this request; the last materialized admission is retained. */
	data class Inactive(
		override val request: SourceProductDrainRequest,
		val reason: String,
		val lastMaterializedAdmissionOrdinal: Long = 0L,
	) : SourceProductDrainResult {
		init {
			require(lastMaterializedAdmissionOrdinal >= 0L)
		}
	}

	/**
	 * Settlement failed at an admission; reports whether that terminal failure was durably recorded.
	 */
	data class Failed(
		override val request: SourceProductDrainRequest,
		val lastMaterializedAdmissionOrdinal: Long,
		val failedAdmissionOrdinal: Long?,
		val failureCode: String,
		val terminalFailureRecorded: Boolean,
	) : SourceProductDrainResult

	/** The requested writer authority changed before settlement could be acknowledged. */
	data class AuthorityChanged(
		override val request: SourceProductDrainRequest,
		val reason: String,
		val lastMaterializedAdmissionOrdinal: Long = 0L,
	) : SourceProductDrainResult {
		init {
			require(lastMaterializedAdmissionOrdinal >= 0L)
		}
	}

	/**
	 * Terminal acquisition evidence proves that this source can never become exactly materializable.
	 *
	 * The durable completeness/count-domain rows remain the authority; this result only carries
	 * their authenticated disposition through lifecycle finalization without claiming queryability.
	 */
	data class Unavailable(
		override val request: SourceProductDrainRequest,
		val reason: String,
	) : SourceProductDrainResult {
		init {
			require(reason.isNotBlank())
		}
	}
}

/**
 * Routes a lifecycle settlement request to its source-owned writer without creating a second writer.
 */
fun interface SourceProductDrainRouter {
	/**
	 * Settles this exact writer binding through the requested admission boundary, or returns its remaining
	 * blocker.
	 */
	suspend fun drainThrough(request: SourceProductDrainRequest): SourceProductDrainResult
}

/**
 * Location-owned implementation must advance only after the protected canonical writer exposes
 * its exact receipt. It must not create a second Location writer.
 */
interface ProtectedLocationSourceDrain {
	/** Signals the existing canonical Location writer to process pending admitted observations. */
	fun requestDrain()
	/**
	 * Settles this exact writer binding through the requested admission boundary, or returns its remaining
	 * blocker.
	 */
	suspend fun drainThrough(request: SourceProductDrainRequest): SourceProductDrainResult
}

/**
 * Safe fallback that reports Location settlement unavailable until the protected writer collaborator is
 * bound.
 */
@Singleton
class RequiredProtectedLocationSourceDrain @Inject constructor() : ProtectedLocationSourceDrain {
	override fun requestDrain() = Unit

	override suspend fun drainThrough(request: SourceProductDrainRequest): SourceProductDrainResult =
		SourceProductDrainResult.Inactive(
			request,
			"PROTECTED_LOCATION_SOURCE_DRAIN_COLLABORATOR_REQUIRED",
		)
}

/**
 * Frozen adapter for the reviewed protected Location producer.
 *
 * It is intentionally not injectable yet: the parent must first accept and assemble the reviewed
 * live/offline [com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalWriter]
 * chain.
 */
internal class ProtectedLocationCanonicalSourceDrain(
	private val handoff: ProtectedLocationCanonicalHandoff,
) : ProtectedLocationSourceDrain {
	override fun requestDrain() {
		handoff.requestDrain()
	}

	override suspend fun drainThrough(
		request: SourceProductDrainRequest,
	): SourceProductDrainResult {
		require(request.source == SourceKind.LOCATION)
		require(request.target == SourceProductDrainTarget.ProtectedLocationWriter)
		return when (val result = handoff.drainThrough(
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			throughAdmissionOrdinal = request.sourceHighWaterAdmissionOrdinal,
		)) {
			is ProtectedLocationCanonicalDrainResult.Complete -> completeResult(
				request = request,
				lastCompletedOrdinal = result.lastCommittedOrdinal,
				factsInserted = result.observationsCommitted +
					result.acceptedSamplesCommitted +
					result.rejectedObservationsCommitted,
				eventsValidated = result.observationsCommitted + result.lifecycleSettled,
			)
			is ProtectedLocationCanonicalDrainResult.Deferred -> SourceProductDrainResult.Deferred(
				request = request,
				lastMaterializedAdmissionOrdinal = result.lastCommittedOrdinal,
				blockedAdmissionOrdinal = result.deferredOrdinal,
				reason = result.reason,
			)
			is ProtectedLocationCanonicalDrainResult.Inactive -> SourceProductDrainResult.Inactive(
				request = request,
				reason = result.reason.name,
				lastMaterializedAdmissionOrdinal = result.lastCommittedOrdinal,
			)
			is ProtectedLocationCanonicalDrainResult.Failed -> failureResult(
				request = request,
				lastCompletedOrdinal = result.lastCommittedOrdinal,
				failedOrdinal = result.failedOrdinal,
				failureCode = result.failureCode,
				terminal = result.terminal,
			)
			is ProtectedLocationCanonicalDrainResult.AuthorityChanged ->
				SourceProductDrainResult.AuthorityChanged(
					request = request,
					reason = result.reason,
					lastMaterializedAdmissionOrdinal = result.lastCommittedOrdinal,
				)
		}
	}
}

/**
 * Dispatches settlement to the appropriate Room-backed source lane and the protected canonical Location
 * writer.
 */
@Singleton
class RoomSourceProductDrainRouter @Inject internal constructor(
	private val database: AppDatabase,
	private val stepsLane: StepsSessionFactProjectionLane,
	private val pressureLane: PressureSessionFactProjectionLane,
	private val activityLane: ActivityCapturedFactProjectionLane,
	private val wifiLane: WifiSessionFactProjectionLane,
	private val cellLane: CellSessionFactProjectionLane,
	private val protectedLocationDrain: ProtectedLocationSourceDrain,
) : SourceProductDrainRouter {
	override suspend fun drainThrough(request: SourceProductDrainRequest): SourceProductDrainResult {
		val authorityFailure = authenticateSourceProductDrainRequest(database, request)
		if (authorityFailure != null) {
			return SourceProductDrainResult.AuthorityChanged(
				request,
				authorityFailure,
			)
		}
		return when (request.source) {
			SourceKind.LOCATION -> protectedLocationDrain.drainThrough(request)
			SourceKind.ACTIVITY -> activityLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
			SourceKind.STEPS -> stepsLane.drainThrough(
				throughAdmissionOrdinal = request.sourceHighWaterAdmissionOrdinal,
				logicalTrackingId = request.logicalTrackingId,
				serviceRunId = request.serviceRunId,
				productMemberships = request.memberships,
				retirementClaims = request.retirementClaims,
			)
				.toSourceResult(request)
			SourceKind.PRESSURE -> pressureLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
			SourceKind.WIFI -> wifiLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
			SourceKind.CELL -> cellLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
		}
	}

	private fun ActivityCapturedFactDrainResult.toSourceResult(
		request: SourceProductDrainRequest,
	): SourceProductDrainResult = when (this) {
		is ActivityCapturedFactDrainResult.Complete ->
			completeResult(request, lastCompletedOrdinal, windowsApplied, eventsValidated)
		is ActivityCapturedFactDrainResult.Deferred -> SourceProductDrainResult.Deferred(
			request,
			lastCompletedOrdinal,
			blockedOrdinal,
			reason,
		)
		is ActivityCapturedFactDrainResult.Inactive -> SourceProductDrainResult.Inactive(request, reason.name)
		is ActivityCapturedFactDrainResult.AuthorityChanged ->
			SourceProductDrainResult.AuthorityChanged(request, reason)
		is ActivityCapturedFactDrainResult.Failed -> failureResult(
			request,
			lastCompletedOrdinal,
			failedOrdinal,
			failureCode,
			terminal,
		)
	}

	private fun StepsSessionFactDrainResult.toSourceResult(
		request: SourceProductDrainRequest,
	): SourceProductDrainResult = when (this) {
		is StepsSessionFactDrainResult.Complete ->
			completeResult(request, lastCompletedOrdinal, factsInserted, eventsValidated)
		StepsSessionFactDrainResult.Inactive ->
			SourceProductDrainResult.Inactive(request, "STEPS_SESSION_FACT_LANE_INACTIVE")
		is StepsSessionFactDrainResult.AuthorityChanged ->
			SourceProductDrainResult.AuthorityChanged(request, reason)
		is StepsSessionFactDrainResult.Failed ->
			failureResult(request, lastCompletedOrdinal, failedOrdinal, failureCode, terminal)
	}

	private fun PressureSessionFactDrainResult.toSourceResult(
		request: SourceProductDrainRequest,
	): SourceProductDrainResult = when (this) {
		is PressureSessionFactDrainResult.Complete ->
			completeResult(request, lastCompletedOrdinal, factsInserted, eventsValidated)
		PressureSessionFactDrainResult.Inactive ->
			SourceProductDrainResult.Inactive(request, "PRESSURE_SESSION_FACT_LANE_INACTIVE")
		is PressureSessionFactDrainResult.AuthorityChanged ->
			SourceProductDrainResult.AuthorityChanged(request, reason)
		is PressureSessionFactDrainResult.Failed ->
			failureResult(request, lastCompletedOrdinal, failedOrdinal, failureCode, terminal)
	}

	private fun WifiSessionFactDrainResult.toSourceResult(
		request: SourceProductDrainRequest,
	): SourceProductDrainResult = when (this) {
		is WifiSessionFactDrainResult.Complete ->
			completeResult(request, lastCompletedOrdinal, factsInserted, eventsValidated)
		WifiSessionFactDrainResult.Inactive ->
			SourceProductDrainResult.Inactive(request, "WIFI_SESSION_FACT_LANE_INACTIVE")
		is WifiSessionFactDrainResult.AuthorityChanged ->
			SourceProductDrainResult.AuthorityChanged(request, reason)
		is WifiSessionFactDrainResult.Failed ->
			failureResult(request, lastCompletedOrdinal, failedOrdinal, failureCode, terminal)
	}

	private fun CellSessionFactDrainResult.toSourceResult(
		request: SourceProductDrainRequest,
	): SourceProductDrainResult = when (this) {
		is CellSessionFactDrainResult.Complete ->
			completeResult(request, lastCompletedOrdinal, factsInserted, eventsValidated)
		CellSessionFactDrainResult.Inactive ->
			SourceProductDrainResult.Inactive(request, "CELL_SESSION_FACT_LANE_INACTIVE")
		is CellSessionFactDrainResult.AuthorityChanged ->
			SourceProductDrainResult.AuthorityChanged(request, reason)
		is CellSessionFactDrainResult.Failed ->
			failureResult(request, lastCompletedOrdinal, failedOrdinal, failureCode, terminal)
	}
}

internal sealed interface SourceProductDrainPlan {
	data class Ready(
		val requests: List<SourceProductDrainRequest>,
		val settledResults: List<SourceProductDrainResult> = emptyList(),
	) : SourceProductDrainPlan
	data class Failed(
		val source: SourceKind?,
		val reason: String,
		val memberships: List<SourceDrainMembership> = emptyList(),
	) : SourceProductDrainPlan
}

internal data class SourceProductDrainAuthority(
	val captureBindings: Map<Int, List<SessionManifestSourceEntity>>,
	val retirementClaims: List<SourceDrainRetirementClaim> = emptyList(),
)

internal suspend fun buildSourceProductDrainPlan(
	database: AppDatabase,
	logicalTrackingId: String,
	serviceRunId: String,
	cutoffElapsedRealtimeNanos: Long,
	cutoffWallTimeMs: Long,
	settlementHighWaterAdmissionOrdinal: Long,
	authenticatedAuthority: SourceProductDrainAuthority,
): SourceProductDrainPlan {
	require(logicalTrackingId.isNotBlank())
	require(serviceRunId.isNotBlank())
	require(cutoffElapsedRealtimeNanos >= 0L)
	require(cutoffWallTimeMs >= 0L)
	require(settlementHighWaterAdmissionOrdinal >= 0L)
	return SourceProductDrainPlanner(
		database, logicalTrackingId, serviceRunId, cutoffElapsedRealtimeNanos, cutoffWallTimeMs,
		settlementHighWaterAdmissionOrdinal,
	).build(authenticatedAuthority)
}

/** One settlement call keeps its original immutable run/cutoff context across complete source phases. */
private class SourceProductDrainPlanner(
	private val database: AppDatabase,
	private val logicalTrackingId: String,
	private val serviceRunId: String,
	private val cutoffElapsedRealtimeNanos: Long,
	private val cutoffWallTimeMs: Long,
	private val settlementHighWaterAdmissionOrdinal: Long,
) {
	suspend fun build(authenticatedAuthority: SourceProductDrainAuthority): SourceProductDrainPlan {
		val authority = readAuthority(authenticatedAuthority)
		if (authority is SourceDrainAuthority.Failed) {
			return SourceProductDrainPlan.Failed(authority.source, authority.reason)
		}
		authority as SourceDrainAuthority.Ready
		val requests = mutableListOf<SourceProductDrainRequest>()
		val settledResults = mutableListOf<SourceProductDrainResult>()
		for (source in SourceKind.entries) {
			val bindings = authority.captureBindings[source.stableCode].orEmpty()
			if (bindings.isEmpty()) {
				continue
			}
			when (val sourcePlan = planSource(source, bindings,
				authority.completeness[source.stableCode].orEmpty(), authenticatedAuthority.retirementClaims)) {
				is SourceProductDrainPlan.Failed -> return sourcePlan
				is SourceProductDrainPlan.Ready -> {
					requests += sourcePlan.requests
					settledResults += sourcePlan.settledResults
				}
			}
		}
		return SourceProductDrainPlan.Ready(requests, settledResults)
	}

	private suspend fun readAuthority(
		authenticatedAuthority: SourceProductDrainAuthority,
	): SourceDrainAuthority = database.withTransaction {
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(logicalTrackingId)
			?: return@withTransaction SourceDrainAuthority.Failed(null, "SOURCE_DRAIN_SESSION_MISSING")
		val run = sessionDao.serviceRun(serviceRunId)
			?: return@withTransaction SourceDrainAuthority.Failed(null, "SOURCE_DRAIN_RUN_MISSING")
		if (session.hasChangedDrainFence(run, logicalTrackingId, serviceRunId,
			cutoffElapsedRealtimeNanos, cutoffWallTimeMs, settlementHighWaterAdmissionOrdinal)
		) {
			return@withTransaction SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_SETTLEMENT_FENCE_CHANGED",
			)
		}
		readSourceDrainAuthority(sessionDao, logicalTrackingId, serviceRunId, authenticatedAuthority)
	}

	// Keep ordered cleanup, missing completeness, Steps evidence and writer provenance outcomes explicit.
	@Suppress("ReturnCount")
	private suspend fun planSource(
		source: SourceKind,
		bindings: List<SessionManifestSourceEntity>,
		completenessRows: List<SourceSessionCompletenessEntity>,
		allRetirementClaims: List<SourceDrainRetirementClaim>,
	): SourceProductDrainPlan {
		val retirementClaims = allRetirementClaims
			.filter { claim -> claim.source == source }
			.sortedWith(
				compareBy(
					SourceDrainRetirementClaim::registrationGeneration,
					SourceDrainRetirementClaim::sourceInstanceId,
					SourceDrainRetirementClaim::actionId,
				),
			)
		val cleanupOnlyClaims = retirementClaims.filter(SourceDrainRetirementClaim::cleanupOnly)
		cleanupProductEvidenceFailure(source, completenessRows, cleanupOnlyClaims)?.let { return it }
		val memberships = completenessRows
			.map(SourceSessionCompletenessEntity::toDrainMembership)
			.sortedWith(compareBy(SourceDrainMembership::sourceInstanceId, SourceDrainMembership::registrationGeneration))
		if (hasMissingCompleteness(memberships, retirementClaims)) {
			return SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_COMPLETENESS_MISSING", memberships)
		}
		val runManifestRevisions = bindings.map(SessionManifestSourceEntity::manifestRevision).distinct().sorted()
		cleanupStepsFailure(source, memberships, retirementClaims)?.let { return it }
		val target = when (source) {
			SourceKind.LOCATION -> SourceProductDrainTarget.ProtectedLocationWriter
			else -> when (val resolution = bindings.sourceLocalTarget()) {
				SourceLocalTargetResolution.LegacyOrUnattributed -> return SourceProductDrainPlan.Ready(emptyList())
				SourceLocalTargetResolution.Invalid -> return SourceProductDrainPlan.Failed(
					source, "SOURCE_DRAIN_WRITER_PROVENANCE_INCONSISTENT",
				)
				is SourceLocalTargetResolution.Ready -> resolution.target
			}
		}
		return if (memberships.isEmpty()) {
			emptySourcePlan(source, runManifestRevisions, memberships, retirementClaims)
		} else {
			materializedSourcePlan(source, memberships, target, runManifestRevisions, retirementClaims, completenessRows)
		}
	}

	private fun cleanupProductEvidenceFailure(
		source: SourceKind,
		completenessRows: List<SourceSessionCompletenessEntity>,
		cleanupOnlyClaims: List<SourceDrainRetirementClaim>,
	): SourceProductDrainPlan.Failed? {
		val cleanupProviderIdentities = cleanupOnlyClaims.map { claim ->
			SourceDrainProviderIdentity(claim.sourceInstanceId, claim.registrationGeneration)
		}.toSet()
		return if (completenessRows.any { row ->
			SourceDrainProviderIdentity(row.sourceInstanceId, row.registrationGeneration) in cleanupProviderIdentities
		}) {
			SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE")
		} else {
			null
		}
	}

	private fun hasMissingCompleteness(
		memberships: List<SourceDrainMembership>,
		retirementClaims: List<SourceDrainRetirementClaim>,
	): Boolean {
		val productClaimProviders = retirementClaims.filterNot(SourceDrainRetirementClaim::cleanupOnly).map { claim ->
			SourceDrainProviderIdentity(claim.sourceInstanceId, claim.registrationGeneration)
		}.toSet()
		val membershipProviders = memberships.map { membership ->
			SourceDrainProviderIdentity(membership.sourceInstanceId, membership.registrationGeneration)
		}.toSet()
		return retirementClaims.isNotEmpty() && membershipProviders != productClaimProviders
	}

	private suspend fun cleanupStepsFailure(
		source: SourceKind,
		memberships: List<SourceDrainMembership>,
		retirementClaims: List<SourceDrainRetirementClaim>,
	): SourceProductDrainPlan.Failed? {
		if (source == SourceKind.STEPS && retirementClaims.any(SourceDrainRetirementClaim::cleanupOnly)) {
			val reason = cleanupOnlyStepsEvidenceFailure(database, logicalTrackingId, serviceRunId,
				memberships, retirementClaims) ?: return null
			return SourceProductDrainPlan.Failed(source, reason)
		}
		return null
	}

	private suspend fun emptySourcePlan(
		source: SourceKind,
		runManifestRevisions: List<Long>,
		memberships: List<SourceDrainMembership>,
		retirementClaims: List<SourceDrainRetirementClaim>,
	): SourceProductDrainPlan {
		if (retirementClaims.isNotEmpty() && retirementClaims.all(SourceDrainRetirementClaim::cleanupOnly)) {
			return when (val read = sourceRunHighWater(
				database, source, logicalTrackingId, serviceRunId, runManifestRevisions,
				settlementHighWaterAdmissionOrdinal, memberships, retirementClaims,
			)) {
				is SourceRunHighWaterRead.Ready -> if (read.highWaterAdmissionOrdinal == 0L) {
					SourceProductDrainPlan.Ready(emptyList())
				} else {
					SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_COMPLETENESS_MISSING")
				}
				SourceRunHighWaterRead.CleanupOnlyProductEvidence ->
					SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE")
				SourceRunHighWaterRead.Unverifiable ->
					SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE")
			}
		}
		return SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_COMPLETENESS_MISSING")
	}

	// Keep WAL evidence and settlement fence failure outcomes before request construction.
	@Suppress("ReturnCount")
	private suspend fun materializedSourcePlan(
		source: SourceKind,
		memberships: List<SourceDrainMembership>,
		target: SourceProductDrainTarget,
		runManifestRevisions: List<Long>,
		retirementClaims: List<SourceDrainRetirementClaim>,
		completenessRows: List<SourceSessionCompletenessEntity>,
	): SourceProductDrainPlan {
		val sourceWalHighWater = when (val read = sourceRunHighWater(
			database, source, logicalTrackingId, serviceRunId, runManifestRevisions,
			settlementHighWaterAdmissionOrdinal, memberships, retirementClaims,
		)) {
			is SourceRunHighWaterRead.Ready -> read.highWaterAdmissionOrdinal
			SourceRunHighWaterRead.CleanupOnlyProductEvidence ->
				return SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE")
			SourceRunHighWaterRead.Unverifiable ->
				return SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE")
		}
		val sourceHighWater = maxOf(
			sourceWalHighWater,
			memberships.mapNotNull(SourceDrainMembership::lastAdmissionOrdinal).maxOrNull() ?: 0L,
		)
		if (sourceHighWater > settlementHighWaterAdmissionOrdinal) {
			return SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_HIGH_WATER_EXCEEDS_SETTLEMENT")
		}
		val request = SourceProductDrainRequest(
			source = source,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			cutoffElapsedRealtimeNanos = cutoffElapsedRealtimeNanos,
			cutoffWallTimeMs = cutoffWallTimeMs,
			settlementHighWaterAdmissionOrdinal = settlementHighWaterAdmissionOrdinal,
			sourceHighWaterAdmissionOrdinal = sourceHighWater,
			memberships = memberships,
			target = target,
			runManifestRevisions = runManifestRevisions,
			retirementClaims = retirementClaims,
		)
		return settleRequest(request, sourceWalHighWater, completenessRows)
	}

	private suspend fun settleRequest(
		request: SourceProductDrainRequest,
		sourceWalHighWater: Long,
		completenessRows: List<SourceSessionCompletenessEntity>,
	): SourceProductDrainPlan = when {
		request.source == SourceKind.STEPS ->
			stepsTerminalDrainPlan(database, request, completenessRows, sourceWalHighWater)
		request.memberships.all(SourceDrainMembership::isExactCompleteSettlement) ->
			SourceProductDrainPlan.Ready(listOf(request))
		else -> SourceProductDrainPlan.Failed(
			source = request.source,
			reason = "SOURCE_DRAIN_SETTLEMENT_INCOMPLETE",
			memberships = request.memberships,
		)
	}

	// Ordered malformed capture, retirement and completeness failures preserve the original reason.
	@Suppress("ReturnCount")
	private suspend fun readSourceDrainAuthority(
		sessionDao: SourceSessionDao,
		logicalTrackingId: String,
		serviceRunId: String,
		authenticatedAuthority: SourceProductDrainAuthority,
	): SourceDrainAuthority {
		val captured = authenticatedAuthority.captureBindings
		if (captured.hasInvalidDrainCaptureBindings(logicalTrackingId)) {
			return SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_MANIFEST_INTEGRITY_MISMATCH",
			)
		}
		authenticateDrainPlanRetirements(sessionDao, logicalTrackingId, serviceRunId,
			captured, authenticatedAuthority.retirementClaims)?.let { return it }
		val rawCompleteness = sessionDao.rawCompletenessForServiceRun(
			serviceRunId,
			MAX_DRAIN_COMPLETENESS_ROWS + 1,
		)
		if (rawCompleteness.size > MAX_DRAIN_COMPLETENESS_ROWS) {
			return SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_COMPLETENESS_OVERFLOW",
			)
		}
		val completenessRows = rawCompleteness.map { raw ->
			raw.validatedOrNull()
				?: return SourceDrainAuthority.Failed(
					null,
					"SOURCE_DRAIN_COMPLETENESS_UNVERIFIABLE",
				)
		}
		if (completenessRows.any { row ->
				row.logicalTrackingId != logicalTrackingId || row.serviceRunId != serviceRunId
			}
		) {
			return SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_COMPLETENESS_UNVERIFIABLE",
			)
		}
		val completeness = completenessRows.groupBy { row -> row.sourceKind }
		return SourceDrainAuthority.Ready(captured, completeness)
	}

	private fun Map<Int, List<SessionManifestSourceEntity>>.hasInvalidDrainCaptureBindings(
		logicalTrackingId: String,
	): Boolean = any { (sourceKind, bindings) ->
		bindings.isEmpty() || bindings.any { binding ->
			binding.logicalTrackingId != logicalTrackingId ||
				binding.sourceKind != sourceKind ||
				binding.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
				!binding.persistenceEligible
		}
	}

	private suspend fun authenticateDrainPlanRetirements(
		sessionDao: SourceSessionDao,
		logicalTrackingId: String,
		serviceRunId: String,
		captured: Map<Int, List<SessionManifestSourceEntity>>,
		retirementClaims: List<SourceDrainRetirementClaim>,
	): SourceDrainAuthority.Failed? {
		if (retirementClaims.any { claim ->
				claim.source.stableCode !in captured
			}
		) {
			return SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_CLEANUP_AUTHORITY_MISMATCH",
			)
		}
		for (claim in retirementClaims) {
			val receipt = sessionDao.rawRunRetirement(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				sourceKind = claim.source.stableCode,
				sourceInstanceId = claim.sourceInstanceId,
				registrationGeneration = claim.registrationGeneration,
			).singleOrNull()?.validatedOrNull()
			if (receipt.doesNotMatchDrainClaim(logicalTrackingId, serviceRunId, claim.source.stableCode, claim)) {
				return SourceDrainAuthority.Failed(
					claim.source,
					"SOURCE_DRAIN_RETIREMENT_AUTHORITY_MISMATCH",
				)
			}
		}
		return null
	}

	private suspend fun stepsTerminalDrainPlan(
		database: AppDatabase,
		request: SourceProductDrainRequest,
		rows: List<SourceSessionCompletenessEntity>,
		sourceWalHighWater: Long,
	): SourceProductDrainPlan = when (
		StepsCountDomainStore(database).authenticateTerminalProductDisposition(
			request.logicalTrackingId,
			request.serviceRunId,
			rows,
		)
	) {
		StepsTerminalProductAuthentication.Materializable -> SourceProductDrainPlan.Ready(listOf(request))
		StepsTerminalProductAuthentication.TerminalUnavailable -> {
			// Unknown provider coverage cannot discard actual admitted observations.
			// The same writer validates/materializes them; completeness stays UNPROVEN.
			unavailableStepsDrainPlan(request, sourceWalHighWater)
		}
		StepsTerminalProductAuthentication.SchemaUnavailable ->
			legacyStepsDrainPlan(request, sourceWalHighWater)
		StepsTerminalProductAuthentication.Unverifiable ->
			SourceProductDrainPlan.Failed(
				request.source,
				"SOURCE_DRAIN_SETTLEMENT_UNVERIFIABLE",
				request.memberships,
			)
	}

	private fun unavailableStepsDrainPlan(
		request: SourceProductDrainRequest,
		sourceWalHighWater: Long,
	): SourceProductDrainPlan =
		if (sourceWalHighWater > 0L) {
			SourceProductDrainPlan.Ready(listOf(request))
		} else {
			SourceProductDrainPlan.Ready(emptyList(), listOf(SourceProductDrainResult.Unavailable(
				request,
				"STEPS_PRODUCT_TERMINALLY_UNAVAILABLE",
			)))
		}

	private fun legacyStepsDrainPlan(
		request: SourceProductDrainRequest,
		sourceWalHighWater: Long,
	): SourceProductDrainPlan {
		return if (request.memberships.all(SourceDrainMembership::isExactCompleteSettlement)) {
			SourceProductDrainPlan.Ready(listOf(request))
		} else if (
			request.memberships.all { membership ->
				membership.isExactCompleteSettlement() ||
					membership.isTerminalUnavailableSettlement()
			} &&
			request.memberships.any(SourceDrainMembership::isTerminalUnavailableSettlement)
		) {
			unavailableStepsDrainPlan(request, sourceWalHighWater)
		} else {
			SourceProductDrainPlan.Failed(
				request.source,
				"SOURCE_DRAIN_SETTLEMENT_INCOMPLETE",
				request.memberships,
			)
		}
	}

	// One pure nullable manifest-target projection keeps its uniform invalid-result guards together.
	@Suppress("ReturnCount", "CyclomaticComplexMethod")
	private fun List<SessionManifestSourceEntity>.sourceLocalTarget(): SourceLocalTargetResolution {
		val projectionAttributed = map { source ->
			listOf(
				source.writerProjectionId,
				source.writerProjectionVersion,
				source.writerBindingGeneration,
			).all { it != null }
		}
		if (projectionAttributed.none { it }) {
			return SourceLocalTargetResolution.LegacyOrUnattributed
		}
		if (!projectionAttributed.all { it }) {
			return SourceLocalTargetResolution.Invalid
		}
		val attributed = mutableSetOf<SourceProductDrainTarget.SourceLocalWriter>()
		for (source in this) {
			val destination = source.outputDestination ?: return SourceLocalTargetResolution.Invalid
			val owner = source.writerOwner ?: return SourceLocalTargetResolution.Invalid
			val ownerGeneration = source.writerOwnerGeneration ?: return SourceLocalTargetResolution.Invalid
			val projectionId = source.writerProjectionId ?: return SourceLocalTargetResolution.Invalid
			val projectionVersion = source.writerProjectionVersion ?: return SourceLocalTargetResolution.Invalid
			val bindingGeneration = source.writerBindingGeneration ?: return SourceLocalTargetResolution.Invalid
			attributed += SourceProductDrainTarget.SourceLocalWriter(
				destination,
				owner,
				ownerGeneration,
				projectionId,
				projectionVersion,
				bindingGeneration,
			)
		}
		return attributed.singleOrNull()?.let { SourceLocalTargetResolution.Ready(it) }
			?: SourceLocalTargetResolution.Invalid
	}
}

private fun SourceDrainMembership.isExactCompleteSettlement(): Boolean =
	appDrainComplete &&
		stopStatus in setOf("COMPLETE", "PARTIAL_UNOBSERVABLE") &&
		unresolvedSequenceStart == null &&
		unresolvedSequenceEndInclusive == null

private fun SourceDrainMembership.isTerminalUnavailableSettlement(): Boolean =
	stopStatus in setOf("PROCESS_RESTARTED", "PARTIAL_UNOBSERVABLE") &&
		!isExactCompleteSettlement()

private sealed interface SourceDrainAuthority {
	data class Ready(
		val captureBindings: Map<Int, List<SessionManifestSourceEntity>>,
		val completeness: Map<Int, List<SourceSessionCompletenessEntity>>,
	) : SourceDrainAuthority

	data class Failed(val source: SourceKind?, val reason: String) : SourceDrainAuthority
}

private sealed interface SourceLocalTargetResolution {
	data object LegacyOrUnattributed : SourceLocalTargetResolution
	data object Invalid : SourceLocalTargetResolution
	data class Ready(val target: SourceProductDrainTarget.SourceLocalWriter) :
		SourceLocalTargetResolution
}

internal sealed interface SourceRunHighWaterRead {
	data class Ready(val highWaterAdmissionOrdinal: Long) : SourceRunHighWaterRead
	data object CleanupOnlyProductEvidence : SourceRunHighWaterRead
	data object Unverifiable : SourceRunHighWaterRead
}

private data class SourceDrainProviderIdentity(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
)

internal data class SourceDrainActionIdentity(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val leaseGeneration: Long,
)

internal suspend fun sourceRunHighWater(
	database: AppDatabase,
	source: SourceKind,
	logicalTrackingId: String,
	serviceRunId: String,
	runManifestRevisions: List<Long>,
	throughOrdinal: Long,
	productMemberships: List<SourceDrainMembership>,
	retirementClaims: List<SourceDrainRetirementClaim>,
): SourceRunHighWaterRead = SourceRunAssociationAudit(
	database, source, logicalTrackingId, serviceRunId, throughOrdinal,
).read(runManifestRevisions, productMemberships, retirementClaims)

/** One invocation owns its high-water progress and lazy accepted-Steps-action cache. */
private class SourceRunAssociationAudit(
	private val database: AppDatabase,
	private val source: SourceKind,
	private val logicalTrackingId: String,
	private val serviceRunId: String,
	private val throughOrdinal: Long,
) {
	private var acceptedStepsActions: Set<SourceDrainActionIdentity>? = null
	private var highWater = 0L
	private var cleanupOnlyProductEvidence = false

	// Ordered input/provider/manifest/exact/wrong-run rejection exits preserve the audit failure boundary.
	@Suppress("ReturnCount")
	suspend fun read(
		runManifestRevisions: List<Long>,
		productMemberships: List<SourceDrainMembership>,
		retirementClaims: List<SourceDrainRetirementClaim>,
	): SourceRunHighWaterRead {
		if (hasInvalidAuditInput(runManifestRevisions, productMemberships, retirementClaims)) {
			return SourceRunHighWaterRead.Unverifiable
		}
		val productProviders = productMemberships.map { membership ->
			SourceDrainProviderIdentity(
				membership.sourceInstanceId,
				membership.registrationGeneration,
			)
		}.toSet()
		val claimsByProvider = retirementClaims.groupBy { claim ->
			SourceDrainProviderIdentity(
				claim.sourceInstanceId,
				claim.registrationGeneration,
			)
		}
		val cleanupOnlyProviders = retirementClaims
			.filter(SourceDrainRetirementClaim::cleanupOnly)
			.map { claim ->
				SourceDrainProviderIdentity(
					claim.sourceInstanceId,
					claim.registrationGeneration,
				)
			}.toSet()
		if (
			productProviders.size != productMemberships.size ||
			productProviders.any { provider -> provider in cleanupOnlyProviders } ||
			(productProviders + claimsByProvider.keys).size > RAW_WAL_PROVIDER_GENERATION_ENVELOPE
		) {
			return SourceRunHighWaterRead.Unverifiable
		}
		val dao = database.sourceEventWalDao()
		if (!auditManifestAssociations(dao, runManifestRevisions, productProviders, claimsByProvider)) {
			return SourceRunHighWaterRead.Unverifiable
		}
		val auditedGenerations = mutableSetOf<Pair<SourceDrainProviderIdentity, Long>>()
		for (manifestRevisions in runManifestRevisions.chunked(RAW_WAL_MANIFEST_QUERY_CHUNK)) {
			if (!auditExactAssociations(dao, manifestRevisions, productProviders, claimsByProvider,
				retirementClaims, auditedGenerations)) {
				return SourceRunHighWaterRead.Unverifiable
			}
			if (!auditWrongRunAssociations(dao, manifestRevisions, productProviders, claimsByProvider)) {
				return SourceRunHighWaterRead.Unverifiable
			}
		}
		return if (cleanupOnlyProductEvidence) {
			SourceRunHighWaterRead.CleanupOnlyProductEvidence
		} else {
			SourceRunHighWaterRead.Ready(highWater)
		}
	}

	private fun hasInvalidAuditInput(
		runManifestRevisions: List<Long>,
		productMemberships: List<SourceDrainMembership>,
		retirementClaims: List<SourceDrainRetirementClaim>,
	): Boolean = runManifestRevisions.isEmpty() ||
		runManifestRevisions.size > MAX_RUN_DRAIN_MANIFEST_REVISIONS ||
		runManifestRevisions.any { revision -> revision <= 0L } ||
		runManifestRevisions != runManifestRevisions.distinct().sorted() ||
		productMemberships.size > RAW_WAL_PROVIDER_GENERATION_ENVELOPE ||
		retirementClaims.size > RAW_WAL_PROVIDER_GENERATION_ENVELOPE

	// Keep page overflow, missing or repeated cursor and row rejection exits explicit in traversal order.
	@Suppress("ReturnCount")
	private suspend fun auditManifestAssociations(
		dao: SourceEventWalDao,
		runManifestRevisions: List<Long>,
		productProviders: Set<SourceDrainProviderIdentity>,
		claimsByProvider: Map<SourceDrainProviderIdentity, List<SourceDrainRetirementClaim>>,
	): Boolean {
		val authenticatedManifestRevisions = runManifestRevisions.toHashSet()
		val associatedProviders = HashSet<SourceDrainProviderIdentity>()
		var afterAssociationRowId: Long? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = dao.rawExactRunSourceManifestRevisionAssociations(
				sourceKind = source.stableCode,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				throughOrdinal = throughOrdinal,
				capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				controlPurposeMask = SourceBrokerPurpose.CONTROL_MASK,
				allowedPurposeMask = SourceBrokerPurpose.ALL_MASK,
				afterAssociationRowId = afterAssociationRowId,
				limit = RAW_WAL_ASSOCIATION_PAGE_SIZE,
			)
			currentCoroutineContext().ensureActive()
			if (page.size > RAW_WAL_ASSOCIATION_PAGE_SIZE) {
				return false
			}
			for (row in page) {
				currentCoroutineContext().ensureActive()
				val pageRowId = row.associationPageRowId
					?: return false
				if (afterAssociationRowId?.let { prior -> pageRowId <= prior } == true) {
					return false
				}
				afterAssociationRowId = pageRowId
				if (!auditManifestAssociation(row, authenticatedManifestRevisions, associatedProviders,
					productProviders, claimsByProvider)) {
					return false
				}
			}
			if (page.size < RAW_WAL_ASSOCIATION_PAGE_SIZE) {
				break
			}
		}
		return true
	}

	// Preserve missing revision/provider, provider envelope and foreign-capture rejection precedence.
	@Suppress("ReturnCount")
	private fun auditManifestAssociation(
		row: RawSourceRunWalManifestRevisionEvidence,
		authenticatedManifestRevisions: Set<Long>,
		associatedProviders: MutableSet<SourceDrainProviderIdentity>,
		productProviders: Set<SourceDrainProviderIdentity>,
		claimsByProvider: Map<SourceDrainProviderIdentity, List<SourceDrainRetirementClaim>>,
	): Boolean {
		val revision = row.sessionManifestRevision ?: return false
		val provider = row.validManifestProviderOrNull() ?: return false
		associatedProviders += provider
		if (associatedProviders.size > RAW_WAL_PROVIDER_GENERATION_ENVELOPE) {
			return false
		}
		if (revision !in authenticatedManifestRevisions) {
			val providerOwnsCapture =
				provider in productProviders || claimsByProvider[provider].orEmpty().isNotEmpty()
			if (
				providerOwnsCapture ||
				row.productEligibleRowCount != 0L ||
				row.controlOnlyRowCount != row.associatedRowCount
			) {
				return false
			}
		}
		return true
	}

	// Decode the complete fixed nullable SQL provider/count row before constructing its existing identity.
	@Suppress("ReturnCount")
	private fun RawSourceRunWalManifestRevisionEvidence.validManifestProviderOrNull(): SourceDrainProviderIdentity? {
		val sourceInstanceId = this.sourceInstanceId
			?: return null
		val registrationGeneration = this.registrationGeneration
			?: return null
		val associatedRowCount = this.associatedRowCount
			?: return null
		val malformedRowCount = this.malformedRowCount
			?: return null
		val productEligibleRowCount = this.productEligibleRowCount
			?: return null
		val controlOnlyRowCount = this.controlOnlyRowCount
			?: return null
		if (
			sourceInstanceId.isBlank() ||
			registrationGeneration <= 0L ||
			!hasValidAssociationCounts(associatedRowCount, malformedRowCount,
				productEligibleRowCount, controlOnlyRowCount)
		) {
			return null
		}
		return SourceDrainProviderIdentity(sourceInstanceId, registrationGeneration)
	}

	private suspend fun auditExactAssociations(
		dao: SourceEventWalDao,
		manifestRevisions: List<Long>,
		productProviders: Set<SourceDrainProviderIdentity>,
		claimsByProvider: Map<SourceDrainProviderIdentity, List<SourceDrainRetirementClaim>>,
		retirementClaims: List<SourceDrainRetirementClaim>,
		auditedGenerations: MutableSet<Pair<SourceDrainProviderIdentity, Long>>,
	): Boolean {
		currentCoroutineContext().ensureActive()
		val exactAssociations = dao.rawExactRunSourceManifestAssociations(
			sourceKind = source.stableCode,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = manifestRevisions,
			throughOrdinal = throughOrdinal,
			capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			controlPurposeMask = SourceBrokerPurpose.CONTROL_MASK,
			allowedPurposeMask = SourceBrokerPurpose.ALL_MASK,
			limit = RAW_WAL_PROVIDER_GENERATION_QUERY_LIMIT,
		)
		if (exactAssociations.size >= RAW_WAL_PROVIDER_GENERATION_QUERY_LIMIT) {
			return false
		}
		for (row in exactAssociations) {
			currentCoroutineContext().ensureActive()
			if (!auditExactAssociation(row, productProviders, claimsByProvider, retirementClaims, auditedGenerations)) {
				return false
			}
		}
		return true
	}

	// Keep count, owner, admission and retained-claim exits before mutating the audit high-water.
	@Suppress("ReturnCount")
	private suspend fun auditExactAssociation(
		row: RawSourceRunWalGenerationEvidence,
		productProviders: Set<SourceDrainProviderIdentity>,
		claimsByProvider: Map<SourceDrainProviderIdentity, List<SourceDrainRetirementClaim>>,
		retirementClaims: List<SourceDrainRetirementClaim>,
		auditedGenerations: MutableSet<Pair<SourceDrainProviderIdentity, Long>>,
	): Boolean {
		val provider = row.validGenerationProviderOrNull() ?: return false
		val lifecycleLeaseGeneration = requireNotNull(row.lifecycleLeaseGeneration)
		val providerClaims = claimsByProvider[provider].orEmpty()
		val providerOwnsCapture = provider in productProviders || providerClaims.isNotEmpty()
		if (!providerOwnsCapture) {
			if (row.productEligibleRowCount != 0L || row.controlOnlyRowCount != row.associatedRowCount) {
				return false
			}
			return true
		}
		if (row.productEligibleRowCount != row.associatedRowCount) {
			return false
		}
		val admissionOrdinal = row.highWaterAdmissionOrdinal ?: return false
		if (admissionOrdinal !in 1L..throughOrdinal) {
			return false
		}
		val exactClaims = providerClaims.filter { claim -> claim.leaseGeneration == lifecycleLeaseGeneration }
		if (!hasExactAssociationClaims(provider, lifecycleLeaseGeneration, exactClaims, retirementClaims)) {
			return false
		}
		return recordAuditedGeneration(
			provider, lifecycleLeaseGeneration, admissionOrdinal, exactClaims, productProviders, auditedGenerations,
		)
	}

	private suspend fun hasExactAssociationClaims(
		provider: SourceDrainProviderIdentity,
		lifecycleLeaseGeneration: Long,
		exactClaims: List<SourceDrainRetirementClaim>,
		retirementClaims: List<SourceDrainRetirementClaim>,
	): Boolean {
		if (retirementClaims.isNotEmpty() && exactClaims.isEmpty()) {
			if (source != SourceKind.STEPS) {
				return false
			}
			val accepted = acceptedStepsActions ?: stepsProductActionIdentities(database, logicalTrackingId,
				serviceRunId, retirementClaims).also { acceptedStepsActions = it }
			if (SourceDrainActionIdentity(provider.sourceInstanceId, provider.registrationGeneration,
				lifecycleLeaseGeneration) !in accepted) {
				return false
			}
		}
		return exactClaims.map(SourceDrainRetirementClaim::cleanupOnly).distinct().size <= 1
	}

	private fun recordAuditedGeneration(
		provider: SourceDrainProviderIdentity,
		lifecycleLeaseGeneration: Long,
		admissionOrdinal: Long,
		exactClaims: List<SourceDrainRetirementClaim>,
		productProviders: Set<SourceDrainProviderIdentity>,
		auditedGenerations: MutableSet<Pair<SourceDrainProviderIdentity, Long>>,
	): Boolean {
		auditedGenerations += provider to lifecycleLeaseGeneration
		if (auditedGenerations.size > RAW_WAL_PROVIDER_GENERATION_ENVELOPE) {
			return false
		}
		if (exactClaims.any(SourceDrainRetirementClaim::cleanupOnly)) {
			cleanupOnlyProductEvidence = true
		} else if (provider !in productProviders) {
			return false
		} else {
			highWater = maxOf(highWater, admissionOrdinal)
		}
		return true
	}

	// Decode the seven required SQL fields and their complete count/provider/lease correspondence.
	// Traversal, retained action policy and high-water mutation remain separate.
	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun RawSourceRunWalGenerationEvidence.validGenerationProviderOrNull(): SourceDrainProviderIdentity? {
		val associatedRowCount = this.associatedRowCount
			?: return null
		val malformedRowCount = this.malformedRowCount
			?: return null
		val productEligibleRowCount = this.productEligibleRowCount
			?: return null
		val controlOnlyRowCount = this.controlOnlyRowCount
			?: return null
		val sourceInstanceId = this.sourceInstanceId
			?: return null
		val registrationGeneration = this.registrationGeneration
			?: return null
		val lifecycleLeaseGeneration = this.lifecycleLeaseGeneration
			?: return null
		if (
			sourceInstanceId.isBlank() ||
			registrationGeneration <= 0L ||
			lifecycleLeaseGeneration <= 0L ||
			!hasValidAssociationCounts(associatedRowCount, malformedRowCount,
				productEligibleRowCount, controlOnlyRowCount)
		) {
			return null
		}
		return SourceDrainProviderIdentity(sourceInstanceId, registrationGeneration)
	}

	private suspend fun auditWrongRunAssociations(
		dao: SourceEventWalDao,
		manifestRevisions: List<Long>,
		productProviders: Set<SourceDrainProviderIdentity>,
		claimsByProvider: Map<SourceDrainProviderIdentity, List<SourceDrainRetirementClaim>>,
	): Boolean {
		currentCoroutineContext().ensureActive()
		val wrongRunAssociations = dao.rawWrongRunSourceManifestAssociations(
			sourceKind = source.stableCode,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			runManifestRevisions = manifestRevisions,
			capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			controlPurposeMask = SourceBrokerPurpose.CONTROL_MASK,
			allowedPurposeMask = SourceBrokerPurpose.ALL_MASK,
			limit = RAW_WAL_PROVIDER_GENERATION_QUERY_LIMIT,
		)
		if (wrongRunAssociations.size >= RAW_WAL_PROVIDER_GENERATION_QUERY_LIMIT) {
			return false
		}
		for (row in wrongRunAssociations) {
			currentCoroutineContext().ensureActive()
			if (!isIgnorableWrongRunAssociation(row, productProviders, claimsByProvider)) {
				return false
			}
		}
		return true
	}

	private fun isIgnorableWrongRunAssociation(
		row: RawSourceRunWalGenerationEvidence,
		productProviders: Set<SourceDrainProviderIdentity>,
		claimsByProvider: Map<SourceDrainProviderIdentity, List<SourceDrainRetirementClaim>>,
	): Boolean {
		val admissionOrdinal = row.highWaterAdmissionOrdinal ?: return false
		val provider = row.validGenerationProviderOrNull() ?: return false
		if (admissionOrdinal <= 0L) {
			return false
		}
		val expectedCapture = provider in productProviders || claimsByProvider[provider].orEmpty().isNotEmpty()
		return !expectedCapture && row.productEligibleRowCount == 0L &&
			row.controlOnlyRowCount == row.associatedRowCount
	}

}

/** Common count contract for bounded WAL association queries, without inferring missing rows. */
private fun hasValidAssociationCounts(
	associatedRows: Long,
	malformedRows: Long,
	productRows: Long,
	controlRows: Long,
): Boolean = associatedRows > 0L && malformedRows == 0L &&
	productRows in 0L..associatedRows && controlRows in 0L..associatedRows

private suspend fun cleanupOnlyStepsEvidenceFailure(
	database: AppDatabase,
	logicalTrackingId: String,
	serviceRunId: String,
	productMemberships: List<SourceDrainMembership>,
	retirementClaims: List<SourceDrainRetirementClaim>,
): String? {
	val countDomainStore = StepsCountDomainStore(database)
	val cleanupOnlyClaims = retirementClaims.filter(SourceDrainRetirementClaim::cleanupOnly)
	for (claim in cleanupOnlyClaims) {
		when (
			countDomainStore.authenticateTerminalSessionCompleteness(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				sourceInstanceId = claim.sourceInstanceId,
				registrationGeneration = claim.registrationGeneration,
			)
		) {
			com.adsamcik.tracker.shared.base.database.StepsTerminalCompletenessAuthentication.Absent ->
				Unit
			else -> return "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE"
		}
	}
	val productProviders = productMemberships.map { membership ->
		SourceDrainProviderIdentity(
			membership.sourceInstanceId,
			membership.registrationGeneration,
		)
	}.toSet()
	val cleanupOnlyProviders = cleanupOnlyClaims.map { claim ->
		SourceDrainProviderIdentity(
			claim.sourceInstanceId,
			claim.registrationGeneration,
		)
	}.toSet()
	val productActions = stepsProductActionIdentities(database, logicalTrackingId, serviceRunId, retirementClaims)
	return auditStepsDrainFacts(database, logicalTrackingId, serviceRunId,
		productProviders, cleanupOnlyProviders, productActions)
}

/** Same immutable settlement fence at plan creation and immediately before source drain. */
private fun LogicalTrackingSessionEntity.hasChangedDrainFence(
	run: SourceServiceRunEntity,
	expectedLogicalTrackingId: String,
	expectedServiceRunId: String,
	cutoffElapsedRealtimeNanos: Long,
	cutoffWallTimeMs: Long,
	settlementHighWaterAdmissionOrdinal: Long,
): Boolean = run.logicalTrackingId != expectedLogicalTrackingId ||
	run.state != SessionLifecycleState.STOPPING.name ||
	state !in setOf(SessionLifecycleState.ACTIVE.name, SessionLifecycleState.STOPPING.name) ||
	currentServiceRunId != expectedServiceRunId ||
	cutoffElapsedNanos != cutoffElapsedRealtimeNanos ||
	cutoffAtMs != cutoffWallTimeMs ||
	finalAdmissionOrdinal != settlementHighWaterAdmissionOrdinal

/** Exact physical provider, action attempt and acknowledged retirement disposition. */
// Keep the complete saved receipt/claim tuple and its two terminal-state alternatives together.
@Suppress("CyclomaticComplexMethod")
private fun SourceRunRetirementEntity?.doesNotMatchDrainClaim(
	expectedLogicalTrackingId: String,
	expectedServiceRunId: String,
	expectedSourceKind: Int,
	claim: SourceDrainRetirementClaim,
): Boolean = this == null ||
	this.logicalTrackingId != expectedLogicalTrackingId ||
	this.serviceRunId != expectedServiceRunId ||
	this.sourceKind != expectedSourceKind ||
	this.sourceInstanceId != claim.sourceInstanceId ||
	this.registrationGeneration != claim.registrationGeneration ||
	this.actionId != claim.actionId ||
	this.attemptCount != claim.attemptCount ||
	this.leaseGeneration != claim.leaseGeneration ||
	(if (claim.cleanupOnly) {
		this.state != SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED
	} else {
		this.state !in setOf(SourceRunRetirementEntity.STATE_ACKNOWLEDGED,
			SourceRunRetirementEntity.STATE_INTERRUPTED)
	})

/** Audits one saved fact; paging remains owned by cleanupOnlyStepsEvidenceFailure. */
// Ordered foreign-owner, nonlive, malformed and missing WAL exits preserve source drain failure precedence.
@Suppress("ReturnCount")
private suspend fun AppDatabase.stepsFactAuthorityFailure(
	fact: StepFactRevisionEntity,
	logicalTrackingId: String,
	serviceRunId: String,
	productProviders: Set<SourceDrainProviderIdentity>,
	cleanupOnlyProviders: Set<SourceDrainProviderIdentity>,
	productActions: Set<SourceDrainActionIdentity>,
): String? {
	if (
		fact.logicalTrackingId != logicalTrackingId ||
		fact.serviceRunId != serviceRunId
	) {
		return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
	}
	if (
		fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
		fact.operation != StepFactRevisionEntity.OPERATION_UPSERT
	) {
		return null
	}
	if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact)) {
		return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
	}
	val admissionOrdinal = fact.sourceAdmissionOrdinal
		?: return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
	val wal = sourceEventWalDao().getByAdmissionOrdinal(admissionOrdinal)
		?: return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
	if (!wal.matchesDrainStepsFact(fact, logicalTrackingId, serviceRunId)) {
		return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
	}
	return wal.stepsDrainProviderFailure(productProviders, cleanupOnlyProviders, productActions)
}

private fun SourceEventWalEntity.matchesDrainStepsFact(
	fact: StepFactRevisionEntity,
	logicalTrackingId: String,
	serviceRunId: String,
): Boolean = sourceKind == SourceKind.STEPS.stableCode &&
	eventId == fact.sourceEventId &&
	this.logicalTrackingId == logicalTrackingId &&
	this.serviceRunId == serviceRunId &&
	authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L &&
	hasQualifiedIntegrity()

private fun SourceEventWalEntity.stepsDrainProviderFailure(
	productProviders: Set<SourceDrainProviderIdentity>,
	cleanupOnlyProviders: Set<SourceDrainProviderIdentity>,
	productActions: Set<SourceDrainActionIdentity>,
): String? {
	val provider = SourceDrainProviderIdentity(
		sourceInstanceId,
		registrationGeneration,
	)
	return when {
		provider in cleanupOnlyProviders ->
			"SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE"
		provider !in productProviders ->
			"SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
		productActions.isNotEmpty() && SourceDrainActionIdentity(
			sourceInstanceId,
			registrationGeneration,
			lifecycleLeaseGeneration
				?: return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE",
		) !in productActions -> "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
		else -> null
	}
}

private fun SourceSessionCompletenessEntity.toDrainMembership() = SourceDrainMembership(
	sourceInstanceId = sourceInstanceId,
	registrationGeneration = registrationGeneration,
	lastAdmissionOrdinal = lastAdmissionOrdinal,
	lastSourceSequence = lastSourceSequence,
	appDrainComplete = appDrainComplete,
	providerCoverage = providerCoverage,
	stopStatus = stopStatus,
	unresolvedSequenceStart = unresolvedSequenceStart,
	unresolvedSequenceEndInclusive = unresolvedSequenceEnd,
)

private fun completeResult(
	request: SourceProductDrainRequest,
	lastCompletedOrdinal: Long,
	factsInserted: Int,
	eventsValidated: Int,
): SourceProductDrainResult =
	if (lastCompletedOrdinal < request.sourceHighWaterAdmissionOrdinal) {
		SourceProductDrainResult.AuthorityChanged(
			request = request,
			reason = "SOURCE_PRODUCT_LANE_CUTOFF_PRECEDES_REQUESTED_HIGH_WATER",
			lastMaterializedAdmissionOrdinal = lastCompletedOrdinal,
		)
	} else {
		SourceProductDrainResult.Complete(
			request,
			lastCompletedOrdinal,
			factsInserted,
			eventsValidated,
		)
	}

private fun failureResult(
	request: SourceProductDrainRequest,
	lastCompletedOrdinal: Long,
	failedOrdinal: Long?,
	failureCode: String,
	terminal: Boolean,
): SourceProductDrainResult = if (terminal) {
	SourceProductDrainResult.Failed(
		request,
		lastCompletedOrdinal,
		failedOrdinal,
		failureCode,
		terminalFailureRecorded = true,
	)
} else {
	SourceProductDrainResult.Deferred(
		request,
		lastCompletedOrdinal,
		failedOrdinal,
		failureCode,
	)
}

internal suspend fun authenticateSourceProductDrainRequest(
	database: AppDatabase,
	request: SourceProductDrainRequest,
): String? = database.withTransaction {
	val dao = database.sourceSessionDao()
	val session = dao.session(request.logicalTrackingId)
		?: return@withTransaction "SOURCE_DRAIN_SESSION_MISSING"
	val run = dao.serviceRun(request.serviceRunId)
		?: return@withTransaction "SOURCE_DRAIN_RUN_MISSING"
	if (session.hasChangedDrainFence(run, request.logicalTrackingId, request.serviceRunId,
		request.cutoffElapsedRealtimeNanos, request.cutoffWallTimeMs, request.settlementHighWaterAdmissionOrdinal)
	) {
		return@withTransaction "SOURCE_DRAIN_SETTLEMENT_FENCE_CHANGED"
	}
	val cleanupOnlyClaims = request.retirementClaims.filter(
		SourceDrainRetirementClaim::cleanupOnly,
	)
	authenticateDrainRetirementClaims(dao, request)?.let { return@withTransaction it }
	val rawCompleteness = dao.rawSourceCompletenessForServiceRun(
		serviceRunId = request.serviceRunId,
		sourceKind = request.source.stableCode,
		limit = MAX_COMPLETENESS_PER_SOURCE + 1,
	)
	if (rawCompleteness.size > MAX_COMPLETENESS_PER_SOURCE) {
		return@withTransaction "SOURCE_DRAIN_COMPLETENESS_OVERFLOW"
	}
	val memberships = rawCompleteness.map { raw ->
		val row = raw.validatedOrNull()
			?: return@withTransaction "SOURCE_DRAIN_COMPLETENESS_UNVERIFIABLE"
		if (!row.belongsToDrainRequest(request)) {
			return@withTransaction "SOURCE_DRAIN_COMPLETENESS_UNVERIFIABLE"
		}
		row.toDrainMembership()
	}.sortedWith(
		compareBy(
			SourceDrainMembership::sourceInstanceId,
			SourceDrainMembership::registrationGeneration,
		),
	)
	drainMembershipAuthorityFailure(request, memberships, cleanupOnlyClaims)?.let {
		return@withTransaction it
	}
	authenticateDrainProductEvidence(database, request, memberships, cleanupOnlyClaims)
}

private fun SourceSessionCompletenessEntity.belongsToDrainRequest(
	request: SourceProductDrainRequest,
): Boolean = logicalTrackingId == request.logicalTrackingId &&
	serviceRunId == request.serviceRunId && sourceKind == request.source.stableCode

private suspend fun authenticateDrainRetirementClaims(
	dao: SourceSessionDao,
	request: SourceProductDrainRequest,
): String? {
	for (claim in request.retirementClaims) {
		val receipt = dao.rawRunRetirement(
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			sourceKind = request.source.stableCode,
			sourceInstanceId = claim.sourceInstanceId,
			registrationGeneration = claim.registrationGeneration,
		).singleOrNull()?.validatedOrNull()
		if (claim.source != request.source ||
			receipt.doesNotMatchDrainClaim(request.logicalTrackingId, request.serviceRunId,
				request.source.stableCode, claim)
		) {
			return "SOURCE_DRAIN_RETIREMENT_AUTHORITY_CHANGED"
		}
	}
	return null
}

private fun drainMembershipAuthorityFailure(
	request: SourceProductDrainRequest,
	memberships: List<SourceDrainMembership>,
	cleanupOnlyClaims: List<SourceDrainRetirementClaim>,
): String? {
	val cleanupOnlyProviders = cleanupOnlyClaims.map { claim ->
		SourceDrainProviderIdentity(claim.sourceInstanceId, claim.registrationGeneration)
	}.toSet()
	if (memberships.any { membership ->
			SourceDrainProviderIdentity(
				membership.sourceInstanceId,
				membership.registrationGeneration,
			) in cleanupOnlyProviders
		}
	) {
		return "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE"
	}
	if (memberships != request.memberships) {
		return "SOURCE_DRAIN_COMPLETENESS_CHANGED"
	}
	val productClaimProviders = request.retirementClaims
		.filterNot(SourceDrainRetirementClaim::cleanupOnly)
		.map { claim ->
			SourceDrainProviderIdentity(
				claim.sourceInstanceId,
				claim.registrationGeneration,
			)
		}.toSet()
	val membershipProviders = memberships.map { membership ->
		SourceDrainProviderIdentity(
			membership.sourceInstanceId,
			membership.registrationGeneration,
		)
	}.toSet()
	if (request.retirementClaims.isNotEmpty() && productClaimProviders != membershipProviders) {
		return "SOURCE_DRAIN_COMPLETENESS_CHANGED"
	}
	return null
}

// Preserve ordered cleanup evidence, missing high-water and changed high-water outcomes.
@Suppress("ReturnCount")
private suspend fun authenticateDrainProductEvidence(
	database: AppDatabase,
	request: SourceProductDrainRequest,
	memberships: List<SourceDrainMembership>,
	cleanupOnlyClaims: List<SourceDrainRetirementClaim>,
): String? {
	if (request.source == SourceKind.STEPS && cleanupOnlyClaims.isNotEmpty()) {
		cleanupOnlyStepsEvidenceFailure(
			database = database,
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			productMemberships = memberships,
			retirementClaims = request.retirementClaims,
		)?.let { reason -> return reason }
	}
	val sourceHighWater = when (
		val read = sourceRunHighWater(
			database,
			request.source,
			request.logicalTrackingId,
			request.serviceRunId,
			request.runManifestRevisions,
			request.settlementHighWaterAdmissionOrdinal,
			memberships,
			request.retirementClaims,
		)
	) {
		is SourceRunHighWaterRead.Ready -> read.highWaterAdmissionOrdinal
		SourceRunHighWaterRead.CleanupOnlyProductEvidence ->
			return "SOURCE_DRAIN_CLEANUP_PRODUCT_EVIDENCE"
		SourceRunHighWaterRead.Unverifiable ->
			return "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE"
	}
	if (
		maxOf(
			sourceHighWater,
			memberships.mapNotNull(SourceDrainMembership::lastAdmissionOrdinal).maxOrNull() ?: 0L,
		) != request.sourceHighWaterAdmissionOrdinal
	) {
		return "SOURCE_DRAIN_HIGH_WATER_CHANGED"
	}
	return null
}

private suspend fun auditStepsDrainFacts(
	database: AppDatabase,
	logicalTrackingId: String,
	serviceRunId: String,
	productProviders: Set<SourceDrainProviderIdentity>,
	cleanupOnlyProviders: Set<SourceDrainProviderIdentity>,
	productActions: Set<SourceDrainActionIdentity>,
): String? {
	var afterFact: UnvalidatedStepFactRevision? = null
	while (true) {
		val rawFacts = database.stepFactRevisionDao().rawRunRevisionsAfter(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			afterWriterProjectionId = afterFact?.writerProjectionId,
			afterWriterProjectionVersion = afterFact?.writerProjectionVersion,
			afterLogicalFactId = afterFact?.logicalFactId,
			afterSemanticRevision = afterFact?.semanticRevision,
			limit = STEPS_DRAIN_FACT_AUDIT_PAGE_SIZE,
		)
		if (rawFacts.isEmpty()) {
			break
		}
		database.auditStepsDrainFactPage(rawFacts, logicalTrackingId, serviceRunId,
			productProviders, cleanupOnlyProviders, productActions, afterFact)?.let { return it }
		afterFact = rawFacts.last()
		if (rawFacts.size < STEPS_DRAIN_FACT_AUDIT_PAGE_SIZE) {
			break
		}
	}
	return null
}

// The complete page audit uses the original run/provider/action context and previous saved cursor.
@Suppress("LongParameterList")
private suspend fun AppDatabase.auditStepsDrainFactPage(
	rawFacts: List<UnvalidatedStepFactRevision>,
	logicalTrackingId: String,
	serviceRunId: String,
	productProviders: Set<SourceDrainProviderIdentity>,
	cleanupOnlyProviders: Set<SourceDrainProviderIdentity>,
	productActions: Set<SourceDrainActionIdentity>,
	afterFact: UnvalidatedStepFactRevision?,
): String? {
	for (rawFact in rawFacts) {
		val fact = rawFact.validatedOrNull()
			?: return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
		stepsFactAuthorityFailure(fact, logicalTrackingId, serviceRunId,
			productProviders, cleanupOnlyProviders, productActions)?.let { return it }
	}
	val last = rawFacts.last()
	if (last.writerProjectionId == afterFact?.writerProjectionId &&
		last.writerProjectionVersion == afterFact?.writerProjectionVersion &&
		last.logicalFactId == afterFact?.logicalFactId && last.semanticRevision == afterFact?.semanticRevision) {
		return "SOURCE_DRAIN_FACT_AUTHORITY_UNVERIFIABLE"
	}
	return null
}

private const val MAX_DRAIN_COMPLETENESS_ROWS = 384
private const val MAX_COMPLETENESS_PER_SOURCE = 64
private const val RAW_WAL_MANIFEST_QUERY_CHUNK = 100
private const val RAW_WAL_ASSOCIATION_PAGE_SIZE = 128
// The bounded run-action history defines the supported distinct provider-generation envelope.
private const val RAW_WAL_PROVIDER_GENERATION_ENVELOPE = MAX_RUN_RETIREMENT_ACTIONS
private const val RAW_WAL_PROVIDER_GENERATION_QUERY_LIMIT =
	RAW_WAL_PROVIDER_GENERATION_ENVELOPE + 1
private const val STEPS_DRAIN_FACT_AUDIT_PAGE_SIZE = 128
