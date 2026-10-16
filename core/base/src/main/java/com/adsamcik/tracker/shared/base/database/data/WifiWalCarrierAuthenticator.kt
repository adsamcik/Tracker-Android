@file:Suppress("LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.ZoneId

const val WIFI_WAL_PAYLOAD_VERSION = 6
const val LEGACY_WIFI_WAL_PAYLOAD_VERSION = 5
const val MAX_WIFI_WAL_PAYLOAD_BYTES = 32_768
private const val MAX_LEGACY_WIFI_WAL_PAYLOAD_BYTES = 2_048

/**
 * One original network reading with available identifier/name, MHz frequency, dBm signal and
 * optional provider time.
 */
data class WifiWalPayloadObservation(
	val identifierToken: String,
	val frequencyMhz: Int,
	val signalLevelDbm: Int,
	val providerTimestampNanos: Long?,
	val networkName: String? = null,
) {
	override fun toString(): String = "WifiWalPayloadObservation"
}

/**
 * One original Wi-Fi delivery with network readings and its scan/refresh metadata.
 */
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

/**
 * Immutable Wi-Fi payload bytes together with their deterministic checksum.
 */
data class EncodedWifiWalPayload internal constructor(
	val bytes: List<Byte>,
	val checksum: String,
) {
	/**
	 * Returns an independent byte-array copy of the encoded payload.
	 */
	fun toByteArray(): ByteArray = bytes.toByteArray()

	override fun toString(): String = "EncodedWifiWalPayload"
}

/**
 * Supported-version, payload-bound and canonical-format Wi-Fi decode failure causes.
 */
enum class WifiWalPayloadDecodeFailure {
	UNSUPPORTED_PAYLOAD_VERSION,
	PAYLOAD_TOO_LARGE,
	MALFORMED_PAYLOAD,
	NON_CANONICAL_PAYLOAD,
}

/**
 * Decoded Wi-Fi payload or a specific format rejection; decoding alone grants no collection
 * permission.
 */
sealed interface WifiWalPayloadDecodeResult {
	/**
	 * Supported canonical payload returned after bounded binary decoding.
	 */
	data class Decoded(val payload: WifiWalPayload) : WifiWalPayloadDecodeResult
	/**
	 * Specific rejected decode, carrier or per-purpose qualification outcome; it carries no accepted
	 * payload.
	 */
	data class Rejected(val reason: WifiWalPayloadDecodeFailure) : WifiWalPayloadDecodeResult
}

/**
 * Reports whether the payload version is within the supported legacy-to-current Wi-Fi range.
 */
fun isSupportedWifiWalPayloadVersion(payloadVersion: Int): Boolean =
	payloadVersion in LEGACY_WIFI_WAL_PAYLOAD_VERSION..WIFI_WAL_PAYLOAD_VERSION

private fun wifiPayloadLimit(payloadVersion: Int): Int =
	if (payloadVersion == LEGACY_WIFI_WAL_PAYLOAD_VERSION) {
		MAX_LEGACY_WIFI_WAL_PAYLOAD_BYTES
	} else {
		MAX_WIFI_WAL_PAYLOAD_BYTES
	}

/**
 * Bounded binary Wi-Fi encoding preserving legacy summary inputs and current network details.
 */
object WifiWalPayloadCodec {
	/**
	 * Serializes Wi-Fi payload values into the supported deterministic binary layout; nullable
	 * measurements keep their absence.
	 */
	fun encode(
		payload: WifiWalPayload,
		payloadVersion: Int = WIFI_WAL_PAYLOAD_VERSION,
	): EncodedWifiWalPayload {
		require(payloadVersion in LEGACY_WIFI_WAL_PAYLOAD_VERSION..WIFI_WAL_PAYLOAD_VERSION)
		require(payload.observations.size <= MAX_WIFI_WAL_CHILDREN)
		if (payloadVersion == LEGACY_WIFI_WAL_PAYLOAD_VERSION) {
			require(payload.observations.all { it.networkName == null })
		}
		val bytes = ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(TYPE_WIFI_RESULTS)
				output.writeInt(payload.observations.size)
				payload.observations.forEach { observation ->
					output.writeUTF(observation.identifierToken)
					output.writeInt(observation.frequencyMhz)
					output.writeInt(observation.signalLevelDbm)
					output.writeNullableLong(observation.providerTimestampNanos)
					if (payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
						output.writeBoolean(observation.networkName != null)
						observation.networkName?.let(output::writeUTF)
					}
				}
				output.writeNullableLong(payload.platformTimestampMs)
				output.writeNullableLong(payload.resultAgeMs)
				output.writeUTF(payload.observationZoneId)
			}
			buffer.toByteArray()
		}
		require(bytes.size <= wifiPayloadLimit(payloadVersion))
		return EncodedWifiWalPayload(bytes.toList(), bytes.sha256())
	}

	/**
	 * Decodes a bounded supported Wi-Fi payload and requires exact canonical bytes; malformed data is
	 * rejected through the declared result type.
	 */
	// Keep each bounded format failure distinct at this public decode result boundary.
	@Suppress("ReturnCount")
	fun decodeCanonical(
		payloadVersion: Int,
		bytes: ByteArray,
	): WifiWalPayloadDecodeResult {
		if (payloadVersion !in LEGACY_WIFI_WAL_PAYLOAD_VERSION..WIFI_WAL_PAYLOAD_VERSION) {
			return WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.UNSUPPORTED_PAYLOAD_VERSION,
			)
		}
		if (bytes.isEmpty() || bytes.size > wifiPayloadLimit(payloadVersion)) {
			return WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.PAYLOAD_TOO_LARGE,
			)
		}
		val payload = runCatching {
			readPayload(payloadVersion, bytes)
		}.getOrElse {
			return WifiWalPayloadDecodeResult.Rejected(
				WifiWalPayloadDecodeFailure.MALFORMED_PAYLOAD,
			)
		}
		val canonical = runCatching { encode(payload, payloadVersion).toByteArray() }.getOrElse {
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

	private fun readPayload(payloadVersion: Int, bytes: ByteArray): WifiWalPayload =
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
					networkName = if (payloadVersion == WIFI_WAL_PAYLOAD_VERSION &&
						input.readBoolean()) {
						input.readUTF()
					} else {
						null
					},
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
}

