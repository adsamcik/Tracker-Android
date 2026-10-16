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
		if (value >= 0) {
			inspect(value)
		}
		return value
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		val boundedLength = min(length, limits.maxReadChunkBytes)
		val count = super.read(buffer, offset, boundedLength)
		for (index in 0 until count.coerceAtLeast(0)) {
			inspect(buffer[offset + index].toInt() and UNSIGNED_BYTE_MASK)
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
		inspectTokenStart(value)
	}

	private fun inspectTokenStart(value: Int) {
		when (value) {
			QUOTE -> startString()
			OBJECT_START -> push(OBJECT)
			ARRAY_START -> push(ARRAY)
			OBJECT_END, ARRAY_END -> if (depth > 0) {
				depth--
			}
			COLON -> setObjectExpectsName(expectsName = false)
			COMMA -> setObjectExpectsName(expectsName = true)
			else -> if (!isWhitespace(value)) {
				startLiteral(value)
			}
		}
	}

	private fun setObjectExpectsName(expectsName: Boolean) {
		if (topContainer() == OBJECT) {
			objectExpectsName[depth - 1] = expectsName
			if (expectsName && depth == ROOT_OBJECT_DEPTH) {
				rootFieldName = null
			}
		}
	}

	private fun startString() {
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
			if (depth == ROOT_OBJECT_DEPTH) {
				rootFieldName = null
			}
		}
		escaped = false
	}

	private fun startLiteral(value: Int) {
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
		val maximum = if (stringIsName) {
			limits.maxNameBytes
		} else {
			stringMaximumBytes
		}
		if (stringBytes > maximum) {
			throw PortableJsonTokenLimitException(
				if (stringIsName) {
					"JSON field name exceeds its lexical byte bound"
				}
				else {
					"JSON string exceeds its lexical byte bound"
				},
			)
		}
	}

	private fun recordLiteralByte() {
		literalBytes++
		if (literalBytes > literalMaximumBytes) {
			throw PortableJsonTokenLimitException(
				if (literalStartedAsNumber) {
					"JSON number exceeds its lexical byte bound"
				}
				else {
					"JSON bare literal exceeds its lexical byte bound"
				},
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

	private fun topContainer(): Int = if (depth == 0) {
		NONE
	} else {
		containers[depth - 1]
	}

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
		const val UNSIGNED_BYTE_MASK = 0xff
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
		if (index >= encoded.length) {
			return null
		}
		val escaped = encoded[index++]
		if (escaped == 'u') {
			val value = decodeJsonUnicodeCodeUnit(encoded, index) ?: return null
			decoded.append(value)
			index += JSON_UNICODE_HEX_DIGITS
		} else {
			decoded.append(decodeJsonSimpleEscape(escaped) ?: return null)
		}
	}
	return decoded.toString()
}

private fun decodeJsonSimpleEscape(escaped: Char): Char? = when (escaped) {
	'"', '\\', '/' -> escaped
	'b' -> '\b'
	'f' -> '\u000c'
	'n' -> '\n'
	'r' -> '\r'
	't' -> '\t'
	else -> null
}

private fun decodeJsonUnicodeCodeUnit(encoded: String, index: Int): Char? {
	if (index + JSON_UNICODE_HEX_DIGITS > encoded.length) {
		return null
	}
	return encoded.substring(index, index + JSON_UNICODE_HEX_DIGITS)
		.toIntOrNull(JSON_HEXADECIMAL_RADIX)?.toChar()
}

/**
 * Raw lexical limits. Six bytes per decoded character admits fully `\\uXXXX`-escaped valid fields.
 */
internal data class PortableJsonTokenLimits(
	val maxNameBytes: Int = DEFAULT_MAX_NAME_CHARACTERS * JSON_ESCAPE_BYTES_PER_CHARACTER,
	val maxStringBytes: Int = DEFAULT_MAX_STRING_CHARACTERS * JSON_ESCAPE_BYTES_PER_CHARACTER,
	val maxNumberBytes: Int = DEFAULT_MAX_NUMBER_BYTES,
	val maxNestingDepth: Int = DEFAULT_MAX_NESTING_DEPTH,
	val maxReadChunkBytes: Int = DEFAULT_MAX_READ_CHUNK_BYTES,
	val maxLiteralBytes: Int = DEFAULT_MAX_LITERAL_BYTES,
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
		const val MIN_JSON_KEYWORD_BYTES = 5
	}
}

internal fun portableJsonDocumentTokenLimits(encodedByteCount: Int) = PortableJsonTokenLimits(
	maxNameBytes = DEFAULT_MAX_NAME_CHARACTERS * JSON_ESCAPE_BYTES_PER_CHARACTER,
	maxStringBytes = encodedByteCount.coerceAtLeast(DEFAULT_MAX_STRING_CHARACTERS * JSON_ESCAPE_BYTES_PER_CHARACTER),
	maxNumberBytes = DEFAULT_MAX_NUMBER_BYTES,
	maxNestingDepth = DEFAULT_MAX_NESTING_DEPTH,
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

private const val JSON_HEXADECIMAL_RADIX = 16
private const val DEFAULT_MAX_NAME_CHARACTERS = 64
private const val DEFAULT_MAX_STRING_CHARACTERS = 128
private const val DEFAULT_MAX_NUMBER_BYTES = 64
private const val DEFAULT_MAX_NESTING_DEPTH = 32
private const val DEFAULT_MAX_READ_CHUNK_BYTES = 256
private const val DEFAULT_MAX_LITERAL_BYTES = 64
