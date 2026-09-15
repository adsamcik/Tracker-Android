package com.adsamcik.tracker.tracker.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutomaticTrackingUnavailableReason(val stableCode: String) {
	CONTROL_RETENTION_POLICY_UNAVAILABLE("CONTROL_RETENTION_POLICY_UNAVAILABLE"),
}

sealed interface AutomaticTrackingOperationalAvailability {
	data object Ready : AutomaticTrackingOperationalAvailability

	data class Unavailable(
		val reason: AutomaticTrackingUnavailableReason,
	) : AutomaticTrackingOperationalAvailability

	val isOperational: Boolean
		get() = this is Ready
}

enum class AmbientTrackingSource {
	STEPS,
	LOCATION,
	WIFI,
	CELL,
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

enum class AmbientSourceUnavailableReason(val stableCode: String) {
	RETENTION_POLICY_UNAVAILABLE("AMBIENT_RETENTION_POLICY_UNAVAILABLE"),
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
) {
	init {
		require(mechanism == null || mechanism in source.allowedMechanisms()) {
			"$mechanism is not an acquisition mechanism for $source"
		}
		when (state) {
			AmbientSourceOperationalState.READY -> {
				require(mechanism != null) { "Ready ambient availability requires a mechanism" }
				require(reason == null) { "Ready ambient availability must not carry a reason" }
			}
			AmbientSourceOperationalState.DEGRADED -> require(
				source == AmbientTrackingSource.STEPS &&
					mechanism == AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS &&
					reason ==
					AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL,
			) {
				"Only Health Connect Steps may be operational with optional background-read gaps"
			}
			AmbientSourceOperationalState.WAITING -> require(
				mechanism == null &&
					reason == AmbientSourceUnavailableReason.RECONCILIATION_PENDING,
			) {
				"Waiting availability must be a provider-neutral pending reconciliation"
			}
			AmbientSourceOperationalState.PERMISSION_REQUIRED -> require(
				mechanism != null && reason == source.permissionReason(mechanism),
			) {
				"Permission remediation must match the source and selected mechanism"
			}
			AmbientSourceOperationalState.UNAVAILABLE -> require(
				mechanism == null &&
					reason != null &&
					reason in source.unavailableReasons(),
			) {
				"Unavailable reason must match the source and must not imply an operational provider"
			}
		}
	}

	val isOperational: Boolean
		get() = state == AmbientSourceOperationalState.READY ||
			state == AmbientSourceOperationalState.DEGRADED

	companion object {
		fun reconciliationPending(
			source: AmbientTrackingSource,
		): AmbientSourceOperationalAvailability = AmbientSourceOperationalAvailability(
			source = source,
			state = AmbientSourceOperationalState.WAITING,
			reason = AmbientSourceUnavailableReason.RECONCILIATION_PENDING,
		)
	}
}

data class TrackingPurposeAvailabilitySnapshot(
	val automaticControl: AutomaticTrackingOperationalAvailability =
		AutomaticTrackingOperationalAvailability.Unavailable(
			AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
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
) {
	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(ownerCasToken.isNotBlank())
	}
}

data class AmbientReconciliationLease(
	val identity: AmbientReconciliationIdentity,
)

data class AmbientSourceReconciliationReport(
	val identity: AmbientReconciliationIdentity,
	val availability: AmbientSourceOperationalAvailability,
) {
	init {
		require(identity.source == availability.source)
		require(availability.state != AmbientSourceOperationalState.WAITING) {
			"A completed reconciliation cannot publish the pre-reconciliation waiting state"
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
		ambientSlots[identity.source] = AmbientPublicationSlot(
			identity = identity,
			state = AmbientPublicationSlotState.ACTIVE,
		)
		publishPending(identity.source)
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
		ambientSlots[expectedIdentity.source] = slot.copy(
			state = AmbientPublicationSlotState.CANCELLED,
		)
		terminalTokens[expectedIdentity.leaseToken()] = AmbientPublicationRejection.CANCELLED
		publishPending(expectedIdentity.source)
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

	private fun publishPending(source: AmbientTrackingSource) {
		mutableAvailability.value = mutableAvailability.value.copy(
			ambientSources = mutableAvailability.value.ambientSources +
				(source to AmbientSourceOperationalAvailability.reconciliationPending(source)),
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
