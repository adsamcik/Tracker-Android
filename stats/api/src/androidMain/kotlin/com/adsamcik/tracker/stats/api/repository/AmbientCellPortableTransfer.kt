package com.adsamcik.tracker.stats.api.repository

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * Wire identifiers and finite fact, gap and receipt limits for the v1 all-day Cell archive.
 */
object AmbientCellPortableFormatV1 {
	const val FORMAT = "tracker-portable-ambient-cell"
	const val SCHEMA_VERSION = 1
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.ambient-cell+json"
	const val FILE_EXTENSION = "trackerambientcell"
	const val MAX_FACTS = 4_096
	const val MAX_GAPS = 4_096
	const val MAX_RECEIPT_FIELD_LENGTH = 4_096
}

/**
 * One all-day Cell summary revision with original identity, UTC millisecond uncertainty bounds and
 * its saved local calendar day.
 */
@Suppress("LongParameterList")
@Serializable
data class PortableAmbientCellFactV1(
	val identity: String,
	val contentChecksum: String,
	val effectChecksum: String,
	val origin: AmbientCellOrigin,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val structuralEpochDay: Long,
	val storedZoneId: String,
	val coverage: AmbientCellCoverage,
	val subscriptionCompleteness: AmbientCellSubscriptionCompleteness,
	val observationCount: Int,
	val registeredObservationCount: Int,
	val technologyMix: AmbientCellTechnologyMix,
	val qualityDistribution: AmbientCellQualityDistribution,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
) {
	init {
		require(
			CELL_DIGEST.matches(identity) &&
				CELL_DIGEST.matches(contentChecksum) &&
				CELL_DIGEST.matches(effectChecksum),
		)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(observedTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		AmbientCellFact(
			identity,
			origin,
			coverageStartTimeMs,
			observedTimeMs,
			latestPossibleTimeMs,
			structuralEpochDay,
			storedZoneId,
			coverage,
			subscriptionCompleteness,
			observationCount,
			registeredObservationCount,
			technologyMix,
			qualityDistribution,
			semanticRevision,
			supersedesSemanticRevision,
		)
		require(AmbientCellPortableIntegrity.factEffectChecksum(this) == effectChecksum)
		require(AmbientCellPortableIntegrity.factChecksum(this) == contentChecksum)
	}
}

/**
 * A recorded all-day Cell gap with UTC millisecond bounds, saved zone/day, origin and reason; no
 * observations are invented.
 */
@Serializable
data class PortableAmbientCellGapV1(
	val identity: String,
	val contentChecksum: String,
	val effectChecksum: String,
	val origin: AmbientCellOrigin,
	val structuralEpochDay: Long,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val storedZoneId: String,
	val reason: String,
) {
	init {
		require(
			CELL_DIGEST.matches(identity) &&
				CELL_DIGEST.matches(contentChecksum) &&
				CELL_DIGEST.matches(effectChecksum),
		)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(startTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		AmbientCellGap(
			identity,
			origin,
			structuralEpochDay,
			startTimeMs,
			endTimeMs,
			storedZoneId,
			reason,
		)
		require(AmbientCellPortableIntegrity.gapEffectChecksum(this) == effectChecksum)
		require(AmbientCellPortableIntegrity.gapChecksum(this) == contentChecksum)
	}
}

/**
 * Nonempty bounded Cell archive with unique gaps and complete sequential fact correction lineages,
 * checked against canonical content.
 */
@Serializable
data class PortableAmbientCellArchiveV1(
	@Required
	val format: String = AmbientCellPortableFormatV1.FORMAT,
	@Required
	val schemaVersion: Int = AmbientCellPortableFormatV1.SCHEMA_VERSION,
	val archiveId: String,
	val contentChecksum: String,
	val facts: List<PortableAmbientCellFactV1>,
	val gaps: List<PortableAmbientCellGapV1>,
) {
	init {
		require(format == AmbientCellPortableFormatV1.FORMAT)
		require(schemaVersion == AmbientCellPortableFormatV1.SCHEMA_VERSION)
		require(CELL_DIGEST.matches(archiveId) && CELL_DIGEST.matches(contentChecksum))
		require((facts.isNotEmpty() || gaps.isNotEmpty()) &&
			facts.size <= AmbientCellPortableFormatV1.MAX_FACTS)
		require(gaps.size <= AmbientCellPortableFormatV1.MAX_GAPS)
		require(facts.map { it.identity to it.semanticRevision }.distinct().size == facts.size)
		require(facts.hasCompleteCorrectionLineages())
		require(gaps.map { it.identity }.distinct().size == gaps.size)
		require(
			facts.mapTo(mutableSetOf()) { it.identity }
				.intersect(gaps.mapTo(mutableSetOf()) { it.identity }).isEmpty(),
		)
		require(AmbientCellPortableIntegrity.archiveChecksum(archiveId, facts, gaps) ==
			contentChecksum)
	}
}

/**
 * Canonical SHA-256 identities and content checksums for all-day Cell files; these do not grant
 * live recording authority.
 */
// Keep this source format's checksum overloads and canonical encoding in one owner.
@Suppress("TooManyFunctions")
object AmbientCellPortableIntegrity {
	/**
	 * Copies the original Cell summary revision and computes its canonical effect/content checksums
	 * without changing measurement times.
	 */
	fun createFact(fact: AmbientCellFact): PortableAmbientCellFactV1 =
		factEffectChecksum(
			fact.origin,
			fact.coverageStartTimeMs,
			fact.observedTimeMs,
			fact.latestPossibleTimeMs,
			fact.structuralEpochDay,
			fact.storedZoneId,
			fact.coverage,
			fact.subscriptionCompleteness,
			fact.observationCount,
			fact.registeredObservationCount,
			fact.technologyMix,
			fact.qualityDistribution,
			fact.semanticRevision,
			fact.supersedesSemanticRevision,
		).let { effectChecksum -> PortableAmbientCellFactV1(
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
			subscriptionCompleteness = fact.subscriptionCompleteness,
			observationCount = fact.observationCount,
			registeredObservationCount = fact.registeredObservationCount,
			technologyMix = fact.technologyMix,
			qualityDistribution = fact.qualityDistribution,
			semanticRevision = fact.semanticRevision,
			supersedesSemanticRevision = fact.supersedesSemanticRevision,
		) }

	/**
	 * Sorts facts by identity/revision and gaps by identity, then constructs a checksummed archive
	 * with complete correction lineages.
	 */
	fun createArchive(
		archiveId: String,
		facts: List<PortableAmbientCellFactV1>,
		gaps: List<PortableAmbientCellGapV1>,
	): PortableAmbientCellArchiveV1 {
		val sortedFacts = facts.sortedWith(compareBy(
			PortableAmbientCellFactV1::identity,
			PortableAmbientCellFactV1::semanticRevision,
		))
		val sortedGaps = gaps.sortedBy { it.identity }
		return PortableAmbientCellArchiveV1(
			archiveId = archiveId,
			contentChecksum = archiveChecksum(archiveId, sortedFacts, sortedGaps),
			facts = sortedFacts,
			gaps = sortedGaps,
		)
	}

	/**
	 * Copies the recorded Cell gap and computes checksums while retaining its original identity, UTC
	 * millisecond bounds and reason.
	 */
	fun createGap(gap: AmbientCellGap): PortableAmbientCellGapV1 =
		gapEffectChecksum(
			gap.origin,
			gap.structuralEpochDay,
			gap.startTimeMs,
			gap.endTimeMs,
			gap.storedZoneId,
			gap.reason,
		).let { effectChecksum -> PortableAmbientCellGapV1(
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
	fun gapChecksum(value: PortableAmbientCellGapV1): String =
		gapChecksum(value.identity, value.effectChecksum)

	/**
	 * Recomputes the gap payload digest from origin, local day/zone, UTC millisecond bounds and
	 * reason.
	 */
	fun gapEffectChecksum(value: PortableAmbientCellGapV1): String = gapEffectChecksum(
		value.origin,
		value.structuralEpochDay,
		value.startTimeMs,
		value.endTimeMs,
		value.storedZoneId,
		value.reason,
	)

	private fun gapEffectChecksum(
		origin: AmbientCellOrigin,
		structuralEpochDay: Long,
		startTimeMs: Long,
		endTimeMs: Long,
		storedZoneId: String,
		reason: String,
	): String = digest(
		"ambient-cell-portable-gap-effect-v1",
		origin,
		structuralEpochDay,
		startTimeMs,
		endTimeMs,
		storedZoneId,
		reason,
	)

	private fun gapChecksum(identity: String, effectChecksum: String): String =
		digest("ambient-cell-portable-gap-v1", identity, effectChecksum)

	/**
	 * Computes the summary digest from its original identity and declared effect checksum.
	 */
	fun factChecksum(value: PortableAmbientCellFactV1): String =
		factChecksum(value.identity, value.effectChecksum)

	private fun factChecksum(identity: String, effectChecksum: String): String =
		digest("ambient-cell-portable-fact-v1", identity, effectChecksum)

	/**
	 * Recomputes the summary payload digest including measurement bounds, counts, quality and
	 * correction revision.
	 */
	fun factEffectChecksum(value: PortableAmbientCellFactV1): String = factEffectChecksum(
		value.origin,
		value.coverageStartTimeMs,
		value.observedTimeMs,
		value.latestPossibleTimeMs,
		value.structuralEpochDay,
		value.storedZoneId,
		value.coverage,
		value.subscriptionCompleteness,
		value.observationCount,
		value.registeredObservationCount,
		value.technologyMix,
		value.qualityDistribution,
		value.semanticRevision,
		value.supersedesSemanticRevision,
	)

	@Suppress("LongParameterList")
	private fun factEffectChecksum(
		origin: AmbientCellOrigin,
		coverageStartTimeMs: Long,
		observedTimeMs: Long,
		latestPossibleTimeMs: Long,
		structuralEpochDay: Long,
		storedZoneId: String,
		coverage: AmbientCellCoverage,
		subscriptionCompleteness: AmbientCellSubscriptionCompleteness,
		observationCount: Int,
		registeredObservationCount: Int,
		technologyMix: AmbientCellTechnologyMix,
		qualityDistribution: AmbientCellQualityDistribution,
		semanticRevision: Long,
		supersedesSemanticRevision: Long?,
	): String = digest(
		"ambient-cell-portable-fact-effect-v1",
		origin,
		coverageStartTimeMs,
		observedTimeMs,
		latestPossibleTimeMs,
		structuralEpochDay,
		storedZoneId,
		coverage,
		subscriptionCompleteness,
		observationCount,
		registeredObservationCount,
		technologyMix,
		qualityDistribution,
		semanticRevision,
		supersedesSemanticRevision,
	)

	/**
	 * Hashes the archive identity and deterministically ordered child identities, revisions and
	 * checksums.
	 */
	fun archiveChecksum(
		archiveId: String,
		facts: List<PortableAmbientCellFactV1>,
		gaps: List<PortableAmbientCellGapV1>,
	): String = digest(
		"ambient-cell-portable-archive-v1",
		archiveId,
		facts.sortedWith(compareBy(
			PortableAmbientCellFactV1::identity,
			PortableAmbientCellFactV1::semanticRevision,
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
		return digest("ambient-cell-portable-identity-v1", namespace, localIdentity)
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
 * Half-open UTC millisecond selection and chosen Cell origins, or exact imported archive
 * membership for a session bundle.
 */
data class ExportPortableAmbientCellRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val origins: Set<AmbientCellOrigin>,
	/** Exact imported file membership for a saved bundle annotation; null keeps the date-range path. */
	val selectedArchiveIds: Set<String>? = null,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(origins.isNotEmpty())
	}
}

/**
 * Receives one complete all-day Cell archive after the bounded database snapshot closes.
 */
fun interface PortableAmbientCellSink {
	/**
	 * Accepts one complete all-day Cell archive after its bounded database snapshot has closed.
	 */
	suspend fun emit(archive: PortableAmbientCellArchiveV1)
}

/**
 * Read-only all-day Cell archive preparation without acquiring a sensor or recording permission.
 */
interface ExportPortableAmbientCell {
	/**
	 * Prepares a bounded saved Cell archive, emits it only after the snapshot transaction closes, and
	 * propagates cancellation.
	 */
	suspend fun export(
		request: ExportPortableAmbientCellRequest,
		sink: PortableAmbientCellSink,
	): ExportPortableAmbientCellResult
}

/**
 * Bounded file-job and entry provenance with a nonnegative UTC millisecond receive time.
 */
data class PortableAmbientCellImportReceipt(
	val jobId: String,
	val entryKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, entryKey, sourceName).forEach {
			require(it.isNotBlank() &&
				it.length <= AmbientCellPortableFormatV1.MAX_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/**
 * Decoded Cell archive and file provenance tied to the original expected collected-data epoch.
 */
data class ImportPortableAmbientCellRequest(
	val archive: PortableAmbientCellArchiveV1,
	val receipt: PortableAmbientCellImportReceipt,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/**
 * Admits imported all-day Cell data without creating native collection authority.
 */
interface ImportPortableAmbientCell {
	/**
	 * Admits the complete decoded Cell archive and file provenance under its original data epoch
	 * without creating native recording permission.
	 */
	suspend fun importArchive(
		request: ImportPortableAmbientCellRequest,
	): ImportPortableAmbientCellResult
}

/**
 * Complete Cell archive counts, empty selection, exceeded dependencies or unavailable storage.
 */
sealed interface ExportPortableAmbientCellResult {
	/**
	 * A complete archive was emitted; reports the represented fact and recorded-gap counts.
	 */
	data class Exported(val factCount: Int, val gapCount: Int) :
		ExportPortableAmbientCellResult
	/**
	 * No saved facts or gaps match the requested export selection.
	 */
	data object NoData : ExportPortableAmbientCellResult
	/**
	 * Preparing the complete transfer would exceed its bounded source dependencies.
	 */
	data object DependencyOverflow : ExportPortableAmbientCellResult
	/**
	 * Storage cannot provide the required snapshot or commit; this is not an empty-data result.
	 */
	data object StorageUnavailable : ExportPortableAmbientCellResult
}

/**
 * All-day Cell archive admission outcome distinguishing duplicates, conflicts, privacy boundaries
 * and storage failure.
 */
sealed interface ImportPortableAmbientCellResult {
	/**
	 * The archive was admitted; reports its saved facts and recorded gaps.
	 */
	data class Applied(val factCount: Int, val gapCount: Int) : ImportPortableAmbientCellResult
	/**
	 * Matching archive content and file provenance are already saved.
	 */
	data object Duplicate : ImportPortableAmbientCellResult
	/**
	 * The same file-job receipt already refers to different archive content or provenance.
	 */
	data object ReceiptConflict : ImportPortableAmbientCellResult
	/**
	 * The declared receive time precedes a represented observation or gap end.
	 */
	data object InvalidReceipt : ImportPortableAmbientCellResult
	/**
	 * A saved deletion or replay fence prevents restoring this archive or its removed readings.
	 */
	data object DeletedArchive : ImportPortableAmbientCellResult
	/**
	 * The archive includes measurement bounds older than the published retention floor.
	 */
	data object RetentionBoundary : ImportPortableAmbientCellResult
	/**
	 * No current portable-import retention approval can be verified for this data epoch.

	 */
	data object RetentionAuthorityUnavailable : ImportPortableAmbientCellResult
	/**
	 * The import job's original collected-data epoch no longer matches the current database.

	 */
	data object CollectedDataEpochChanged : ImportPortableAmbientCellResult
	/**
	 * Preparing the complete transfer would exceed its bounded source dependencies.
	 */
	data object DependencyOverflow : ImportPortableAmbientCellResult
	/**
	 * Storage cannot provide the required snapshot or commit; this is not an empty-data result.
	 */
	data object StorageUnavailable : ImportPortableAmbientCellResult
}

private val CELL_DIGEST = Regex("[0-9a-f]{64}")

private fun List<PortableAmbientCellFactV1>.hasCompleteCorrectionLineages(): Boolean =
	groupBy(PortableAmbientCellFactV1::identity).values.all { lineage ->
		val ordered = lineage.sortedBy(PortableAmbientCellFactV1::semanticRevision)
		ordered.withIndex().all { (index, fact) ->
			val expectedRevision = index + 1L
			fact.semanticRevision == expectedRevision &&
				fact.supersedesSemanticRevision == expectedRevision.takeIf { it > 1L }?.minus(1L)
		}
	}
