package com.adsamcik.tracker.stats.api.repository

/** Complete v2 archive sink reached only after source evidence and graph authentication. */
fun interface PortableStepsArchiveV2Sink {
	suspend fun emit(archive: PortableStepsArchiveV2)
}

/** Read-only authenticated export; it cannot acquire a provider or create session authority. */
interface ExportPortableStepsV2 {
	suspend fun export(
		request: ExportPortableStepsRequest,
		sink: PortableStepsArchiveV2Sink,
	): ExportPortableStepsResult
}

/** Durable file-job provenance. It is destination metadata, never source count-domain authority. */
data class PortableStepsImportReceipt(
	val jobId: String,
	val entryKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, entryKey, sourceName).forEach { value ->
			require(value.isNotBlank())
			require(value.length <= MAX_PORTABLE_STEPS_IMPORT_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/** Decoder-owned bounded metadata for one complete v2 archive. */
data class PortableStepsImportMetadataV2(
	val encodedByteCount: Long,
	val archiveContentChecksum: PortableStepsDigest,
	val entryCount: Int,
	val receiptCount: Int,
	val ownerRevisionCount: Int,
	val completenessMarkerCount: Int,
	val rootCount: Int,
) {
	init {
		require(encodedByteCount in 1L..StepsPortableFormatV2.MAX_FILE_BYTES)
		require(entryCount in 1..StepsPortableFormatV2.MAX_ENTRIES)
		require(receiptCount in 0..entryCount * PortableCountDomainFormatV2.MAX_RECEIPTS)
		require(ownerRevisionCount in entryCount..entryCount * PortableCountDomainFormatV2.MAX_OWNER_REVISIONS)
		require(
			completenessMarkerCount in
				entryCount..entryCount * PortableCountDomainFormatV2.MAX_COMPLETENESS_MARKERS,
		)
		require(rootCount in entryCount..entryCount * PortableCountDomainFormatV2.MAX_ROOTS)
	}
}

data class ImportPortableStepsV2Request(
	val archiveContentChecksum: PortableStepsDigest,
	val entryOrdinal: Int,
	val entry: PortableStepsEntryV2,
	val receipt: PortableStepsImportReceipt,
	val metadata: PortableStepsImportMetadataV2,
) {
	init {
		require(entryOrdinal in 0 until metadata.entryCount)
		require(archiveContentChecksum == metadata.archiveContentChecksum)
	}
}

/** Atomic v2 product-and-graph admission into imported-origin storage only. */
interface ImportPortableStepsV2 {
	suspend fun importEntry(request: ImportPortableStepsV2Request): ImportPortableStepsResult
}

/** Legacy v1 file admission with durable provenance and an explicitly UNPROVEN graph. */
interface ImportPortableStepsV1WithReceipt {
	suspend fun importEntry(
		entry: PortableStepsEntryV1,
		receipt: PortableStepsImportReceipt,
		entryOrdinal: Int,
	): ImportPortableStepsResult
}

private const val MAX_PORTABLE_STEPS_IMPORT_RECEIPT_FIELD_LENGTH = 4_096
