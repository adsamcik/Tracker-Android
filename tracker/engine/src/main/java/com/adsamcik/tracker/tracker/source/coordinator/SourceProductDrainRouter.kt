package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
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

sealed interface SourceProductDrainTarget {
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
		require(source == SourceKind.LOCATION || target is SourceProductDrainTarget.SourceLocalWriter)
		require(source != SourceKind.LOCATION || target == SourceProductDrainTarget.ProtectedLocationWriter)
	}
}

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

	data class Deferred(
		override val request: SourceProductDrainRequest,
		val lastMaterializedAdmissionOrdinal: Long,
		val blockedAdmissionOrdinal: Long?,
		val reason: String,
	) : SourceProductDrainResult

	data class Inactive(
		override val request: SourceProductDrainRequest,
		val reason: String,
		val lastMaterializedAdmissionOrdinal: Long = 0L,
	) : SourceProductDrainResult {
		init {
			require(lastMaterializedAdmissionOrdinal >= 0L)
		}
	}

	data class Failed(
		override val request: SourceProductDrainRequest,
		val lastMaterializedAdmissionOrdinal: Long,
		val failedAdmissionOrdinal: Long?,
		val failureCode: String,
		val terminalFailureRecorded: Boolean,
	) : SourceProductDrainResult

	data class AuthorityChanged(
		override val request: SourceProductDrainRequest,
		val reason: String,
		val lastMaterializedAdmissionOrdinal: Long = 0L,
	) : SourceProductDrainResult {
		init {
			require(lastMaterializedAdmissionOrdinal >= 0L)
		}
	}
}

fun interface SourceProductDrainRouter {
	suspend fun drainThrough(request: SourceProductDrainRequest): SourceProductDrainResult
}

/**
 * Location-owned implementation must advance only after the protected canonical writer exposes
 * its exact receipt. It must not create a second Location writer.
 */
interface ProtectedLocationSourceDrain {
	fun requestDrain()
	suspend fun drainThrough(request: SourceProductDrainRequest): SourceProductDrainResult
}

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
		val current = buildSourceProductDrainPlan(
			database = database,
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			cutoffElapsedRealtimeNanos = request.cutoffElapsedRealtimeNanos,
			cutoffWallTimeMs = request.cutoffWallTimeMs,
			settlementHighWaterAdmissionOrdinal = request.settlementHighWaterAdmissionOrdinal,
		)
		if (current !is SourceProductDrainPlan.Ready ||
			current.requests.singleOrNull { it.source == request.source } != request
		) {
			return SourceProductDrainResult.AuthorityChanged(
				request,
				(current as? SourceProductDrainPlan.Failed)?.reason
					?: "SOURCE_DRAIN_REQUEST_AUTHORITY_CHANGED",
			)
		}
		return when (request.source) {
			SourceKind.LOCATION -> protectedLocationDrain.drainThrough(request)
			SourceKind.ACTIVITY -> activityLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
			SourceKind.STEPS -> stepsLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
			SourceKind.PRESSURE -> pressureLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
			SourceKind.WIFI -> wifiLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
			SourceKind.CELL -> cellLane.drainThrough(request.sourceHighWaterAdmissionOrdinal)
				.toSourceResult(request)
		}
	}
}

internal sealed interface SourceProductDrainPlan {
	data class Ready(val requests: List<SourceProductDrainRequest>) : SourceProductDrainPlan
	data class Failed(val source: SourceKind?, val reason: String) : SourceProductDrainPlan
}

