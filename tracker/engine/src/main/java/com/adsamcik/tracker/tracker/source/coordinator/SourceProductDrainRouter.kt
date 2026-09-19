package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsTerminalProductAuthentication
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
		val captured = authenticatedAuthority.captureBindings
		if (captured.any { (sourceKind, bindings) ->
				bindings.isEmpty() || bindings.any { binding ->
					binding.logicalTrackingId != logicalTrackingId ||
						binding.sourceKind != sourceKind ||
						binding.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
						!binding.persistenceEligible
				}
			}
		) {
			return@withTransaction SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_MANIFEST_INTEGRITY_MISMATCH",
			)
		}
		val rawCompleteness = sessionDao.rawCompletenessForServiceRun(
			serviceRunId,
			MAX_DRAIN_COMPLETENESS_ROWS + 1,
		)
		if (rawCompleteness.size > MAX_DRAIN_COMPLETENESS_ROWS) {
			return@withTransaction SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_COMPLETENESS_OVERFLOW",
			)
		}
		val completenessRows = rawCompleteness.map { raw ->
			raw.validatedOrNull()
				?: return@withTransaction SourceDrainAuthority.Failed(
					null,
					"SOURCE_DRAIN_COMPLETENESS_UNVERIFIABLE",
				)
		}
		if (completenessRows.any { row ->
				row.logicalTrackingId != logicalTrackingId || row.serviceRunId != serviceRunId
			}
		) {
			return@withTransaction SourceDrainAuthority.Failed(
				null,
				"SOURCE_DRAIN_COMPLETENESS_UNVERIFIABLE",
			)
		}
		val completeness = completenessRows.groupBy { row -> row.sourceKind }
		SourceDrainAuthority.Ready(captured, completeness)
	}
	if (authority is SourceDrainAuthority.Failed) {
		return SourceProductDrainPlan.Failed(authority.source, authority.reason)
	}
	authority as SourceDrainAuthority.Ready
	val requests = mutableListOf<SourceProductDrainRequest>()
	val settledResults = mutableListOf<SourceProductDrainResult>()
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
		val sourceWalHighWater = when (
			val read = sourceRunHighWater(
				database,
				source,
				logicalTrackingId,
				serviceRunId,
				settlementHighWaterAdmissionOrdinal,
			)
		) {
			is SourceRunHighWaterRead.Ready -> read.highWaterAdmissionOrdinal
			SourceRunHighWaterRead.Unverifiable ->
				return SourceProductDrainPlan.Failed(
					source,
					"SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE",
				)
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
		)
		if (source == SourceKind.STEPS) {
			val rows = authority.completeness.getValue(source.stableCode)
			when (
				StepsCountDomainStore(database).authenticateTerminalProductDisposition(
					logicalTrackingId,
					serviceRunId,
					rows,
				)
			) {
				StepsTerminalProductAuthentication.Materializable -> requests += request
				StepsTerminalProductAuthentication.TerminalUnavailable ->
					settledResults += SourceProductDrainResult.Unavailable(
						request,
						"STEPS_PRODUCT_TERMINALLY_UNAVAILABLE",
					)
				StepsTerminalProductAuthentication.SchemaUnavailable -> {
					if (memberships.all(SourceDrainMembership::isExactCompleteSettlement)) {
						requests += request
					} else if (
						memberships.all { membership ->
							membership.isExactCompleteSettlement() ||
								membership.isTerminalUnavailableSettlement()
						} &&
						memberships.any(SourceDrainMembership::isTerminalUnavailableSettlement)
					) {
						settledResults += SourceProductDrainResult.Unavailable(
							request,
							"STEPS_PRODUCT_TERMINALLY_UNAVAILABLE",
						)
					} else {
						return SourceProductDrainPlan.Failed(
							source,
							"SOURCE_DRAIN_SETTLEMENT_INCOMPLETE",
							memberships,
						)
					}
				}
				StepsTerminalProductAuthentication.Unverifiable ->
					return SourceProductDrainPlan.Failed(
						source,
						"SOURCE_DRAIN_SETTLEMENT_UNVERIFIABLE",
						memberships,
					)
			}
		} else if (memberships.all(SourceDrainMembership::isExactCompleteSettlement)) {
			requests += request
		} else {
			return SourceProductDrainPlan.Failed(
				source = source,
				reason = "SOURCE_DRAIN_SETTLEMENT_INCOMPLETE",
				memberships = memberships,
			)
		}
	}
	return SourceProductDrainPlan.Ready(requests, settledResults)
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

private sealed interface SourceRunHighWaterRead {
	data class Ready(val highWaterAdmissionOrdinal: Long) : SourceRunHighWaterRead
	data object Unverifiable : SourceRunHighWaterRead
}

