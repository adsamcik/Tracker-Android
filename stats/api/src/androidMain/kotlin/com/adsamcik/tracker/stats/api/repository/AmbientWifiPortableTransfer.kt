package com.adsamcik.tracker.stats.api.repository

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

object AmbientWifiPortableFormatV1 {
	const val FORMAT = "tracker-portable-ambient-wifi"
	const val SCHEMA_VERSION = 1
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.ambient-wifi+json"
	const val FILE_EXTENSION = "trackerambientwifi"
	const val MAX_FACTS = 4_096
	const val MAX_GAPS = 4_096
	const val MAX_RECEIPT_FIELD_LENGTH = 4_096
}

@Suppress("LongParameterList")
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

data class PortableAmbientWifiArchiveV1(
	val format: String = AmbientWifiPortableFormatV1.FORMAT,
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

object AmbientWifiPortableIntegrity {
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

	fun gapChecksum(value: PortableAmbientWifiGapV1): String =
		gapChecksum(value.identity, value.effectChecksum)

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

	fun factChecksum(value: PortableAmbientWifiFactV1): String =
		factChecksum(value.identity, value.effectChecksum)

	private fun factChecksum(identity: String, effectChecksum: String): String =
		digest("ambient-wifi-portable-fact-v1", identity, effectChecksum)

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

	fun opaqueIdentity(namespace: String, localIdentity: String): String {
		require(namespace.isNotBlank() && localIdentity.isNotBlank())
		return digest("ambient-wifi-portable-identity-v1", namespace, localIdentity)
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

data class ExportPortableAmbientWifiRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val origins: Set<AmbientWifiOrigin>,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(origins.isNotEmpty())
	}
}

fun interface PortableAmbientWifiSink {
	suspend fun emit(archive: PortableAmbientWifiArchiveV1)
}

interface ExportPortableAmbientWifi {
	suspend fun export(
		request: ExportPortableAmbientWifiRequest,
		sink: PortableAmbientWifiSink,
	): ExportPortableAmbientWifiResult
}

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

data class ImportPortableAmbientWifiRequest(
	val archive: PortableAmbientWifiArchiveV1,
	val receipt: PortableAmbientWifiImportReceipt,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

interface ImportPortableAmbientWifi {
	suspend fun importArchive(
		request: ImportPortableAmbientWifiRequest,
	): ImportPortableAmbientWifiResult
}

sealed interface ExportPortableAmbientWifiResult {
	data class Exported(val factCount: Int, val gapCount: Int) :
		ExportPortableAmbientWifiResult
	data object NoData : ExportPortableAmbientWifiResult
	data object DependencyOverflow : ExportPortableAmbientWifiResult
	data object StorageUnavailable : ExportPortableAmbientWifiResult
}

sealed interface ImportPortableAmbientWifiResult {
	data class Applied(val factCount: Int, val gapCount: Int) : ImportPortableAmbientWifiResult
	data object Duplicate : ImportPortableAmbientWifiResult
	data object ReceiptConflict : ImportPortableAmbientWifiResult
	data object InvalidReceipt : ImportPortableAmbientWifiResult
	data object DeletedArchive : ImportPortableAmbientWifiResult
	data object RetentionBoundary : ImportPortableAmbientWifiResult
	data object RetentionAuthorityUnavailable : ImportPortableAmbientWifiResult
	data object CollectedDataEpochChanged : ImportPortableAmbientWifiResult
	data object DependencyOverflow : ImportPortableAmbientWifiResult
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
