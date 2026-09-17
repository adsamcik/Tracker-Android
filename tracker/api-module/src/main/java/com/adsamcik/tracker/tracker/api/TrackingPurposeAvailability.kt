package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.model.tracking.TrackingSource as CanonicalTrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity as CanonicalSourcePurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutomaticTrackingUnavailableReason(
	val stableCode: String,
	val containmentReason: TrackingDecisionContainmentReason,
) {
	AUTO_005_CONTROL_EVIDENCE_UNRESOLVED(
		"AUTO_005_CONTROL_EVIDENCE_UNRESOLVED",
		TrackingDecisionContainmentReason.AUTO_005_CONTROL_EVIDENCE_UNRESOLVED,
	),
	CONTROL_RETENTION_POLICY_UNAVAILABLE(
		"CONTROL_RETENTION_POLICY_UNAVAILABLE",
		TrackingDecisionContainmentReason.RETENTION_AUTHORITY_UNAVAILABLE,
	),
}

sealed interface AutomaticTrackingOperationalAvailability {
	data class Ready(
		val identity: TrackingPurposeLeaseIdentity,
	) : AutomaticTrackingOperationalAvailability {
		init {
			require(identity.source == CanonicalTrackingSource.ACTIVITY)
			require(identity.purpose == TrackingPurpose.CONTROL)
			require(identity.executionRevision > 0L) {
				"Operational automatic CONTROL requires a bound execution revision"
			}
		}
	}

	data class Unavailable(
		val reason: AutomaticTrackingUnavailableReason,
		val lastIdentity: TrackingPurposeLeaseIdentity? = null,
	) : AutomaticTrackingOperationalAvailability {
		init {
			lastIdentity?.let { identity ->
				require(identity.source == CanonicalTrackingSource.ACTIVITY)
				require(identity.purpose == TrackingPurpose.CONTROL)
			}
		}
	}

	val isOperational: Boolean
		get() = this is Ready

	val authorityIdentityOrNull: TrackingPurposeLeaseIdentity?
		get() = (this as? Ready)?.identity
}

enum class AmbientTrackingSource(
	val canonicalSource: CanonicalTrackingSource,
) {
	STEPS(CanonicalTrackingSource.STEPS),
	LOCATION(CanonicalTrackingSource.LOCATION),
	WIFI(CanonicalTrackingSource.WIFI),
	CELL(CanonicalTrackingSource.CELL),
}

enum class AmbientSourceOperationalState {
	READY,
	WAITING,
	PERMISSION_REQUIRED,
	UNAVAILABLE,
	DEGRADED,
}

enum class AmbientAcquisitionMechanism {
	HEALTH_CONNECT_MOBILE_STEPS,
	LOCAL_RECORDING_STEPS,
	PASSIVE_LOCATION,
	WIFI_SCAN_RESULTS,
	CELL_CHANGE_CALLBACKS,
}

enum class AmbientSourceUnavailableReason(
	val stableCode: String,
	val containmentReason: TrackingDecisionContainmentReason? = null,
) {
	RETENTION_POLICY_UNAVAILABLE(
		"AMBIENT_RETENTION_POLICY_UNAVAILABLE",
		TrackingDecisionContainmentReason.RETENTION_AUTHORITY_UNAVAILABLE,
	),
	RECONCILIATION_PENDING("AMBIENT_RECONCILIATION_PENDING"),
	ROLLOUT_CONTAINED("AMBIENT_ROLLOUT_CONTAINED"),
	HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED("HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED"),
	HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL("HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL"),
	HEALTH_CONNECT_UNAVAILABLE("HEALTH_CONNECT_UNAVAILABLE"),
	HEALTH_CONNECT_UPDATE_REQUIRED("HEALTH_CONNECT_UPDATE_REQUIRED"),
	HEALTH_CONNECT_PROBE_FAILED("HEALTH_CONNECT_PROBE_FAILED"),
	LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED("LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED"),
	LOCAL_RECORDING_UNAVAILABLE("LOCAL_RECORDING_UNAVAILABLE"),
	BACKGROUND_LOCATION_PERMISSION_REQUIRED("BACKGROUND_LOCATION_PERMISSION_REQUIRED"),
	WIFI_SCAN_PERMISSION_REQUIRED("WIFI_SCAN_PERMISSION_REQUIRED"),
	CELL_SCAN_PERMISSION_REQUIRED("CELL_SCAN_PERMISSION_REQUIRED"),
	PLATFORM_UNAVAILABLE("AMBIENT_PLATFORM_UNAVAILABLE"),
	PROVIDER_UNAVAILABLE("AMBIENT_PROVIDER_UNAVAILABLE"),
}