private suspend fun sourceRunHighWater(
	database: AppDatabase,
	source: SourceKind,
	logicalTrackingId: String,
	serviceRunId: String,
	throughOrdinal: Long,
): SourceRunHighWaterRead {
	val rows = database.sourceEventWalDao().rawRunSourceCaptureHighWater(
		sourceKind = source.stableCode,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		throughOrdinal = throughOrdinal,
		capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		allowedPurposeMask = SourceBrokerPurpose.ALL_MASK,
		limit = RAW_WAL_HIGH_WATER_ENVELOPE,
	)
	var highWater = 0L
	for (row in rows) {
		val admissionOrdinal = row.admissionOrdinal
			?: return SourceRunHighWaterRead.Unverifiable
		val registrationGeneration = row.registrationGeneration
			?: return SourceRunHighWaterRead.Unverifiable
		val purposeEligibilityMask = row.authorizationPurposeEligibilityMask
			?: return SourceRunHighWaterRead.Unverifiable
		if (
			row.storageClassSignature != RAW_WAL_HIGH_WATER_STORAGE_CLASSES ||
			row.eventId.isNullOrBlank() ||
			admissionOrdinal !in 1L..throughOrdinal ||
			row.sourceKind != source.stableCode.toLong() ||
			row.logicalTrackingId != logicalTrackingId ||
			row.serviceRunId != serviceRunId ||
			row.sourceInstanceId.isNullOrBlank() ||
			registrationGeneration <= 0L ||
			purposeEligibilityMask < 0L ||
			(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK) != purposeEligibilityMask
		) {
			return SourceRunHighWaterRead.Unverifiable
		}
		if (
			(purposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE) != 0L
		) {
			highWater = maxOf(highWater, admissionOrdinal)
		}
	}
	return SourceRunHighWaterRead.Ready(highWater)
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

internal suspend fun authenticateSourceProductDrainRequest(
	database: AppDatabase,
	request: SourceProductDrainRequest,
): String? = database.withTransaction {
	val dao = database.sourceSessionDao()
	val session = dao.session(request.logicalTrackingId)
		?: return@withTransaction "SOURCE_DRAIN_SESSION_MISSING"
	val run = dao.serviceRun(request.serviceRunId)
		?: return@withTransaction "SOURCE_DRAIN_RUN_MISSING"
	if (
		run.logicalTrackingId != request.logicalTrackingId ||
		run.state != SessionLifecycleState.STOPPING.name ||
		session.state !in setOf(
			SessionLifecycleState.ACTIVE.name,
			SessionLifecycleState.STOPPING.name,
		) ||
		session.currentServiceRunId != request.serviceRunId ||
		session.cutoffElapsedNanos != request.cutoffElapsedRealtimeNanos ||
		session.cutoffAtMs != request.cutoffWallTimeMs ||
		session.finalAdmissionOrdinal != request.settlementHighWaterAdmissionOrdinal
	) {
		return@withTransaction "SOURCE_DRAIN_SETTLEMENT_FENCE_CHANGED"
	}
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
		if (
			row.logicalTrackingId != request.logicalTrackingId ||
			row.serviceRunId != request.serviceRunId ||
			row.sourceKind != request.source.stableCode
		) {
			return@withTransaction "SOURCE_DRAIN_COMPLETENESS_UNVERIFIABLE"
		}
		row.toDrainMembership()
	}.sortedWith(
		compareBy(
			SourceDrainMembership::sourceInstanceId,
			SourceDrainMembership::registrationGeneration,
		),
	)
	if (memberships != request.memberships) {
		return@withTransaction "SOURCE_DRAIN_COMPLETENESS_CHANGED"
	}
	val sourceHighWater = when (
		val read = sourceRunHighWater(
			database,
			request.source,
			request.logicalTrackingId,
			request.serviceRunId,
			request.settlementHighWaterAdmissionOrdinal,
		)
	) {
		is SourceRunHighWaterRead.Ready -> read.highWaterAdmissionOrdinal
		SourceRunHighWaterRead.Unverifiable ->
			return@withTransaction "SOURCE_DRAIN_HIGH_WATER_UNVERIFIABLE"
	}
	if (
		maxOf(
			sourceHighWater,
			memberships.mapNotNull(SourceDrainMembership::lastAdmissionOrdinal).maxOrNull() ?: 0L,
		) != request.sourceHighWaterAdmissionOrdinal
	) {
		return@withTransaction "SOURCE_DRAIN_HIGH_WATER_CHANGED"
	}
	null
}

private const val MAX_DRAIN_COMPLETENESS_ROWS = 384
private const val MAX_COMPLETENESS_PER_SOURCE = 64
private const val RAW_WAL_HIGH_WATER_ENVELOPE = 2
private const val RAW_WAL_HIGH_WATER_STORAGE_CLASSES =
	"text|integer|integer|text|text|text|integer|integer"
