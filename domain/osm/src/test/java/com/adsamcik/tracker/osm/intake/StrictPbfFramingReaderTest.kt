package com.adsamcik.tracker.osm.intake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream
import org.junit.jupiter.api.Test

class StrictPbfFramingReaderTest {

	@Test
	fun `valid raw header and zlib data are delivered only after strict validation`() {
		val data = "bounded-data".toByteArray(StandardCharsets.UTF_8)
		val delivered = mutableListOf<StrictPbfBlock>()
		val ledger = ledger()

		val stats = reader().read(
			input = ByteArrayInputStream(validFile(data)),
			ledger = ledger,
			onBlock = delivered::add,
		)

		stats shouldBe StrictPbfReadStats(blockCount = 2L, dataBlockCount = 1L)
		delivered.map { it.type } shouldBe listOf(StrictPbfBlockType.OSM_HEADER, StrictPbfBlockType.OSM_DATA)
		delivered[0].rawBytes.contentEquals(headerBlock()) shouldBe true
		String(delivered[1].rawBytes, StandardCharsets.UTF_8) shouldBe "bounded-data"
		PbfResource.entries.forEach { resource -> ledger.used(resource) shouldBe 0L }
	}

	@Test
	fun `truncated outer framing is rejected before a block is published`() {
		val delivered = mutableListOf<StrictPbfBlock>()

		val failure = shouldThrow<StrictPbfFramingFailure> {
			reader().read(
				input = ByteArrayInputStream(byteArrayOf(0, 0, 0)),
				ledger = ledger(),
				onBlock = delivered::add,
			)
		}

		failure.reason shouldBe StrictPbfFramingFailureReason.TRUNCATED_FRAME
		delivered shouldBe emptyList()
	}

	@Test
	fun `blob header cap is checked before allocating the declared header`() {
		val limits = limits(maxBlobHeaderBytes = 32)

		val failure = rejection(
			bytes = intBigEndian(33),
			limits = limits,
		)

		failure.reason shouldBe StrictPbfFramingFailureReason.BLOB_HEADER_TOO_LARGE
	}

	@Test
	fun `declared blob length is bounded and truncated blobs are rejected`() {
		val oversized = concat(
			framePrefix(blobHeader(type = "OSMHeader", dataSize = 65)),
			ByteArray(0),
		)
		val tooLarge = rejection(
			bytes = oversized,
			limits = limits(maxBlobBytes = 64),
		)
		tooLarge.reason shouldBe StrictPbfFramingFailureReason.BLOB_TOO_LARGE

		val truncated = concat(
			framePrefix(blobHeader(type = "OSMHeader", dataSize = 4)),
			byteArrayOf(0x0A, 0x00),
		)
		val truncation = rejection(truncated)
		truncation.reason shouldBe StrictPbfFramingFailureReason.TRUNCATED_FRAME
	}

	@Test
	fun `unrepresentable protobuf varints cannot wrap into negative lengths`() {
		val overflowedDataSizeHeader = concat(
			fieldBytes(field = 1, value = "OSMHeader".toByteArray(StandardCharsets.US_ASCII)),
			byteArrayOf(0x18),
			ByteArray(9) { 0x80.toByte() },
			byteArrayOf(0x01),
		)

		val failure = rejection(framePrefix(overflowedDataSizeHeader))

		failure.reason shouldBe StrictPbfFramingFailureReason.MALFORMED_PROTOBUF
	}

	@Test
	fun `unsupported compression and multiple compression payloads are rejected`() {
		val unsupported = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			frame("OSMData", fieldBytes(field = 4, value = byteArrayOf(1))),
		)
		rejection(unsupported).reason shouldBe StrictPbfFramingFailureReason.UNSUPPORTED_COMPRESSION