data class AmbientSourceOperationalAvailability(
	val source: AmbientTrackingSource,
	val state: AmbientSourceOperationalState,
	val mechanism: AmbientAcquisitionMechanism? = null,
	val reason: AmbientSourceUnavailableReason? = null,
	val operationalIdentity: TrackingPurposeLeaseIdentity? = null,
	val lastIdentity: TrackingPurposeLeaseIdentity? = null,
) {
	init {
		require(mechanism == null || mechanism in source.allowedMechanisms()) {
			"$mechanism is not an acquisition mechanism for $source"
		}
		operationalIdentity?.requireAmbientIdentity(source)
		lastIdentity?.requireAmbientIdentity(source)
		when (state) {
			AmbientSourceOperationalState.READY -> {
				require(mechanism != null) { "Ready ambient availability requires a mechanism" }
				require(reason == null) { "Ready ambient availability must not carry a reason" }
				require(operationalIdentity != null) {
					"Ready ambient availability requires exact authority identity"
				}
				require(operationalIdentity.executionRevision > 0L) {
					"Ready ambient availability requires a bound execution revision"
				}
				require(lastIdentity == null) {
					"Ready ambient availability cannot also carry a last non-operational identity"
				}
			}
			AmbientSourceOperationalState.DEGRADED -> {
				require(
					source == AmbientTrackingSource.STEPS &&
						mechanism == AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS &&
						reason ==
						AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL,
				) {
					"Only Health Connect Steps may be operational with optional background-read gaps"
				}
				require(operationalIdentity != null && operationalIdentity.executionRevision > 0L) {
					"Degraded operational ambient availability requires exact bound authority"
				}
				require(lastIdentity == null)
			}
			AmbientSourceOperationalState.WAITING -> {
				require(
					mechanism == null &&
						reason == AmbientSourceUnavailableReason.RECONCILIATION_PENDING,
				) {
					"Waiting availability must be a provider-neutral pending reconciliation"
				}
				require(operationalIdentity == null) {
					"Waiting ambient availability cannot grant authority"
				}
			}
			AmbientSourceOperationalState.PERMISSION_REQUIRED -> {
				require(mechanism != null && reason == source.permissionReason(mechanism)) {
					"Permission remediation must match the source and selected mechanism"
				}
				require(operationalIdentity == null) {
					"Permission-required ambient availability cannot grant authority"
				}
			}
			AmbientSourceOperationalState.UNAVAILABLE -> {
				require(
					mechanism == null &&
						reason != null &&
						reason in source.unavailableReasons(),
				) {
					"Unavailable reason must match the source and must not imply an operational provider"
				}
				require(operationalIdentity == null) {
					"Unavailable ambient availability cannot grant authority"
				}
			}
		}
	}

	val isOperational: Boolean
		get() = state == AmbientSourceOperationalState.READY ||
			state == AmbientSourceOperationalState.DEGRADED

	companion object {
		fun reconciliationPending(
			source: AmbientTrackingSource,
			lastIdentity: TrackingPurposeLeaseIdentity? = null,
		): AmbientSourceOperationalAvailability = AmbientSourceOperationalAvailability(
			source = source,
			state = AmbientSourceOperationalState.WAITING,
			reason = AmbientSourceUnavailableReason.RECONCILIATION_PENDING,
			lastIdentity = lastIdentity,
		)
	}
}

