package com.adsamcik.tracker.stats.api.repository

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

object AmbientCellPortableFormatV1 {
	const val FORMAT = "tracker-portable-ambient-cell"
	const val SCHEMA_VERSION = 1
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.ambient-cell+json"
	const val FILE_EXTENSION = "trackerambientcell"
	const val MAX_FACTS = 4_096
	const val MAX_GAPS = 4_096
	const val MAX_RECEIPT_FIELD_LENGTH = 4_096
}

@Suppress("LongParameterList")
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

data class PortableAmbientCellArchiveV1(
	val format: String = AmbientCellPortableFormatV1.FORMAT,
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

object AmbientCellPortableIntegrity {
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

	fun gapChecksum(value: PortableAmbientCellGapV1): String =
		gapChecksum(value.identity, value.effectChecksum)

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

	fun factChecksum(value: PortableAmbientCellFactV1): String =
		factChecksum(value.identity, value.effectChecksum)

	private fun factChecksum(identity: String, effectChecksum: String): String =
		digest("ambient-cell-portable-fact-v1", identity, effectChecksum)

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

	fun opaqueIdentity(namespace: String, localIdentity: String): String {
		require(namespace.isNotBlank() && localIdentity.isNotBlank())
		return digest("ambient-cell-portable-identity-v1", namespace, localIdentity)
	}

	private fun digest(namespace: String, vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest("$namespace\u001f$canonical".toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

}

data class ExportPortableAmbientCellRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val origins: Set<AmbientCellOrigin>,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(origins.isNotEmpty())
	}
}

fun interface PortableAmbientCellSink {
	suspend fun emit(archive: PortableAmbientCellArchiveV1)
}

interface ExportPortableAmbientCell {
	suspend fun export(
		request: ExportPortableAmbientCellRequest,
		sink: PortableAmbientCellSink,
	): ExportPortableAmbientCellResult
}

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

data class ImportPortableAmbientCellRequest(
	val archive: PortableAmbientCellArchiveV1,
	val receipt: PortableAmbientCellImportReceipt,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

interface ImportPortableAmbientCell {
	suspend fun importArchive(
		request: ImportPortableAmbientCellRequest,
	): ImportPortableAmbientCellResult
}

sealed interface ExportPortableAmbientCellResult {
	data class Exported(val factCount: Int, val gapCount: Int) :
		ExportPortableAmbientCellResult
	data object NoData : ExportPortableAmbientCellResult
	data object DependencyOverflow : ExportPortableAmbientCellResult
	data object StorageUnavailable : ExportPortableAmbientCellResult
}

sealed interface ImportPortableAmbientCellResult {
	data class Applied(val factCount: Int, val gapCount: Int) : ImportPortableAmbientCellResult
	data object Duplicate : ImportPortableAmbientCellResult
	data object ReceiptConflict : ImportPortableAmbientCellResult
	data object InvalidReceipt : ImportPortableAmbientCellResult
	data object DeletedArchive : ImportPortableAmbientCellResult
	data object RetentionBoundary : ImportPortableAmbientCellResult
	data object RetentionAuthorityUnavailable : ImportPortableAmbientCellResult
	data object CollectedDataEpochChanged : ImportPortableAmbientCellResult
	data object DependencyOverflow : ImportPortableAmbientCellResult
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
