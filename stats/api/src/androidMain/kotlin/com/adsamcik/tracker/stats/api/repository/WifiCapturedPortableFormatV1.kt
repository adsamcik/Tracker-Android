package com.adsamcik.tracker.stats.api.repository

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId

/** Stable, identity-free v1 interchange for captured Wi-Fi product evidence. */
object WifiCapturedPortableFormatV1 {
	const val FORMAT = "tracker-portable-captured-wifi"
	const val SCHEMA_VERSION = 1
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.captured-wifi+json"
	const val FILE_EXTENSION = "trackerwifi"

	const val MAX_RUNS_PER_ENTRY = 64
	const val MAX_MANIFESTS = 256
	const val MAX_MANIFEST_SOURCES = 4_096
	const val MAX_COMPLETENESS_ROWS = 256
	const val MAX_AUTHORIZATION_ROWS = 4_096
	const val MAX_DEMANDS = 4_096
	const val MAX_TERMINAL_FAILURES = 256
	const val MAX_OBSERVATIONS_PER_RUN = 4_096
	const val MAX_OBSERVATIONS_PER_ENTRY = 4_096
	const val MAX_TEXT_LENGTH = 128
	const val MAX_LOCAL_IDENTITY_LENGTH = 4_096
}

enum class PortableWifiIdentityKind { LOGICAL_ENTRY, PHYSICAL_RUN, OBSERVATION }

@JvmInline
value class PortableWifiOpaqueIdentity(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}

	companion object {
		fun derive(kind: PortableWifiIdentityKind, localIdentity: String): PortableWifiOpaqueIdentity {
			require(localIdentity.isNotBlank())
			require(localIdentity.length <= WifiCapturedPortableFormatV1.MAX_LOCAL_IDENTITY_LENGTH)
			return PortableWifiOpaqueIdentity(
				PortableWifiIntegrity.digest(
					"tracker-portable-wifi-identity-v1",
					listOf(kind.name, localIdentity),
				),
			)
		}
	}
}

@JvmInline
value class PortableWifiDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}
}

@JvmInline
value class PortableWifiDeletionScopeDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value))
	}
}

enum class PortableWifiSessionMode { MANUAL, AUTOMATIC }
enum class PortableWifiCaptureCoverage { WHOLE_RUN, PARTIAL_RUN, NOT_CAPTURED }
enum class PortableWifiRunAvailability { RETAINED, NO_RETAINED_OBSERVATION, NOT_CAPTURED }
enum class PortableWifiAcquisitionCompleteness { COMPLETE, PARTIAL, UNKNOWN }
enum class PortableWifiResultCompleteness { COMPLETE, PARTIAL }
enum class PortableWifiAvailability { AVAILABLE }

/** One qualified provider delivery without SSID, BSSID, provider, or local database identity. */
@Suppress("LongParameterList")
data class PortableCapturedWifiObservationV1(
	val identity: PortableWifiOpaqueIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
	val aggregateOwnerIdentity: PortableWifiOpaqueIdentity?,
	val aggregateOwnerSemanticRevision: Long?,
	val contentChecksum: PortableWifiDigest,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val storedZoneId: String,
	val availability: PortableWifiAvailability,
	val resultCompleteness: PortableWifiResultCompleteness,
	val submittedResultCount: Int,
	val acceptedResultCount: Int,
	val staleResultCount: Int,
	val clockUnverifiableResultCount: Int,
	val malformedResultCount: Int,
	val observationCount: Int,
	val twoPointFourGhzCount: Int,
	val fiveGhzCount: Int,
	val sixGhzCount: Int,
	val otherBandCount: Int,
	val strongestSignalDbm: Int,
	val weakestSignalDbm: Int,
	val meanSignalDbm: Double,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
) {
	init {
		require(semanticRevision > 0L)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require((aggregateOwnerIdentity == null) == (aggregateOwnerSemanticRevision == null))
		require(aggregateOwnerSemanticRevision?.let { it > 0L } != false)
		require(coverageStartTimeMs >= 0L && observedTimeMs >= coverageStartTimeMs)
		require(latestPossibleTimeMs >= observedTimeMs && wallTimeUncertaintyMs >= 0L)
		require(storedZoneId.isNotBlank() && storedZoneId.length <= WifiCapturedPortableFormatV1.MAX_TEXT_LENGTH)
		requireValidZone(storedZoneId)
		val rejected = sumExact(staleResultCount, clockUnverifiableResultCount, malformedResultCount)
		require(submittedResultCount > 0 && acceptedResultCount > 0)
		require(sumExact(acceptedResultCount, rejected) == submittedResultCount)
		require((resultCompleteness == PortableWifiResultCompleteness.COMPLETE) == (rejected == 0))
		require(observationCount == acceptedResultCount)
		require(listOf(twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount).all { it >= 0 })
		require(sumExact(twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount) == observationCount)
		require(strongestSignalDbm >= weakestSignalDbm)
		require(meanSignalDbm.isFinite() && meanSignalDbm in weakestSignalDbm.toDouble()..strongestSignalDbm.toDouble())
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence?.let { it in 0f..1f } != false)
		require(PortableWifiIntegrity.observationChecksum(this) == contentChecksum)
	}
}

