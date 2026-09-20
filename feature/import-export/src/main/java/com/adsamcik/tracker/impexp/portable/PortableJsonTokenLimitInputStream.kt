package com.adsamcik.tracker.impexp.portable

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * UTF-8 JSON lexical guard that rejects oversized tokens before a parser materializes them.
 *
 * JSON structure and escapes are ASCII, so byte-level tracking remains valid while non-ASCII
 * string content is conservatively charged by encoded byte. Every unquoted bare token remains in
 * one bounded literal state, including malformed number suffixes and invalid alphabetic or
 * punctuation literals. Reads are capped at 256 bytes, bounding prefetch past the exact rejection
 * point even when the downstream parser requests a large buffer. Semantic JSON validation remains
 * the caller's responsibility.
 */
internal class PortableJsonTokenLimitInputStream(
	input: InputStream,
	private val limits: PortableJsonTokenLimits = PortableJsonTokenLimits(),
) : FilterInputStream(input) {
	private val containers = IntArray(limits.maxNestingDepth)
	private val objectExpectsName = BooleanArray(limits.maxNestingDepth)
	private val nameBytes = ByteArray(limits.maxNameBytes)
	private var depth = 0
	private var inString = false
	private var stringIsName = false
	private var stringBytes = 0
	private var stringMaximumBytes = limits.maxStringBytes
	private var nameByteCount = 0
	private var rootFieldName: String? = null
	private var escaped = false
	private var inLiteral = false
	private var literalBytes = 0
	private var literalStartedAsNumber = false
	private var literalMaximumBytes = limits.maxLiteralBytes

	override fun read(): Int {
		val value = super.read()
		if (value >= 0) inspect(value)
		return value
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		val boundedLength = min(length, limits.maxReadChunkBytes)
		val count = super.read(buffer, offset, boundedLength)
		for (index in 0 until count.coerceAtLeast(0)) {
			inspect(buffer[offset + index].toInt() and 0xff)
		}
		return count
	}

	private fun inspect(value: Int) {
		if (inString) {
			inspectString(value)
			return
		}
		if (inLiteral) {
			if (!isLiteralDelimiter(value)) {
				recordLiteralByte()
				return
			}
			inLiteral = false
			literalBytes = 0
			literalStartedAsNumber = false
		}
		when (value) {
			QUOTE -> {
				inString = true
				stringIsName = expectsObjectName()
				stringBytes = 0
				stringMaximumBytes = if (!stringIsName && depth == ROOT_OBJECT_DEPTH) {
					limits.rootStringValueMaxBytes[rootFieldName] ?: limits.maxStringBytes
				} else {
					limits.maxStringBytes
				}
				if (stringIsName) {
					nameByteCount = 0
					if (depth == ROOT_OBJECT_DEPTH) rootFieldName = null
				}
				escaped = false
			}
			OBJECT_START -> push(OBJECT)
			ARRAY_START -> push(ARRAY)
			OBJECT_END, ARRAY_END -> if (depth > 0) depth--
			COLON -> if (topContainer() == OBJECT) objectExpectsName[depth - 1] = false
			COMMA -> if (topContainer() == OBJECT) {
				objectExpectsName[depth - 1] = true
				if (depth == ROOT_OBJECT_DEPTH) rootFieldName = null
			}
			else -> if (!isWhitespace(value)) {
				inLiteral = true
				literalBytes = 1
				literalStartedAsNumber = value == MINUS || value in DIGIT_ZERO..DIGIT_NINE
				literalMaximumBytes = if (literalStartedAsNumber) {
					if (depth == ROOT_OBJECT_DEPTH) {
						limits.rootNumberValueMaxBytes[rootFieldName] ?: limits.maxNumberBytes
					} else {
						limits.maxNumberBytes
					}
				} else {
					limits.maxLiteralBytes
				}
			}
		}
	}

	private fun inspectString(value: Int) {
		if (escaped) {
			recordStringByte(value)
			escaped = false
			return
		}
		when (value) {
			ESCAPE -> {
				recordStringByte(value)
				escaped = true
			}
			QUOTE -> {
				inString = false
				if (stringIsName && depth == ROOT_OBJECT_DEPTH) {
					rootFieldName = decodeJsonFieldName(nameBytes, nameByteCount)
				}
				stringBytes = 0
			}
			else -> recordStringByte(value)
		}
	}

	private fun recordStringByte(value: Int) {
		if (stringIsName && nameByteCount < nameBytes.size) {
			nameBytes[nameByteCount++] = value.toByte()
		}
		stringBytes++
		val maximum = if (stringIsName) limits.maxNameBytes else stringMaximumBytes
		if (stringBytes > maximum) {
			throw PortableJsonTokenLimitException(
				if (stringIsName) "JSON field name exceeds its lexical byte bound"
				else "JSON string exceeds its lexical byte bound",
			)
		}
	}

	private fun recordLiteralByte() {
		literalBytes++
		if (literalBytes > literalMaximumBytes) {
			throw PortableJsonTokenLimitException(
				if (literalStartedAsNumber) "JSON number exceeds its lexical byte bound"
				else "JSON bare literal exceeds its lexical byte bound",
			)
		}
	}

	private fun push(container: Int) {
		if (depth >= containers.size) {
			throw PortableJsonTokenLimitException("JSON nesting exceeds its lexical bound")
		}
		containers[depth] = container
		objectExpectsName[depth] = container == OBJECT
		depth++
	}

	private fun expectsObjectName(): Boolean =
		topContainer() == OBJECT && objectExpectsName[depth - 1]

	private fun topContainer(): Int = if (depth == 0) NONE else containers[depth - 1]

	private fun isLiteralDelimiter(value: Int): Boolean =
		isWhitespace(value) ||
			value == QUOTE ||
			value == OBJECT_START ||
			value == OBJECT_END ||
			value == ARRAY_START ||
			value == ARRAY_END ||
			value == COLON ||
			value == COMMA

	private fun isWhitespace(value: Int): Boolean =
		value == SPACE || value == TAB || value == CARRIAGE_RETURN || value == LINE_FEED

	private companion object {
		const val ROOT_OBJECT_DEPTH = 1
		const val NONE = 0
		const val OBJECT = 1
		const val ARRAY = 2
		const val QUOTE = '"'.code
		const val ESCAPE = '\\'.code
		const val OBJECT_START = '{'.code
		const val OBJECT_END = '}'.code
		const val ARRAY_START = '['.code
		const val ARRAY_END = ']'.code
		const val COLON = ':'.code
		const val COMMA = ','.code
		const val MINUS = '-'.code
		const val DIGIT_ZERO = '0'.code
		const val DIGIT_NINE = '9'.code
		const val SPACE = ' '.code
		const val TAB = '\t'.code
		const val CARRIAGE_RETURN = '\r'.code
		const val LINE_FEED = '\n'.code
	}
}

