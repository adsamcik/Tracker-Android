package com.adsamcik.tracker.osm.intake

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Strict, bounded reader for the outer OpenStreetMap PBF file-block framing.
 *
 * This reader deliberately owns the byte framing and zlib boundary rather than
 * delegating untrusted `raw_size` values to a library that might allocate first.
 * It validates the `BlobHeader`, `Blob`, compression choice, exact zlib stream,
 * and required HeaderBlock features before exposing one bounded raw block to
 * [onBlock]. It does not parse PrimitiveBlocks and is not wired into the
 * release-gated legacy importer.
 *
 * Every cap comes from [StrictPbfFramingLimits]; there are no product defaults.
 * The supplied [PbfResourceLedger] additionally charges fixed/frame buffers,
 * transient decoded output, and cumulative parsing work.
 */
class StrictPbfFramingReader(
	private val limits: StrictPbfFramingLimits,
) {

	/**
	 * Reads a complete PBF stream. [onBlock] receives a new bounded byte array
	 * for each validated raw block. It must not retain that array without making
	 * its own downstream reservation; the reader releases its transient ledger
	 * charges immediately after the callback returns.
	 */
	fun read(
		input: InputStream,
		ledger: PbfResourceLedger,
		onBlock: (StrictPbfBlock) -> Unit,
	): StrictPbfReadStats {
		val lease = ledger.openLease()
		try {
			// This fixed prelude allocation is charged before it exists. It is
			// independent of every untrusted length in the file.
			lease.reserve(PbfResource.MANAGED_MEMORY_BYTES, FRAME_LENGTH_BYTES.toLong())
			val frameLengthBytes = ByteArray(FRAME_LENGTH_BYTES)
			var blockCount = 0L
			var dataBlockCount = 0L
			var sawHeader = false
			var sawData = false

			while (true) {
				val first = readFirstByteOrEof(input)
				if (first < 0) break
				if (blockCount >= limits.maxBlocks) reject(StrictPbfFramingFailureReason.BLOCK_LIMIT_EXCEEDED)
				lease.reserve(PbfResource.WORK_UNITS, 1L)
				frameLengthBytes[0] = first.toByte()
				readExactly(input, frameLengthBytes, offset = 1, length = FRAME_LENGTH_BYTES - 1)

				val blobHeaderSize = readBigEndianLength(frameLengthBytes)
				if (blobHeaderSize <= 0 || blobHeaderSize > limits.maxBlobHeaderBytes) {
					reject(StrictPbfFramingFailureReason.BLOB_HEADER_TOO_LARGE)
				}
				val blobHeaderBytes = allocateAndRead(
					input = input,
					lease = lease,
					byteCount = blobHeaderSize,
				)
				val blobHeader = try {
					parseBlobHeader(blobHeaderBytes, lease)
				} finally {
					lease.release(PbfResource.MANAGED_MEMORY_BYTES, blobHeaderSize.toLong())
				}

				val type = blobHeader.type
				when {
					!sawHeader && type != StrictPbfBlockType.OSM_HEADER ->
						reject(StrictPbfFramingFailureReason.INVALID_BLOCK_ORDER)
					type == StrictPbfBlockType.OSM_HEADER && sawHeader ->
						reject(StrictPbfFramingFailureReason.INVALID_BLOCK_ORDER)
					type == StrictPbfBlockType.OSM_HEADER && sawData ->
						reject(StrictPbfFramingFailureReason.INVALID_BLOCK_ORDER)
				}

				val blobBytes = allocateAndRead(
					input = input,
					lease = lease,
					byteCount = blobHeader.dataSize,
				)
				val decoded = try {
					val blob = parseBlob(blobBytes, lease)
					decodeBlob(blobBytes, blob, lease)
				} finally {
					lease.release(PbfResource.MANAGED_MEMORY_BYTES, blobHeader.dataSize.toLong())
				}

				try {
					if (type == StrictPbfBlockType.OSM_HEADER) {
						validateHeaderBlock(decoded.rawBytes, lease)
						sawHeader = true
					} else {
						sawData = true
						dataBlockCount++
					}
					onBlock(StrictPbfBlock(type = type, rawBytes = decoded.rawBytes))
				} finally {
					lease.release(PbfResource.OUTPUT_BYTES, decoded.outputChargeBytes)
					lease.release(PbfResource.MANAGED_MEMORY_BYTES, decoded.memoryChargeBytes)
				}
				blockCount++
			}

			if (!sawHeader) reject(StrictPbfFramingFailureReason.MISSING_HEADER_BLOCK)
			return StrictPbfReadStats(blockCount = blockCount, dataBlockCount = dataBlockCount)
		} finally {
			lease.close()
		}
	}

	private fun allocateAndRead(
		input: InputStream,
		lease: PbfResourceLease,
		byteCount: Int,
	): ByteArray {
		// Treat each byte copied from the source as one caller-budgeted unit of
		// work. This admission happens before the allocation/read so a stream
		// cannot consume unbounded I/O work merely because its memory cap holds.
		lease.reserveAll(
			mapOf(
				PbfResource.MANAGED_MEMORY_BYTES to byteCount.toLong(),
				PbfResource.WORK_UNITS to byteCount.toLong(),
			),
		)
		val bytes = ByteArray(byteCount)
		readExactly(input, bytes, offset = 0, length = bytes.size)
		return bytes
	}

	private fun parseBlobHeader(bytes: ByteArray, lease: PbfResourceLease): ParsedBlobHeader {
		val cursor = ProtoCursor(bytes)
		var fields = 0
		var type: StrictPbfBlockType? = null
		var dataSize: Int? = null
		while (!cursor.atEnd()) {
			consumeFieldBudget(lease, ++fields)
			val key = cursor.readKey()
			when (key ushr FIELD_NUMBER_SHIFT) {
				BLOB_HEADER_TYPE_FIELD -> {
					requireWireType(key, WIRE_LENGTH_DELIMITED)
					if (type != null) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
					type = StrictPbfBlockType.fromWireName(bytes, cursor.readLengthDelimited())
						?: reject(StrictPbfFramingFailureReason.UNSUPPORTED_BLOCK_TYPE)
				}
				BLOB_HEADER_INDEX_DATA_FIELD -> {
					requireWireType(key, WIRE_LENGTH_DELIMITED)
					cursor.readLengthDelimited() // Bounded by the BlobHeader allocation; not materialized.
				}
				BLOB_HEADER_DATA_SIZE_FIELD -> {
					requireWireType(key, WIRE_VARINT)
					if (dataSize != null) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
					dataSize = cursor.readNonNegativeInt()
				}
				else -> reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			}
		}
		val resolvedType = type ?: reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
		val resolvedDataSize = dataSize ?: reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
		if (resolvedDataSize <= 0 || resolvedDataSize > limits.maxBlobBytes) {
			reject(StrictPbfFramingFailureReason.BLOB_TOO_LARGE)
		}
		return ParsedBlobHeader(resolvedType, resolvedDataSize)
	}

	private fun parseBlob(bytes: ByteArray, lease: PbfResourceLease): ParsedBlob {
		val cursor = ProtoCursor(bytes)
		var fields = 0
		var raw: ByteSlice? = null
		var zlib: ByteSlice? = null
		var rawSize: Int? = null
		while (!cursor.atEnd()) {
			consumeFieldBudget(lease, ++fields)
			val key = cursor.readKey()
			when (key ushr FIELD_NUMBER_SHIFT) {
				BLOB_RAW_FIELD -> {
					requireWireType(key, WIRE_LENGTH_DELIMITED)
					if (raw != null) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
					raw = cursor.readLengthDelimited()
				}
				BLOB_RAW_SIZE_FIELD -> {
					requireWireType(key, WIRE_VARINT)
					if (rawSize != null) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
					rawSize = cursor.readNonNegativeInt()
				}
				BLOB_ZLIB_FIELD -> {
					requireWireType(key, WIRE_LENGTH_DELIMITED)
					if (zlib != null) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
					zlib = cursor.readLengthDelimited()
				}
				BLOB_LZMA_FIELD,
				BLOB_BZIP2_FIELD,
				BLOB_LZ4_FIELD,
				BLOB_ZSTD_FIELD,
				-> {
					requireWireType(key, WIRE_LENGTH_DELIMITED)
					cursor.readLengthDelimited()
					reject(StrictPbfFramingFailureReason.UNSUPPORTED_COMPRESSION)
				}
				else -> reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			}
		}

		if (raw != null && zlib != null) reject(StrictPbfFramingFailureReason.MULTIPLE_COMPRESSION_PAYLOADS)
		if (raw == null && zlib == null) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)

		return when {
			raw != null -> {
				if (PbfCompression.RAW !in limits.allowedCompressions) {
					reject(StrictPbfFramingFailureReason.UNSUPPORTED_COMPRESSION)
				}
				val resolvedRawSize = rawSize ?: raw.length
				if (resolvedRawSize != raw.length) reject(StrictPbfFramingFailureReason.RAW_SIZE_MISMATCH)
				if (resolvedRawSize > limits.maxRawBlobBytes) {
					reject(StrictPbfFramingFailureReason.RAW_BLOB_TOO_LARGE)
				}
				ParsedBlob(compression = PbfCompression.RAW, payload = raw, rawSize = resolvedRawSize)
			}
			else -> {
				if (PbfCompression.ZLIB !in limits.allowedCompressions) {
					reject(StrictPbfFramingFailureReason.UNSUPPORTED_COMPRESSION)
				}
				val resolvedRawSize = rawSize ?: reject(StrictPbfFramingFailureReason.RAW_SIZE_MISMATCH)
				if (resolvedRawSize > limits.maxRawBlobBytes) {
					reject(StrictPbfFramingFailureReason.RAW_BLOB_TOO_LARGE)
				}
				ParsedBlob(compression = PbfCompression.ZLIB, payload = checkNotNull(zlib), rawSize = resolvedRawSize)
			}
		}
	}

	private fun decodeBlob(
		blobBytes: ByteArray,
		blob: ParsedBlob,
		lease: PbfResourceLease,
	): DecodedBlob {
		val probeBytes = if (blob.compression == PbfCompression.ZLIB) 1L else 0L
		val memoryCharge = checkedAdd(blob.rawSize.toLong(), probeBytes)
		val outputCharge = blob.rawSize.toLong()
		lease.reserveAll(
			mapOf(
				PbfResource.MANAGED_MEMORY_BYTES to memoryCharge,
				PbfResource.OUTPUT_BYTES to outputCharge,
				// Raw copying and decompression are both bounded by the declared raw
				// output length, so charge that work before materializing output.
				PbfResource.WORK_UNITS to blob.rawSize.toLong(),
			),
		)
		var decoded = false
		try {
			val rawBytes = when (blob.compression) {
				PbfCompression.RAW -> copyRawPayload(blobBytes, blob.payload, blob.rawSize)
				PbfCompression.ZLIB -> inflateExactly(blobBytes, blob.payload, blob.rawSize)
			}
			decoded = true
			return DecodedBlob(rawBytes, memoryCharge, outputCharge)
		} finally {
			if (!decoded) {
				// Reservations are transient and must be balanced on every rejected
				// block. This also runs for fatal VM errors without treating them as
				// routine parser control flow.
				lease.release(PbfResource.OUTPUT_BYTES, outputCharge)
				lease.release(PbfResource.MANAGED_MEMORY_BYTES, memoryCharge)
			}
		}
	}

	private fun copyRawPayload(source: ByteArray, payload: ByteSlice, rawSize: Int): ByteArray {
		val output = ByteArray(rawSize)
		System.arraycopy(source, payload.offset, output, 0, rawSize)
		return output
	}

	private fun inflateExactly(source: ByteArray, payload: ByteSlice, rawSize: Int): ByteArray {
		val output = ByteArray(rawSize)
		val probe = ByteArray(1)
		val inflater = Inflater()
		try {
			inflater.setInput(source, payload.offset, payload.length)
			var written = 0
			while (written < rawSize) {
				val count = try {
					inflater.inflate(output, written, rawSize - written)
				} catch (_: DataFormatException) {
					reject(StrictPbfFramingFailureReason.MALFORMED_COMPRESSED_DATA)
				}
				if (count > 0) {
					written += count
					continue
				}
				handleInflaterStall(inflater, finishedMeansSizeMismatch = true)
			}

			if (!inflater.finished()) {
				val extra = try {
					inflater.inflate(probe)
				} catch (_: DataFormatException) {
					reject(StrictPbfFramingFailureReason.MALFORMED_COMPRESSED_DATA)
				}
				if (extra > 0) reject(StrictPbfFramingFailureReason.RAW_SIZE_MISMATCH)
				if (!inflater.finished()) {
					handleInflaterStall(inflater, finishedMeansSizeMismatch = false)
				}
			}
			if (inflater.remaining != 0) reject(StrictPbfFramingFailureReason.TRAILING_COMPRESSED_DATA)
			return output
		} finally {
			inflater.end()
		}
	}

	private fun handleInflaterStall(inflater: Inflater, finishedMeansSizeMismatch: Boolean): Nothing {
		when {
			inflater.finished() && finishedMeansSizeMismatch ->
				reject(StrictPbfFramingFailureReason.RAW_SIZE_MISMATCH)
			inflater.needsDictionary() -> reject(StrictPbfFramingFailureReason.UNSUPPORTED_COMPRESSION)
			inflater.needsInput() -> reject(StrictPbfFramingFailureReason.TRUNCATED_COMPRESSED_DATA)
			else -> reject(StrictPbfFramingFailureReason.MALFORMED_COMPRESSED_DATA)
		}
	}

	private fun validateHeaderBlock(rawHeader: ByteArray, lease: PbfResourceLease) {
		val requiredFeaturesFound = BooleanArray(limits.requiredHeaderFeatureBytes.size)
		val cursor = ProtoCursor(rawHeader)
		var fields = 0
		var requiredFeatureCount = 0
		while (!cursor.atEnd()) {
			consumeFieldBudget(lease, ++fields)
			val key = cursor.readKey()
			if ((key ushr FIELD_NUMBER_SHIFT) == HEADER_REQUIRED_FEATURE_FIELD) {
				requireWireType(key, WIRE_LENGTH_DELIMITED)
				val feature = cursor.readLengthDelimited()
				requiredFeatureCount++
				if (requiredFeatureCount > limits.maxRequiredFeatureCount ||
					feature.length > limits.maxRequiredFeatureBytes
				) {
					reject(StrictPbfFramingFailureReason.REQUIRED_FEATURE_LIMIT_EXCEEDED)
				}
				if (!limits.isSupportedRequiredFeature(rawHeader, feature)) {
					reject(StrictPbfFramingFailureReason.UNSUPPORTED_REQUIRED_FEATURE)
				}
				limits.markRequiredFeatureIfPresent(rawHeader, feature, requiredFeaturesFound)
			} else {
				cursor.skipField(key and WIRE_TYPE_MASK)
			}
		}
		if (requiredFeaturesFound.any { !it }) {
			reject(StrictPbfFramingFailureReason.REQUIRED_FEATURE_MISSING)
		}
	}

	private fun consumeFieldBudget(lease: PbfResourceLease, fields: Int) {
		if (fields > limits.maxFieldsPerMessage) {
			reject(StrictPbfFramingFailureReason.FIELD_LIMIT_EXCEEDED)
		}
		lease.reserve(PbfResource.WORK_UNITS, 1L)
	}

	private fun readFirstByteOrEof(input: InputStream): Int = try {
		input.read()
	} catch (_: IOException) {
		reject(StrictPbfFramingFailureReason.SOURCE_READ_FAILED)
	} catch (_: SecurityException) {
		reject(StrictPbfFramingFailureReason.SOURCE_READ_FAILED)
	}

	private fun readExactly(input: InputStream, destination: ByteArray, offset: Int, length: Int) {
		var written = 0
		while (written < length) {
			val count = try {
				input.read(destination, offset + written, length - written)
			} catch (_: IOException) {
				reject(StrictPbfFramingFailureReason.SOURCE_READ_FAILED)
			} catch (_: SecurityException) {
				reject(StrictPbfFramingFailureReason.SOURCE_READ_FAILED)
			}
			if (count < 0) reject(StrictPbfFramingFailureReason.TRUNCATED_FRAME)
			if (count == 0) {
				val single = readFirstByteOrEof(input)
				if (single < 0) reject(StrictPbfFramingFailureReason.TRUNCATED_FRAME)
				destination[offset + written] = single.toByte()
				written++
			} else {
				written += count
			}
		}
	}

	private fun readBigEndianLength(bytes: ByteArray): Int {
		val value =
			((bytes[0].toLong() and BYTE_MASK) shl 24) or
				((bytes[1].toLong() and BYTE_MASK) shl 16) or
				((bytes[2].toLong() and BYTE_MASK) shl 8) or
				(bytes[3].toLong() and BYTE_MASK)
		if (value > Int.MAX_VALUE.toLong()) reject(StrictPbfFramingFailureReason.BLOB_HEADER_TOO_LARGE)
		return value.toInt()
	}

	private fun checkedAdd(first: Long, second: Long): Long = try {
		Math.addExact(first, second)
	} catch (_: ArithmeticException) {
		reject(StrictPbfFramingFailureReason.RAW_BLOB_TOO_LARGE)
	}

	private data class ParsedBlobHeader(
		val type: StrictPbfBlockType,
		val dataSize: Int,
	)

	private data class ParsedBlob(
		val compression: PbfCompression,
		val payload: ByteSlice,
		val rawSize: Int,
	)

	private data class DecodedBlob(
		val rawBytes: ByteArray,
		val memoryChargeBytes: Long,
		val outputChargeBytes: Long,
	)

	private class ProtoCursor(private val bytes: ByteArray) {
		private var position = 0

		fun atEnd(): Boolean = position == bytes.size

		fun readKey(): Int {
			val key = readVarint()
			if (key < 0L || key > Int.MAX_VALUE.toLong() || (key ushr FIELD_NUMBER_SHIFT) == 0L) {
				reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			}
			val wireType = (key and WIRE_TYPE_MASK.toLong()).toInt()
			if (wireType !in SUPPORTED_WIRE_TYPES) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			return key.toInt()
		}

		fun readNonNegativeInt(): Int {
			val value = readVarint()
			if (value < 0L || value > Int.MAX_VALUE.toLong()) {
				reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			}
			return value.toInt()
		}

		fun readLengthDelimited(): ByteSlice {
			val length = readNonNegativeInt()
			if (length > bytes.size - position) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			val slice = ByteSlice(position, length)
			position += length
			return slice
		}

		fun skipField(wireType: Int) {
			when (wireType) {
				WIRE_VARINT -> readVarint()
				WIRE_FIXED_64 -> skipFixed(8)
				WIRE_LENGTH_DELIMITED -> readLengthDelimited()
				WIRE_FIXED_32 -> skipFixed(4)
				else -> reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			}
		}

		private fun skipFixed(length: Int) {
			if (length > bytes.size - position) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			position += length
		}

		private fun readVarint(): Long {
			var value = 0L
			for (index in 0 until MAX_VARINT_BYTES) {
				if (position >= bytes.size) reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
				val byte = bytes[position++].toInt() and BYTE_MASK_INT
				// The parser represents varints in a signed Long. A non-zero tenth
				// group would set bit 63 (or exceed it), making an unsigned length or
				// key appear negative after conversion. Reject it rather than letting
				// a hostile size flow into offset arithmetic.
				if (index == MAX_VARINT_BYTES - 1 && byte != 0) {
					reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
				}
				value = value or ((byte and 0x7F).toLong() shl (index * 7))
				if ((byte and 0x80) == 0) return value
			}
			reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
		}
	}

	companion object {
		private const val FRAME_LENGTH_BYTES = 4
		private const val BYTE_MASK = 0xFFL
		private const val BYTE_MASK_INT = 0xFF
		private const val MAX_VARINT_BYTES = 10
		private const val FIELD_NUMBER_SHIFT = 3
		private const val WIRE_TYPE_MASK = 0x7
		private const val WIRE_VARINT = 0
		private const val WIRE_FIXED_64 = 1
		private const val WIRE_LENGTH_DELIMITED = 2
		private const val WIRE_FIXED_32 = 5
		private val SUPPORTED_WIRE_TYPES = setOf(
			WIRE_VARINT,
			WIRE_FIXED_64,
			WIRE_LENGTH_DELIMITED,
			WIRE_FIXED_32,
		)

		private const val BLOB_HEADER_TYPE_FIELD = 1
		private const val BLOB_HEADER_INDEX_DATA_FIELD = 2
		private const val BLOB_HEADER_DATA_SIZE_FIELD = 3

		private const val BLOB_RAW_FIELD = 1
		private const val BLOB_RAW_SIZE_FIELD = 2
		private const val BLOB_ZLIB_FIELD = 3
		private const val BLOB_LZMA_FIELD = 4
		private const val BLOB_BZIP2_FIELD = 5
		private const val BLOB_LZ4_FIELD = 6
		private const val BLOB_ZSTD_FIELD = 7

		private const val HEADER_REQUIRED_FEATURE_FIELD = 4

		private fun requireWireType(key: Int, expected: Int) {
			if ((key and WIRE_TYPE_MASK) != expected) {
				reject(StrictPbfFramingFailureReason.MALFORMED_PROTOBUF)
			}
		}
	}
}

