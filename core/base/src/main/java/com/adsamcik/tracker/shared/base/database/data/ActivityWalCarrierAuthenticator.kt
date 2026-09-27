@file:Suppress("ComplexCondition", "LongMethod")

package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

const val ACTIVITY_WAL_PAYLOAD_VERSION = 1
const val MAX_ACTIVITY_WAL_PAYLOAD_BYTES = 64

sealed interface ActivityWalPayload {
	val activityType: Int
	val providerElapsedRealtimeNanos: Long?

	data class Transition(
		override val activityType: Int,
		val transitionType: Int,
		override val providerElapsedRealtimeNanos: Long,
	) : ActivityWalPayload

	data class Recognition(
		override val activityType: Int,
		val confidencePercent: Int,
		override val providerElapsedRealtimeNanos: Long?,
	) : ActivityWalPayload
}

object ActivityWalPayloadCodec {
	fun encode(payload: ActivityWalPayload): ByteArray =
		ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				when (payload) {
					is ActivityWalPayload.Transition -> {
						require(payload.activityType in SUPPORTED_ACTIVITY_TYPES)
						require(payload.transitionType in SUPPORTED_TRANSITIONS)
						require(payload.providerElapsedRealtimeNanos >= 0L)
						output.writeInt(TYPE_ACTIVITY_TRANSITION)
						output.writeInt(payload.activityType)
						output.writeInt(payload.transitionType)
						output.writeLong(payload.providerElapsedRealtimeNanos)
					}
					is ActivityWalPayload.Recognition -> {
						require(payload.activityType in SUPPORTED_ACTIVITY_TYPES)
						require(payload.confidencePercent in 0..100)
						require(
							payload.providerElapsedRealtimeNanos == null ||
								payload.providerElapsedRealtimeNanos >= 0L,
						)
						output.writeInt(TYPE_ACTIVITY_RECOGNITION)
						output.writeInt(payload.activityType)
						output.writeInt(payload.confidencePercent)
						output.writeBoolean(payload.providerElapsedRealtimeNanos != null)
						payload.providerElapsedRealtimeNanos?.let(output::writeLong)
					}
				}
			}
			buffer.toByteArray()
		}

	fun decodeCanonical(payloadVersion: Int, bytes: ByteArray): ActivityWalPayload? =
		runCatching {
			require(payloadVersion == ACTIVITY_WAL_PAYLOAD_VERSION)
			require(bytes.isNotEmpty() && bytes.size <= MAX_ACTIVITY_WAL_PAYLOAD_BYTES)
			val decoded = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
				val payload = when (input.readInt()) {
					TYPE_ACTIVITY_TRANSITION -> ActivityWalPayload.Transition(
						activityType = input.readInt(),
						transitionType = input.readInt(),
						providerElapsedRealtimeNanos = input.readLong(),
					)
					TYPE_ACTIVITY_RECOGNITION -> ActivityWalPayload.Recognition(
						activityType = input.readInt(),
						confidencePercent = input.readInt(),
						providerElapsedRealtimeNanos =
							if (input.readBoolean()) input.readLong() else null,
					)
					else -> error("Unsupported Activity WAL payload")
				}
				require(input.available() == 0)
				payload
			}
			require(encode(decoded).contentEquals(bytes))
			decoded
		}.getOrNull()
}

enum class ActivityWalCarrierFailure {
	WRONG_SOURCE,
	UNSUPPORTED_PAYLOAD_VERSION,
	INTEGRITY_MISMATCH,
	CONTROL_ONLY,
	INCOMPLETE_CAPTURE_AUTHORITY,
	MALFORMED_PROVIDER_ENVELOPE,
	PAYLOAD_INVALID,
	FACT_LINKAGE_MISMATCH,
	FACT_SEMANTICS_MISMATCH,
}

data class AuthenticatedActivityWalCarrier internal constructor(
	val payload: ActivityWalPayload,
)

sealed interface ActivityWalCarrierAuthentication {
	data class Authenticated(
		val carrier: AuthenticatedActivityWalCarrier,
	) : ActivityWalCarrierAuthentication

	data class Rejected(val reason: ActivityWalCarrierFailure) : ActivityWalCarrierAuthentication
}

