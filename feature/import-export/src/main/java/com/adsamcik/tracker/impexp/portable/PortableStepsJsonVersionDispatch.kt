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
	if (header.format != expectedFormat) throw failure("Unexpected portable format")
	return header.schemaVersion ?: throw failure("Missing schema version")
}

private data class PortableRootHeader(
	val format: String?,
	val schemaVersion: Int?,
)

private class PortableRootHeaderScanner(
	bytes: PortableJsonBytes,
	private val maximumFormatBytes: Int,
) {
	private val source = bytes.backingArray
	private val size = bytes.size
	private var index = 0
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
			if (peek() != QUOTE) malformed()
			val name = readString(MAX_NAME_BYTES, capture = true)
			skipWhitespace()
			expect(COLON)
			skipWhitespace()
			when (name) {
				FORMAT_FIELD -> {
					if (sawFormat) malformed()
					sawFormat = true
					if (peek() != QUOTE) malformed()
					format = readString(maximumFormatBytes, capture = true)
				}
				SCHEMA_VERSION_FIELD -> {
					if (sawSchemaVersion) malformed()
					sawSchemaVersion = true
					schemaVersion = readSchemaVersion()
				}
				else -> skipValue(ROOT_DEPTH)
			}
			skipWhitespace()
			when {
				consume(COMMA) -> {
					skipWhitespace()
					if (peek() == OBJECT_END) malformed()
				}
				consume(OBJECT_END) -> break
				else -> malformed()
			}
		}
		requireEndOfDocument()
		return PortableRootHeader(format, schemaVersion)
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
		if (consume(OBJECT_END)) return
		while (true) {
			if (peek() != QUOTE) malformed()
			readString(MAX_NAME_BYTES, capture = false)
			skipWhitespace()
			expect(COLON)
			skipValue(depth)
			skipWhitespace()
			when {
				consume(COMMA) -> {
					skipWhitespace()
					if (peek() == OBJECT_END) malformed()
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
		if (consume(ARRAY_END)) return
		while (true) {
			skipValue(depth)
			skipWhitespace()
			when {
				consume(COMMA) -> {
					skipWhitespace()
					if (peek() == ARRAY_END) malformed()
				}
				consume(ARRAY_END) -> return
				else -> malformed()
			}
		}
	}

	private fun readSchemaVersion(): Int {
		val start = index
		readNumber(MAX_SCHEMA_VERSION_BYTES)
		if (index == start || source[start].toInt() !in DIGIT_ZERO..DIGIT_NINE) malformed()
		if (index - start > 1 && source[start].toInt() == DIGIT_ZERO) malformed()
		var value = 0
		for (position in start until index) {
			val byte = source[position].toInt()
			if (byte !in DIGIT_ZERO..DIGIT_NINE) malformed()
			value = try {
				Math.addExact(Math.multiplyExact(value, 10), byte - DIGIT_ZERO)
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
			if (peek() !in DIGIT_ZERO..DIGIT_NINE) malformed()
		}
		when (peek()) {
			DIGIT_ZERO -> {
				index++
				requireTokenBound(start, maximumBytes)
				if (peek() in DIGIT_ZERO..DIGIT_NINE) malformed()
			}
			in DIGIT_ONE..DIGIT_NINE -> while (peek() in DIGIT_ZERO..DIGIT_NINE) {
				index++
				requireTokenBound(start, maximumBytes)
			}
			else -> malformed()
		}
		if (consume(DECIMAL_POINT)) {
			requireTokenBound(start, maximumBytes)
			if (peek() !in DIGIT_ZERO..DIGIT_NINE) malformed()
			while (peek() in DIGIT_ZERO..DIGIT_NINE) {
				index++
				requireTokenBound(start, maximumBytes)
			}
		}
		if (peek() == EXPONENT_LOWER || peek() == EXPONENT_UPPER) {
			index++
			requireTokenBound(start, maximumBytes)
			if (peek() == PLUS || peek() == MINUS) {
				index++
				requireTokenBound(start, maximumBytes)
			}
			if (peek() !in DIGIT_ZERO..DIGIT_NINE) malformed()
			while (peek() in DIGIT_ZERO..DIGIT_NINE) {
				index++
				requireTokenBound(start, maximumBytes)
			}
		}
		if (!isValueDelimiter(peek())) malformed()
	}

	private fun readKeyword(keyword: ByteArray) {
		if (size - index < keyword.size) malformed()
		keyword.indices.forEach { offset ->
			if (source[index + offset] != keyword[offset]) malformed()
		}
		index += keyword.size
		if (!isValueDelimiter(peek())) malformed()
	}

	private fun readString(
		maximumEncodedBytes: Int?,
		capture: Boolean,
	): String? {
		expect(QUOTE)
		val contentStart = index
		val decoded = if (capture) StringBuilder() else null
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
					val escaped = next()
					when (escaped) {
						QUOTE, ESCAPE, SOLIDUS -> decoded?.append(escaped.toChar())
						BACKSPACE_ESCAPE -> decoded?.append('\b')
						FORM_FEED_ESCAPE -> decoded?.append('\u000c')
						NEWLINE_ESCAPE -> decoded?.append('\n')
						CARRIAGE_RETURN_ESCAPE -> decoded?.append('\r')
						TAB_ESCAPE -> decoded?.append('\t')
						UNICODE_ESCAPE -> decoded?.append(readUnicodeEscape())
						else -> malformed()
					}
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
			if (maximumEncodedBytes != null && index - contentStart > maximumEncodedBytes) {
				bound()
			}
		}
	}

	private fun readUnicodeEscape(): Char {
		var value = 0
		repeat(JSON_UNICODE_HEX_DIGITS) {
			val digit = next()
			value = Math.addExact(Math.multiplyExact(value, 16), digit.hexValue())
		}
		return value.toChar()
	}

	private fun readUtf8CodePoint(): Int {
		val first = next()
		return when (first) {
			in 0xc2..0xdf ->
				((first and 0x1f) shl 6) or continuation()
			in 0xe0..0xef -> {
				val second = next()
				if (second !in 0x80..0xbf ||
					(first == 0xe0 && second < 0xa0) ||
					(first == 0xed && second >= 0xa0)
				) {
					malformed()
				}
				((first and 0x0f) shl 12) or
					((second and 0x3f) shl 6) or
					continuation()
			}
			in 0xf0..0xf4 -> {
				val second = next()
				if (second !in 0x80..0xbf ||
					(first == 0xf0 && second < 0x90) ||
					(first == 0xf4 && second >= 0x90)
				) {
					malformed()
				}
				((first and 0x07) shl 18) or
					((second and 0x3f) shl 12) or
					(continuation() shl 6) or
					continuation()
			}
			else -> malformed()
		}
	}

	private fun continuation(): Int {
		val value = next()
		if (value !in 0x80..0xbf) malformed()
		return value and 0x3f
	}

	private fun Int.hexValue(): Int = when (this) {
		in DIGIT_ZERO..DIGIT_NINE -> this - DIGIT_ZERO
		in LOWER_A..LOWER_F -> this - LOWER_A + 10
		in UPPER_A..UPPER_F -> this - UPPER_A + 10
		else -> malformed()
	}

	private fun skipWhitespace() {
		while (isWhitespace(peek())) index++
	}

	private fun requireDepth(depth: Int) {
		if (depth > MAX_NESTING_DEPTH) bound()
	}

	private fun requireTokenBound(start: Int, maximumBytes: Int) {
		if (index - start > maximumBytes) bound()
	}

	private fun requireEndOfDocument() {
		skipWhitespace()
		if (index != size) malformed()
	}

	private fun expect(expected: Int) {
		if (!consume(expected)) malformed()
	}

	private fun consume(expected: Int): Boolean {
		if (peek() != expected) return false
		index++
		return true
	}

	private fun next(): Int {
		val value = peek()
		if (value < 0) malformed()
		index++
		return value
	}

	private fun peek(): Int =
		if (index >= size) END_OF_INPUT else source[index].toInt() and 0xff

	private fun isValueDelimiter(value: Int): Boolean =
		value == COMMA ||
			value == OBJECT_END ||
			value == ARRAY_END ||
			value == END_OF_INPUT ||
			isWhitespace(value)

	private fun isWhitespace(value: Int): Boolean =
		value == SPACE || value == TAB || value == CARRIAGE_RETURN || value == LINE_FEED

	private fun malformed(): Nothing =
		throw PortableHeaderScanException(PortableHeaderScanFailure.MALFORMED)

	private fun bound(): Nothing =
		throw PortableHeaderScanException(PortableHeaderScanFailure.BOUND)

	private companion object {
		const val ROOT_DEPTH = 1
		const val MAX_NAME_BYTES = 384
		const val MAX_NESTING_DEPTH = 32
		const val MAX_NUMBER_BYTES = 64
		const val MAX_SCHEMA_VERSION_BYTES = 10
		const val JSON_UNICODE_HEX_DIGITS = 4
		const val END_OF_INPUT = -1
		const val QUOTE = '"'.code
		const val ESCAPE = '\\'.code
		const val SOLIDUS = '/'.code
		const val OBJECT_START = '{'.code
		const val OBJECT_END = '}'.code
		const val ARRAY_START = '['.code
		const val ARRAY_END = ']'.code
		const val COLON = ':'.code
		const val COMMA = ','.code
		const val MINUS = '-'.code
		const val PLUS = '+'.code
		const val DECIMAL_POINT = '.'.code
		const val EXPONENT_LOWER = 'e'.code
		const val EXPONENT_UPPER = 'E'.code
		const val TRUE_START = 't'.code
		const val FALSE_START = 'f'.code
		const val NULL_START = 'n'.code
		const val BACKSPACE_ESCAPE = 'b'.code
		const val FORM_FEED_ESCAPE = 'f'.code
		const val NEWLINE_ESCAPE = 'n'.code
		const val CARRIAGE_RETURN_ESCAPE = 'r'.code
		const val TAB_ESCAPE = 't'.code
		const val UNICODE_ESCAPE = 'u'.code
		const val DIGIT_ZERO = '0'.code
		const val DIGIT_ONE = '1'.code
		const val DIGIT_NINE = '9'.code
		const val LOWER_A = 'a'.code
		const val LOWER_F = 'f'.code
		const val UPPER_A = 'A'.code
		const val UPPER_F = 'F'.code
		const val SPACE = ' '.code
		const val TAB = '\t'.code
		const val CARRIAGE_RETURN = '\r'.code
		const val LINE_FEED = '\n'.code
		const val UTF8_NON_ASCII_START = 0x80
		val TRUE = "true".encodeToByteArray()
		val FALSE = "false".encodeToByteArray()
		val NULL = "null".encodeToByteArray()
	}
}

private enum class PortableHeaderScanFailure { BOUND, MALFORMED }

private class PortableHeaderScanException(
	val kind: PortableHeaderScanFailure,
) : IllegalArgumentException()

private const val FORMAT_FIELD = "format"
private const val SCHEMA_VERSION_FIELD = "schemaVersion"
private const val MAX_JSON_ESCAPE_BYTES_PER_CHARACTER = 6
