package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainFormatV2

fun interface PortableAmbientStepsArchiveV2Sink {
	suspend fun emit(archive: PortableAmbientStepsArchiveV2)
}

interface ExportPortableAmbientStepsV2 {
	suspend fun export(
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveV2Sink,
	): ExportPortableAmbientStepsResult
}

interface ReexportImportedAmbientStepsV2 : ExportPortableAmbientStepsV2

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

interface ImportPortableAmbientStepsV2 {
	suspend fun importArchive(
		request: ImportPortableAmbientStepsV2Request,
	): ImportPortableAmbientStepsResult
}
