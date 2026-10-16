package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV2
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV2

internal fun portableStepsSchemaVersion(bytes: ByteArray): Int =
	portableStepsSchemaVersion(PortableJsonBytes.wrap(bytes))

internal fun portableStepsSchemaVersion(bytes: PortableJsonBytes): Int = schemaVersion(
	bytes,
	StepsPortableFormatV1.FORMAT,
	StepsPortableFormatV1.MAX_FILE_BYTES,
	{ message -> PortableStepsJsonException(message) },
).also { version ->
	if (version !in setOf(StepsPortableFormatV1.SCHEMA_VERSION, StepsPortableFormatV2.SCHEMA_VERSION)) {
		throw PortableStepsJsonException("Unsupported Portable Steps schema version")
	}
}

internal fun portableAmbientStepsSchemaVersion(bytes: ByteArray): Int =
	portableAmbientStepsSchemaVersion(PortableJsonBytes.wrap(bytes))

internal fun portableAmbientStepsSchemaVersion(bytes: PortableJsonBytes): Int = schemaVersion(
	bytes,
	AmbientStepsPortableFormatV1.FORMAT,
	AmbientStepsPortableFormatV1.MAX_FILE_BYTES,
	{ message -> PortableAmbientStepsFormatException(message) },
).also { version ->
	if (version !in setOf(
			AmbientStepsPortableFormatV1.SCHEMA_VERSION,
			AmbientStepsPortableFormatV2.SCHEMA_VERSION,
		)
	) {
		throw PortableAmbientStepsFormatException("Unsupported Ambient Steps schema version")
	}
}

private fun schemaVersion(
	bytes: PortableJsonBytes,
	expectedFormat: String,
	maximumBytes: Long,
	failure: (String) -> Exception,
): Int {
	if (bytes.size == 0 || bytes.size.toLong() > maximumBytes) {
		throw failure("Portable document exceeds its byte bound")
	}
	val header = try {
		PortableRootHeaderScanner(
			bytes = bytes,
			maximumFormatBytes = expectedFormat.length * MAX_JSON_ESCAPE_BYTES_PER_CHARACTER,
		).scan()
	} catch (error: PortableHeaderScanException) {
		throw failure(
			when (error.kind) {
				PortableHeaderScanFailure.BOUND ->
					"Portable header token exceeds its lexical bound"
				PortableHeaderScanFailure.MALFORMED -> "Malformed portable document"
			},
		)
	}
	return header.schemaVersionFor(expectedFormat, failure)
}

private fun PortableRootHeader.schemaVersionFor(
	expectedFormat: String,
	failure: (String) -> Exception,
): Int {
	if (format != expectedFormat) {
		throw failure("Unexpected portable format")
	}
	return schemaVersion ?: throw failure("Missing schema version")
}

private data class PortableRootHeader(
	val format: String?,
	val schemaVersion: Int?,
)