private fun decodeJsonFieldName(bytes: ByteArray, length: Int): String? {
	val encoded = String(bytes, 0, length, Charsets.UTF_8)
	val decoded = StringBuilder(encoded.length)
	var index = 0
	while (index < encoded.length) {
		val character = encoded[index++]
		if (character != '\\') {
			decoded.append(character)
			continue
		}
		if (index >= encoded.length) return null
		when (val escaped = encoded[index++]) {
			'"', '\\', '/' -> decoded.append(escaped)
			'b' -> decoded.append('\b')
			'f' -> decoded.append('\u000c')
			'n' -> decoded.append('\n')
			'r' -> decoded.append('\r')
			't' -> decoded.append('\t')
			'u' -> {
				if (index + JSON_UNICODE_HEX_DIGITS > encoded.length) return null
				val value = encoded.substring(index, index + JSON_UNICODE_HEX_DIGITS)
					.toIntOrNull(16) ?: return null
				decoded.append(value.toChar())
				index += JSON_UNICODE_HEX_DIGITS
			}
			else -> return null
		}
	}
	return decoded.toString()
}

/**
 * Raw lexical limits. Six bytes per decoded character admits fully `\\uXXXX`-escaped valid fields.
 */
internal data class PortableJsonTokenLimits(
	val maxNameBytes: Int = 64 * MAX_ESCAPED_BYTES_PER_CHARACTER,
	val maxStringBytes: Int = 128 * MAX_ESCAPED_BYTES_PER_CHARACTER,
	val maxNumberBytes: Int = 64,
	val maxNestingDepth: Int = 32,
	val maxReadChunkBytes: Int = 256,
	val maxLiteralBytes: Int = 64,
	val rootStringValueMaxBytes: Map<String, Int> = emptyMap(),
	val rootNumberValueMaxBytes: Map<String, Int> = emptyMap(),
) {
	init {
		require(maxNameBytes > 0)
		require(maxStringBytes >= maxNameBytes)
		require(maxNumberBytes > 0)
		require(maxLiteralBytes >= MIN_JSON_KEYWORD_BYTES)
		require(maxNestingDepth > 0)
		require(maxReadChunkBytes > 0)
		require(rootStringValueMaxBytes.values.all { it in 1..maxStringBytes })
		require(rootNumberValueMaxBytes.values.all { it in 1..maxNumberBytes })
	}

	private companion object {
		const val MAX_ESCAPED_BYTES_PER_CHARACTER = 6
		const val MIN_JSON_KEYWORD_BYTES = 5
	}
}

internal fun portableJsonDocumentTokenLimits(encodedByteCount: Int) = PortableJsonTokenLimits(
	maxNameBytes = 384,
	maxStringBytes = encodedByteCount.coerceAtLeast(768),
	maxNumberBytes = 64,
	maxNestingDepth = 32,
)

internal fun portableJsonHeaderTokenLimits(
	encodedByteCount: Int,
	expectedFormat: String,
) = portableJsonDocumentTokenLimits(encodedByteCount).copy(
	rootStringValueMaxBytes = mapOf(
		"format" to expectedFormat.length * JSON_ESCAPE_BYTES_PER_CHARACTER,
	),
	rootNumberValueMaxBytes = mapOf(
		"schemaVersion" to MAX_SCHEMA_VERSION_TOKEN_BYTES,
	),
)

internal class PortableJsonTokenLimitException(
	message: String,
) : IOException(message) {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}

private const val JSON_UNICODE_HEX_DIGITS = 4
private const val JSON_ESCAPE_BYTES_PER_CHARACTER = 6
private const val MAX_SCHEMA_VERSION_TOKEN_BYTES = 10
