package com.adsamcik.tracker.stats.api.repository

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * Wire identifiers and finite fact, gap and receipt limits for the v1 all-day Wi-Fi archive.
 */
object AmbientWifiPortableFormatV1 {
	const val FORMAT = "tracker-portable-ambient-wifi"
	const val SCHEMA_VERSION = 1
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.ambient-wifi+json"
	const val FILE_EXTENSION = "trackerambientwifi"
	const val MAX_FACTS = 4_096
	const val MAX_GAPS = 4_096
	const val MAX_RECEIPT_FIELD_LENGTH = 4_096
}

/**
 * One all-day Wi-Fi summary revision with original identity, UTC millisecond uncertainty bounds
 * and its saved local calendar day.
 */
@Suppress("LongParameterList")
@Serializable
data class PortableAmbientWifiFactV1(
	val identity: String,
	val contentChecksum: String,
	val effectChecksum: String,
	val origin: AmbientWifiOrigin,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val structuralEpochDay: Long,
	val storedZoneId: String,
	val coverage: AmbientWifiCoverage,
	val observationCount: Int,
	val twoPointFourGhzCount: Int,
	val fiveGhzCount: Int,
	val sixGhzCount: Int,
	val otherBandCount: Int,
	val strongestSignalDbm: Int,
	val weakestSignalDbm: Int,
	val meanSignalDbm: Double,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
) {
	init {
		require(
			DIGEST.matches(identity) &&
				DIGEST.matches(contentChecksum) &&
				DIGEST.matches(effectChecksum),
		)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(observedTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		require(
			AmbientWifiPortableIntegrity.factEffectChecksum(
				origin,
				coverageStartTimeMs,
				observedTimeMs,
				latestPossibleTimeMs,
				structuralEpochDay,
				storedZoneId,
				coverage,
				observationCount,
				twoPointFourGhzCount,
				fiveGhzCount,
				sixGhzCount,
				otherBandCount,
				strongestSignalDbm,
				weakestSignalDbm,
				meanSignalDbm,
				semanticRevision,
				supersedesSemanticRevision,
			) == effectChecksum,
		)
		require(AmbientWifiPortableIntegrity.factChecksum(this) == contentChecksum)
		AmbientWifiFact(
			identity,
			origin,
			coverageStartTimeMs,
			observedTimeMs,
			latestPossibleTimeMs,
			structuralEpochDay,
			storedZoneId,
			coverage,
			observationCount,
			twoPointFourGhzCount,
			fiveGhzCount,
			sixGhzCount,
			otherBandCount,
			strongestSignalDbm,
			weakestSignalDbm,
			meanSignalDbm,
			semanticRevision,
			supersedesSemanticRevision,
		)
	}
}

/**
 * A recorded all-day Wi-Fi gap with UTC millisecond bounds, saved zone/day, origin and reason; no
 * observations are invented.
 */
@Serializable
data class PortableAmbientWifiGapV1(
	val identity: String,
	val contentChecksum: String,
	val effectChecksum: String,
	val origin: AmbientWifiOrigin,
	val structuralEpochDay: Long,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val storedZoneId: String,
	val reason: String,
) {
	init {
		require(
			DIGEST.matches(identity) &&
				DIGEST.matches(contentChecksum) &&
				DIGEST.matches(effectChecksum),
		)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(startTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		AmbientWifiGap(
			identity,
			origin,
			structuralEpochDay,
			startTimeMs,
			endTimeMs,
			storedZoneId,
			reason,
		)
		require(AmbientWifiPortableIntegrity.gapEffectChecksum(this) == effectChecksum)
		require(AmbientWifiPortableIntegrity.gapChecksum(this) == contentChecksum)
	}
}

/**
 * Nonempty bounded Wi-Fi archive with unique gaps and complete sequential fact correction
 * lineages, checked against canonical content.
 */
@Serializable
data class PortableAmbientWifiArchiveV1(
	@Required
	val format: String = AmbientWifiPortableFormatV1.FORMAT,
	@Required
	val schemaVersion: Int = AmbientWifiPortableFormatV1.SCHEMA_VERSION,
	val archiveId: String,
	val contentChecksum: String,
	val facts: List<PortableAmbientWifiFactV1>,
	val gaps: List<PortableAmbientWifiGapV1>,
) {
	init {
		require(format == AmbientWifiPortableFormatV1.FORMAT)
		require(schemaVersion == AmbientWifiPortableFormatV1.SCHEMA_VERSION)
		require(DIGEST.matches(archiveId) && DIGEST.matches(contentChecksum))
		require((facts.isNotEmpty() || gaps.isNotEmpty()) &&
			facts.size <= AmbientWifiPortableFormatV1.MAX_FACTS)
		require(gaps.size <= AmbientWifiPortableFormatV1.MAX_GAPS)
		require(facts.map { it.identity to it.semanticRevision }.distinct().size == facts.size)
		require(facts.hasCompleteCorrectionLineages())
		require(gaps.map { it.identity }.distinct().size == gaps.size)
		require(
			facts.mapTo(mutableSetOf()) { it.identity }
				.intersect(gaps.mapTo(mutableSetOf()) { it.identity }).isEmpty(),
		)
		require(AmbientWifiPortableIntegrity.archiveChecksum(archiveId, facts, gaps) ==
			contentChecksum)
	}
}

/**
 * Canonical SHA-256 identities and content checksums for all-day Wi-Fi files; these do not grant
 * live recording authority.
 */
// Keep this source format's checksum overloads and canonical encoding in one owner.
@Suppress("TooManyFunctions")
object AmbientWifiPortableIntegrity {
	/**
	 * Copies the original Wi-Fi summary revision and computes its canonical effect/content checksums
	 * without changing measurement times.
	 */
	fun createFact(fact: AmbientWifiFact): PortableAmbientWifiFactV1 =
		factEffectChecksum(
			fact.origin,
			fact.coverageStartTimeMs,
			fact.observedTimeMs,
			fact.latestPossibleTimeMs,
			fact.structuralEpochDay,
			fact.storedZoneId,
			fact.coverage,
			fact.observationCount,
			fact.twoPointFourGhzCount,
			fact.fiveGhzCount,
			fact.sixGhzCount,
			fact.otherBandCount,
			fact.strongestSignalDbm,
			fact.weakestSignalDbm,
			fact.meanSignalDbm,
			fact.semanticRevision,
			fact.supersedesSemanticRevision,
		).let { effectChecksum -> PortableAmbientWifiFactV1(
			identity = fact.identity,
			contentChecksum = factChecksum(fact.identity, effectChecksum),
			effectChecksum = effectChecksum,
			origin = fact.origin,
			coverageStartTimeMs = fact.coverageStartTimeMs,
			observedTimeMs = fact.observedTimeMs,
			latestPossibleTimeMs = fact.latestPossibleTimeMs,
			structuralEpochDay = fact.structuralEpochDay,
			storedZoneId = fact.storedZoneId,
			coverage = fact.coverage,
			observationCount = fact.observationCount,
			twoPointFourGhzCount = fact.twoPointFourGhzCount,
			fiveGhzCount = fact.fiveGhzCount,
			sixGhzCount = fact.sixGhzCount,
			otherBandCount = fact.otherBandCount,
			strongestSignalDbm = fact.strongestSignalDbm,
			weakestSignalDbm = fact.weakestSignalDbm,
			meanSignalDbm = fact.meanSignalDbm,
			semanticRevision = fact.semanticRevision,
			supersedesSemanticRevision = fact.supersedesSemanticRevision,
		) }

	/**
	 * Sorts facts by identity/revision and gaps by identity, then constructs a checksummed archive
	 * with complete correction lineages.
	 */
	fun createArchive(
		archiveId: String,
		facts: List<PortableAmbientWifiFactV1>,
		gaps: List<PortableAmbientWifiGapV1>,
	): PortableAmbientWifiArchiveV1 {
		val sortedFacts = facts.sortedWith(compareBy(
			PortableAmbientWifiFactV1::identity,
			PortableAmbientWifiFactV1::semanticRevision,
		))
		val sortedGaps = gaps.sortedBy { it.identity }
		return PortableAmbientWifiArchiveV1(
			archiveId = archiveId,
			contentChecksum = archiveChecksum(archiveId, sortedFacts, sortedGaps),
			facts = sortedFacts,
			gaps = sortedGaps,
		)
	}

	/**
	 * Copies the recorded Wi-Fi gap and computes checksums while retaining its original identity, UTC
	 * millisecond bounds and reason.
	 */
	fun createGap(gap: AmbientWifiGap): PortableAmbientWifiGapV1 =
		gapEffectChecksum(
			gap.origin,
			gap.structuralEpochDay,
			gap.startTimeMs,
			gap.endTimeMs,
			gap.storedZoneId,
			gap.reason,
		).let { effectChecksum -> PortableAmbientWifiGapV1(
			identity = gap.identity,
			contentChecksum = gapChecksum(gap.identity, effectChecksum),
			effectChecksum = effectChecksum,
			origin = gap.origin,
			structuralEpochDay = gap.structuralEpochDay,
			startTimeMs = gap.startTimeMs,
			endTimeMs = gap.endTimeMs,
			storedZoneId = gap.storedZoneId,
			reason = gap.reason,
		) }

	/**
	 * Computes the gap digest from its original identity and declared effect checksum.
	 */
	fun gapChecksum(value: PortableAmbientWifiGapV1): String =
		gapChecksum(value.identity, value.effectChecksum)

	/**
	 * Recomputes the gap payload digest from origin, local day/zone, UTC millisecond bounds and
	 * reason.
	 */
	fun gapEffectChecksum(value: PortableAmbientWifiGapV1): String = gapEffectChecksum(
		value.origin,
		value.structuralEpochDay,
		value.startTimeMs,
		value.endTimeMs,
		value.storedZoneId,
		value.reason,
	)

	private fun gapEffectChecksum(
		origin: AmbientWifiOrigin,
		structuralEpochDay: Long,
		startTimeMs: Long,
		endTimeMs: Long,
		storedZoneId: String,
		reason: String,
	): String = digest(
		"ambient-wifi-portable-gap-effect-v1",
		origin,
		structuralEpochDay,
		startTimeMs,
		endTimeMs,
		storedZoneId,
		reason,
	)

	private fun gapChecksum(identity: String, effectChecksum: String): String =
		digest("ambient-wifi-portable-gap-v1", identity, effectChecksum)

	/**
	 * Computes the summary digest from its original identity and declared effect checksum.
	 */
	fun factChecksum(value: PortableAmbientWifiFactV1): String =
		factChecksum(value.identity, value.effectChecksum)

	private fun factChecksum(identity: String, effectChecksum: String): String =
		digest("ambient-wifi-portable-fact-v1", identity, effectChecksum)

	/**
	 * Recomputes the summary payload digest including measurement bounds, counts, quality and
	 * correction revision.
	 */
	@Suppress("LongParameterList")
	fun factEffectChecksum(
		origin: AmbientWifiOrigin,
		coverageStartTimeMs: Long,
		observedTimeMs: Long,
		latestPossibleTimeMs: Long,
		structuralEpochDay: Long,
		storedZoneId: String,
		coverage: AmbientWifiCoverage,
		observationCount: Int,
		twoPointFourGhzCount: Int,
		fiveGhzCount: Int,
		sixGhzCount: Int,
		otherBandCount: Int,
		strongestSignalDbm: Int,
		weakestSignalDbm: Int,
		meanSignalDbm: Double,
		semanticRevision: Long,
		supersedesSemanticRevision: Long?,
	): String = digest(
		"ambient-wifi-portable-fact-effect-v1",
		origin,
		coverageStartTimeMs,
		observedTimeMs,
		latestPossibleTimeMs,
		structuralEpochDay,
		storedZoneId,
		coverage,
		observationCount,
		twoPointFourGhzCount,
		fiveGhzCount,
		sixGhzCount,
		otherBandCount,
		strongestSignalDbm,
		weakestSignalDbm,
		meanSignalDbm,
		semanticRevision,
		supersedesSemanticRevision,
	)

	/**
	 * Hashes the archive identity and deterministically ordered child identities, revisions and
	 * checksums.
	 */
	fun archiveChecksum(
		archiveId: String,
		facts: List<PortableAmbientWifiFactV1>,
		gaps: List<PortableAmbientWifiGapV1>,
	): String = digest(
		"ambient-wifi-portable-archive-v1",
		archiveId,
		facts.sortedWith(compareBy(
			PortableAmbientWifiFactV1::identity,
			PortableAmbientWifiFactV1::semanticRevision,
		)).joinToString {
			"${it.identity}:${it.semanticRevision}:${it.contentChecksum}"
		},
		gaps.sortedBy { it.identity }.joinToString {
			"${it.identity}:${it.contentChecksum}"
		},
	)

	/**
	 * Hashes a nonblank namespace and local identity into a portable identity without exposing the
	 * local key.
	 */
	fun opaqueIdentity(namespace: String, localIdentity: String): String {
		require(namespace.isNotBlank() && localIdentity.isNotBlank())
		return digest("ambient-wifi-portable-identity-v1", namespace, localIdentity)
	}

	private fun digest(namespace: String, vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) {
				"-1:"
			} else {
				"${text.length}:$text"
			}
		}
		return MessageDigest.getInstance("SHA-256")
			.digest("$namespace\u001f$canonical".toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}
}

/**
 * Half-open UTC millisecond selection and chosen Wi-Fi origins, or exact imported archive
 * membership for a session bundle.
 */
data class ExportPortableAmbientWifiRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val origins: Set<AmbientWifiOrigin>,
	/** Exact imported file membership for a saved bundle annotation; null keeps the date-range path. */
	val selectedArchiveIds: Set<String>? = null,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(origins.isNotEmpty())
	}
}

