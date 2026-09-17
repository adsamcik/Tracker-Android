package com.adsamcik.tracker.diagnostics

/** The complete set of fields the module-owned local adapter may encode. */
enum class TrackingDiagnosticField(val wireName: String) {
	SOURCE("source"),
	PURPOSE("purpose"),
	PIPELINE_STAGE("pipeline_stage"),
	OPERATION("operation"),
	RESULT("result"),
	REASON("reason"),
	LIFECYCLE("lifecycle"),
	SCOPE_EVENT_COUNT_BUCKET("scope_event_count_bucket"),
	SCOPE_DURATION_BUCKET("scope_duration_bucket"),
	ENCODED_ENVELOPE_SIZE_BUCKET("encoded_envelope_size_bucket"),
	QUEUE_BACKLOG_BUCKET("queue_backlog_bucket"),
	DRAINED_ENVELOPE_COUNT_BUCKET("drained_envelope_count_bucket"),
	REMAINING_ENVELOPE_BACKLOG_BUCKET("remaining_envelope_backlog_bucket"),
	PERSISTED_ENVELOPE_COUNT_BUCKET("persisted_envelope_count_bucket"),
}

enum class TrackingDiagnosticPrivacyRejectionReason {
	INVALID_EVENT,
	METRIC_OPERATION_MISMATCH,
	UNKNOWN_FIELD,
	COORDINATES,
	SENSOR_VALUES,
	RADIO_IDENTIFIERS,
	OPAQUE_SELECTIONS,
	FILE_REFERENCES,
	CHECKSUMS,
	STABLE_IDENTIFIERS,
	EXCEPTION_DETAILS,
	PROVIDER_PAYLOADS,
}

sealed interface TrackingDiagnosticPrivacyValidation {
	data object Allowed : TrackingDiagnosticPrivacyValidation

	data class Rejected(
		val reason: TrackingDiagnosticPrivacyRejectionReason,
	) : TrackingDiagnosticPrivacyValidation
}

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
			TrackingDiagnosticPrivacyRejectionReason.EXCEPTION_DETAILS to setOf(
				"exceptionmessage",
				"errormessage",
				"localizedmessage",
				"cause",
				"stacktrace",
				"throwable",
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

	internal fun validate(
		event: RecordedTrackingDiagnosticEvent,
	): TrackingDiagnosticPrivacyValidation = when {
		!event.reason.isCompatibleWith(event.result) ->
			TrackingDiagnosticPrivacyValidation.Rejected(
				TrackingDiagnosticPrivacyRejectionReason.INVALID_EVENT,
			)
		event.metrics != TrackingDiagnosticMetricPolicy.allowedMetrics(
			event.source,
			event.pipelineStage,
			event.operation,
		) &&
			event.metrics.isNotEmpty() ->
			TrackingDiagnosticPrivacyValidation.Rejected(
				TrackingDiagnosticPrivacyRejectionReason.METRIC_OPERATION_MISMATCH,
			)
		else -> TrackingDiagnosticPrivacyValidation.Allowed
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
