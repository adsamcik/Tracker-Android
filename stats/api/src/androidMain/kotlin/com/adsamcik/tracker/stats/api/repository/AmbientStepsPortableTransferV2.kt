package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainFormatV2

/**
 * Receives one complete v2 all-day Steps archive containing its saved count-domain graph.
 */
fun interface PortableAmbientStepsArchiveV2Sink {
	/**
	 * Accepts one complete all-day Steps archive after its bounded database snapshot has closed.
	 */
	suspend fun emit(archive: PortableAmbientStepsArchiveV2)
}

/**
 * Read-only all-day Steps export retaining v2 count-domain identities and owner revisions.
 */
interface ExportPortableAmbientStepsV2 {
	/**
	 * Prepares a bounded saved Steps archive, emits it only after the snapshot transaction closes,
	 * and propagates cancellation.
	 */
	suspend fun export(
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveV2Sink,
	): ExportPortableAmbientStepsResult
}

/**
 * Re-exports imported-origin all-day Steps in v2 without creating native recording authority.
 */
interface ReexportImportedAmbientStepsV2 : ExportPortableAmbientStepsV2

/**
 * Decoder-supplied byte, day, fact, gap and graph counts checked against finite v2 file limits.
 */
data class PortableAmbientStepsImportMetadataV2(
	val encodedByteCount: Long,
	val archiveContentChecksum: AmbientStepsPortableDigest,
	val dayCount: Int,
	val factCount: Int,
	val gapCount: Int,
	val receiptCount: Int,
	val ownerRevisionCount: Int,
	val rootCount: Int,
) {
	init {
		require(encodedByteCount in 1L..AmbientStepsPortableFormatV2.MAX_FILE_BYTES)
		require(dayCount in 1..AmbientStepsPortableFormatV2.MAX_DAYS)
		require(factCount in 1..AmbientStepsPortableFormatV1.MAX_FACTS)
		require(gapCount in 0..AmbientStepsPortableFormatV1.MAX_GAPS)
		require(receiptCount in 0..dayCount * PortableCountDomainFormatV2.MAX_RECEIPTS)
		require(ownerRevisionCount in dayCount..dayCount * PortableCountDomainFormatV2.MAX_OWNER_REVISIONS)
		require(rootCount in dayCount..dayCount * PortableCountDomainFormatV2.MAX_ROOTS)
	}
}

/**
 * Decoded v2 all-day Steps archive and matching checksum metadata, bound to its file receipt and
 * original data epoch.
 */
data class ImportPortableAmbientStepsV2Request(
	val archive: PortableAmbientStepsArchiveV2,
	val receipt: PortableAmbientStepsImportReceipt,
	val metadata: PortableAmbientStepsImportMetadataV2,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(archive.contentChecksum == metadata.archiveContentChecksum)
	}
}

/**
 * Admits a decoded v2 all-day Steps archive into imported storage without starting collection.
 */
interface ImportPortableAmbientStepsV2 {
	/**
	 * Admits the complete decoded Steps archive and file provenance under its original data epoch
	 * without creating native recording permission.
	 */
	suspend fun importArchive(
		request: ImportPortableAmbientStepsV2Request,
	): ImportPortableAmbientStepsResult
}