/**
 * Receives one complete all-day Wi-Fi archive after the bounded database snapshot closes.
 */
fun interface PortableAmbientWifiSink {
	/**
	 * Accepts one complete all-day Wi-Fi archive after its bounded database snapshot has closed.
	 */
	suspend fun emit(archive: PortableAmbientWifiArchiveV1)
}

/**
 * Read-only all-day Wi-Fi archive preparation without acquiring a sensor or recording permission.
 */
interface ExportPortableAmbientWifi {
	/**
	 * Prepares a bounded saved Wi-Fi archive, emits it only after the snapshot transaction closes,
	 * and propagates cancellation.
	 */
	suspend fun export(
		request: ExportPortableAmbientWifiRequest,
		sink: PortableAmbientWifiSink,
	): ExportPortableAmbientWifiResult
}

/**
 * Bounded file-job and entry provenance with a nonnegative UTC millisecond receive time.
 */
data class PortableAmbientWifiImportReceipt(
	val jobId: String,
	val entryKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, entryKey, sourceName).forEach {
			require(it.isNotBlank() &&
				it.length <= AmbientWifiPortableFormatV1.MAX_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/**
 * Decoded Wi-Fi archive and file provenance tied to the original expected collected-data epoch.
 */
data class ImportPortableAmbientWifiRequest(
	val archive: PortableAmbientWifiArchiveV1,
	val receipt: PortableAmbientWifiImportReceipt,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/**
 * Admits imported all-day Wi-Fi data without creating native collection authority.
 */
interface ImportPortableAmbientWifi {
	/**
	 * Admits the complete decoded Wi-Fi archive and file provenance under its original data epoch
	 * without creating native recording permission.
	 */
	suspend fun importArchive(
		request: ImportPortableAmbientWifiRequest,
	): ImportPortableAmbientWifiResult
}

/**
 * Complete Wi-Fi archive counts, empty selection, exceeded dependencies or unavailable storage.
 */
sealed interface ExportPortableAmbientWifiResult {
	/**
	 * A complete archive was emitted; reports the represented fact and recorded-gap counts.
	 */
	data class Exported(val factCount: Int, val gapCount: Int) :
		ExportPortableAmbientWifiResult
	/**
	 * No saved facts or gaps match the requested export selection.
	 */
	data object NoData : ExportPortableAmbientWifiResult
	/**
	 * Preparing the complete transfer would exceed its bounded source dependencies.
	 */
	data object DependencyOverflow : ExportPortableAmbientWifiResult
	/**
	 * Storage cannot provide the required snapshot or commit; this is not an empty-data result.
	 */
	data object StorageUnavailable : ExportPortableAmbientWifiResult
}

/**
 * All-day Wi-Fi archive admission outcome distinguishing duplicates, conflicts, privacy boundaries
 * and storage failure.
 */
sealed interface ImportPortableAmbientWifiResult {
	/**
	 * The archive was admitted; reports its saved facts and recorded gaps.
	 */
	data class Applied(val factCount: Int, val gapCount: Int) : ImportPortableAmbientWifiResult
	/**
	 * Matching archive content and file provenance are already saved.
	 */
	data object Duplicate : ImportPortableAmbientWifiResult
	/**
	 * The same file-job receipt already refers to different archive content or provenance.
	 */
	data object ReceiptConflict : ImportPortableAmbientWifiResult
	/**
	 * The declared receive time precedes a represented observation or gap end.
	 */
	data object InvalidReceipt : ImportPortableAmbientWifiResult
	/**
	 * A saved deletion or replay fence prevents restoring this archive or its removed readings.
	 */
	data object DeletedArchive : ImportPortableAmbientWifiResult
	/**
	 * The archive includes measurement bounds older than the published retention floor.
	 */
	data object RetentionBoundary : ImportPortableAmbientWifiResult
	/**
	 * No current portable-import retention approval can be verified for this data epoch.

	 */
	data object RetentionAuthorityUnavailable : ImportPortableAmbientWifiResult
	/**
	 * The import job's original collected-data epoch no longer matches the current database.

	 */
	data object CollectedDataEpochChanged : ImportPortableAmbientWifiResult
	/**
	 * Preparing the complete transfer would exceed its bounded source dependencies.
	 */
	data object DependencyOverflow : ImportPortableAmbientWifiResult
	/**
	 * Storage cannot provide the required snapshot or commit; this is not an empty-data result.
	 */
	data object StorageUnavailable : ImportPortableAmbientWifiResult
}

private val DIGEST = Regex("[0-9a-f]{64}")

private fun List<PortableAmbientWifiFactV1>.hasCompleteCorrectionLineages(): Boolean =
	groupBy(PortableAmbientWifiFactV1::identity).values.all { lineage ->
		val ordered = lineage.sortedBy(PortableAmbientWifiFactV1::semanticRevision)
		ordered.withIndex().all { (index, fact) ->
			val expectedRevision = index + 1L
			fact.semanticRevision == expectedRevision &&
				fact.supersedesSemanticRevision == expectedRevision.takeIf { it > 1L }?.minus(1L)
		}
	}