private class PortableRootHeaderScanner(
	bytes: PortableJsonBytes,
	private val maximumFormatBytes: Int,
) {
	val source = bytes.backingArray
	val size = bytes.size
	var index = 0
	private var format: String? = null
	private var schemaVersion: Int? = null
	private var sawFormat = false
	private var sawSchemaVersion = false

	fun scan(): PortableRootHeader {
		skipWhitespace()
		expect(OBJECT_START)
		skipWhitespace()
		if (consume(OBJECT_END)) {
			requireEndOfDocument()
			return PortableRootHeader(format, schemaVersion)
		}
		while (true) {
			readRootMember()
			skipWhitespace()
			when {
				consume(COMMA) -> {
					skipWhitespace()
					if (peek() == OBJECT_END) {
						malformed()
					}
				}
				consume(OBJECT_END) -> break
				else -> malformed()
			}
		}
		requireEndOfDocument()
		return PortableRootHeader(format, schemaVersion)
	}

	private fun readRootMember() {
		if (peek() != QUOTE) {
			malformed()
		}
		val name = readString(MAX_NAME_BYTES, capture = true)
		skipWhitespace()
		expect(COLON)
		skipWhitespace()
		when (name) {
			FORMAT_FIELD -> {
				if (sawFormat) {
					malformed()
				}
				sawFormat = true
				if (peek() != QUOTE) {
					malformed()
				}
				format = readString(maximumFormatBytes, capture = true)
			}
			SCHEMA_VERSION_FIELD -> {
				if (sawSchemaVersion) {
					malformed()
				}
				sawSchemaVersion = true
				schemaVersion = readSchemaVersion()
			}
			else -> skipValue(ROOT_DEPTH)
		}
	}

	private fun skipValue(parentDepth: Int) {
		skipWhitespace()
		when (peek()) {
			QUOTE -> readString(maximumEncodedBytes = null, capture = false)
			OBJECT_START -> skipObject(Math.addExact(parentDepth, 1))
			ARRAY_START -> skipArray(Math.addExact(parentDepth, 1))
			TRUE_START -> readKeyword(TRUE)
			FALSE_START -> readKeyword(FALSE)
			NULL_START -> readKeyword(NULL)
			MINUS -> readNumber(MAX_NUMBER_BYTES)
			in DIGIT_ZERO..DIGIT_NINE -> readNumber(MAX_NUMBER_BYTES)
			else -> malformed()
		}
	}

	private fun skipObject(depth: Int) {
		requireDepth(depth)
		expect(OBJECT_START)
		skipWhitespace()
		if (consume(OBJECT_END)) {
			return
		}
		while (true) {
			if (peek() != QUOTE) {
				malformed()
			}
			readString(MAX_NAME_BYTES, capture = false)
			skipWhitespace()
			expect(COLON)
			skipValue(depth)
			skipWhitespace()
			when {
				consume(COMMA) -> {
					skipWhitespace()
					if (peek() == OBJECT_END) {
						malformed()
					}
				}
				consume(OBJECT_END) -> return
				else -> malformed()
			}
		}
	}

	private fun skipArray(depth: Int) {
		requireDepth(depth)
		expect(ARRAY_START)
		skipWhitespace()
		if (consume(ARRAY_END)) {
			return
		}
		while (true) {
			skipValue(depth)
			skipWhitespace()
			when {
				consume(COMMA) -> {
					skipWhitespace()
					if (peek() == ARRAY_END) {
						malformed()
					}
				}
				consume(ARRAY_END) -> return
				else -> malformed()
			}
		}
	}

	private fun readSchemaVersion(): Int {
		val start = index
		readNumber(MAX_SCHEMA_VERSION_BYTES)
		if (index == start || source[start].toInt() !in DIGIT_ZERO..DIGIT_NINE) {
			malformed()
		}
		if (index - start > 1 && source[start].toInt() == DIGIT_ZERO) {
			malformed()
		}
		var value = 0
		for (position in start until index) {
			val byte = source[position].toInt()
			if (byte !in DIGIT_ZERO..DIGIT_NINE) {
				malformed()
			}
			value = try {
				Math.addExact(Math.multiplyExact(value, DECIMAL_RADIX), byte - DIGIT_ZERO)
			} catch (_: ArithmeticException) {
				bound()
			}
		}
		return value
	}

	private fun readNumber(maximumBytes: Int) {
		val start = index
		if (consume(MINUS)) {
			requireTokenBound(start, maximumBytes)
			if (peek() !in DIGIT_ZERO..DIGIT_NINE) {
				malformed()
			}
		}
		when (peek()) {
			DIGIT_ZERO -> {
				index++
				requireTokenBound(start, maximumBytes)
				if (peek() in DIGIT_ZERO..DIGIT_NINE) {
					malformed()
				}
			}
			in DIGIT_ONE..DIGIT_NINE -> while (peek() in DIGIT_ZERO..DIGIT_NINE) {
				index++
				requireTokenBound(start, maximumBytes)
			}
			else -> malformed()
		}
		readFraction(start, maximumBytes)
		readExponent(start, maximumBytes)
		if (!isValueDelimiter(peek())) {
			malformed()
		}
	}

	private fun readFraction(start: Int, maximumBytes: Int) {
		if (consume(DECIMAL_POINT)) {
			requireTokenBound(start, maximumBytes)
			if (peek() !in DIGIT_ZERO..DIGIT_NINE) {
				malformed()
			}
			while (peek() in DIGIT_ZERO..DIGIT_NINE) {
				index++
				requireTokenBound(start, maximumBytes)
			}
		}
	}

	private fun readExponent(start: Int, maximumBytes: Int) {
		if (peek() == EXPONENT_LOWER || peek() == EXPONENT_UPPER) {
			index++
			requireTokenBound(start, maximumBytes)
			if (peek() == PLUS || peek() == MINUS) {
				index++
				requireTokenBound(start, maximumBytes)
			}
			if (peek() !in DIGIT_ZERO..DIGIT_NINE) {
				malformed()
			}
			while (peek() in DIGIT_ZERO..DIGIT_NINE) {
				index++
				requireTokenBound(start, maximumBytes)
			}
		}
	}

	private fun readKeyword(keyword: ByteArray) {
		if (size - index < keyword.size) {
			malformed()
		}
		keyword.indices.forEach { offset ->
			if (source[index + offset] != keyword[offset]) {
				malformed()
			}
		}
		index += keyword.size
		if (!isValueDelimiter(peek())) {
			malformed()
		}
	}

	private fun readString(
		maximumEncodedBytes: Int?,
		capture: Boolean,
	): String? {
		expect(QUOTE)
		val contentStart = index
		val decoded = if (capture) {
			StringBuilder()
		} else {
			null
		}
		while (true) {
			val value = peek()
			when {
				value < 0 -> malformed()
				value == QUOTE -> {
					index++
					return decoded?.toString()
				}
				value == ESCAPE -> {
					index++
					val escaped = readEscape()
					decoded?.append(escaped)
				}
				value < SPACE -> malformed()
				value < UTF8_NON_ASCII_START -> {
					index++
					decoded?.append(value.toChar())
				}
				else -> {
					val codePoint = readUtf8CodePoint()
					decoded?.appendCodePoint(codePoint)
				}
			}
			requireTokenBound(contentStart, maximumEncodedBytes)
		}
	}

	private fun readEscape(): Char = when (val escaped = next()) {
		QUOTE, ESCAPE, SOLIDUS -> escaped.toChar()
		BACKSPACE_ESCAPE -> '\b'
		FORM_FEED_ESCAPE -> '\u000c'
		NEWLINE_ESCAPE -> '\n'
		CARRIAGE_RETURN_ESCAPE -> '\r'
		TAB_ESCAPE -> '\t'
		UNICODE_ESCAPE -> readUnicodeEscape()
		else -> malformed()
	}

	private fun readUnicodeEscape(): Char {
		var value = 0
		repeat(JSON_UNICODE_HEX_DIGITS) {
			val digit = next()
			value = Math.addExact(Math.multiplyExact(value, HEX_RADIX), digit.hexValue())
		}
		return value.toChar()
	}

	private fun readUtf8CodePoint(): Int {
		val first = next()
		return when (first) {
			in UTF8_TWO_BYTE_MIN..UTF8_TWO_BYTE_MAX ->
				((first and UTF8_TWO_BYTE_LEAD_MASK) shl UTF8_CONTINUATION_BITS) or continuation()
			in UTF8_THREE_BYTE_MIN..UTF8_THREE_BYTE_MAX -> readThreeByteCodePoint(first)
			in UTF8_FOUR_BYTE_MIN..UTF8_FOUR_BYTE_MAX -> readFourByteCodePoint(first)
			else -> malformed()
		}
	}

	private fun readThreeByteCodePoint(first: Int): Int {
		val second = next()
		val overlong = first == UTF8_THREE_BYTE_MIN && second < UTF8_THREE_BYTE_BOUNDARY
		val surrogate = first == UTF8_SURROGATE_LEAD && second >= UTF8_THREE_BYTE_BOUNDARY
		if (second !in UTF8_CONTINUATION_MIN..UTF8_CONTINUATION_MAX || overlong || surrogate) {
			malformed()
		}
		return ((first and UTF8_THREE_BYTE_LEAD_MASK) shl UTF8_TWO_CONTINUATIONS_BITS) or
			((second and UTF8_CONTINUATION_MASK) shl UTF8_CONTINUATION_BITS) or
			continuation()
	}

	private fun readFourByteCodePoint(first: Int): Int {
		val second = next()
		val overlong = first == UTF8_FOUR_BYTE_MIN && second < UTF8_FOUR_BYTE_BOUNDARY
		val outOfRange = first == UTF8_FOUR_BYTE_MAX && second >= UTF8_FOUR_BYTE_BOUNDARY
		if (second !in UTF8_CONTINUATION_MIN..UTF8_CONTINUATION_MAX || overlong || outOfRange) {
			malformed()
		}
		return ((first and UTF8_FOUR_BYTE_LEAD_MASK) shl UTF8_THREE_CONTINUATIONS_BITS) or
			((second and UTF8_CONTINUATION_MASK) shl UTF8_TWO_CONTINUATIONS_BITS) or
			(continuation() shl UTF8_CONTINUATION_BITS) or
			continuation()
	}


}