/** Explicit, caller-selected cap and format policy for [StrictPbfFramingReader]. */
data class StrictPbfFramingLimits(
	val maxBlobHeaderBytes: Int,
	val maxBlobBytes: Int,
	val maxRawBlobBytes: Int,
	val maxBlocks: Long,
	val maxFieldsPerMessage: Int,
	val maxRequiredFeatureCount: Int,
	val maxRequiredFeatureBytes: Int,
	val allowedCompressions: Set<PbfCompression>,
	val supportedRequiredFeatures: Set<String>,
	val requiredHeaderFeatures: Set<String>,
) {
	internal val supportedRequiredFeatureBytes = supportedRequiredFeatures
		.map { it.toByteArray(StandardCharsets.UTF_8) }
	internal val requiredHeaderFeatureBytes = requiredHeaderFeatures
		.map { it.toByteArray(StandardCharsets.UTF_8) }

	init {
		require(maxBlobHeaderBytes > 0) { "maxBlobHeaderBytes must be positive" }
		require(maxBlobBytes > 0) { "maxBlobBytes must be positive" }
		require(maxRawBlobBytes >= 0) { "maxRawBlobBytes must be non-negative" }
		require(maxBlocks > 0L) { "maxBlocks must be positive" }
		require(maxFieldsPerMessage > 0) { "maxFieldsPerMessage must be positive" }
		require(maxRequiredFeatureCount > 0) { "maxRequiredFeatureCount must be positive" }
		require(maxRequiredFeatureBytes > 0) { "maxRequiredFeatureBytes must be positive" }
		require(allowedCompressions.isNotEmpty()) { "at least one compression must be allowed" }
		require(supportedRequiredFeatures.isNotEmpty()) { "supported required features must be declared" }
		require(requiredHeaderFeatures.isNotEmpty()) { "required header features must be declared" }
		require(requiredHeaderFeatures.all { it in supportedRequiredFeatures }) {
			"required header features must be supported"
		}
		require(supportedRequiredFeatures.size <= maxRequiredFeatureCount) {
			"supported feature policy exceeds maxRequiredFeatureCount"
		}
		require(requiredHeaderFeatures.size <= maxRequiredFeatureCount) {
			"required feature policy exceeds maxRequiredFeatureCount"
		}
		require(supportedRequiredFeatureBytes.all { it.isNotEmpty() && it.size <= maxRequiredFeatureBytes }) {
			"supported feature names must fit the declared byte cap"
		}
	}

	internal fun isSupportedRequiredFeature(bytes: ByteArray, feature: ByteSliceAccess): Boolean {
		return supportedRequiredFeatureBytes.any { expected -> feature.matches(bytes, expected) }
	}

	internal fun markRequiredFeatureIfPresent(
		bytes: ByteArray,
		feature: ByteSliceAccess,
		found: BooleanArray,
	) {
		requiredHeaderFeatureBytes.forEachIndexed { index, expected ->
			if (feature.matches(bytes, expected)) found[index] = true
		}
	}
}

