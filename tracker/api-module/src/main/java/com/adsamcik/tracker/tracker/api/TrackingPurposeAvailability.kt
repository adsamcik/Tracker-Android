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

	companion object {
		@JvmStatic
		fun ready(identity: TrackingPurposeLeaseIdentity): Ready =
			Ready(identity)

		@JvmStatic
		@JvmOverloads
		fun unavailable(
			reason: AutomaticTrackingUnavailableReason,
			lastIdentity: TrackingPurposeLeaseIdentity? = null,
		): Unavailable = Unavailable(reason, lastIdentity)

		/** Legacy callers receive fail-closed status; identityless Ready authority no longer exists. */
		@JvmStatic
		fun legacyUnavailable(): Unavailable = Unavailable(
			AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
		)
	}
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
		@JvmStatic
		fun ready(
			source: AmbientTrackingSource,
			mechanism: AmbientAcquisitionMechanism,
			identity: TrackingPurposeLeaseIdentity,
		): AmbientSourceOperationalAvailability = AmbientSourceOperationalAvailability(
			source = source,
			state = AmbientSourceOperationalState.READY,
			mechanism = mechanism,
			operationalIdentity = identity,
		)

		@JvmStatic
		@JvmOverloads
		fun unavailable(
			source: AmbientTrackingSource,
			reason: AmbientSourceUnavailableReason,
			lastIdentity: TrackingPurposeLeaseIdentity? = null,
		): AmbientSourceOperationalAvailability = AmbientSourceOperationalAvailability(
			source = source,
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = reason,
			lastIdentity = lastIdentity,
		)

		@JvmStatic
		@JvmOverloads
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

data class TrackingPurposeAuthorityVector(
	val sourcePurpose: CanonicalSourcePurpose,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	val executionRevision: Long,
	val retainedFromMs: Long? = null,
) {
	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(executionRevision >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
	}
}

