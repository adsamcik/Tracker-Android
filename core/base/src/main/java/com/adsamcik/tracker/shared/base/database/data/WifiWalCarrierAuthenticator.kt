@file:Suppress("LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.ZoneId

const val WIFI_WAL_PAYLOAD_VERSION = 5
const val MAX_WIFI_WAL_PAYLOAD_BYTES = 2_048

data class WifiWalPayloadObservation(
	val identifierToken: String,
	val frequencyMhz: Int,
	val signalLevelDbm: Int,
	val providerTimestampNanos: Long?,
)

data class WifiWalPayload(
	val observations: List<WifiWalPayloadObservation>,
	val platformTimestampMs: Long?,
	val resultAgeMs: Long?,
	val observationZoneId: String,
) {
	init {
		require(observations.size <= MAX_WIFI_WAL_CHILDREN)
		require(observationZoneId.isNotBlank() && observationZoneId.length <= MAX_WIFI_ZONE_ID_LENGTH)
		require(runCatching { ZoneId.of(observationZoneId) }.isSuccess)
	}
}

data class EncodedWifiWalPayload internal constructor(
	val bytes: List<Byte>,
	val checksum: String,
) {
	fun toByteArray(): ByteArray = bytes.toByteArray()
}

enum class WifiWalPayloadDecodeFailure {
	UNSUPPORTED_PAYLOAD_VERSION,
	PAYLOAD_TOO_LARGE,
	MALFORMED_PAYLOAD,
	NON_CANONICAL_PAYLOAD,
}

sealed interface WifiWalPayloadDecodeResult {
	data class Decoded(val payload: WifiWalPayload) : WifiWalPayloadDecodeResult
	data class Rejected(val reason: WifiWalPayloadDecodeFailure) : WifiWalPayloadDecodeResult
}

object WifiWalPayloadCodec {
	fun encode(payload: WifiWalPayload): EncodedWifiWalPayload {
		require(payload.observations.size <= MAX_WIFI_WAL_CHILDREN)
		val bytes = ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(TYPE_WIFI_RESULTS)
				output.writeInt(payload.observations.size)
				payload.observations.forEach { observation ->
					output.writeUTF(observation.identifierToken)
					output.writeInt(observation.frequencyMhz)
					output.writeInt(observation.signalLevelDbm)
					output.writeNullableLong(observation.providerTimestampNanos)
				}
				output.writeNullableLong(payload.platformTimestampMs)
				output.writeNullableLong(payload.resultAgeMs)
				output.writeUTF(payload.observationZoneId)
			}
			buffer.toByteArray()
		}
		require(bytes.size <= MAX_WIFI_WAL_PAYLOAD_BYTES)
		return EncodedWifiWalPayload(bytes.toList(), bytes.sha256())
	}

	fun decodeCanonical(
		payloadVersion: Int,
		bytes: ByteArray,
	): WifiWalPayloadDecodeResult {
		if (payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
			return WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.UNSUPPORTED_PAYLOAD_VERSION,
			)
		}
		if (bytes.isEmpty() || bytes.size > MAX_WIFI_WAL_PAYLOAD_BYTES) {
			return WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.PAYLOAD_TOO_LARGE,
			)
		}
		val payload = runCatching {
			DataInputStream(ByteArrayInputStream(bytes)).use { input ->
				require(input.readInt() == TYPE_WIFI_RESULTS)
				val count = input.readInt()
				require(count in 0..MAX_WIFI_WAL_CHILDREN)
				val observations = List(count) {
					WifiWalPayloadObservation(
						identifierToken = input.readUTF(),
						frequencyMhz = input.readInt(),
						signalLevelDbm = input.readInt(),
						providerTimestampNanos = input.readNullableLong(),
					)
				}
				val platformTimestampMs = input.readNullableLong()
				val resultAgeMs = input.readNullableLong()
				val zoneId = input.readUTF()
				require(input.available() == 0)
				WifiWalPayload(
					observations = observations,
					platformTimestampMs = platformTimestampMs,
					resultAgeMs = resultAgeMs,
					observationZoneId = zoneId,
				)
			}
		}.getOrElse {
			return WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.MALFORMED_PAYLOAD,
			)
		}
		val canonical = runCatching { encode(payload).toByteArray() }.getOrElse {
			return WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.MALFORMED_PAYLOAD,
			)
		}
		return if (canonical.contentEquals(bytes)) {
			WifiWalPayloadDecodeResult.Decoded(payload)
		} else {
			WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.NON_CANONICAL_PAYLOAD,
			)
		}
	}
}

