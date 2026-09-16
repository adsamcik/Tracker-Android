package com.adsamcik.tracker.diagnostics

/** The complete set of fields a local Tracebox adapter may encode. */
enum class TrackingDiagnosticField(val wireName: String) {
	SOURCE("source"),
	PURPOSE("purpose"),
	PIPELINE_STAGE("pipeline_stage"),
	OPERATION("operation"),
	RESULT("result"),
	REASON("reason"),
	CORRELATION_TOKEN("correlation_token"),
	COUNT_BUCKET("count_bucket"),
	DURATION_BUCKET("duration_bucket"),
	BACKLOG_BUCKET("backlog_bucket"),
	SIZE_BUCKET("size_bucket"),
}

enum class TrackingDiagnosticPrivacyRejectionReason {
	INVALID_EVENT,
	UNKNOWN_FIELD,
	COORDINATES,
	SENSOR_VALUES,
	RADIO_IDENTIFIERS,
	OPAQUE_SELECTIONS,
	FILE_REFERENCES,
	CHECKSUMS,
	STABLE_IDENTIFIERS,
	PROVIDER_PAYLOADS,
}

sealed interface TrackingDiagnosticPrivacyValidation {
	data object Allowed : TrackingDiagnosticPrivacyValidation

	data class Rejected(
		val reason: TrackingDiagnosticPrivacyRejectionReason,
	) : TrackingDiagnosticPrivacyValidation
}

/**
 * Privacy gate for the fixed event and any future local Tracebox adapter schema.
 *
 * Adapter schemas are allowlist-only. Forbidden aliases are classified explicitly so a review
 * cannot mistake an unknown coordinate, sensor, radio, selection, file, checksum, identity, or
 * provider-payload field for a harmless extension.
 */
object TrackingDiagnosticPrivacyValidator {
	val allowedFields: Set<TrackingDiagnosticField> = TrackingDiagnosticField.entries.toSet()

	private val allowedWireNames = allowedFields.mapTo(mutableSetOf()) { field -> field.wireName }

	private val forbiddenAliases =
		listOf(
			TrackingDiagnosticPrivacyRejectionReason.COORDINATES to setOf(
				"latitude",
				"longitude",
				"coordinate",
				"altitude",
			),
			TrackingDiagnosticPrivacyRejectionReason.SENSOR_VALUES to setOf(
				"sensorvalue",
				"pressurehpa",
				"pressurevalue",
				"stepcount",
				"stepvalue",
				"stepdelta",
				"activitytype",
				"activityconfidence",
				"acceleration",
				"gyroscope",
				"magnetometer",
				"barometer",
				"speedmeters",
				"bearingdegrees",
				"rssi",
				"rsrp",
				"rsrq",
				"sinr",
			),
			TrackingDiagnosticPrivacyRejectionReason.RADIO_IDENTIFIERS to setOf(
				"bssid",
				"ssid",
				"cellid",
				"networkid",
				"radioid",
				"tac",
				"lac",
				"pci",
				"arfcn",
				"earfcn",
				"nrarfcn",
				"mcc",
				"mnc",
			),
			TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS to setOf(
				"opaqueselection",
				"opaqueid",
				"selectedopaque",
				"selection",
				"selectionid",
				"selectedid",
				"sourceeventid",
				"logicaltrackingid",
				"servicerunid",
				"sessionid",
			),
			TrackingDiagnosticPrivacyRejectionReason.FILE_REFERENCES to setOf(
				"filename",
				"filepath",
				"contenturi",
				"documenturi",
				"uri",
				"path",
			),
			TrackingDiagnosticPrivacyRejectionReason.CHECKSUMS to setOf(
				"checksum",
				"digest",
				"sha256",
				"sha3",
				"hash",
			),
			TrackingDiagnosticPrivacyRejectionReason.STABLE_IDENTIFIERS to setOf(
				"androidid",
				"advertisingid",
				"deviceid",
				"userid",
				"accountid",
				"installationid",
				"serialnumber",
				"imei",
				"imsi",
			),
			TrackingDiagnosticPrivacyRejectionReason.PROVIDER_PAYLOADS to setOf(
				"providerpayload",
				"rawpayload",
				"payload",
				"freeform",
				"attributes",
				"extras",
				"metadata",
				"providervalue",
			),
		)

	fun validate(event: TrackingDiagnosticEvent): TrackingDiagnosticPrivacyValidation =
		if (event.reason.isCompatibleWith(event.result)) {
			TrackingDiagnosticPrivacyValidation.Allowed
		} else {
			TrackingDiagnosticPrivacyValidation.Rejected(
				TrackingDiagnosticPrivacyRejectionReason.INVALID_EVENT,
			)
		}

	/**
	 * Validates developer-authored schema keys only. Runtime values and arbitrary attribute maps are
	 * intentionally not accepted by this API in any build type.
	 */
	fun validateAdapterSchema(fieldNames: Iterable<String>): TrackingDiagnosticPrivacyValidation {
		fieldNames.forEach { fieldName ->
			val normalized = fieldName.trim().lowercase()
			if (normalized in allowedWireNames) return@forEach

			val compact = normalized.filter(Char::isLetterOrDigit)
			val forbiddenReason = forbiddenAliases.firstNotNullOfOrNull { (reason, aliases) ->
				reason.takeIf { aliases.any(compact::contains) }
			}
			return TrackingDiagnosticPrivacyValidation.Rejected(
				forbiddenReason ?: TrackingDiagnosticPrivacyRejectionReason.UNKNOWN_FIELD,
			)
		}
		return TrackingDiagnosticPrivacyValidation.Allowed
	}
}