data class TrackingPurposeAuthorityRevision(
	val policyRevision: Long?,
	val collectedDataEpoch: Long?,
	val rolloutRevision: Long?,
	val retainedFromMs: Long? = null,
) {
	val isAvailable: Boolean
		get() = policyRevision != null &&
			collectedDataEpoch != null &&
			rolloutRevision != null

	init {
		require(policyRevision == null || policyRevision > 0L)
		require(collectedDataEpoch == null || collectedDataEpoch >= 0L)
		require(rolloutRevision == null || rolloutRevision >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(
			listOf(policyRevision, collectedDataEpoch, rolloutRevision)
				.all { it == null } ||
				listOf(policyRevision, collectedDataEpoch, rolloutRevision).all { it != null },
		) {
			"Current purpose authority revision must be wholly available or wholly unavailable"
		}
	}

	companion object {
		val UNAVAILABLE = TrackingPurposeAuthorityRevision(null, null, null, null)
	}
}

/**
 * Consumer-facing projection that accepts operational publication only while its complete
 * authority vector still matches current policy, consent, deletion, retained floor, rollout, and
 * execution state.
 */
data class CurrentTrackingPurposeAvailability(
	val published: TrackingPurposeAvailabilitySnapshot,
	val currentAuthorities: Map<CanonicalSourcePurpose, TrackingPurposeAuthorityVector>,
) {
	init {
		require(currentAuthorities.all { (sourcePurpose, authority) ->
			sourcePurpose == authority.sourcePurpose
		}) {
			"Current purpose authority keys must match their vectors"
		}
	}

	val automaticControl: AutomaticTrackingOperationalAvailability =
		when (val availability = published.automaticControl) {
			is AutomaticTrackingOperationalAvailability.Ready ->
				if (isCurrent(availability.identity)) {
					availability
				} else {
					AutomaticTrackingOperationalAvailability.Unavailable(
						AutomaticTrackingUnavailableReason
							.CONTROL_RETENTION_POLICY_UNAVAILABLE,
						availability.identity,
					)
				}
			is AutomaticTrackingOperationalAvailability.Unavailable -> availability
		}

	val ambientSources: Map<AmbientTrackingSource, AmbientSourceOperationalAvailability> =
		published.ambientSources.mapValues { (source, availability) ->
			if (availability.isOperational &&
				availability.operationalIdentity?.let(::isCurrent) != true
			) {
				AmbientSourceOperationalAvailability.reconciliationPending(
					source,
					availability.operationalIdentity,
				)
			} else {
				availability
			}
		}

	fun isCurrent(identity: TrackingPurposeLeaseIdentity): Boolean =
		currentAuthorities[identity.sourcePurpose]?.let(identity::matchesAuthority) == true

	companion object {
		val SAFE_DEFAULT = CurrentTrackingPurposeAvailability(
			published = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
			currentAuthorities = emptyMap(),
		)
	}
}

interface TrackingPurposeAvailabilityReader {
	/**
	 * Raw parent-owned publication catalog. Runtime and product consumers must use
	 * [CurrentTrackingPurposeAvailabilityReader] so a stale Ready value cannot grant work.
	 */
	val availability: StateFlow<TrackingPurposeAvailabilitySnapshot>
}

interface CurrentTrackingPurposeAvailabilityReader {
	/**
	 * Authority-checked consumer projection. Runtime monitors, settings, future caller guards, and
	 * broker adapters consume this boundary; reading it never probes or registers a provider.
	 */
	val availability: StateFlow<CurrentTrackingPurposeAvailability>
	val authorityRevision: StateFlow<TrackingPurposeAuthorityRevision>

	suspend fun isCurrent(identity: TrackingPurposeLeaseIdentity): Boolean =
		availability.value.isCurrent(identity)
}

interface TrackingPurposeAvailabilityReporter {
	fun beginOrReplaceAutomaticControlLease(
		identity: TrackingPurposeLeaseIdentity,
	): AutomaticControlLeaseStartResult
	fun cancelAutomaticControlLease(
		expectedIdentity: TrackingPurposeLeaseIdentity,
	): AutomaticControlPublicationAcceptance
	/** Trusted authority loss reset. It can only remove readiness, never grant it. */
	fun invalidateAutomaticControl()
	fun tryAccept(
		report: AutomaticControlReconciliationReport,
	): AutomaticControlPublicationAcceptance
	fun beginOrReplaceAmbientLease(
		identity: AmbientReconciliationIdentity,
	): AmbientLeaseStartResult
	fun cancelAmbientLease(
		expectedIdentity: AmbientReconciliationIdentity,
	): AmbientPublicationAcceptance
	/** Trusted authority loss reset. It can only return one source to pending. */
	fun invalidateAmbient(source: AmbientTrackingSource)
	/** Trusted retention failure publication. It cannot carry or create operational authority. */
	fun publishAmbientUnavailable(
		source: AmbientTrackingSource,
		reason: AmbientSourceUnavailableReason,
	)
	fun tryAccept(
		report: AmbientSourceReconciliationReport,
	): AmbientPublicationAcceptance
}

data class AutomaticControlReconciliationLease(
	val identity: TrackingPurposeLeaseIdentity,
) {
	init {
		identity.requireAutomaticControlIdentity()
	}
}

data class AutomaticControlReconciliationReport(
	val identity: TrackingPurposeLeaseIdentity,
	val availability: AutomaticTrackingOperationalAvailability,
) {
	init {
		identity.requireAutomaticControlIdentity()
		when (availability) {
			is AutomaticTrackingOperationalAvailability.Ready ->
				require(availability.identity == identity) {
					"Operational automatic CONTROL publication must carry the exact lease identity"
				}
			is AutomaticTrackingOperationalAvailability.Unavailable ->
				require(
					availability.lastIdentity == null ||
						availability.lastIdentity == identity,
				) {
					"Non-operational automatic CONTROL publication may retain only its exact lease identity"
				}
		}
	}
}

sealed interface AutomaticControlPublicationAcceptance {
	data class Accepted(
		val snapshot: TrackingPurposeAvailabilitySnapshot,
	) : AutomaticControlPublicationAcceptance

	data class Rejected(
		val reason: TrackingPurposePublicationRejection,
	) : AutomaticControlPublicationAcceptance
}

sealed interface AutomaticControlLeaseStartResult {
	data class Started(
		val lease: AutomaticControlReconciliationLease,
		val snapshot: TrackingPurposeAvailabilitySnapshot,
	) : AutomaticControlLeaseStartResult

	data class Rejected(
		val reason: TrackingPurposePublicationRejection,
	) : AutomaticControlLeaseStartResult
}

data class AmbientReconciliationIdentity(
	val source: AmbientTrackingSource,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	val ownerCasToken: String,
	val executionRevision: Long,
	val retainedFromMs: Long? = null,
) {
	constructor(
		source: AmbientTrackingSource,
		policyRevision: Long,
		consentEpoch: Long,
		collectedDataEpoch: Long,
		rolloutRevision: Long,
		ownerCasToken: String,
		retainedFromMs: Long? = null,
	) : this(
		source = source,
		policyRevision = policyRevision,
		consentEpoch = consentEpoch,
		collectedDataEpoch = collectedDataEpoch,
		rolloutRevision = rolloutRevision,
		ownerCasToken = ownerCasToken,
		executionRevision = 0L,
		retainedFromMs = retainedFromMs,
	)

	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(ownerCasToken.isNotBlank())
		require(executionRevision >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
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
			retainedFromMs = retainedFromMs,
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
				retainedFromMs = identity.retainedFromMs,
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

enum class TrackingPurposePublicationRejection {
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
	/**
	 * Returns status for one exact parent-issued lease. Implementations must not treat invocation as
	 * demand, provider, consent, retention, or writer authority.
	 */
	suspend fun reconcile(lease: AmbientReconciliationLease): AmbientSourceOperationalAvailability

	/**
	 * Non-cancellable exact cleanup after an apply-then-fail, stale completion, or cancellation.
	 * Implementations with no physical side effect may keep the default successful no-op.
	 */
	suspend fun compensate(lease: AmbientReconciliationLease): Boolean = true
}

fun interface AutomaticControlReconciliationCallback {
	/**
	 * Returns status for one exact Activity CONTROL lease. Until AUTO-005 is resolved, production
	 * reconciliation remains [AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE].
	 */
	suspend fun reconcile(
		lease: AutomaticControlReconciliationLease,
	): AutomaticTrackingOperationalAvailability
}

data class TrackingPurposeSourceOwnerRegistration(
	val sourcePurpose: CanonicalSourcePurpose,
	val registrationId: String,
) {
	init {
		require(registrationId.isNotBlank())
	}
}

/**
 * Process-local callback registration only. Registration and callback invocation grant no source
 * demand, provider, consent, retention, broker, or writer authority.
 */
interface TrackingPurposeSourceOwnerRegistrar {
	suspend fun registerAutomaticControlOwner(
		executionRevision: Long,
		callback: AutomaticControlReconciliationCallback,
	): TrackingPurposeSourceOwnerRegistration

	suspend fun registerAmbientSourceOwner(
		source: AmbientTrackingSource,
		executionRevision: Long,
		callback: AmbientSourceReconciliationCallback,
	): TrackingPurposeSourceOwnerRegistration

	suspend fun unregister(registration: TrackingPurposeSourceOwnerRegistration)
}

/** Existing settings and direct authority collectors use this bounded reconciliation signal. */
fun interface TrackingPurposeSettingsReconciler {
	suspend fun reconcileCurrentSettings()
}

fun interface TrackingRetentionFloorReconciler {
	suspend fun reconcile(
		expectedStartupGeneration: Long,
		retainedFromMs: Long,
		approvedSources: Set<AmbientTrackingSource>,
	): TrackingRetentionFloorReconciliationResult
}

sealed interface TrackingRetentionFloorReconciliationResult {
	data class Complete(
		val retainedFromMs: Long,
		val reconciledSources: Set<AmbientTrackingSource>,
	) : TrackingRetentionFloorReconciliationResult {
		init {
			require(retainedFromMs >= 0L)
			require(reconciledSources.none { it == AmbientTrackingSource.LOCATION })
		}
	}

	data class Retryable(
		val debt: TrackingRetentionFloorReconciliationDebt,
	) : TrackingRetentionFloorReconciliationResult
}

data class TrackingRetentionFloorReconciliationDebt(
	val retainedFromMs: Long,
	val failures: List<TrackingRetentionFloorReconciliationFailure>,
) {
	init {
		require(retainedFromMs >= 0L)
		require(failures.isNotEmpty())
	}
}

data class TrackingRetentionFloorReconciliationFailure(
	val source: AmbientTrackingSource?,
	val reason: TrackingRetentionFloorReconciliationFailureReason,
)

enum class TrackingRetentionFloorReconciliationFailureReason {
	STARTUP_GENERATION_CHANGED,
	RETENTION_AUTHORITY_UNAVAILABLE,
	AUTHORITY_FLOOR_MISMATCH,
	OWNER_RECONCILIATION_FAILED,
	PUBLICATION_REJECTED,
	COMPENSATION_FAILED,
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
	private var automaticControlSlot: AutomaticControlPublicationSlot? = null
	private val ambientSlots = mutableMapOf<AmbientTrackingSource, AmbientPublicationSlot>()
	private val terminalTokens =
		mutableMapOf<TrackingPurposeLeaseToken, TrackingPurposePublicationRejection>()

	override val availability: StateFlow<TrackingPurposeAvailabilitySnapshot> =
		mutableAvailability.asStateFlow()

	override fun beginOrReplaceAutomaticControlLease(
		identity: TrackingPurposeLeaseIdentity,
	): AutomaticControlLeaseStartResult = synchronized(lock) {
		identity.requireAutomaticControlIdentity()
		terminalTokens[identity.leaseToken()]?.let { rejection ->
			return@synchronized AutomaticControlLeaseStartResult.Rejected(rejection)
		}
		val previous = automaticControlSlot
		if (previous?.identity == identity && previous.state == PublicationSlotState.ACTIVE) {
			return@synchronized AutomaticControlLeaseStartResult.Started(
				lease = AutomaticControlReconciliationLease(identity),
				snapshot = mutableAvailability.value,
			)
		}
		if (previous?.identity?.ownerCasToken == identity.ownerCasToken) {
			return@synchronized AutomaticControlLeaseStartResult.Rejected(
				TrackingPurposePublicationRejection.TOKEN_REUSED,
			)
		}
		previous?.let { slot ->
			terminalTokens.putIfAbsent(
				slot.identity.leaseToken(),
				if (slot.state == PublicationSlotState.CONSUMED) {
					TrackingPurposePublicationRejection.TOKEN_CONSUMED
				} else {
					TrackingPurposePublicationRejection.CANCELLED
				},
			)
		}
		val prior = mutableAvailability.value.automaticControl
		automaticControlSlot = AutomaticControlPublicationSlot(
			identity = identity,
			state = PublicationSlotState.ACTIVE,
		)
		publishAutomaticControlPending(prior.authorityIdentityOrNull ?: prior.lastIdentityOrNull)
		AutomaticControlLeaseStartResult.Started(
			lease = AutomaticControlReconciliationLease(identity),
			snapshot = mutableAvailability.value,
		)
	}

	override fun cancelAutomaticControlLease(
		expectedIdentity: TrackingPurposeLeaseIdentity,
	): AutomaticControlPublicationAcceptance = synchronized(lock) {
		expectedIdentity.requireAutomaticControlIdentity()
		val slot = automaticControlSlot
			?: return@synchronized AutomaticControlPublicationAcceptance.Rejected(
				TrackingPurposePublicationRejection.STALE_IDENTITY,
			)
		if (slot.identity != expectedIdentity) {
			return@synchronized AutomaticControlPublicationAcceptance.Rejected(
				TrackingPurposePublicationRejection.STALE_IDENTITY,
			)
		}
		when (slot.state) {
			PublicationSlotState.CONSUMED ->
				return@synchronized AutomaticControlPublicationAcceptance.Rejected(
					TrackingPurposePublicationRejection.TOKEN_CONSUMED,
				)
			PublicationSlotState.CANCELLED ->
				return@synchronized AutomaticControlPublicationAcceptance.Rejected(
					TrackingPurposePublicationRejection.CANCELLED,
				)
			PublicationSlotState.ACTIVE -> Unit
		}
		terminalTokens.putIfAbsent(
			expectedIdentity.leaseToken(),
			TrackingPurposePublicationRejection.CANCELLED,
		)?.let { terminal ->
			return@synchronized AutomaticControlPublicationAcceptance.Rejected(terminal)
		}
		automaticControlSlot = slot.copy(state = PublicationSlotState.CANCELLED)
		val prior = mutableAvailability.value.automaticControl
		publishAutomaticControlPending(prior.authorityIdentityOrNull ?: prior.lastIdentityOrNull)
		AutomaticControlPublicationAcceptance.Accepted(mutableAvailability.value)
	}

	override fun invalidateAutomaticControl() {
		synchronized(lock) {
			val prior = mutableAvailability.value.automaticControl
			automaticControlSlot = automaticControlSlot?.let { slot ->
				val terminal = terminalTokens.getOrPut(slot.identity.leaseToken()) {
					slot.state.terminalRejection()
				}
				slot.copy(state = terminal.toSlotState())
			}
			publishAutomaticControlPending(prior.authorityIdentityOrNull ?: prior.lastIdentityOrNull)
		}
	}

	override fun tryAccept(
		report: AutomaticControlReconciliationReport,
	): AutomaticControlPublicationAcceptance = synchronized(lock) {
		val slot = automaticControlSlot
			?: return@synchronized AutomaticControlPublicationAcceptance.Rejected(
				TrackingPurposePublicationRejection.STALE_IDENTITY,
			)
		when {
			slot.identity != report.identity -> AutomaticControlPublicationAcceptance.Rejected(
				TrackingPurposePublicationRejection.STALE_IDENTITY,
			)
			slot.state == PublicationSlotState.CANCELLED ->
				AutomaticControlPublicationAcceptance.Rejected(
					TrackingPurposePublicationRejection.CANCELLED,
				)
			slot.state == PublicationSlotState.CONSUMED ->
				AutomaticControlPublicationAcceptance.Rejected(
					TrackingPurposePublicationRejection.TOKEN_CONSUMED,
				)
			else -> {
				val terminal = terminalTokens.putIfAbsent(
					report.identity.leaseToken(),
					TrackingPurposePublicationRejection.TOKEN_CONSUMED,
				)
				if (terminal != null) {
					return@synchronized AutomaticControlPublicationAcceptance.Rejected(terminal)
				}
				automaticControlSlot = slot.copy(state = PublicationSlotState.CONSUMED)
				mutableAvailability.value = mutableAvailability.value.copy(
					automaticControl = report.availability,
				)
				AutomaticControlPublicationAcceptance.Accepted(mutableAvailability.value)
			}
		}
	}

	override fun beginOrReplaceAmbientLease(
		identity: AmbientReconciliationIdentity,
	): AmbientLeaseStartResult = synchronized(lock) {
		terminalTokens[identity.purposeLeaseIdentity.leaseToken()]?.let { rejection ->
			return@synchronized AmbientLeaseStartResult.Rejected(rejection.toAmbientRejection())
		}
		val previous = ambientSlots[identity.source]
		if (previous?.identity == identity && previous.state == PublicationSlotState.ACTIVE) {
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
				slot.identity.purposeLeaseIdentity.leaseToken(),
				if (slot.state == PublicationSlotState.CONSUMED) {
					TrackingPurposePublicationRejection.TOKEN_CONSUMED
				} else {
					TrackingPurposePublicationRejection.CANCELLED
				},
			)
		}
		val priorAvailability = mutableAvailability.value.ambientSources.getValue(identity.source)
		ambientSlots[identity.source] = AmbientPublicationSlot(
			identity = identity,
			state = PublicationSlotState.ACTIVE,
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
			PublicationSlotState.CONSUMED ->
				return@synchronized AmbientPublicationAcceptance.Rejected(
					AmbientPublicationRejection.TOKEN_CONSUMED,
				)
			PublicationSlotState.CANCELLED ->
				return@synchronized AmbientPublicationAcceptance.Rejected(
					AmbientPublicationRejection.CANCELLED,
				)
			PublicationSlotState.ACTIVE -> Unit
		}
		terminalTokens.putIfAbsent(
			expectedIdentity.purposeLeaseIdentity.leaseToken(),
			TrackingPurposePublicationRejection.CANCELLED,
		)?.let { terminal ->
			return@synchronized AmbientPublicationAcceptance.Rejected(
				terminal.toAmbientRejection(),
			)
		}
		ambientSlots[expectedIdentity.source] = slot.copy(
			state = PublicationSlotState.CANCELLED,
		)
		val priorAvailability =
			mutableAvailability.value.ambientSources.getValue(expectedIdentity.source)
		publishPending(
			expectedIdentity.source,
			priorAvailability.operationalIdentity ?: priorAvailability.lastIdentity,
		)
		AmbientPublicationAcceptance.Accepted(mutableAvailability.value)
	}

	override fun invalidateAmbient(source: AmbientTrackingSource) {
		synchronized(lock) {
			val priorAvailability = mutableAvailability.value.ambientSources.getValue(source)
			ambientSlots[source] = ambientSlots[source]?.let { slot ->
				val terminal = terminalTokens.getOrPut(
					slot.identity.purposeLeaseIdentity.leaseToken(),
				) {
					slot.state.terminalRejection()
				}
				slot.copy(state = terminal.toSlotState())
			}
			publishPending(
				source,
				priorAvailability.operationalIdentity ?: priorAvailability.lastIdentity,
			)
		}
	}

	override fun publishAmbientUnavailable(
		source: AmbientTrackingSource,
		reason: AmbientSourceUnavailableReason,
	) {
		synchronized(lock) {
			val priorAvailability = mutableAvailability.value.ambientSources.getValue(source)
			ambientSlots[source] = ambientSlots[source]?.let { slot ->
				val terminal = terminalTokens.getOrPut(
					slot.identity.purposeLeaseIdentity.leaseToken(),
				) {
					slot.state.terminalRejection()
				}
				slot.copy(state = terminal.toSlotState())
			}
			mutableAvailability.value = mutableAvailability.value.copy(
				ambientSources = mutableAvailability.value.ambientSources +
					(
						source to AmbientSourceOperationalAvailability.unavailable(
							source,
							reason,
							priorAvailability.operationalIdentity ?: priorAvailability.lastIdentity,
						)
					),
			)
		}
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
			slot.state == PublicationSlotState.CANCELLED ->
				AmbientPublicationAcceptance.Rejected(AmbientPublicationRejection.CANCELLED)
			slot.state == PublicationSlotState.CONSUMED ->
				AmbientPublicationAcceptance.Rejected(AmbientPublicationRejection.TOKEN_CONSUMED)
			else -> {
				val terminal = terminalTokens.putIfAbsent(
					report.identity.purposeLeaseIdentity.leaseToken(),
					TrackingPurposePublicationRejection.TOKEN_CONSUMED,
				)
				if (terminal != null) {
					return@synchronized AmbientPublicationAcceptance.Rejected(
						terminal.toAmbientRejection(),
					)
				}
				ambientSlots[report.identity.source] = slot.copy(
					state = PublicationSlotState.CONSUMED,
				)
				mutableAvailability.value = mutableAvailability.value.copy(
					ambientSources = mutableAvailability.value.ambientSources +
						(report.identity.source to report.availability),
				)
				AmbientPublicationAcceptance.Accepted(mutableAvailability.value)
			}
		}
	}

	private fun publishAutomaticControlPending(
		lastIdentity: TrackingPurposeLeaseIdentity?,
	) {
		mutableAvailability.value = mutableAvailability.value.copy(
			automaticControl = AutomaticTrackingOperationalAvailability.Unavailable(
				reason = AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				lastIdentity = lastIdentity,
			),
		)
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
	val state: PublicationSlotState,
)

private data class AutomaticControlPublicationSlot(
	val identity: TrackingPurposeLeaseIdentity,
	val state: PublicationSlotState,
)

private data class TrackingPurposeLeaseToken(
	val sourcePurpose: CanonicalSourcePurpose,
	val ownerCasToken: String,
)

private fun TrackingPurposeLeaseIdentity.leaseToken(): TrackingPurposeLeaseToken =
	TrackingPurposeLeaseToken(sourcePurpose, ownerCasToken)

fun TrackingPurposeLeaseIdentity.matchesAuthority(
	authority: TrackingPurposeAuthorityVector,
): Boolean = sourcePurpose == authority.sourcePurpose &&
	policyRevision == authority.policyRevision &&
	consentEpoch == authority.consentEpoch &&
	collectedDataEpoch == authority.collectedDataEpoch &&
	rolloutRevision == authority.rolloutRevision &&
	executionRevision == authority.executionRevision &&
	retainedFromMs == authority.retainedFromMs

private val AutomaticTrackingOperationalAvailability.lastIdentityOrNull:
	TrackingPurposeLeaseIdentity?
	get() = (this as? AutomaticTrackingOperationalAvailability.Unavailable)?.lastIdentity

private fun TrackingPurposeLeaseIdentity.requireAutomaticControlIdentity() {
	require(source == CanonicalTrackingSource.ACTIVITY)
	require(purpose == TrackingPurpose.CONTROL)
}

private fun TrackingPurposeLeaseIdentity.requireAmbientIdentity(source: AmbientTrackingSource) {
	require(this.source == source.canonicalSource)
	require(purpose == TrackingPurpose.AMBIENT_PRODUCT)
}

internal fun AmbientTrackingSource.toTrackingSource(): TrackingSource =
	canonicalSource.toApiTrackingSource()

private enum class PublicationSlotState {
	ACTIVE,
	CONSUMED,
	CANCELLED,
}

private fun PublicationSlotState.terminalRejection(): TrackingPurposePublicationRejection =
	when (this) {
		PublicationSlotState.CONSUMED -> TrackingPurposePublicationRejection.TOKEN_CONSUMED
		PublicationSlotState.ACTIVE,
		PublicationSlotState.CANCELLED,
		-> TrackingPurposePublicationRejection.CANCELLED
	}

private fun TrackingPurposePublicationRejection.toSlotState(): PublicationSlotState = when (this) {
	TrackingPurposePublicationRejection.TOKEN_CONSUMED -> PublicationSlotState.CONSUMED
	TrackingPurposePublicationRejection.CANCELLED -> PublicationSlotState.CANCELLED
	TrackingPurposePublicationRejection.STALE_IDENTITY,
	TrackingPurposePublicationRejection.TOKEN_REUSED,
	-> error("Non-terminal publication rejection cannot own a slot")
}

private fun TrackingPurposePublicationRejection.toAmbientRejection():
	AmbientPublicationRejection = when (this) {
	TrackingPurposePublicationRejection.CANCELLED -> AmbientPublicationRejection.CANCELLED
	TrackingPurposePublicationRejection.STALE_IDENTITY ->
		AmbientPublicationRejection.STALE_IDENTITY
	TrackingPurposePublicationRejection.TOKEN_CONSUMED ->
		AmbientPublicationRejection.TOKEN_CONSUMED
	TrackingPurposePublicationRejection.TOKEN_REUSED ->
		AmbientPublicationRejection.TOKEN_REUSED
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