/**
 * Half-open provider or authority acceptance interval measured in elapsed-realtime nanoseconds.
 */
data class WifiWalProviderInterval(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}

	/**
	 * Tests membership in this half-open elapsed-realtime interval, in nanoseconds.
	 */
	operator fun contains(value: Long): Boolean =
		value >= startInclusiveNanos && value < endExclusiveNanos
}

/**
 * Original Wi-Fi source/session/policy/consent/data ownership and provider-time bounds used to
 * qualify each reading.
 */
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

/**
 * Per-reading Wi-Fi acceptance outcome independent of other readings in the delivery.
 */
enum class WifiWalChildDisposition {
	ACCEPTED,
	STALE,
	CLOCK_UNVERIFIABLE,
	MALFORMED,
}

/**
 * Qualified Wi-Fi band counts and signal statistics without retaining network identifiers.
 */
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

/**
 * Qualified/rejected Wi-Fi item counts, optional measured bounds and aggregate; complete means
 * every submitted item qualified.
 */
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

/**
 * Specific original Wi-Fi envelope, payload, time, child and revision comparison failures.
 */
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
	CHILD_EVIDENCE_MISMATCH,
}

/**
 * Original decoded Wi-Fi delivery after envelope checks, with optional per-purpose measured
 * coverage.
 */
data class AuthenticatedWifiWalCarrier internal constructor(
	val deliveryIdentity: String,
	val payload: WifiWalPayload,
	val coverage: WifiWalCoverage?,
)

/**
 * Typed Wi-Fi carrier comparison result with the exact rejection cause.
 */
sealed interface WifiWalCarrierAuthentication {
	/**
	 * Successful original-carrier comparison containing its checked, decoded measurement payload.
	 */
	data class Authenticated(
		val carrier: AuthenticatedWifiWalCarrier,
	) : WifiWalCarrierAuthentication