data class TrackingPurposeAvailabilitySnapshot(
	val automaticControl: AutomaticTrackingOperationalAvailability =
		AutomaticTrackingOperationalAvailability.Unavailable(
			AutomaticTrackingUnavailableReason.AUTO_005_CONTROL_EVIDENCE_UNRESOLVED,
		),
	val ambientSources: Map<AmbientTrackingSource, AmbientSourceOperationalAvailability> =
		AmbientTrackingSource.entries.associateWith { source ->
			AmbientSourceOperationalAvailability.reconciliationPending(source)
		},
) {
	init {
		require(ambientSources.keys == AmbientTrackingSource.entries.toSet()) {
			"Ambient availability must describe every approved ambient source"
		}
		require(ambientSources.all { (source, availability) -> source == availability.source }) {
			"Ambient availability keys must match their source values"
		}
	}

	companion object {
		val SAFE_DEFAULT = TrackingPurposeAvailabilitySnapshot()
	}
}

interface TrackingPurposeAvailabilityReader {
	/** Parent-owned runtime catalog; reading it must not itself probe or register a provider. */
	val availability: StateFlow<TrackingPurposeAvailabilitySnapshot>
}

interface TrackingPurposeAvailabilityReporter {
	/** Publishes only after the owning runtime has reconciled policy, permission, rollout, and provider. */
	fun reportAutomaticControl(availability: AutomaticTrackingOperationalAvailability)
	fun beginOrReplaceAmbientLease(
		identity: AmbientReconciliationIdentity,
	): AmbientLeaseStartResult
	fun cancelAmbientLease(
		expectedIdentity: AmbientReconciliationIdentity,
	): AmbientPublicationAcceptance
	fun tryAccept(
		report: AmbientSourceReconciliationReport,
	): AmbientPublicationAcceptance
}

data class AmbientReconciliationIdentity(
	val source: AmbientTrackingSource,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	val ownerCasToken: String,
	val executionRevision: Long,
) {
	constructor(
		source: AmbientTrackingSource,
		policyRevision: Long,
		consentEpoch: Long,
		collectedDataEpoch: Long,
		rolloutRevision: Long,
		ownerCasToken: String,
	) : this(
		source = source,
		policyRevision = policyRevision,
		consentEpoch = consentEpoch,
		collectedDataEpoch = collectedDataEpoch,
		rolloutRevision = rolloutRevision,
		ownerCasToken = ownerCasToken,
		executionRevision = 0L,
	)

	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(ownerCasToken.isNotBlank())
		require(executionRevision >= 0L)
	}

	val sourcePurpose: CanonicalSourcePurpose
		get() = source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT)

	val purposeLeaseIdentity: TrackingPurposeLeaseIdentity
		get() = TrackingPurposeLeaseIdentity(
			sourcePurpose = sourcePurpose,
			policyRevision = policyRevision,
			consentEpoch = consentEpoch,
			collectedDataEpoch = collectedDataEpoch,
			rolloutRevision = rolloutRevision,
			executionRevision = executionRevision,
			ownerCasToken = ownerCasToken,
		)

	companion object {
		fun from(identity: TrackingPurposeLeaseIdentity): AmbientReconciliationIdentity {
			require(identity.purpose == TrackingPurpose.AMBIENT_PRODUCT) {
				"Ambient reconciliation requires AMBIENT_PRODUCT purpose"
			}
			return AmbientReconciliationIdentity(
				source = AmbientTrackingSource.entries.single { source ->
					source.canonicalSource == identity.source
				},
				policyRevision = identity.policyRevision,
				consentEpoch = identity.consentEpoch,
				collectedDataEpoch = identity.collectedDataEpoch,
				rolloutRevision = identity.rolloutRevision,
				ownerCasToken = identity.ownerCasToken,
				executionRevision = identity.executionRevision,
			)
		}
	}
}