private enum class PortableHeaderScanFailure { BOUND, MALFORMED }

private class PortableHeaderScanException(
	val kind: PortableHeaderScanFailure,
) : IllegalArgumentException()

private const val FORMAT_FIELD = "format"
private const val SCHEMA_VERSION_FIELD = "schemaVersion"
private const val MAX_JSON_ESCAPE_BYTES_PER_CHARACTER = 6

private fun isWhitespace(value: Int): Boolean =
	value == SPACE || value == TAB || value == CARRIAGE_RETURN || value == LINE_FEED


private fun isValueDelimiter(value: Int): Boolean =
	value == COMMA ||
		value == OBJECT_END ||
		value == ARRAY_END ||
		value == END_OF_INPUT ||
		isWhitespace(value)


private fun malformed(): Nothing =
	throw PortableHeaderScanException(PortableHeaderScanFailure.MALFORMED)


private fun bound(): Nothing =
	throw PortableHeaderScanException(PortableHeaderScanFailure.BOUND)


private fun PortableRootHeaderScanner.peek(): Int =
	if (index >= size) {
		END_OF_INPUT
	} else {
		source[index].toInt() and BYTE_MASK
	}


private fun PortableRootHeaderScanner.next(): Int {
	val value = peek()
	if (value < 0) {
		malformed()
	}
	index++
	return value
}


