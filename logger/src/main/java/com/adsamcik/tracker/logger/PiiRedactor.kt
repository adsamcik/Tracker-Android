package com.adsamcik.tracker.logger

/**
 * Centralized PII redaction for all logging paths.
 * Removes coordinate-like patterns from text to prevent location data leaks.
 */
object PiiRedactor {
	private val LAT_LON_PATTERN = Regex(
		"""(?i)(lat(?:itude)?|lon(?:gitude)?|location|coord(?:inate)?|position|LatLng)\s*[=:(]\s*-?\d+\.?\d*"""
	)
	private val COORDINATE_PATTERN = Regex("""(?<!\d)-?\d{1,3}\.\d{5,}""")
	private val LATLNG_TOSTRING = Regex("""(?i)LatLng\([^)]+\)""")
	private val LOCATION_TOSTRING = Regex("""(?i)Location\[.*?lat=.*?\]""")
	private const val REDACTED = "[REDACTED]"

	fun redact(text: String): String {
		var result = LATLNG_TOSTRING.replace(text, REDACTED)
		result = LOCATION_TOSTRING.replace(result, REDACTED)
		result = LAT_LON_PATTERN.replace(result, REDACTED)
		result = COORDINATE_PATTERN.replace(result, REDACTED)
		return result
	}
}