data class AmbientReconciliationLease(
	val identity: AmbientReconciliationIdentity,
) {
	val purposeLeaseIdentity: TrackingPurposeLeaseIdentity
		get() = identity.purposeLeaseIdentity
}

data class AmbientSourceReconciliationReport(
	val identity: AmbientReconciliationIdentity,
	val availability: AmbientSourceOperationalAvailability,
) {
	init {
		require(identity.source == availability.source)
		require(availability.state != AmbientSourceOperationalState.WAITING) {
			"A completed reconciliation cannot publish the pre-reconciliation waiting state"
		}
		if (availability.isOperational) {
			require(availability.operationalIdentity == identity.purposeLeaseIdentity) {
				"Operational ambient publication must carry the exact reconciliation identity"
			}
		} else {
			require(availability.operationalIdentity == null)
			require(
				availability.lastIdentity == null ||
					availability.lastIdentity == identity.purposeLeaseIdentity,
			) {
				"Non-operational ambient publication may retain only its exact last identity"
			}
		}
	}
}

sealed interface AmbientPublicationAcceptance {
	data class Accepted(
		val snapshot: TrackingPurposeAvailabilitySnapshot,
	) : AmbientPublicationAcceptance

	data class Rejected(
		val reason: AmbientPublicationRejection,
	) : AmbientPublicationAcceptance
}

sealed interface AmbientLeaseStartResult {
	data class Started(
		val lease: AmbientReconciliationLease,
		val snapshot: TrackingPurposeAvailabilitySnapshot,
	) : AmbientLeaseStartResult

	data class Rejected(
		val reason: AmbientPublicationRejection,
	) : AmbientLeaseStartResult
}

enum class AmbientPublicationRejection {
	CANCELLED,
	STALE_IDENTITY,
	TOKEN_CONSUMED,
	TOKEN_REUSED,
}

sealed interface AmbientSourceReconciliationResult {
	data class Reconciled(
		val report: AmbientSourceReconciliationReport,
	) : AmbientSourceReconciliationResult {
		init {
			require(report.availability.isOperational)
		}
	}

	data class Unavailable(
		val report: AmbientSourceReconciliationReport,
	) : AmbientSourceReconciliationResult {
		init {
			require(!report.availability.isOperational)
		}
	}
}

fun interface AmbientSourceReconciliationCallback {
	/** Parent-owned lifecycle entry point. Settings UI never invokes this merely from preference state. */
	suspend fun reconcile(lease: AmbientReconciliationLease): AmbientSourceReconciliationResult
}

/**
 * Process-local atomic publication boundary. Runtime owners remain responsible for checking live
 * SourcePolicy, consent, collected-data, rollout, and provider authority before issuing a lease.
 * Publishing status never grants policy, demand, registration, or provider authority.
 */