	/**
	 * Specific rejected decode, carrier or per-purpose qualification outcome; it carries no accepted
	 * payload.
	 */
	data class Rejected(val reason: WifiWalCarrierFailure) : WifiWalCarrierAuthentication
}

/** Exact Wi-Fi WAL predicate shared by projection, history, lifecycle, and exact proof reads. */
object WifiWalCarrierAuthenticator {
	/**
	 * Checks the original Wi-Fi WAL checksum, provider envelope, supported payload and recorded
	 * capture binding; reports the exact rejection cause.
	 */
	@Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
	fun authenticate(
		wal: SourceEventWalEntity,
		expectedAuthority: WifiWalCaptureAuthority? = null,
	): WifiWalCarrierAuthentication {
		if (wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_WIFI) {
			return rejected(WifiWalCarrierFailure.WRONG_SOURCE)
		}
		if (!isSupportedWifiWalPayloadVersion(wal.payloadVersion)) {
			return rejected(WifiWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (wal.payload.size > wifiPayloadLimit(wal.payloadVersion)) {
			return rejected(WifiWalCarrierFailure.PAYLOAD_TOO_LARGE)
		}
		if (!LOWERCASE_SHA_256.matches(wal.payloadChecksum) ||
			wal.payloadChecksum != wal.calculatedPayloadChecksum()
		) {
			return rejected(WifiWalCarrierFailure.PAYLOAD_INTEGRITY_MISMATCH)
		}
		if (!LOWERCASE_SHA_256.matches(wal.integrityIdentity) ||
			wal.integrityIdentity != wal.calculatedIntegrityIdentity()
		) {
			return rejected(WifiWalCarrierFailure.WAL_INTEGRITY_MISMATCH)
		}
		if (wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION && !wal.hasCompleteCaptureBinding()) {
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
		if (wal.payloadVersion == LEGACY_WIFI_WAL_PAYLOAD_VERSION &&
			payload.observations.any { it.identifierToken.isNotEmpty() || it.networkName != null }
		) {
			return rejected(WifiWalCarrierFailure.IDENTITY_BEARING_CHILD)
		}
		if (payload.resultAgeMs != null ||
			payload.observations.any { it.providerTimestampNanos?.let { time -> time <= 0L } != false }
		) {
			return rejected(WifiWalCarrierFailure.INVALID_PROVIDER_TIME)
		}
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
		) {
			return rejected(WifiWalCarrierFailure.PROVIDER_TIMELINE_MISMATCH)
		}
		val deliveryIdentity = canonicalWifiProviderDeliveryIdentity(
			wal.clockDomainId,
			payload.observations,
			wal.payloadVersion,
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

	/**
	 * Checks the saved Wi-Fi revision against its original WAL carrier, ownership, measured values
	 * and supplied child/count-domain evidence.
	 */
	// Keep original-carrier, owner and missing-coverage failures in their existing order.
	@Suppress("ReturnCount")
	fun authenticate(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		children: List<WifiCapturedChildEntity>? = null,
	): WifiWalCarrierAuthentication {
		val authenticated = authenticate(wal)
		if (authenticated !is WifiWalCarrierAuthentication.Authenticated) {
			return authenticated
		}
		val authority = fact.captureAuthority()
		if (!wal.matches(authority, authenticated.carrier.payload)) {
			return rejected(WifiWalCarrierFailure.CAPTURE_AUTHORITY_MISMATCH)
		}
		val selectedIndexes = if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
			if (children == null || !children.authenticateFor(fact, wal, authenticated.carrier.payload)) {
				return rejected(WifiWalCarrierFailure.CHILD_EVIDENCE_MISMATCH)
			}
			children.map(WifiCapturedChildEntity::itemIndex).toSet()
		} else {
			null
		}
		val coverage = qualify(wal, authenticated.carrier.payload, authority, selectedIndexes)
			?: return rejected(WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		val carrier = authenticated.carrier.copy(coverage = coverage)
		return authenticateFactProjection(fact, wal, carrier)
	}

	// These saved-fact checks preserve their distinct linkage, semantics and integrity rejections.
	@Suppress("ReturnCount")
	private fun authenticateFactProjection(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		carrier: AuthenticatedWifiWalCarrier,
	): WifiWalCarrierAuthentication {
		if (!fact.hasExactWalLink(wal, carrier)) {
			return rejected(WifiWalCarrierFailure.FACT_LINKAGE_MISMATCH)
		}
		if (!fact.matchesCoverage(carrier)) {
			return rejected(WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		if (fact.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
			!authenticatesAggregate(fact, carrier)
		) {
			return rejected(WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		if (!WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(fact)) {
			return rejected(WifiWalCarrierFailure.FACT_INTEGRITY_MISMATCH)
		}
		return WifiWalCarrierAuthentication.Authenticated(carrier)
	}

	/**
	 * Returns whether the saved Wi-Fi fact and supplied original carrier pass the complete carrier
	 * comparison.
	 */
	fun authenticates(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		children: List<WifiCapturedChildEntity>? = null,
	): Boolean = authenticate(fact, wal, children) is WifiWalCarrierAuthentication.Authenticated

	/**
	 * Checks that aggregate values and original owner revision match the qualified Wi-Fi carrier and
	 * any dependent coverage-only fact.
	 */
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
			aggregateFact.matchesAggregate(aggregate)
	}

	private fun qualifyChildDispositions(
		wal: SourceEventWalEntity,
		payload: WifiWalPayload,
		authority: WifiWalCaptureAuthority,
		selectedIndexes: Set<Int>?,
	): List<WifiWalChildDisposition> {
		val dispositions = payload.observations.mapIndexed { index, observation ->
			val providerTime = requireNotNull(observation.providerTimestampNanos)
			when {
				!observation.hasSupportedWifiProductShape() ->
					WifiWalChildDisposition.MALFORMED
				selectedIndexes != null && index !in selectedIndexes ->
					WifiWalChildDisposition.STALE
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
		return dispositions
	}

	// Missing or nonrepresentable measured time remains unknown at each original ordered exit.
	@Suppress("ReturnCount")
	private fun qualify(
		wal: SourceEventWalEntity,
		payload: WifiWalPayload,
		authority: WifiWalCaptureAuthority,
		selectedIndexes: Set<Int>? = null,
	): WifiWalCoverage? {
		if (authority.maximumObservationAgeNanos < 0L ||
			authority.resultContract != WIFI_RESULT_CONTRACT
		) {
			return null
		}
		val dispositions = qualifyChildDispositions(wal, payload, authority, selectedIndexes)
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
		if (ageNanos < 0L) {
			return null
		}
		val wall = wal.wallTimeMs ?: return null
		val baseUncertainty = wal.wallTimeUncertaintyMs ?: return null
		val ageMs = ageNanos / NANOS_PER_MILLISECOND
		if (wall < ageMs) {
			return null
		}
		val observedWall = wall - ageMs
		val uncertainty = runCatching {
			Math.addExact(
				baseUncertainty,
				if (ageNanos % NANOS_PER_MILLISECOND == 0L) {
					0L
				} else {
					1L
				},
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

/**
 * Hashes the clock domain and canonically sorted measured Wi-Fi readings into a delivery identity;
 * timestamps must exist.
 */
fun canonicalWifiProviderDeliveryIdentity(
	clockDomainId: String,
	observations: List<WifiWalPayloadObservation>,
	payloadVersion: Int = WIFI_WAL_PAYLOAD_VERSION,
): String {
	require(isSupportedWifiWalPayloadVersion(payloadVersion))
	require(clockDomainId.isNotBlank())
	require(observations.isNotEmpty())
	if (payloadVersion == LEGACY_WIFI_WAL_PAYLOAD_VERSION) {
		require(observations.all { it.identifierToken.isEmpty() && it.networkName == null })
	}
	val canonical = buildString {
		append(if (payloadVersion == LEGACY_WIFI_WAL_PAYLOAD_VERSION) {
			"wifi-v1|"
		} else {
			"wifi-v2|"
		})
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
			if (payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
				append(':')
				append(observation.identifierToken.length)
				append(':')
				append(observation.identifierToken)
				append(':')
				append(observation.networkName?.length ?: -1)
				append(':')
				append(observation.networkName.orEmpty())
			}
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

// Keep the complete capture-header nullability and purpose contract together.
// Separating its fields would hide the schema being checked.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.hasCompleteCaptureBinding(): Boolean =
	logicalTrackingId?.isNotBlank() == true &&
		serviceRunId?.isNotBlank() == true &&
		sourceInstanceId.isNotBlank() &&
		registrationGeneration > 0L &&
		// Wi-Fi WAL may derive the exact plan revision from its authenticated manifest.
		(configRevision == null || configRevision > 0L) &&
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

// One callback wire schema with a necessary version branch.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.hasCanonicalProducerEnvelope(): Boolean =
	admissionOrdinal > 0L &&
		providerDedupKey == null &&
		deliveryIdentity?.let(LOWERCASE_SHA_256::matches) == true &&
		deliveryUnitIndex == 0 &&
		deliveryUnitCount == 1 &&
		sourceSequence >= 0L &&
		(if (payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
			hasCanonicalPerReadingBinding()
		} else
			{
			planAttribution == CAPTURED_REGISTRATION_PLAN_ATTRIBUTION
		}) &&
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

/** v6 retains the real callback header independently of historical capture purposes. */
// Keep the two persisted header shapes and their fixed nullability requirements together.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.hasCanonicalPerReadingBinding(): Boolean {
	if (sourceInstanceId.isBlank() || registrationGeneration <= 0L ||
		configRevision?.let { it > 0L } == false || physicalConfigurationFingerprint.isNullOrBlank() ||
		authorizationRevision?.let { it > 0L } != true ||
		authorizationFingerprint?.let(LOWERCASE_SHA_256::matches) != true ||
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK != authorizationPurposeEligibilityMask ||
		capturedCollectedDataEpoch < 0L || clockDomainId.isBlank()
	) {
		return false
	}
	return if (authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
		hasCompleteCaptureBinding() && planAttribution == CAPTURED_REGISTRATION_PLAN_ATTRIBUTION
	} else {
		listOf(logicalTrackingId, serviceRunId, sourcePolicyRevision, captureConsentEpoch,
		sessionManifestRevision, lifecycleLeaseGeneration).all { it == null } &&
		configRevision?.let { it > 0L } == true && planAttribution == RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION
	}
}

// This is a complete cross-type tuple comparison.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.matches(
	authority: WifiWalCaptureAuthority,
	payload: WifiWalPayload,
): Boolean =
	(payloadVersion == WIFI_WAL_PAYLOAD_VERSION || (
		logicalTrackingId == authority.logicalTrackingId && serviceRunId == authority.serviceRunId &&
		(configRevision == null || configRevision == authority.configurationRevision) &&
		authorizationRevision == authority.authorizationRevision &&
		authorizationFingerprint == authority.authorizationFingerprint &&
		authorizationPurposeEligibilityMask == authority.purposeEligibilityMask &&
		sourcePolicyRevision == authority.sourcePolicyRevision &&
            captureConsentEpoch == authority.captureConsentEpoch &&
		sessionManifestRevision == authority.manifestRevision &&
            lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration)) &&
		sourceInstanceId == authority.sourceInstanceId && registrationGeneration == authority.registrationGeneration &&
		physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		capturedCollectedDataEpoch == authority.collectedDataEpoch && clockDomainId == authority.clockDomainId &&
		(payloadVersion == WIFI_WAL_PAYLOAD_VERSION || payload.observationZoneId == authority.zoneId)

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

// Already deliberately retained as one long structural comparison.
@Suppress("ComplexCondition", "LongMethod", "CyclomaticComplexMethod")
private fun WifiCapturedFactRevisionEntity.hasExactWalLink(
	wal: SourceEventWalEntity,
	carrier: AuthenticatedWifiWalCarrier,
): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		sourceEventId == wal.eventId &&
		sourceAdmissionOrdinal == wal.admissionOrdinal &&
		walIntegrityIdentity == wal.integrityIdentity &&
		payloadChecksum == wal.payloadChecksum &&
		(payloadVersion == null || payloadVersion == wal.payloadVersion) &&
		sourceDeliveryIdentity == carrier.deliveryIdentity &&
		sourceDeliveryIdentity == wal.deliveryIdentity &&
		deliveryUnitIndex == wal.deliveryUnitIndex &&
		deliveryUnitCount == wal.deliveryUnitCount &&
		sourceSequence == wal.sourceSequence &&
		planAttribution == WifiCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION &&
		(wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION || (
		logicalTrackingId == wal.logicalTrackingId &&
		serviceRunId == wal.serviceRunId &&
		(wal.configRevision == null || configurationRevision == wal.configRevision) &&
		authorizationRevision == wal.authorizationRevision &&
		authorizationFingerprint == wal.authorizationFingerprint &&
		purposeEligibilityMask == wal.authorizationPurposeEligibilityMask &&
		sourcePolicyRevision == wal.sourcePolicyRevision &&
		captureConsentEpoch == wal.captureConsentEpoch &&
		manifestRevision == wal.sessionManifestRevision &&
		lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration)) &&
		sourceInstanceId == wal.sourceInstanceId &&
		registrationGeneration == wal.registrationGeneration &&
		physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint &&
		collectedDataEpoch == wal.capturedCollectedDataEpoch &&
		clockDomainId == wal.clockDomainId &&
		observedIntervalStartNanos == wal.observedIntervalStartNanos &&
		observedElapsedNanos == wal.observedElapsedNanos &&
		receivedElapsedNanos == wal.receivedElapsedNanos &&
		acquiredAtMs == wal.acquiredAtMs &&
		qualityFlags == wal.qualityFlags &&
		qualityConfidence == wal.qualityConfidence

// One persisted coverage representation correspondence; null rejection belongs with the exact values that
// follow so an unavailable derived field cannot be filled from another observation.
@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
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

/** A v6 fact may claim only exact, purpose-owned children of its original callback. */
private fun List<WifiCapturedChildEntity>.authenticateFor(
	fact: WifiCapturedFactRevisionEntity,
	wal: SourceEventWalEntity,
	payload: WifiWalPayload,
): Boolean {
	if (isEmpty() || size != fact.acceptedResultCount || size > MAX_WIFI_WAL_CHILDREN ||
		map(WifiCapturedChildEntity::itemIndex).distinct().size != size
	) {
		return false
	}
	val wallAnchor = wal.wallTimeMs ?: return false
	val uncertaintyAnchor = wal.wallTimeUncertaintyMs ?: return false
	return all { child ->
		val observation = payload.observations.getOrNull(child.itemIndex) ?: return@all false
		child.authenticatesObservation(fact, wal, observation, wallAnchor, uncertaintyAnchor)
	}
}

// One saved child rejects missing provider time, mismatched ownership and nonrepresentable clock values.
@Suppress("ReturnCount")
private fun WifiCapturedChildEntity.authenticatesObservation(
	fact: WifiCapturedFactRevisionEntity,
	wal: SourceEventWalEntity,
	observation: WifiWalPayloadObservation,
	wallAnchor: Long,
	uncertaintyAnchor: Long,
): Boolean {
	val child = this
	val providerNanos = observation.providerTimestampNanos ?: return false
	val ageNanos = wal.observedElapsedNanos - providerNanos
	// Compare the saved age and four owner fields as one mismatch guard.
	@Suppress("ComplexCondition")
	if (ageNanos < 0L || child.writerProjectionId != fact.writerProjectionId ||
		child.writerProjectionVersion != fact.writerProjectionVersion ||
		child.logicalFactId != fact.logicalFactId ||
		child.semanticRevision != fact.semanticRevision
	) {
		return false
	}
	val wall = runCatching { Math.subtractExact(wallAnchor, ageNanos / NANOS_PER_MILLISECOND) }
		.getOrNull() ?: return false
	val uncertainty = runCatching {
		Math.addExact(uncertaintyAnchor,
			if (ageNanos % NANOS_PER_MILLISECOND == 0L) {
				0L
			} else {
				1L
			})
	}.getOrNull() ?: return false
	return matchesMeasuredObservation(fact, observation, providerNanos, wall, uncertainty)
}

private fun WifiCapturedChildEntity.matchesMeasuredObservation(
	fact: WifiCapturedFactRevisionEntity,
	observation: WifiWalPayloadObservation,
	providerNanos: Long,
	wall: Long,
	uncertainty: Long,
): Boolean {
	val child = this
	val expectedBssid = observation.identifierToken.takeIf(String::isNotEmpty)
	val owner = RadioChildOwner(
		RadioChildPurpose.SESSION, fact.writerProjectionId,
		fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision,
		child.itemIndex,
	)
	return child.providerTimestampNanos == providerNanos && child.observedWallTimeMs == wall &&
		child.wallTimeUncertaintyMs == uncertainty && child.bssid == expectedBssid &&
		child.ssid == observation.networkName &&
		child.frequencyMhz == observation.frequencyMhz &&
		child.signalDbm == observation.signalLevelDbm &&
		child.contentChecksum == RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(
			owner, providerNanos, wall, uncertainty, expectedBssid,
			observation.networkName, observation.frequencyMhz,
			observation.signalLevelDbm,
		))
}

/** Compares measured aggregate values after ownership and integrity have been authenticated. */
private fun WifiCapturedFactRevisionEntity.matchesAggregate(aggregate: WifiWalIdentityFreeAggregate): Boolean =
	observationCount == aggregate.observationCount &&
		twoPointFourGhzCount == aggregate.twoPointFourGhzCount &&
		fiveGhzCount == aggregate.fiveGhzCount &&
		sixGhzCount == aggregate.sixGhzCount &&
		otherBandCount == aggregate.otherBandCount &&
		strongestSignalDbm == aggregate.strongestSignalDbm &&
		weakestSignalDbm == aggregate.weakestSignalDbm &&
		signalSumDbm == aggregate.signalSumDbm

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
	if (value != null) {
		writeLong(value)
	}
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) {
	readLong()
} else {
	null
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun rejected(reason: WifiWalCarrierFailure) =
	WifiWalCarrierAuthentication.Rejected(reason)

private enum class WifiBand { TWO_POINT_FOUR_GHZ, FIVE_GHZ, SIX_GHZ, OTHER }

private const val TYPE_WIFI_RESULTS = 7
const val MAX_WIFI_WAL_CHILDREN = 64
private const val MAX_WIFI_ZONE_ID_LENGTH = 64
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CAPTURED_REGISTRATION_PLAN_ATTRIBUTION = 0
private const val WIFI_PROVIDER_WALL_UNCERTAINTY_MS = 1L
private const val WIFI_RESULT_CONTRACT = "ANDROID_SCAN_RESULTS_V1"
internal fun WifiWalPayloadObservation.hasSupportedWifiProductShape(): Boolean =
	frequencyMhz in WIFI_MIN_FREQUENCY_MHZ..WIFI_MAX_FREQUENCY_MHZ &&
		signalLevelDbm in WIFI_MIN_SIGNAL_DBM..WIFI_MAX_SIGNAL_DBM &&
		identifierToken.length <= MAX_WIFI_BSSID_LENGTH &&
		(networkName == null || networkName.isNotEmpty() &&
			networkName.toByteArray(Charsets.UTF_8).size <= MAX_WIFI_NAME_BYTES)

private const val WIFI_MIN_FREQUENCY_MHZ = 2_400
private const val WIFI_MAX_FREQUENCY_MHZ = 7_125
private const val WIFI_MIN_SIGNAL_DBM = -200
private const val WIFI_MAX_SIGNAL_DBM = 0
private const val MAX_WIFI_BSSID_LENGTH = 64
private const val MAX_WIFI_NAME_BYTES = 128
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
private val WIFI_WAL_OBSERVATION_ORDER = compareBy<WifiWalPayloadObservation>(
	WifiWalPayloadObservation::frequencyMhz,
	WifiWalPayloadObservation::signalLevelDbm,
	WifiWalPayloadObservation::providerTimestampNanos,
	WifiWalPayloadObservation::identifierToken,
	WifiWalPayloadObservation::networkName,
)

private const val RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION = 2
