package com.adsamcik.tracker.stats.api.repository

/** Complete v2 archive sink reached only after source evidence and graph authentication. */
fun interface PortableStepsArchiveV2Sink {
	/**
	 * Accepts the complete v2 Steps archive with its original count-domain graph after source
	 * snapshot preparation.
	 */
	suspend fun emit(archive: PortableStepsArchiveV2)
}

/** Read-only authenticated export; it cannot acquire a provider or create session authority. */
interface ExportPortableStepsV2 {
	/**
	 * Prepares a bounded v2 Steps archive from the requested saved range and emits only a complete
	 * verified snapshot; cancellation propagates.
	 */
	suspend fun export(
		request: ExportPortableStepsRequest,
		sink: PortableStepsArchiveV2Sink,
	): ExportPortableStepsResult

	/** Preserves the selected entries' original graphs; the consumer discards bytes on non-success. */
	suspend fun export(
		request: ExportSelectedPortableStepsRequest,
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

/**
 * One archive entry and ordinal with matching decoder checksum metadata, file provenance and the
 * original job data epoch.
 */
data class ImportPortableStepsV2Request(
	val archiveContentChecksum: PortableStepsDigest,
	val entryOrdinal: Int,
	val entry: PortableStepsEntryV2,
	val receipt: PortableStepsImportReceipt,
	val metadata: PortableStepsImportMetadataV2,
	/** Original file-job epoch, checked again by the source writer inside its transaction. */
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(entryOrdinal in 0 until metadata.entryCount)
		require(archiveContentChecksum == metadata.archiveContentChecksum)
	}
}

/** Atomic v2 product-and-graph admission into imported-origin storage only. */
interface ImportPortableStepsV2 {
	/**
	 * Admits the selected v2 archive entry and graph atomically, preserving original file provenance
	 * and rechecking the job data epoch.
	 */
	suspend fun importEntry(request: ImportPortableStepsV2Request): ImportPortableStepsResult
}

/** Legacy v1 file admission with durable provenance and an explicitly UNPROVEN graph. */
interface ImportPortableStepsV1WithReceipt {
	/**
	 * Admits a legacy v1 entry with its file receipt/ordinal and expected data epoch, retaining an
	 * explicitly unproven count-domain graph.
	 */
	suspend fun importEntry(
		entry: PortableStepsEntryV1,
		receipt: PortableStepsImportReceipt,
		entryOrdinal: Int,
		expectedCollectedDataEpoch: Long,
	): ImportPortableStepsResult
}

private const val MAX_PORTABLE_STEPS_IMPORT_RECEIPT_FIELD_LENGTH = 4_096