class AtomicTrackingPurposeAvailabilityStore :
	TrackingPurposeAvailabilityReader,
	TrackingPurposeAvailabilityReporter {
	private val lock = Any()
	private val mutableAvailability = MutableStateFlow(TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT)
	private val ambientSlots = mutableMapOf<AmbientTrackingSource, AmbientPublicationSlot>()
	private val terminalTokens =
		mutableMapOf<AmbientLeaseToken, AmbientPublicationRejection>()

	override val availability: StateFlow<TrackingPurposeAvailabilitySnapshot> =
		mutableAvailability.asStateFlow()

	override fun reportAutomaticControl(
		availability: AutomaticTrackingOperationalAvailability,
	) {
		synchronized(lock) {
			mutableAvailability.value =
				mutableAvailability.value.copy(automaticControl = availability)
		}
	}

	override fun beginOrReplaceAmbientLease(
		identity: AmbientReconciliationIdentity,
	): AmbientLeaseStartResult = synchronized(lock) {
		terminalTokens[identity.leaseToken()]?.let { rejection ->
			return@synchronized AmbientLeaseStartResult.Rejected(rejection)
		}
		val previous = ambientSlots[identity.source]
		if (previous?.identity == identity && previous.state == AmbientPublicationSlotState.ACTIVE) {
			return@synchronized AmbientLeaseStartResult.Started(
				lease = AmbientReconciliationLease(identity),
				snapshot = mutableAvailability.value,
			)
		}
		if (previous?.identity?.ownerCasToken == identity.ownerCasToken) {
			return@synchronized AmbientLeaseStartResult.Rejected(
				AmbientPublicationRejection.TOKEN_REUSED,
			)
		}
		previous?.let { slot ->
			terminalTokens.putIfAbsent(
				slot.identity.leaseToken(),
				if (slot.state == AmbientPublicationSlotState.CONSUMED) {
					AmbientPublicationRejection.TOKEN_CONSUMED
				} else {
					AmbientPublicationRejection.CANCELLED
				},
			)
		}
		val priorAvailability = mutableAvailability.value.ambientSources.getValue(identity.source)
		ambientSlots[identity.source] = AmbientPublicationSlot(
			identity = identity,
			state = AmbientPublicationSlotState.ACTIVE,
		)
		publishPending(
			identity.source,
			priorAvailability.operationalIdentity ?: priorAvailability.lastIdentity,
		)
		AmbientLeaseStartResult.Started(
			lease = AmbientReconciliationLease(identity),
			snapshot = mutableAvailability.value,
		)
	}

	override fun cancelAmbientLease(
		expectedIdentity: AmbientReconciliationIdentity,
	): AmbientPublicationAcceptance = synchronized(lock) {
		val slot = ambientSlots[expectedIdentity.source]
			?: return@synchronized AmbientPublicationAcceptance.Rejected(
				AmbientPublicationRejection.STALE_IDENTITY,
			)
		if (slot.identity != expectedIdentity) {
			return@synchronized AmbientPublicationAcceptance.Rejected(
				AmbientPublicationRejection.STALE_IDENTITY,
			)
		}
		when (slot.state) {
			AmbientPublicationSlotState.CONSUMED ->
				return@synchronized AmbientPublicationAcceptance.Rejected(
					AmbientPublicationRejection.TOKEN_CONSUMED,
				)
			AmbientPublicationSlotState.CANCELLED ->
				return@synchronized AmbientPublicationAcceptance.Rejected(
					AmbientPublicationRejection.CANCELLED,
				)
			AmbientPublicationSlotState.ACTIVE -> Unit
		}
		ambientSlots[expectedIdentity.source] = slot.copy(
			state = AmbientPublicationSlotState.CANCELLED,
		)
		terminalTokens[expectedIdentity.leaseToken()] = AmbientPublicationRejection.CANCELLED
		val priorAvailability =
			mutableAvailability.value.ambientSources.getValue(expectedIdentity.source)
		publishPending(
			expectedIdentity.source,
			priorAvailability.operationalIdentity ?: priorAvailability.lastIdentity,
		)
		AmbientPublicationAcceptance.Accepted(mutableAvailability.value)
	}

	override fun tryAccept(
		report: AmbientSourceReconciliationReport,
	): AmbientPublicationAcceptance = synchronized(lock) {
		val slot = ambientSlots[report.identity.source]
			?: return@synchronized AmbientPublicationAcceptance.Rejected(
				AmbientPublicationRejection.STALE_IDENTITY,
			)
		when {
			slot.identity != report.identity -> AmbientPublicationAcceptance.Rejected(
				AmbientPublicationRejection.STALE_IDENTITY,
			)
			slot.state == AmbientPublicationSlotState.CANCELLED ->
				AmbientPublicationAcceptance.Rejected(AmbientPublicationRejection.CANCELLED)
			slot.state == AmbientPublicationSlotState.CONSUMED ->
				AmbientPublicationAcceptance.Rejected(AmbientPublicationRejection.TOKEN_CONSUMED)
			else -> {
				ambientSlots[report.identity.source] = slot.copy(
					state = AmbientPublicationSlotState.CONSUMED,
				)
				terminalTokens[report.identity.leaseToken()] =
					AmbientPublicationRejection.TOKEN_CONSUMED
				mutableAvailability.value = mutableAvailability.value.copy(
					ambientSources = mutableAvailability.value.ambientSources +
						(report.identity.source to report.availability),
				)
				AmbientPublicationAcceptance.Accepted(mutableAvailability.value)
			}
		}
	}

	private fun publishPending(
		source: AmbientTrackingSource,
		lastIdentity: TrackingPurposeLeaseIdentity?,
	) {
		mutableAvailability.value = mutableAvailability.value.copy(
			ambientSources = mutableAvailability.value.ambientSources +
				(
					source to AmbientSourceOperationalAvailability.reconciliationPending(
						source,
						lastIdentity,
					)
				),
		)
	}
}