data class WifiWalProviderInterval(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}

	operator fun contains(value: Long): Boolean =
		value >= startInclusiveNanos && value < endExclusiveNanos
}

@Suppress("LongParameterList")
data class WifiWalCaptureAuthority(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val manifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val collectedDataEpoch: Long,
	val clockDomainId: String,
	val zoneId: String,
	val providerAcceptance: WifiWalProviderInterval,
	val authorizationEffect: WifiWalProviderInterval,
	val sessionRunEffect: WifiWalProviderInterval,
	val maximumObservationAgeNanos: Long,
	val resultContract: String,
)

enum class WifiWalChildDisposition {
	ACCEPTED,
	STALE,
	CLOCK_UNVERIFIABLE,
	MALFORMED,
}

data class WifiWalIdentityFreeAggregate(
	val observationCount: Int,
	val twoPointFourGhzCount: Int,
	val fiveGhzCount: Int,
	val sixGhzCount: Int,
	val otherBandCount: Int,
	val strongestSignalDbm: Int,
	val weakestSignalDbm: Int,
	val signalSumDbm: Long,
)

data class WifiWalCoverage(
	val submittedResultCount: Int,
	val acceptedResultCount: Int,
	val staleResultCount: Int,
	val clockUnverifiableResultCount: Int,
	val malformedResultCount: Int,
	val coverageIntervalStartNanos: Long?,
	val coverageIntervalEndNanos: Long?,
	val observedWallTimeMs: Long?,
	val wallTimeUncertaintyMs: Long?,
	val aggregate: WifiWalIdentityFreeAggregate?,
) {
	val completeness: String =
		if (acceptedResultCount == submittedResultCount) {
			WifiCapturedFactRevisionEntity.COVERAGE_COMPLETE
		} else {
			WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL
		}
}

enum class WifiWalCarrierFailure {
	WRONG_SOURCE,
	UNSUPPORTED_PAYLOAD_VERSION,
	PAYLOAD_TOO_LARGE,
	PAYLOAD_DECODE_FAILED,
	NON_CANONICAL_PAYLOAD,
	PAYLOAD_INTEGRITY_MISMATCH,
	WAL_INTEGRITY_MISMATCH,
	INCOMPLETE_CAPTURE_BINDING,
	MALFORMED_PROVIDER_ENVELOPE,
	EMPTY_PROVIDER_DELIVERY,
	TOO_MANY_CHILDREN,
	IDENTITY_BEARING_CHILD,
	INVALID_PROVIDER_TIME,
	NON_CANONICAL_CHILD_ORDER,
	PROVIDER_TIMELINE_MISMATCH,
	DELIVERY_IDENTITY_MISMATCH,
	CAPTURE_AUTHORITY_MISMATCH,
	FACT_LINKAGE_MISMATCH,
	FACT_SEMANTICS_MISMATCH,
	FACT_INTEGRITY_MISMATCH,
}

data class AuthenticatedWifiWalCarrier internal constructor(
	val deliveryIdentity: String,
	val payload: WifiWalPayload,
	val coverage: WifiWalCoverage?,
)

sealed interface WifiWalCarrierAuthentication {
	data class Authenticated(
		val carrier: AuthenticatedWifiWalCarrier,
	) : WifiWalCarrierAuthentication