/** Compression payloads the strict reader can explicitly allow. */
enum class PbfCompression {
	RAW,
	ZLIB,
}

/** A validated outer PBF block. `rawBytes` is bounded by the caller's policy. */
data class StrictPbfBlock(
	val type: StrictPbfBlockType,
	val rawBytes: ByteArray,
)

enum class StrictPbfBlockType {
	OSM_HEADER,
	OSM_DATA;

	internal companion object {
		private val headerName = "OSMHeader".toByteArray(StandardCharsets.US_ASCII)
		private val dataName = "OSMData".toByteArray(StandardCharsets.US_ASCII)

		fun fromWireName(bytes: ByteArray, access: ByteSliceAccess): StrictPbfBlockType? {
			return when {
				access.matches(bytes, headerName) -> OSM_HEADER
				access.matches(bytes, dataName) -> OSM_DATA
				else -> null
			}
		}
	}
}

/** Aggregate framing counts; raw data is delivered through the callback. */
data class StrictPbfReadStats(
	val blockCount: Long,
	val dataBlockCount: Long,
)

/** Stable, permanent reasons for rejecting an untrusted PBF outer stream. */
class StrictPbfFramingFailure(
	val reason: StrictPbfFramingFailureReason,
) : PbfIntakeFailure(
	code = PbfIntakeFailureCode.STRICT_PBF_REJECTED,
	message = "PBF framing rejected: ${reason.name}",
)