private data class AmbientPublicationSlot(
	val identity: AmbientReconciliationIdentity,
	val state: AmbientPublicationSlotState,
)

private data class AmbientLeaseToken(
	val source: AmbientTrackingSource,
	val ownerCasToken: String,
)

private fun AmbientReconciliationIdentity.leaseToken(): AmbientLeaseToken =
	AmbientLeaseToken(source, ownerCasToken)

private fun TrackingPurposeLeaseIdentity.requireAmbientIdentity(source: AmbientTrackingSource) {
	require(this.source == source.canonicalSource)
	require(purpose == TrackingPurpose.AMBIENT_PRODUCT)
}

internal fun AmbientTrackingSource.toTrackingSource(): TrackingSource =
	canonicalSource.toApiTrackingSource()

private enum class AmbientPublicationSlotState {
	ACTIVE,
	CONSUMED,
	CANCELLED,
}

private fun AmbientTrackingSource.allowedMechanisms(): Set<AmbientAcquisitionMechanism> = when (this) {
	AmbientTrackingSource.STEPS -> setOf(
		AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
		AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
	)
	AmbientTrackingSource.LOCATION -> setOf(AmbientAcquisitionMechanism.PASSIVE_LOCATION)
	AmbientTrackingSource.WIFI -> setOf(AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS)
	AmbientTrackingSource.CELL -> setOf(AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS)
}

private fun AmbientTrackingSource.permissionReason(
	mechanism: AmbientAcquisitionMechanism,
): AmbientSourceUnavailableReason? = when (this) {
	AmbientTrackingSource.STEPS -> when (mechanism) {
		AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS ->
			AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED
		AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS ->
			AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED
		else -> null
	}
	AmbientTrackingSource.LOCATION ->
		AmbientSourceUnavailableReason.BACKGROUND_LOCATION_PERMISSION_REQUIRED
	AmbientTrackingSource.WIFI -> AmbientSourceUnavailableReason.WIFI_SCAN_PERMISSION_REQUIRED
	AmbientTrackingSource.CELL -> AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED
}

private fun AmbientTrackingSource.unavailableReasons(): Set<AmbientSourceUnavailableReason> {
	val source = this
	return buildSet {
		add(AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE)
		add(AmbientSourceUnavailableReason.ROLLOUT_CONTAINED)
		add(AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE)
		add(AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
		if (source == AmbientTrackingSource.STEPS) {
			add(AmbientSourceUnavailableReason.HEALTH_CONNECT_UNAVAILABLE)
			add(AmbientSourceUnavailableReason.HEALTH_CONNECT_UPDATE_REQUIRED)
			add(AmbientSourceUnavailableReason.HEALTH_CONNECT_PROBE_FAILED)
			add(AmbientSourceUnavailableReason.LOCAL_RECORDING_UNAVAILABLE)
		}
	}
}