private fun PortableRootHeaderScanner.consume(expected: Int): Boolean {
	if (peek() != expected) {
		return false
	}
	index++
	return true
}


private fun PortableRootHeaderScanner.expect(expected: Int) {
	if (!consume(expected)) {
		malformed()
	}
}


private fun PortableRootHeaderScanner.requireEndOfDocument() {
	skipWhitespace()
	if (index != size) {
		malformed()
	}
}


private fun PortableRootHeaderScanner.requireTokenBound(start: Int, maximumBytes: Int?) {
	if (maximumBytes != null && index - start > maximumBytes) {
		bound()
	}
}


private fun PortableRootHeaderScanner.requireDepth(depth: Int) {
	if (depth > MAX_NESTING_DEPTH) {
		bound()
	}
}


private fun PortableRootHeaderScanner.skipWhitespace() {
	while (isWhitespace(peek())) index++
}


private fun Int.hexValue(): Int = when (this) {
	in DIGIT_ZERO..DIGIT_NINE -> this - DIGIT_ZERO
	in LOWER_A..LOWER_F -> this - LOWER_A + DECIMAL_RADIX
	in UPPER_A..UPPER_F -> this - UPPER_A + DECIMAL_RADIX
	else -> malformed()
}


private fun PortableRootHeaderScanner.continuation(): Int {
	val value = next()
	if (value !in UTF8_CONTINUATION_MIN..UTF8_CONTINUATION_MAX) {
		malformed()
	}
	return value and UTF8_CONTINUATION_MASK
}