enum class StrictPbfFramingFailureReason {
	SOURCE_READ_FAILED,
	TRUNCATED_FRAME,
	BLOB_HEADER_TOO_LARGE,
	BLOB_TOO_LARGE,
	RAW_BLOB_TOO_LARGE,
	BLOCK_LIMIT_EXCEEDED,
	FIELD_LIMIT_EXCEEDED,
	MALFORMED_PROTOBUF,
	INVALID_BLOCK_ORDER,
	MISSING_HEADER_BLOCK,
	UNSUPPORTED_BLOCK_TYPE,
	UNSUPPORTED_COMPRESSION,
	MULTIPLE_COMPRESSION_PAYLOADS,
	RAW_SIZE_MISMATCH,
	MALFORMED_COMPRESSED_DATA,
	TRUNCATED_COMPRESSED_DATA,
	TRAILING_COMPRESSED_DATA,
	REQUIRED_FEATURE_LIMIT_EXCEEDED,
	UNSUPPORTED_REQUIRED_FEATURE,
	REQUIRED_FEATURE_MISSING,
}

/**
 * Private bridge allowing policy/type helpers to compare bounded slices without
 * allocating attacker-controlled Strings or copies.
 */
internal interface ByteSliceAccess {
	fun matches(bytes: ByteArray, expected: ByteArray): Boolean
}

private data class ByteSlice(val offset: Int, val length: Int) : ByteSliceAccess {
	override fun matches(bytes: ByteArray, expected: ByteArray): Boolean {
		if (length != expected.size) return false
		for (index in expected.indices) {
			if (bytes[offset + index] != expected[index]) return false
		}
		return true
	}
}

private fun reject(reason: StrictPbfFramingFailureReason): Nothing =
	throw StrictPbfFramingFailure(reason)
