package com.adsamcik.tracker.osm.imp

/**
 * Stable, non-sensitive outcomes from an OSM import Worker.
 *
 * These values are safe to put in WorkManager output data. User-facing text
 * is deliberately mapped separately by the Settings UI; callers must never
 * serialize a URI, display name, parser exception, OSM identifier, tag, or
 * input content as an import result.
 */
enum class OsmImportFailureCode {
	/** The release gate rejected the import before any source or database work. */
	PBF_IMPORT_UNAVAILABLE,

	/** Required Worker input was absent or invalid. */
	INVALID_REQUEST,

	/** The gated parser rejected deterministic PBF input. */
	PARSE_FAILED,

	/** The selected source could not be opened or read. */
	SOURCE_UNAVAILABLE,

	/** An unexpected recoverable exception occurred without exposing details. */
	INTERNAL_ERROR,

	/** A legacy or unrecognized WorkManager output code. */
	UNKNOWN;

	companion object {
		fun fromWireValue(value: String?): OsmImportFailureCode =
			entries.firstOrNull { it.name == value } ?: UNKNOWN
	}
}

/** Result of asking the controller to enqueue an offline PBF import. */
sealed interface OsmImportEnqueueResult {
	data object Enqueued : OsmImportEnqueueResult

	data class Rejected(val failureCode: OsmImportFailureCode) : OsmImportEnqueueResult
}