private const val UTF8_TWO_BYTE_MIN = 0xc2
private const val UTF8_TWO_BYTE_MAX = 0xdf
private const val UTF8_TWO_BYTE_LEAD_MASK = 0x1f
private const val UTF8_THREE_BYTE_MIN = 0xe0
private const val UTF8_THREE_BYTE_MAX = 0xef
private const val UTF8_CONTINUATION_MIN = 0x80
private const val UTF8_CONTINUATION_MAX = 0xbf
private const val UTF8_THREE_BYTE_BOUNDARY = 0xa0
private const val UTF8_SURROGATE_LEAD = 0xed
private const val UTF8_THREE_BYTE_LEAD_MASK = 0x0f
private const val UTF8_CONTINUATION_MASK = 0x3f
private const val UTF8_FOUR_BYTE_MIN = 0xf0
private const val UTF8_FOUR_BYTE_MAX = 0xf4
private const val UTF8_FOUR_BYTE_BOUNDARY = 0x90
private const val UTF8_FOUR_BYTE_LEAD_MASK = 0x07
private const val BYTE_MASK = 0xff
private const val UTF8_CONTINUATION_BITS = 6
private const val UTF8_TWO_CONTINUATIONS_BITS = 12
private const val UTF8_THREE_CONTINUATIONS_BITS = 18
private const val DECIMAL_RADIX = 10
private const val HEX_RADIX = 16
private const val ROOT_DEPTH = 1
private const val MAX_NAME_BYTES = 384
private const val MAX_NESTING_DEPTH = 32
private const val MAX_NUMBER_BYTES = 64
private const val MAX_SCHEMA_VERSION_BYTES = 10
private const val JSON_UNICODE_HEX_DIGITS = 4
private const val END_OF_INPUT = -1
private const val QUOTE = '"'.code
private const val ESCAPE = '\\'.code
private const val SOLIDUS = '/'.code
private const val OBJECT_START = '{'.code
private const val OBJECT_END = '}'.code
private const val ARRAY_START = '['.code
private const val ARRAY_END = ']'.code
private const val COLON = ':'.code
private const val COMMA = ','.code
private const val MINUS = '-'.code
private const val PLUS = '+'.code
private const val DECIMAL_POINT = '.'.code
private const val EXPONENT_LOWER = 'e'.code
private const val EXPONENT_UPPER = 'E'.code
private const val TRUE_START = 't'.code
private const val FALSE_START = 'f'.code
private const val NULL_START = 'n'.code
private const val BACKSPACE_ESCAPE = 'b'.code
private const val FORM_FEED_ESCAPE = 'f'.code
private const val NEWLINE_ESCAPE = 'n'.code
private const val CARRIAGE_RETURN_ESCAPE = 'r'.code
private const val TAB_ESCAPE = 't'.code
private const val UNICODE_ESCAPE = 'u'.code
private const val DIGIT_ZERO = '0'.code
private const val DIGIT_ONE = '1'.code
private const val DIGIT_NINE = '9'.code
private const val LOWER_A = 'a'.code
private const val LOWER_F = 'f'.code
private const val UPPER_A = 'A'.code
private const val UPPER_F = 'F'.code
private const val SPACE = ' '.code
private const val TAB = '\t'.code
private const val CARRIAGE_RETURN = '\r'.code
private const val LINE_FEED = '\n'.code
private const val UTF8_NON_ASCII_START = UTF8_CONTINUATION_MIN
private val TRUE = "true".encodeToByteArray()
private val FALSE = "false".encodeToByteArray()
private val NULL = "null".encodeToByteArray()
