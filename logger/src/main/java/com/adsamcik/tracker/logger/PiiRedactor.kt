package com.adsamcik.tracker.logger

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Centralized PII redaction for all logging paths.
 * Removes coordinate-like patterns from text to prevent location data leaks.
 */
object PiiRedactor {
	private val LAT_LON_PATTERN = Regex(
		"""(?i)(lat(?:itude)?|lon(?:gitude)?|lng|location|coord(?:inate)?|position|LatLng)\s*[=:(]\s*-?\d+\.?\d*"""
	)
	private val COORDINATE_PATTERN = Regex("""(?<!\d)-?\d{1,3}\.\d{5,}""")
	private val LATLNG_TOSTRING = Regex("""(?i)LatLng\([^)]+\)""")
	private val LOCATION_TOSTRING = Regex("""(?i)Location\[.*?lat=.*?\]""")
	private val EMAIL_PATTERN = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
	private val PHONE_LABEL_PATTERN = Regex("""(?i)\b(phone|mobile|tel(?:ephone)?|contact)\s*[=:(]\s*[\d+\s().-]{7,}\d""")
	private val MAC_ADDRESS_PATTERN = Regex("""(?i)\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\b""")
	private const val REDACTED = "[REDACTED]"

	fun redact(text: String): String {
		var result = LATLNG_TOSTRING.replace(text, REDACTED)
		result = LOCATION_TOSTRING.replace(result, REDACTED)
		result = LAT_LON_PATTERN.replace(result, REDACTED)
		result = COORDINATE_PATTERN.replace(result, REDACTED)
		result = EMAIL_PATTERN.replace(result, REDACTED)
		result = PHONE_LABEL_PATTERN.replace(result, REDACTED)
		result = MAC_ADDRESS_PATTERN.replace(result, REDACTED)
		return result
	}

	fun redactThrowable(exception: Throwable): Throwable = Throwable(
		redact(exception.message ?: exception::class.java.simpleName),
		exception.cause?.let { redactThrowable(it) }
	).apply {
		stackTrace = exception.stackTrace
		exception.suppressed.forEach { addSuppressed(redactThrowable(it)) }
	}

	fun redactThrowableToString(exception: Throwable): String {
		val writer = StringWriter()
		exception.printStackTrace(PrintWriter(writer))
		return redact(writer.toString())
	}
}