		val conflicting = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			frame(
				"OSMData",
				concat(
					fieldBytes(field = 1, value = byteArrayOf(1)),
					fieldVarint(field = 2, value = 1L),
					fieldBytes(field = 3, value = zlib(byteArrayOf(1))),
				),
			),
		)
		rejection(conflicting).reason shouldBe StrictPbfFramingFailureReason.MULTIPLE_COMPRESSION_PAYLOADS
	}

	@Test
	fun `raw and zlib raw size mismatches are rejected before publication`() {
		val rawMismatch = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			frame("OSMData", rawBlob(raw = byteArrayOf(1, 2), declaredRawSize = 1)),
		)
		rejection(rawMismatch).reason shouldBe StrictPbfFramingFailureReason.RAW_SIZE_MISMATCH

		val compressedMismatch = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			frame("OSMData", zlibBlob(raw = byteArrayOf(1, 2), declaredRawSize = 1)),
		)
		rejection(compressedMismatch).reason shouldBe StrictPbfFramingFailureReason.RAW_SIZE_MISMATCH
	}

	@Test
	fun `malformed truncated and trailing zlib streams are rejected exactly`() {
		val malformed = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			// Valid zlib header followed by a DEFLATE block with reserved BTYPE=3.
			frame(
				"OSMData",
				zlibBlobFromPayload(payload = byteArrayOf(0x78, 0x9C.toByte(), 0x07), declaredRawSize = 1),
			),
		)
		rejection(malformed).reason shouldBe StrictPbfFramingFailureReason.MALFORMED_COMPRESSED_DATA

		val compressed = zlib(byteArrayOf(1, 2, 3))
		val truncated = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			frame(
				"OSMData",
				zlibBlobFromPayload(payload = compressed.copyOf(compressed.size - 1), declaredRawSize = 3),
			),
		)
		rejection(truncated).reason shouldBe StrictPbfFramingFailureReason.TRUNCATED_COMPRESSED_DATA

		val trailing = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			frame(
				"OSMData",
				zlibBlobFromPayload(payload = concat(compressed, byteArrayOf(0x55)), declaredRawSize = 3),
			),
		)
		rejection(trailing).reason shouldBe StrictPbfFramingFailureReason.TRAILING_COMPRESSED_DATA
	}

	@Test
	fun `header required feature policy is enforced without decoding attacker strings`() {
		val unsupported = frame(
			"OSMHeader",
			rawBlob(headerBlock(requiredFeatures = listOf("HistoricalInformation"))),
		)
		rejection(unsupported).reason shouldBe StrictPbfFramingFailureReason.UNSUPPORTED_REQUIRED_FEATURE

		val missing = frame("OSMHeader", rawBlob(headerBlock(requiredFeatures = emptyList())))
		rejection(missing).reason shouldBe StrictPbfFramingFailureReason.REQUIRED_FEATURE_MISSING

		val tooMany = frame(
			"OSMHeader",
			rawBlob(headerBlock(requiredFeatures = listOf("OsmSchema-V0.6", "OsmSchema-V0.6"))),
		)
		rejection(tooMany, limits = limits(maxRequiredFeatureCount = 1)).reason shouldBe
			StrictPbfFramingFailureReason.REQUIRED_FEATURE_LIMIT_EXCEEDED
	}

	@Test
	fun `field raw byte and cumulative work caps reject before output allocation`() {
		val fieldLimited = frame("OSMHeader", rawBlob(headerBlock()))
		rejection(fieldLimited, limits = limits(maxFieldsPerMessage = 1)).reason shouldBe
			StrictPbfFramingFailureReason.FIELD_LIMIT_EXCEEDED

		val rawCap = concat(
			frame("OSMHeader", rawBlob(headerBlock())),
			frame("OSMData", zlibBlob(raw = ByteArray(24) { 7 })),
		)
		rejection(rawCap, limits = limits(maxRawBlobBytes = 20)).reason shouldBe
			StrictPbfFramingFailureReason.RAW_BLOB_TOO_LARGE

		val workLedger = ledger(workUnits = 4L)
		val workFailure = shouldThrow<PbfIntakeFailure.ResourceLimitExceeded> {
			reader().read(
				input = ByteArrayInputStream(frame("OSMHeader", rawBlob(headerBlock()))),
				ledger = workLedger,
				onBlock = {},
			)
		}
		workFailure.resource shouldBe PbfResource.WORK_UNITS
		PbfResource.entries.forEach { resource -> workLedger.used(resource) shouldBe 0L }
	}

	private fun reader(limits: StrictPbfFramingLimits = limits()): StrictPbfFramingReader =
		StrictPbfFramingReader(limits)

	private fun rejection(
		bytes: ByteArray,
		limits: StrictPbfFramingLimits = limits(),
	): StrictPbfFramingFailure = shouldThrow<StrictPbfFramingFailure> {
		reader(limits).read(
			input = ByteArrayInputStream(bytes),
			ledger = ledger(),
			onBlock = {},
		)
	}

	private fun limits(
		maxBlobHeaderBytes: Int = 256,
		maxBlobBytes: Int = 4_096,
		maxRawBlobBytes: Int = 4_096,
		maxBlocks: Long = 16L,
		maxFieldsPerMessage: Int = 32,
		maxRequiredFeatureCount: Int = 4,
		maxRequiredFeatureBytes: Int = 64,
	): StrictPbfFramingLimits = StrictPbfFramingLimits(
		maxBlobHeaderBytes = maxBlobHeaderBytes,
		maxBlobBytes = maxBlobBytes,
		maxRawBlobBytes = maxRawBlobBytes,
		maxBlocks = maxBlocks,
		maxFieldsPerMessage = maxFieldsPerMessage,
		maxRequiredFeatureCount = maxRequiredFeatureCount,
		maxRequiredFeatureBytes = maxRequiredFeatureBytes,
		allowedCompressions = setOf(PbfCompression.RAW, PbfCompression.ZLIB),
		supportedRequiredFeatures = setOf("OsmSchema-V0.6"),
		requiredHeaderFeatures = setOf("OsmSchema-V0.6"),
	)

	private fun ledger(
		managedMemoryBytes: Long = 64 * 1_024L,
		outputBytes: Long = 64 * 1_024L,
		workUnits: Long = 64 * 1_024L,
	): PbfResourceLedger = PbfResourceLedger(
		PbfResourceLimits(
			sourceBytes = 0L,
			privateSnapshotDiskBytes = 0L,
			managedMemoryBytes = managedMemoryBytes,
			retainedGraphBytes = 0L,
			outputBytes = outputBytes,
			workUnits = workUnits,
		),
	)

	private fun validFile(data: ByteArray): ByteArray = concat(
		frame("OSMHeader", rawBlob(headerBlock())),
		frame("OSMData", zlibBlob(data)),
	)

	private fun headerBlock(requiredFeatures: List<String> = listOf("OsmSchema-V0.6")): ByteArray =
		concat(*requiredFeatures.map { fieldBytes(4, it.toByteArray(StandardCharsets.UTF_8)) }.toTypedArray())

	private fun rawBlob(raw: ByteArray, declaredRawSize: Int? = null): ByteArray = concat(
		fieldBytes(field = 1, value = raw),
		declaredRawSize?.let { fieldVarint(field = 2, value = it.toLong()) } ?: ByteArray(0),
	)

	private fun zlibBlob(raw: ByteArray, declaredRawSize: Int = raw.size): ByteArray =
		zlibBlobFromPayload(payload = zlib(raw), declaredRawSize = declaredRawSize)

	private fun zlibBlobFromPayload(payload: ByteArray, declaredRawSize: Int): ByteArray = concat(
		fieldVarint(field = 2, value = declaredRawSize.toLong()),
		fieldBytes(field = 3, value = payload),
	)

	private fun frame(type: String, blob: ByteArray): ByteArray = concat(
		framePrefix(blobHeader(type = type, dataSize = blob.size)),
		blob,
	)

	private fun framePrefix(header: ByteArray): ByteArray = concat(intBigEndian(header.size), header)

	private fun blobHeader(type: String, dataSize: Int): ByteArray = concat(
		fieldBytes(field = 1, value = type.toByteArray(StandardCharsets.US_ASCII)),
		fieldVarint(field = 3, value = dataSize.toLong()),
	)

	private fun fieldBytes(field: Int, value: ByteArray): ByteArray = concat(
		varint((field shl 3 or 2).toLong()),
		varint(value.size.toLong()),
		value,
	)

	private fun fieldVarint(field: Int, value: Long): ByteArray = concat(
		varint((field shl 3).toLong()),
		varint(value),
	)

	private fun varint(value: Long): ByteArray {
		require(value >= 0L)
		var remaining = value
		val output = ByteArrayOutputStream()
		do {
			var byte = (remaining and 0x7F).toInt()
			remaining = remaining ushr 7
			if (remaining != 0L) byte = byte or 0x80
			output.write(byte)
		} while (remaining != 0L)
		return output.toByteArray()
	}

	private fun zlib(raw: ByteArray): ByteArray {
		val output = ByteArrayOutputStream()
		DeflaterOutputStream(output).use { stream -> stream.write(raw) }
		return output.toByteArray()
	}

	private fun intBigEndian(value: Int): ByteArray {
		require(value >= 0)
		return byteArrayOf(
			(value ushr 24).toByte(),
			(value ushr 16).toByte(),
			(value ushr 8).toByte(),
			value.toByte(),
		)
	}

	private fun concat(vararg arrays: ByteArray): ByteArray {
		val size = arrays.sumOf { it.size }
		val output = ByteArray(size)
		var offset = 0
		arrays.forEach { array ->
			array.copyInto(output, destinationOffset = offset)
			offset += array.size
		}
		return output
	}
}