data class PortableCapturedWifiRunV1(
	val identity: PortableWifiOpaqueIdentity,
	val deletionScopeDigest: PortableWifiDeletionScopeDigest,
	val contentChecksum: PortableWifiDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val storedZoneIds: List<String>,
	val captureCoverage: PortableWifiCaptureCoverage,
	val availability: PortableWifiRunAvailability,
	val acquisitionCompleteness: PortableWifiAcquisitionCompleteness,
	val hasUnresolvedProviderRange: Boolean,
	val retentionLoss: Boolean,
	val observations: List<PortableCapturedWifiObservationV1>,
) {
	init {
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(storedZoneIds.isNotEmpty() && storedZoneIds == storedZoneIds.distinct().sorted())
		require(storedZoneIds.all { zone ->
			zone.isNotBlank() && zone.length <= WifiCapturedPortableFormatV1.MAX_TEXT_LENGTH && hasValidZone(zone)
		})
		require(observations.size <= WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_RUN)
		require(observations == observations.sortedWith(PORTABLE_WIFI_OBSERVATION_ORDER))
		require(observations.map { it.identity }.distinct().size == observations.size)
		when (availability) {
			PortableWifiRunAvailability.RETAINED -> require(
				captureCoverage != PortableWifiCaptureCoverage.NOT_CAPTURED && observations.isNotEmpty(),
			)
			PortableWifiRunAvailability.NO_RETAINED_OBSERVATION -> require(
				captureCoverage != PortableWifiCaptureCoverage.NOT_CAPTURED && observations.isEmpty(),
			)
			PortableWifiRunAvailability.NOT_CAPTURED -> require(
				captureCoverage == PortableWifiCaptureCoverage.NOT_CAPTURED && observations.isEmpty() &&
					acquisitionCompleteness == PortableWifiAcquisitionCompleteness.UNKNOWN &&
					!hasUnresolvedProviderRange && !retentionLoss,
			)
		}
		require(!hasUnresolvedProviderRange || acquisitionCompleteness != PortableWifiAcquisitionCompleteness.COMPLETE)
		require(PortableWifiIntegrity.runChecksum(this) == contentChecksum)
	}
}