object ActivityWalCarrierAuthenticator {
	@Suppress("ComplexCondition", "ReturnCount")
	fun authenticate(wal: SourceEventWalEntity): ActivityWalCarrierAuthentication {
		if (wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY) {
			return rejected(ActivityWalCarrierFailure.WRONG_SOURCE)
		}
		if (wal.payloadVersion != ACTIVITY_WAL_PAYLOAD_VERSION) {
			return rejected(ActivityWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (!wal.hasExactQualifiedIntegrity(MAX_ACTIVITY_WAL_PAYLOAD_BYTES)) {
			return rejected(ActivityWalCarrierFailure.INTEGRITY_MISMATCH)
		}
		if (wal.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return rejected(ActivityWalCarrierFailure.CONTROL_ONLY)
		}
		if (!wal.hasCompleteSessionCaptureBinding()) {
			return rejected(ActivityWalCarrierFailure.INCOMPLETE_CAPTURE_AUTHORITY)
		}
		if (
			wal.providerDedupKey != null ||
			wal.deliveryIdentity?.let(SOURCE_WAL_SHA_256::matches) != true ||
			wal.deliveryUnitIndex?.let { it >= 0 } != true ||
			wal.deliveryUnitCount?.let { count ->
				count > 0 && requireNotNull(wal.deliveryUnitIndex) < count
			} != true ||
			wal.sourceSequence <= 0L ||
			wal.observedIntervalStartNanos != wal.observedElapsedNanos ||
			wal.observedElapsedNanos < 0L ||
			wal.receivedElapsedNanos < wal.observedElapsedNanos ||
			wal.receivedWallTimeMs?.let { it < 0L } == true ||
			wal.wallTimeMs?.let { it >= 0L } != true ||
			wal.wallTimeUncertaintyMs?.let { it >= 0L } != true ||
			wal.acquiredAtMs != wal.wallTimeMs ||
			wal.createdAtMs < 0L ||
			wal.activityAutomationEpoch?.let { it < 0L } == true ||
			!wal.hasCanonicalQuality()
		) {
			return rejected(ActivityWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		val payload = ActivityWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
			?: return rejected(ActivityWalCarrierFailure.PAYLOAD_INVALID)
		if (payload.providerElapsedRealtimeNanos != wal.observedElapsedNanos) {
			return rejected(ActivityWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		return ActivityWalCarrierAuthentication.Authenticated(
			AuthenticatedActivityWalCarrier(payload),
		)
	}

	fun authenticate(
		revision: ActivityCapturedWindowRevisionEntity,
		evidence: ActivityCapturedEvidenceEntity,
		wal: SourceEventWalEntity,
	): ActivityWalCarrierAuthentication {
		val authentication = authenticate(wal)
		if (authentication !is ActivityWalCarrierAuthentication.Authenticated) {
			return authentication
		}
		if (
			evidence.sourceEventId != wal.eventId ||
			evidence.sourceAdmissionOrdinal != wal.admissionOrdinal ||
			evidence.sourceSequence != wal.sourceSequence ||
			evidence.providerElapsedRealtimeNanos != wal.observedElapsedNanos ||
			evidence.receivedElapsedRealtimeNanos != wal.receivedElapsedNanos ||
			revision.logicalTrackingId != wal.logicalTrackingId ||
			revision.serviceRunId != wal.serviceRunId ||
			revision.sourceInstanceId != wal.sourceInstanceId ||
			revision.registrationGeneration != wal.registrationGeneration ||
			revision.configurationRevision != wal.configRevision ||
			revision.physicalConfigurationFingerprint != wal.physicalConfigurationFingerprint ||
			revision.authorizationRevision != wal.authorizationRevision ||
			revision.authorizationFingerprint != wal.authorizationFingerprint ||
			revision.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			revision.sourcePolicyRevision != wal.sourcePolicyRevision ||
			revision.captureConsentEpoch != wal.captureConsentEpoch ||
			revision.manifestRevision != wal.sessionManifestRevision ||
			revision.lifecycleLeaseGeneration != wal.lifecycleLeaseGeneration ||
			revision.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			revision.clockDomainId != wal.clockDomainId
		) {
			return rejected(ActivityWalCarrierFailure.FACT_LINKAGE_MISMATCH)
		}
		if (
			wal.observedElapsedNanos !in
			revision.providerAcceptanceStartNanos until revision.providerAcceptanceEndNanos ||
			wal.observedElapsedNanos !in
			revision.authorizationEffectStartNanos until revision.authorizationEffectEndNanos ||
			wal.observedElapsedNanos !in
			revision.sessionRunEffectStartNanos until revision.sessionRunEffectEndNanos ||
			!evidence.matches(authentication.carrier.payload)
		) {
			return rejected(ActivityWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		return authentication
	}

	fun authenticates(
		revision: ActivityCapturedWindowRevisionEntity,
		evidence: ActivityCapturedEvidenceEntity,
		wal: SourceEventWalEntity,
	): Boolean =
		authenticate(revision, evidence, wal) is ActivityWalCarrierAuthentication.Authenticated
}

private fun ActivityCapturedEvidenceEntity.matches(payload: ActivityWalPayload): Boolean =
	when (payload) {
		is ActivityWalPayload.Transition ->
			observationKind == ActivityCapturedEvidenceEntity.KIND_TRANSITION &&
				observedActivity == payload.activityType.activityName &&
				transitionChange == payload.transitionType.transitionName &&
				confidencePercent == null &&
				coverageEndExclusiveElapsedRealtimeNanos == null
		is ActivityWalPayload.Recognition ->
			observationKind == ActivityCapturedEvidenceEntity.KIND_SAMPLED_CLASSIFICATION &&
				observedActivity == payload.activityType.activityName &&
				confidencePercent == payload.confidencePercent &&
				transitionChange == null &&
				coverageEndExclusiveElapsedRealtimeNanos?.let {
					it > providerElapsedRealtimeNanos
				} == true
	}

private val Int.activityName: String?
	get() = ACTIVITY_NAMES.getOrNull(this)

private val Int.transitionName: String?
	get() = when (this) {
		0 -> "ENTER"
		1 -> "EXIT"
		else -> null
	}

private fun rejected(reason: ActivityWalCarrierFailure) =
	ActivityWalCarrierAuthentication.Rejected(reason)

private const val TYPE_ACTIVITY_TRANSITION = 2
private const val TYPE_ACTIVITY_RECOGNITION = 3
private val SUPPORTED_ACTIVITY_TYPES = 0..7
private val SUPPORTED_TRANSITIONS = 0..1
private val ACTIVITY_NAMES = listOf(
	"STILL",
	"WALKING",
	"RUNNING",
	"ON_BICYCLE",
	"IN_VEHICLE",
	"ON_FOOT",
	"TILTING",
	"UNKNOWN",
)