	data class Rejected(val reason: WifiWalCarrierFailure) : WifiWalCarrierAuthentication
}

/** Exact Wi-Fi WAL predicate shared by projection, history, lifecycle, and exact proof reads. */
object WifiWalCarrierAuthenticator {
	@Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
	fun authenticate(
		wal: SourceEventWalEntity,
		expectedAuthority: WifiWalCaptureAuthority? = null,
	): WifiWalCarrierAuthentication {
		if (wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_WIFI) {
			return rejected(WifiWalCarrierFailure.WRONG_SOURCE)
		}
		if (wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
			return rejected(WifiWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (wal.payload.size > MAX_WIFI_WAL_PAYLOAD_BYTES) {
			return rejected(WifiWalCarrierFailure.PAYLOAD_TOO_LARGE)
		}
		if (!LOWERCASE_SHA_256.matches(wal.payloadChecksum) ||
			wal.payloadChecksum != wal.calculatedPayloadChecksum()
		) return rejected(WifiWalCarrierFailure.PAYLOAD_INTEGRITY_MISMATCH)
		if (!LOWERCASE_SHA_256.matches(wal.integrityIdentity) ||
			wal.integrityIdentity != wal.calculatedIntegrityIdentity()
		) return rejected(WifiWalCarrierFailure.WAL_INTEGRITY_MISMATCH)
		if (!wal.hasCompleteCaptureBinding()) {
			return rejected(WifiWalCarrierFailure.INCOMPLETE_CAPTURE_BINDING)
		}
		if (!wal.hasCanonicalProducerEnvelope()) {
			return rejected(WifiWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		val payload = when (val decoded = WifiWalPayloadCodec.decodeCanonical(
			wal.payloadVersion,
			wal.payload,
		)) {
			is WifiWalPayloadDecodeResult.Decoded -> decoded.payload
			is WifiWalPayloadDecodeResult.Rejected ->
				return rejected(decoded.reason.toCarrierFailure())
		}
		if (payload.observations.isEmpty()) {
			return rejected(WifiWalCarrierFailure.EMPTY_PROVIDER_DELIVERY)
		}
		if (payload.observations.size > MAX_WIFI_WAL_CHILDREN) {
			return rejected(WifiWalCarrierFailure.TOO_MANY_CHILDREN)
		}
		if (payload.observations.any { it.identifierToken.isNotEmpty() }) {
			return rejected(WifiWalCarrierFailure.IDENTITY_BEARING_CHILD)
		}
		if (payload.resultAgeMs != null ||
			payload.observations.any { it.providerTimestampNanos?.let { time -> time <= 0L } != false }
		) return rejected(WifiWalCarrierFailure.INVALID_PROVIDER_TIME)
		if (payload.observations != payload.observations.sortedWith(WIFI_WAL_OBSERVATION_ORDER)) {
			return rejected(WifiWalCarrierFailure.NON_CANONICAL_CHILD_ORDER)
		}
		val providerTimes = payload.observations.map { observation ->
			observation.providerTimestampNanos
				?: return rejected(WifiWalCarrierFailure.INVALID_PROVIDER_TIME)
		}
		val latest = providerTimes.maxOrNull()
		if (providerTimes.minOrNull() != wal.observedIntervalStartNanos ||
			latest != wal.observedElapsedNanos ||
			payload.platformTimestampMs != requireNotNull(latest) / NANOS_PER_MILLISECOND
		) return rejected(WifiWalCarrierFailure.PROVIDER_TIMELINE_MISMATCH)
		val deliveryIdentity = canonicalWifiProviderDeliveryIdentity(
			wal.clockDomainId,
			payload.observations,
		)
		if (deliveryIdentity != wal.deliveryIdentity) {
			return rejected(WifiWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH)
		}
		if (expectedAuthority != null && !wal.matches(expectedAuthority, payload)) {
			return rejected(WifiWalCarrierFailure.CAPTURE_AUTHORITY_MISMATCH)
		}
		val coverage = expectedAuthority?.let { authority ->
			qualify(wal, payload, authority)
				?: return rejected(WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		return WifiWalCarrierAuthentication.Authenticated(
			AuthenticatedWifiWalCarrier(deliveryIdentity, payload, coverage),
		)
	}

	fun authenticate(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): WifiWalCarrierAuthentication {
		val authenticated = authenticate(wal, fact.captureAuthority())
		if (authenticated !is WifiWalCarrierAuthentication.Authenticated) return authenticated
		if (!fact.hasExactWalLink(wal, authenticated.carrier)) {
			return rejected(WifiWalCarrierFailure.FACT_LINKAGE_MISMATCH)
		}
		if (!fact.matchesCoverage(authenticated.carrier)) {
			return rejected(WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		if (fact.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
			!authenticatesAggregate(fact, authenticated.carrier)
		) return rejected(WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		if (!WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(fact)) {
			return rejected(WifiWalCarrierFailure.FACT_INTEGRITY_MISMATCH)
		}
		return authenticated
	}

	fun authenticates(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): Boolean = authenticate(fact, wal) is WifiWalCarrierAuthentication.Authenticated

	fun authenticatesAggregate(
		aggregateFact: WifiCapturedFactRevisionEntity,
		carrier: AuthenticatedWifiWalCarrier,
		dependentFact: WifiCapturedFactRevisionEntity = aggregateFact,
	): Boolean {
		val aggregate = carrier.coverage?.aggregate ?: return false
		return aggregateFact.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
			WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(aggregateFact) &&
			(dependentFact.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ||
				(dependentFact.aggregateOwnerLogicalFactId == aggregateFact.logicalFactId &&
					dependentFact.aggregateOwnerSemanticRevision ==
					aggregateFact.semanticRevision &&
					dependentFact.aggregateOwnerCursorRevision ==
					aggregateFact.semanticRevision)) &&
			aggregateFact.captureAuthorityKey() == dependentFact.captureAuthorityKey() &&
			aggregateFact.observationCount == aggregate.observationCount &&
			aggregateFact.twoPointFourGhzCount == aggregate.twoPointFourGhzCount &&
			aggregateFact.fiveGhzCount == aggregate.fiveGhzCount &&
			aggregateFact.sixGhzCount == aggregate.sixGhzCount &&
			aggregateFact.otherBandCount == aggregate.otherBandCount &&
			aggregateFact.strongestSignalDbm == aggregate.strongestSignalDbm &&
			aggregateFact.weakestSignalDbm == aggregate.weakestSignalDbm &&
			aggregateFact.signalSumDbm == aggregate.signalSumDbm
	}

	private fun qualify(
		wal: SourceEventWalEntity,
		payload: WifiWalPayload,
		authority: WifiWalCaptureAuthority,
	): WifiWalCoverage? {
		if (authority.maximumObservationAgeNanos < 0L ||
			authority.resultContract != WIFI_RESULT_CONTRACT
		) return null
		val dispositions = payload.observations.map { observation ->
			val providerTime = requireNotNull(observation.providerTimestampNanos)
			when {
				observation.frequencyMhz !in WIFI_MIN_FREQUENCY_MHZ..WIFI_MAX_FREQUENCY_MHZ ||
					observation.signalLevelDbm !in WIFI_MIN_SIGNAL_DBM..WIFI_MAX_SIGNAL_DBM ->
					WifiWalChildDisposition.MALFORMED
				providerTime > wal.receivedElapsedNanos ->
					WifiWalChildDisposition.CLOCK_UNVERIFIABLE
				providerTime !in authority.providerAcceptance ||
					providerTime !in authority.authorizationEffect ||
					providerTime !in authority.sessionRunEffect ||
					wal.receivedElapsedNanos - providerTime >
					authority.maximumObservationAgeNanos -> WifiWalChildDisposition.STALE
				else -> WifiWalChildDisposition.ACCEPTED
			}
		}
		val accepted = payload.observations.zip(dispositions)
			.filter { it.second == WifiWalChildDisposition.ACCEPTED }
			.map(Pair<WifiWalPayloadObservation, WifiWalChildDisposition>::first)
		if (accepted.isEmpty()) {
			return WifiWalCoverage(
				submittedResultCount = payload.observations.size,
				acceptedResultCount = 0,
				staleResultCount = dispositions.count { it == WifiWalChildDisposition.STALE },
				clockUnverifiableResultCount =
					dispositions.count { it == WifiWalChildDisposition.CLOCK_UNVERIFIABLE },
				malformedResultCount =
					dispositions.count { it == WifiWalChildDisposition.MALFORMED },
				coverageIntervalStartNanos = null,
				coverageIntervalEndNanos = null,
				observedWallTimeMs = null,
				wallTimeUncertaintyMs = null,
				aggregate = null,
			)
		}
		val acceptedTimes = accepted.map { requireNotNull(it.providerTimestampNanos) }
		val coverageStart = requireNotNull(acceptedTimes.minOrNull())
		val coverageEnd = requireNotNull(acceptedTimes.maxOrNull())
		val ageNanos = wal.observedElapsedNanos - coverageEnd
		if (ageNanos < 0L) return null
		val wall = wal.wallTimeMs ?: return null
		val baseUncertainty = wal.wallTimeUncertaintyMs ?: return null
		val ageMs = ageNanos / NANOS_PER_MILLISECOND
		if (wall < ageMs) return null
		val observedWall = wall - ageMs
		val uncertainty = runCatching {
			Math.addExact(
				baseUncertainty,
				if (ageNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L,
			)
		}.getOrNull() ?: return null
		val aggregate = accepted.toAggregate()
		return WifiWalCoverage(
			submittedResultCount = payload.observations.size,
			acceptedResultCount = accepted.size,
			staleResultCount = dispositions.count { it == WifiWalChildDisposition.STALE },
			clockUnverifiableResultCount =
				dispositions.count { it == WifiWalChildDisposition.CLOCK_UNVERIFIABLE },
			malformedResultCount = dispositions.count { it == WifiWalChildDisposition.MALFORMED },
			coverageIntervalStartNanos = coverageStart,
			coverageIntervalEndNanos = coverageEnd,
			observedWallTimeMs = observedWall,
			wallTimeUncertaintyMs = uncertainty,
			aggregate = aggregate,
		)
	}
}

fun canonicalWifiProviderDeliveryIdentity(
	clockDomainId: String,
	observations: List<WifiWalPayloadObservation>,
): String {
	require(clockDomainId.isNotBlank())
	require(observations.isNotEmpty())
	require(observations.all { it.identifierToken.isEmpty() })
	val canonical = buildString {
		append("wifi-v1|")
		append(clockDomainId.length)
		append(':')
		append(clockDomainId)
		append("|items:")
		observations.sortedWith(WIFI_WAL_OBSERVATION_ORDER).forEach { observation ->
			append(observation.frequencyMhz)
			append(':')
			append(observation.signalLevelDbm)
			append(':')
			append(requireNotNull(observation.providerTimestampNanos))
			append('|')
		}
	}
	return canonical.toByteArray(Charsets.UTF_8).sha256()
}

private fun WifiWalPayloadDecodeFailure.toCarrierFailure(): WifiWalCarrierFailure = when (this) {
	WifiWalPayloadDecodeFailure.UNSUPPORTED_PAYLOAD_VERSION ->
		WifiWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION
	WifiWalPayloadDecodeFailure.PAYLOAD_TOO_LARGE -> WifiWalCarrierFailure.PAYLOAD_TOO_LARGE
	WifiWalPayloadDecodeFailure.MALFORMED_PAYLOAD -> WifiWalCarrierFailure.PAYLOAD_DECODE_FAILED
	WifiWalPayloadDecodeFailure.NON_CANONICAL_PAYLOAD -> WifiWalCarrierFailure.NON_CANONICAL_PAYLOAD
}

@Suppress("ComplexCondition")
private fun SourceEventWalEntity.hasCompleteCaptureBinding(): Boolean =
	logicalTrackingId?.isNotBlank() == true &&
		serviceRunId?.isNotBlank() == true &&
		sourceInstanceId.isNotBlank() &&
		registrationGeneration > 0L &&
		configRevision?.let { it > 0L } == true &&
		physicalConfigurationFingerprint?.isNotBlank() == true &&
		authorizationRevision?.let { it > 0L } == true &&
		authorizationFingerprint?.let(LOWERCASE_SHA_256::matches) == true &&
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK ==
		authorizationPurposeEligibilityMask &&
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L &&
		sourcePolicyRevision?.let { it > 0L } == true &&
		captureConsentEpoch?.let { it >= 0L } == true &&
		sessionManifestRevision?.let { it > 0L } == true &&
		lifecycleLeaseGeneration?.let { it > 0L } == true &&
		capturedCollectedDataEpoch >= 0L &&
		clockDomainId.isNotBlank()

@Suppress("ComplexCondition")
private fun SourceEventWalEntity.hasCanonicalProducerEnvelope(): Boolean =
	admissionOrdinal > 0L &&
		providerDedupKey == null &&
		deliveryIdentity?.let(LOWERCASE_SHA_256::matches) == true &&
		deliveryUnitIndex == 0 &&
		deliveryUnitCount == 1 &&
		sourceSequence >= 0L &&
		planAttribution == CAPTURED_REGISTRATION_PLAN_ATTRIBUTION &&
		activityAutomationEpoch == null &&
		observedIntervalStartNanos?.let { it > 0L } == true &&
		observedElapsedNanos >= requireNotNull(observedIntervalStartNanos) &&
		receivedElapsedNanos >= observedElapsedNanos &&
		wallTimeMs?.let { wall ->
			receivedWallTimeMs?.let { receivedWall -> receivedWall >= wall }
		} == true &&
		requireNotNull(wallTimeMs) >= 0L &&
		wallTimeUncertaintyMs == WIFI_PROVIDER_WALL_UNCERTAINTY_MS &&
		acquiredAtMs == wallTimeMs &&
		createdAtMs >= 0L &&
		qualityFlags == 0L &&
		qualityConfidence == null

@Suppress("ComplexCondition")
private fun SourceEventWalEntity.matches(
	authority: WifiWalCaptureAuthority,
	payload: WifiWalPayload,
): Boolean =
	logicalTrackingId == authority.logicalTrackingId &&
		serviceRunId == authority.serviceRunId &&
		sourceInstanceId == authority.sourceInstanceId &&
		registrationGeneration == authority.registrationGeneration &&
		configRevision == authority.configurationRevision &&
		physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		authorizationRevision == authority.authorizationRevision &&
		authorizationFingerprint == authority.authorizationFingerprint &&
		authorizationPurposeEligibilityMask == authority.purposeEligibilityMask &&
		sourcePolicyRevision == authority.sourcePolicyRevision &&
		captureConsentEpoch == authority.captureConsentEpoch &&
		sessionManifestRevision == authority.manifestRevision &&
		lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration &&
		capturedCollectedDataEpoch == authority.collectedDataEpoch &&
		clockDomainId == authority.clockDomainId &&
		payload.observationZoneId == authority.zoneId

private fun WifiCapturedFactRevisionEntity.captureAuthority() = WifiWalCaptureAuthority(
	logicalTrackingId = logicalTrackingId,
	serviceRunId = serviceRunId,
	sourceInstanceId = sourceInstanceId,
	registrationGeneration = registrationGeneration,
	configurationRevision = configurationRevision,
	physicalConfigurationFingerprint = physicalConfigurationFingerprint,
	authorizationRevision = authorizationRevision,
	authorizationFingerprint = authorizationFingerprint,
	purposeEligibilityMask = purposeEligibilityMask,
	sourcePolicyRevision = sourcePolicyRevision,
	captureConsentEpoch = captureConsentEpoch,
	manifestRevision = manifestRevision,
	lifecycleLeaseGeneration = lifecycleLeaseGeneration,
	collectedDataEpoch = collectedDataEpoch,
	clockDomainId = clockDomainId,
	zoneId = storedZoneId,
	providerAcceptance = WifiWalProviderInterval(
		providerAcceptanceStartNanos,
		providerAcceptanceEndNanos,
	),
	authorizationEffect = WifiWalProviderInterval(
		authorizationEffectStartNanos,
		authorizationEffectEndNanos,
	),
	sessionRunEffect = WifiWalProviderInterval(
		sessionRunEffectStartNanos,
		sessionRunEffectEndNanos,
	),
	maximumObservationAgeNanos = maximumObservationAgeNanos,
	resultContract = resultContract,
)

@Suppress("ComplexCondition", "LongMethod")
private fun WifiCapturedFactRevisionEntity.hasExactWalLink(
	wal: SourceEventWalEntity,
	carrier: AuthenticatedWifiWalCarrier,
): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		sourceEventId == wal.eventId &&
		sourceAdmissionOrdinal == wal.admissionOrdinal &&
		walIntegrityIdentity == wal.integrityIdentity &&
		payloadChecksum == wal.payloadChecksum &&
		sourceDeliveryIdentity == carrier.deliveryIdentity &&
		sourceDeliveryIdentity == wal.deliveryIdentity &&
		deliveryUnitIndex == wal.deliveryUnitIndex &&
		deliveryUnitCount == wal.deliveryUnitCount &&
		sourceSequence == wal.sourceSequence &&
		planAttribution == WifiCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION &&
		logicalTrackingId == wal.logicalTrackingId &&
		serviceRunId == wal.serviceRunId &&
		sourceInstanceId == wal.sourceInstanceId &&
		registrationGeneration == wal.registrationGeneration &&
		configurationRevision == wal.configRevision &&
		physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint &&
		authorizationRevision == wal.authorizationRevision &&
		authorizationFingerprint == wal.authorizationFingerprint &&
		purposeEligibilityMask == wal.authorizationPurposeEligibilityMask &&
		sourcePolicyRevision == wal.sourcePolicyRevision &&
		captureConsentEpoch == wal.captureConsentEpoch &&
		manifestRevision == wal.sessionManifestRevision &&
		lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration &&
		collectedDataEpoch == wal.capturedCollectedDataEpoch &&
		clockDomainId == wal.clockDomainId &&
		observedIntervalStartNanos == wal.observedIntervalStartNanos &&
		observedElapsedNanos == wal.observedElapsedNanos &&
		receivedElapsedNanos == wal.receivedElapsedNanos &&
		acquiredAtMs == wal.acquiredAtMs &&
		qualityFlags == wal.qualityFlags &&
		qualityConfidence == wal.qualityConfidence

@Suppress("ComplexCondition")
private fun WifiCapturedFactRevisionEntity.matchesCoverage(
	carrier: AuthenticatedWifiWalCarrier,
): Boolean {
	val coverage = carrier.coverage ?: return false
	val coverageStart = coverage.coverageIntervalStartNanos ?: return false
	val coverageEnd = coverage.coverageIntervalEndNanos ?: return false
	val derivedWallTime = coverage.observedWallTimeMs ?: return false
	val derivedUncertainty = coverage.wallTimeUncertaintyMs ?: return false
	return coverage.acceptedResultCount > 0 &&
		coverageIntervalStartNanos == coverageStart &&
		coverageIntervalEndNanos == coverageEnd &&
		observedWallTimeMs == derivedWallTime &&
		wallTimeUncertaintyMs == derivedUncertainty &&
		availability == WifiCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE &&
		submittedResultCount == coverage.submittedResultCount &&
		acceptedResultCount == coverage.acceptedResultCount &&
		staleResultCount == coverage.staleResultCount &&
		clockUnverifiableResultCount == coverage.clockUnverifiableResultCount &&
		malformedResultCount == coverage.malformedResultCount &&
		coverageCompleteness == coverage.completeness &&
		appliedAtMs == derivedWallTime
}

private fun WifiCapturedFactRevisionEntity.captureAuthorityKey(): List<Any?> = listOf(
	logicalTrackingId,
	serviceRunId,
	sessionSegmentId,
	capturedSourceCodes,
	controlSourceCodes,
	sourceInstanceId,
	registrationGeneration,
	configurationRevision,
	physicalConfigurationFingerprint,
	authorizationRevision,
	authorizationFingerprint,
	purposeEligibilityMask,
	sourcePolicyRevision,
	captureConsentEpoch,
	manifestRevision,
	lifecycleLeaseGeneration,
	collectedDataEpoch,
	scopeDeletionGeneration,
	clockDomainId,
	storedZoneId,
	planPayloadVersion,
	planPayloadChecksum,
	maximumObservationAgeNanos,
	resultContract,
	registrationAppliedAtNanos,
	providerAcceptanceStartNanos,
	providerAcceptanceEndNanos,
	authorizationEffectStartNanos,
	authorizationEffectEndNanos,
	sessionRunEffectStartNanos,
	sessionRunEffectEndNanos,
)

private fun List<WifiWalPayloadObservation>.toAggregate(): WifiWalIdentityFreeAggregate {
	val frequencies = groupingBy { observation -> wifiBand(observation.frequencyMhz) }.eachCount()
	val signals = map(WifiWalPayloadObservation::signalLevelDbm)
	return WifiWalIdentityFreeAggregate(
		observationCount = size,
		twoPointFourGhzCount = frequencies[WifiBand.TWO_POINT_FOUR_GHZ] ?: 0,
		fiveGhzCount = frequencies[WifiBand.FIVE_GHZ] ?: 0,
		sixGhzCount = frequencies[WifiBand.SIX_GHZ] ?: 0,
		otherBandCount = frequencies[WifiBand.OTHER] ?: 0,
		strongestSignalDbm = signals.max(),
		weakestSignalDbm = signals.min(),
		signalSumDbm = signals.sumOf { it.toLong() },
	)
}

private fun wifiBand(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
	in 2_400..2_500 -> WifiBand.TWO_POINT_FOUR_GHZ
	in 4_900..5_900 -> WifiBand.FIVE_GHZ
	in 5_925..7_125 -> WifiBand.SIX_GHZ
	else -> WifiBand.OTHER
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun rejected(reason: WifiWalCarrierFailure) =
	WifiWalCarrierAuthentication.Rejected(reason)

private enum class WifiBand { TWO_POINT_FOUR_GHZ, FIVE_GHZ, SIX_GHZ, OTHER }

private const val TYPE_WIFI_RESULTS = 7
private const val MAX_WIFI_WAL_CHILDREN = 64
private const val MAX_WIFI_ZONE_ID_LENGTH = 64
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CAPTURED_REGISTRATION_PLAN_ATTRIBUTION = 0
private const val WIFI_PROVIDER_WALL_UNCERTAINTY_MS = 1L
private const val WIFI_RESULT_CONTRACT = "ANDROID_SCAN_RESULTS_V1"
private const val WIFI_MIN_FREQUENCY_MHZ = 2_400
private const val WIFI_MAX_FREQUENCY_MHZ = 7_125
private const val WIFI_MIN_SIGNAL_DBM = -200
private const val WIFI_MAX_SIGNAL_DBM = 0
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
private val WIFI_WAL_OBSERVATION_ORDER = compareBy<WifiWalPayloadObservation>(
	WifiWalPayloadObservation::frequencyMhz,
	WifiWalPayloadObservation::signalLevelDbm,
	WifiWalPayloadObservation::providerTimestampNanos,
)