data class PortableCapturedWifiEntryV1(
	val format: String = WifiCapturedPortableFormatV1.FORMAT,
	val schemaVersion: Int = WifiCapturedPortableFormatV1.SCHEMA_VERSION,
	val identity: PortableWifiOpaqueIdentity,
	val contentChecksum: PortableWifiDigest,
	val sessionMode: PortableWifiSessionMode,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val runs: List<PortableCapturedWifiRunV1>,
) {
	init {
		require(format == WifiCapturedPortableFormatV1.FORMAT)
		require(schemaVersion == WifiCapturedPortableFormatV1.SCHEMA_VERSION)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(runs.isNotEmpty() && runs.size <= WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(runs == runs.sortedWith(PORTABLE_WIFI_RUN_ORDER))
		require(runs.map { it.identity }.distinct().size == runs.size)
		require(runs.map { it.deletionScopeDigest }.distinct().size == runs.size)
		require(runs.sumOf { it.observations.size } <= WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY)
		require(startTimeMs == runs.minOf { it.startTimeMs } && endTimeMs == runs.maxOf { it.endTimeMs })
		require(runs.any { it.captureCoverage != PortableWifiCaptureCoverage.NOT_CAPTURED })
		require(PortableWifiIntegrity.entryChecksum(this) == contentChecksum)
	}
}

val PORTABLE_WIFI_OBSERVATION_ORDER: Comparator<PortableCapturedWifiObservationV1> =
	compareBy<PortableCapturedWifiObservationV1>(
		PortableCapturedWifiObservationV1::coverageStartTimeMs,
		PortableCapturedWifiObservationV1::observedTimeMs,
		{ it.identity.value },
	)

val PORTABLE_WIFI_RUN_ORDER: Comparator<PortableCapturedWifiRunV1> =
	compareBy<PortableCapturedWifiRunV1>(PortableCapturedWifiRunV1::startTimeMs) { it.identity.value }

/** Canonical binary hashing kept next to the sole Wi-Fi v1 format. */
@Suppress("TooManyFunctions")
object PortableWifiIntegrity {
	internal fun digest(namespace: String, parts: List<Any?>): String = canonicalDigest(namespace) {
		writeInt(parts.size)
		parts.forEach { part -> writeNullableString(part?.toString()) }
	}

	fun observationChecksum(value: PortableCapturedWifiObservationV1): PortableWifiDigest =
		observationChecksum(value.toCanonical())

	@Suppress("LongParameterList")
	fun createObservation(
		identity: PortableWifiOpaqueIdentity,
		semanticRevision: Long,
		supersedesSemanticRevision: Long?,
		aggregateOwnerIdentity: PortableWifiOpaqueIdentity?,
		aggregateOwnerSemanticRevision: Long?,
		coverageStartTimeMs: Long,
		observedTimeMs: Long,
		latestPossibleTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		storedZoneId: String,
		availability: PortableWifiAvailability,
		resultCompleteness: PortableWifiResultCompleteness,
		submittedResultCount: Int,
		acceptedResultCount: Int,
		staleResultCount: Int,
		clockUnverifiableResultCount: Int,
		malformedResultCount: Int,
		observationCount: Int,
		twoPointFourGhzCount: Int,
		fiveGhzCount: Int,
		sixGhzCount: Int,
		otherBandCount: Int,
		strongestSignalDbm: Int,
		weakestSignalDbm: Int,
		meanSignalDbm: Double,
		sourceQualityFlags: Long,
		sourceQualityConfidence: Float?,
	): PortableCapturedWifiObservationV1 {
		val content = PortableWifiObservationCanonical(
			identity, semanticRevision, supersedesSemanticRevision, aggregateOwnerIdentity,
			aggregateOwnerSemanticRevision, coverageStartTimeMs, observedTimeMs,
			latestPossibleTimeMs, wallTimeUncertaintyMs, storedZoneId, availability,
			resultCompleteness, submittedResultCount, acceptedResultCount, staleResultCount,
			clockUnverifiableResultCount, malformedResultCount, observationCount,
			twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount,
			strongestSignalDbm, weakestSignalDbm, meanSignalDbm, sourceQualityFlags,
			sourceQualityConfidence,
		)
		return PortableCapturedWifiObservationV1(
			identity, semanticRevision, supersedesSemanticRevision, aggregateOwnerIdentity,
			aggregateOwnerSemanticRevision, observationChecksum(content), coverageStartTimeMs,
			observedTimeMs, latestPossibleTimeMs, wallTimeUncertaintyMs, storedZoneId,
			availability, resultCompleteness, submittedResultCount, acceptedResultCount,
			staleResultCount, clockUnverifiableResultCount, malformedResultCount,
			observationCount, twoPointFourGhzCount, fiveGhzCount, sixGhzCount,
			otherBandCount, strongestSignalDbm, weakestSignalDbm, meanSignalDbm,
			sourceQualityFlags, sourceQualityConfidence,
		)
	}

	private fun observationChecksum(value: PortableWifiObservationCanonical): PortableWifiDigest =
		PortableWifiDigest(canonicalDigest("tracker-portable-wifi-observation-v1") {
			writeNullableString(value.identity.value)
			writeLong(value.semanticRevision)
			writeNullableLong(value.supersedesSemanticRevision)
			writeNullableString(value.aggregateOwnerIdentity?.value)
			writeNullableLong(value.aggregateOwnerSemanticRevision)
			writeLong(value.coverageStartTimeMs)
			writeLong(value.observedTimeMs)
			writeLong(value.latestPossibleTimeMs)
			writeLong(value.wallTimeUncertaintyMs)
			writeNullableString(value.storedZoneId)
			writeNullableString(value.availability.name)
			writeNullableString(value.resultCompleteness.name)
			listOf(
				value.submittedResultCount, value.acceptedResultCount, value.staleResultCount,
				value.clockUnverifiableResultCount, value.malformedResultCount, value.observationCount,
				value.twoPointFourGhzCount, value.fiveGhzCount, value.sixGhzCount, value.otherBandCount,
				value.strongestSignalDbm, value.weakestSignalDbm,
			).forEach { count -> writeInt(count) }
			writeDouble(value.meanSignalDbm)
			writeLong(value.sourceQualityFlags)
			writeNullableFloat(value.sourceQualityConfidence)
		})

	fun runChecksum(value: PortableCapturedWifiRunV1): PortableWifiDigest = runChecksum(value.toCanonical())

	@Suppress("LongParameterList")
	fun createRun(
		identity: PortableWifiOpaqueIdentity,
		deletionScopeDigest: PortableWifiDeletionScopeDigest,
		startTimeMs: Long,
		endTimeMs: Long,
		storedZoneIds: List<String>,
		captureCoverage: PortableWifiCaptureCoverage,
		availability: PortableWifiRunAvailability,
		acquisitionCompleteness: PortableWifiAcquisitionCompleteness,
		hasUnresolvedProviderRange: Boolean,
		retentionLoss: Boolean,
		observations: List<PortableCapturedWifiObservationV1>,
	): PortableCapturedWifiRunV1 {
		val content = PortableWifiRunCanonical(
			identity, deletionScopeDigest, startTimeMs, endTimeMs, storedZoneIds.toList(),
			captureCoverage, availability, acquisitionCompleteness, hasUnresolvedProviderRange,
			retentionLoss, observations.toList(),
		)
		return PortableCapturedWifiRunV1(
			identity, deletionScopeDigest, runChecksum(content), startTimeMs, endTimeMs,
			content.storedZoneIds, captureCoverage, availability, acquisitionCompleteness,
			hasUnresolvedProviderRange, retentionLoss, content.observations,
		)
	}

	private fun runChecksum(value: PortableWifiRunCanonical): PortableWifiDigest =
		PortableWifiDigest(canonicalDigest("tracker-portable-wifi-run-v1") {
			writeNullableString(value.identity.value)
			writeNullableString(value.deletionScopeDigest.value)
			writeLong(value.startTimeMs)
			writeLong(value.endTimeMs)
			writeInt(value.storedZoneIds.size)
			value.storedZoneIds.forEach(::writeNullableString)
			writeNullableString(value.captureCoverage.name)
			writeNullableString(value.availability.name)
			writeNullableString(value.acquisitionCompleteness.name)
			writeBoolean(value.hasUnresolvedProviderRange)
			writeBoolean(value.retentionLoss)
			writeInt(value.observations.size)
			value.observations.forEach { observation ->
				writeNullableString(observation.identity.value)
				writeNullableString(observation.contentChecksum.value)
			}
		})

	fun entryChecksum(value: PortableCapturedWifiEntryV1): PortableWifiDigest =
		entryChecksum(value.toCanonical())

	fun createEntry(
		identity: PortableWifiOpaqueIdentity,
		sessionMode: PortableWifiSessionMode,
		startTimeMs: Long,
		endTimeMs: Long,
		runs: List<PortableCapturedWifiRunV1>,
	): PortableCapturedWifiEntryV1 {
		val content = PortableWifiEntryCanonical(identity, sessionMode, startTimeMs, endTimeMs, runs.toList())
		return PortableCapturedWifiEntryV1(
			identity = identity,
			contentChecksum = entryChecksum(content),
			sessionMode = sessionMode,
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
			runs = content.runs,
		)
	}

	private fun entryChecksum(value: PortableWifiEntryCanonical): PortableWifiDigest =
		PortableWifiDigest(canonicalDigest("tracker-portable-wifi-entry-v1") {
			writeNullableString(WifiCapturedPortableFormatV1.FORMAT)
			writeInt(WifiCapturedPortableFormatV1.SCHEMA_VERSION)
			writeNullableString(value.identity.value)
			writeNullableString(value.sessionMode.name)
			writeLong(value.startTimeMs)
			writeLong(value.endTimeMs)
			writeInt(value.runs.size)
			value.runs.forEach { run ->
				writeNullableString(run.identity.value)
				writeNullableString(run.contentChecksum.value)
			}
		})
}

@Suppress("LongParameterList")
private data class PortableWifiObservationCanonical(
	val identity: PortableWifiOpaqueIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
	val aggregateOwnerIdentity: PortableWifiOpaqueIdentity?,
	val aggregateOwnerSemanticRevision: Long?,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val storedZoneId: String,
	val availability: PortableWifiAvailability,
	val resultCompleteness: PortableWifiResultCompleteness,
	val submittedResultCount: Int,
	val acceptedResultCount: Int,
	val staleResultCount: Int,
	val clockUnverifiableResultCount: Int,
	val malformedResultCount: Int,
	val observationCount: Int,
	val twoPointFourGhzCount: Int,
	val fiveGhzCount: Int,
	val sixGhzCount: Int,
	val otherBandCount: Int,
	val strongestSignalDbm: Int,
	val weakestSignalDbm: Int,
	val meanSignalDbm: Double,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
)

private fun PortableCapturedWifiObservationV1.toCanonical() = PortableWifiObservationCanonical(
	identity, semanticRevision, supersedesSemanticRevision, aggregateOwnerIdentity,
	aggregateOwnerSemanticRevision, coverageStartTimeMs, observedTimeMs, latestPossibleTimeMs,
	wallTimeUncertaintyMs, storedZoneId, availability, resultCompleteness, submittedResultCount,
	acceptedResultCount, staleResultCount, clockUnverifiableResultCount, malformedResultCount,
	observationCount, twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount,
	strongestSignalDbm, weakestSignalDbm, meanSignalDbm, sourceQualityFlags, sourceQualityConfidence,
)

private data class PortableWifiRunCanonical(
	val identity: PortableWifiOpaqueIdentity,
	val deletionScopeDigest: PortableWifiDeletionScopeDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val storedZoneIds: List<String>,
	val captureCoverage: PortableWifiCaptureCoverage,
	val availability: PortableWifiRunAvailability,
	val acquisitionCompleteness: PortableWifiAcquisitionCompleteness,
	val hasUnresolvedProviderRange: Boolean,
	val retentionLoss: Boolean,
	val observations: List<PortableCapturedWifiObservationV1>,
)

private fun PortableCapturedWifiRunV1.toCanonical() = PortableWifiRunCanonical(
	identity, deletionScopeDigest, startTimeMs, endTimeMs, storedZoneIds, captureCoverage,
	availability, acquisitionCompleteness, hasUnresolvedProviderRange, retentionLoss, observations,
)

private data class PortableWifiEntryCanonical(
	val identity: PortableWifiOpaqueIdentity,
	val sessionMode: PortableWifiSessionMode,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val runs: List<PortableCapturedWifiRunV1>,
)

private fun PortableCapturedWifiEntryV1.toCanonical() = PortableWifiEntryCanonical(
	identity, sessionMode, startTimeMs, endTimeMs, runs,
)

private fun canonicalDigest(namespace: String, body: DataOutputStream.() -> Unit): String {
	val bytes = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeUTF(namespace)
			output.body()
		}
		buffer.toByteArray()
	}
	return MessageDigest.getInstance("SHA-256").digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeNullableString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeNullableFloat(value: Float?) {
	writeBoolean(value != null)
	if (value != null) writeFloat(value)
}

private fun sumExact(vararg values: Int): Int = values.fold(0, Math::addExact)

private fun requireValidZone(zoneId: String) {
	if (!hasValidZone(zoneId)) throw IllegalArgumentException("Invalid stored Wi-Fi zone")
}

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private val SHA_256_HEX = Regex("[0-9a-f]{64}")
