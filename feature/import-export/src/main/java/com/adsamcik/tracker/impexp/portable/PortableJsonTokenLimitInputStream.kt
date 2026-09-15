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
	private var depth = 0
	private var inString = false
	private var stringIsName = false
	private var stringBytes = 0
	private var escaped = false
	private var inLiteral = false
	private var literalBytes = 0
	private var literalStartedAsNumber = false

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
				escaped = false
			}
			OBJECT_START -> push(OBJECT)
			ARRAY_START -> push(ARRAY)
			OBJECT_END, ARRAY_END -> if (depth > 0) depth--
			COLON -> if (topContainer() == OBJECT) objectExpectsName[depth - 1] = false
			COMMA -> if (topContainer() == OBJECT) objectExpectsName[depth - 1] = true
			else -> if (!isWhitespace(value)) {
				inLiteral = true
				literalBytes = 1
				literalStartedAsNumber = value == MINUS || value in DIGIT_ZERO..DIGIT_NINE
			}
		}
	}

	private fun inspectString(value: Int) {
		if (escaped) {
			recordStringByte()
			escaped = false
			return
		}
		when (value) {
			ESCAPE -> {
				recordStringByte()
				escaped = true
			}
			QUOTE -> {
				inString = false
				stringBytes = 0
			}
			else -> recordStringByte()
		}
	}

	private fun recordStringByte() {
		stringBytes++
		val maximum = if (stringIsName) limits.maxNameBytes else limits.maxStringBytes
		if (stringBytes > maximum) {
			throw PortableJsonTokenLimitException(
				if (stringIsName) "JSON field name exceeds its lexical byte bound"
				else "JSON string exceeds its lexical byte bound",
			)
		}
	}

	private fun recordLiteralByte() {
		literalBytes++
		val maximum = if (literalStartedAsNumber) {
			limits.maxNumberBytes
		} else {
			limits.maxLiteralBytes
		}
		if (literalBytes > maximum) {
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
) {
	init {
		require(maxNameBytes > 0)
		require(maxStringBytes >= maxNameBytes)
		require(maxNumberBytes > 0)
		require(maxLiteralBytes >= MIN_JSON_KEYWORD_BYTES)
		require(maxNestingDepth > 0)
		require(maxReadChunkBytes > 0)
	}

	private companion object {
		const val MAX_ESCAPED_BYTES_PER_CHARACTER = 6
		const val MIN_JSON_KEYWORD_BYTES = 5
	}
}

internal class PortableJsonTokenLimitException(
	message: String,
) : IOException(message) {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}