internal suspend fun buildSourceProductDrainPlan(
	database: AppDatabase,
	logicalTrackingId: String,
	serviceRunId: String,
	cutoffElapsedRealtimeNanos: Long,
	cutoffWallTimeMs: Long,
	settlementHighWaterAdmissionOrdinal: Long,
): SourceProductDrainPlan {
	require(logicalTrackingId.isNotBlank())
	require(serviceRunId.isNotBlank())
	require(cutoffElapsedRealtimeNanos >= 0L)
	require(cutoffWallTimeMs >= 0L)
	require(settlementHighWaterAdmissionOrdinal >= 0L)
	val authority = database.withTransaction {
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(logicalTrackingId)
			?: return@withTransaction SourceDrainAuthority.Failed(null, "SOURCE_DRAIN_SESSION_MISSING")
		val run = sessionDao.serviceRun(serviceRunId)
			?: return@withTransaction SourceDrainAuthority.Failed(null, "SOURCE_DRAIN_RUN_MISSING")
		if (run.logicalTrackingId != logicalTrackingId ||
			run.state != SessionLifecycleState.STOPPING.name ||
			session.state !in setOf(
				SessionLifecycleState.ACTIVE.name,
				SessionLifecycleState.STOPPING.name,
			) ||
			session.currentServiceRunId != serviceRunId ||
			session.cutoffElapsedNanos != cutoffElapsedRealtimeNanos ||
			session.cutoffAtMs != cutoffWallTimeMs ||
			session.finalAdmissionOrdinal != settlementHighWaterAdmissionOrdinal
		) {
			return@withTransaction SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_SETTLEMENT_FENCE_CHANGED",
			)
		}
		val manifests = sessionDao.manifestsForServiceRun(serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
		if (manifests.isEmpty() || manifests.size > MAX_MANIFESTS_PER_RUN) {
			return@withTransaction SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_MANIFEST_TIMELINE_UNAVAILABLE",
			)
		}
		val sources = mutableListOf<SessionManifestSourceEntity>()
		for (manifest in manifests) {
			val bindings = sessionDao.manifestSources(logicalTrackingId, manifest.manifestRevision)
			if (!SessionManifestIntegrity.verify(manifest, bindings)) {
				return@withTransaction SourceDrainAuthority.Failed(
					null,
					"SOURCE_DRAIN_MANIFEST_INTEGRITY_MISMATCH",
				)
			}
			sources += bindings
		}
		val captured = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible
		}.groupBy { source -> source.sourceKind }
		val completeness = sessionDao.completenessForServiceRun(logicalTrackingId, serviceRunId)
			.groupBy { row -> row.sourceKind }
		SourceDrainAuthority.Ready(captured, completeness)
	}
	if (authority is SourceDrainAuthority.Failed) {
		return SourceProductDrainPlan.Failed(authority.source, authority.reason)
	}
	authority as SourceDrainAuthority.Ready
	val requests = mutableListOf<SourceProductDrainRequest>()
	for (source in SourceKind.entries) {
		val bindings = authority.captureBindings[source.stableCode].orEmpty()
		if (bindings.isEmpty()) continue
		val target = when (source) {
			SourceKind.LOCATION -> SourceProductDrainTarget.ProtectedLocationWriter
			else -> when (val resolution = bindings.sourceLocalTarget()) {
				SourceLocalTargetResolution.LegacyOrUnattributed -> continue
				SourceLocalTargetResolution.Invalid -> return SourceProductDrainPlan.Failed(
					source,
					"SOURCE_DRAIN_WRITER_PROVENANCE_INCONSISTENT",
				)
				is SourceLocalTargetResolution.Ready -> resolution.target
			}
		}
		val memberships = authority.completeness[source.stableCode].orEmpty()
			.map(SourceSessionCompletenessEntity::toDrainMembership)
			.sortedWith(compareBy(SourceDrainMembership::sourceInstanceId, SourceDrainMembership::registrationGeneration))
		if (memberships.isEmpty()) {
			return SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_COMPLETENESS_MISSING")
		}
		if (memberships.any { membership -> !membership.isExactCompleteSettlement() }) {
			// Terminal interrupted acquisition is truthful lifecycle evidence, not materialization
			// authority. Do not route it to a product lane or convert it into QUERYABLE success.
			continue
		}
		val sourceWalHighWater = sourceRunHighWater(
			database,
			source,
			logicalTrackingId,
			serviceRunId,
			settlementHighWaterAdmissionOrdinal,
		)
		val sourceHighWater = maxOf(
			sourceWalHighWater,
			memberships.mapNotNull(SourceDrainMembership::lastAdmissionOrdinal).maxOrNull() ?: 0L,
		)
		if (sourceHighWater > settlementHighWaterAdmissionOrdinal) {
			return SourceProductDrainPlan.Failed(source, "SOURCE_DRAIN_HIGH_WATER_EXCEEDS_SETTLEMENT")
		}

		private fun SourceDrainMembership.isExactCompleteSettlement(): Boolean =
			appDrainComplete &&
				stopStatus in setOf("COMPLETE", "PARTIAL_UNOBSERVABLE") &&
				unresolvedSequenceStart == null &&
				unresolvedSequenceEndInclusive == null
		requests += SourceProductDrainRequest(
			source = source,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			cutoffElapsedRealtimeNanos = cutoffElapsedRealtimeNanos,
			cutoffWallTimeMs = cutoffWallTimeMs,
			settlementHighWaterAdmissionOrdinal = settlementHighWaterAdmissionOrdinal,
			sourceHighWaterAdmissionOrdinal = sourceHighWater,
			memberships = memberships,
			target = target,
		)
	}
	return SourceProductDrainPlan.Ready(requests)
}

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

private fun List<SessionManifestSourceEntity>.sourceLocalTarget(): SourceLocalTargetResolution {
	val projectionAttributed = map { source ->
		listOf(
			source.writerProjectionId,
			source.writerProjectionVersion,
			source.writerBindingGeneration,
		).all { it != null }
	}
	if (projectionAttributed.none { it }) return SourceLocalTargetResolution.LegacyOrUnattributed
	if (!projectionAttributed.all { it }) return SourceLocalTargetResolution.Invalid
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

private suspend fun sourceRunHighWater(
	database: AppDatabase,
	source: SourceKind,
	logicalTrackingId: String,
	serviceRunId: String,
	throughOrdinal: Long,
): Long {
	var cursor = 0L
	var highWater = 0L
	while (cursor < throughOrdinal) {
		val rows = database.sourceEventWalDao().sourceEventsAfterThrough(
			sourceKind = source.stableCode,
			afterOrdinal = cursor,
			throughOrdinal = throughOrdinal,
			limit = SOURCE_WAL_PAGE_SIZE,
		)
		if (rows.isEmpty()) break
		rows.asSequence()
			.filter { row ->
				row.logicalTrackingId == logicalTrackingId &&
					row.serviceRunId == serviceRunId &&
					row.authorizationPurposeEligibilityMask and
					SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
			}
			.maxOfOrNull { row -> row.admissionOrdinal }
			?.let { highWater = maxOf(highWater, it) }
		val next = rows.last().admissionOrdinal
		check(next > cursor) { "Source WAL drain planning did not advance" }
		cursor = next
	}
	return highWater
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

private const val MAX_MANIFESTS_PER_RUN = 128
private const val SOURCE_WAL_PAGE_SIZE = 256
