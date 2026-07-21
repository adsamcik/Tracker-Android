package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationDecision
import com.adsamcik.tracker.stats.api.signal.LocationDecisionSignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.LocationObservationSignal
import com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiNetworkReading
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The result of decoding a row from the pending-signal durable buffer.
 *
 * A failed decode is intentionally not represented by `null`: recovery needs
 * a deterministic classification in order to move a permanently bad row to
 * quarantine instead of acknowledging it as if it had been delivered.
 */
sealed interface PendingSignalDecodeResult {
	data class Valid(val signal: TrackingSignal) : PendingSignalDecodeResult

	data class Malformed(
		val reason: PendingSignalDecodeFailure,
	) : PendingSignalDecodeResult

	data class Unsupported(
		val reason: PendingSignalDecodeFailure,
	) : PendingSignalDecodeResult
}

/** Stable, persisted quarantine reason codes for pending-signal payloads. */
enum class PendingSignalDecodeFailure(val code: String) {
	MISSING_SIGNAL_ID("missing_signal_id"),
	MISSING_PAYLOAD_CHECKSUM("missing_payload_checksum"),
	PAYLOAD_CHECKSUM_MISMATCH("payload_checksum_mismatch"),
	MALFORMED_ENVELOPE("malformed_envelope"),
	MALFORMED_PAYLOAD("malformed_payload"),
	UNSUPPORTED_ENVELOPE_VERSION("unsupported_envelope_version"),
	UNSUPPORTED_SIGNAL_TYPE("unsupported_signal_type"),
	UNSUPPORTED_ACTIVITY_TYPE("unsupported_activity_type"),
	UNSUPPORTED_POLICY_TIER("unsupported_policy_tier"),
	UNSUPPORTED_LOCATION_DECISION("unsupported_location_decision"),
	UNSUPPORTED_WIFI_COORDINATE_PROVENANCE("unsupported_wifi_coordinate_provenance"),
}

/**
 * Lightweight JSON serializer for [TrackingSignal].
 *
 * Uses manual [StringBuilder] for serialization (hot-path friendly, zero
 * reflection) and [JSONObject] for deserialization (cold-path only, runs
 * during flush/recovery). New durable rows use [encode], which places this
 * payload in a versioned envelope and records a checksum in the Room row.
 */
internal object SignalSerializer {
	internal const val LEGACY_ENVELOPE_VERSION = 0
	internal const val CURRENT_ENVELOPE_VERSION = 1
	private const val TRACKING_SIGNAL_TYPE_CODE = "tracking_signal"
	private const val TYPE_FIELD = "type"
	private const val PAYLOAD_FIELD = "payload"
	private const val HEX_DIGITS = "0123456789abcdef"

	/** Metadata written beside an encoded signal in `pending_signal`. */
	data class EncodedSignal(
		val envelopeVersion: Int,
		val payloadJson: String,
		val payloadChecksum: String,
	)

	/**
	 * Encode a current-format durable payload.
	 *
	 * The JSON contains a stable type code; the version and SHA-256 checksum are
	 * stored in dedicated Room columns so they can be inspected and indexed
	 * without parsing the JSON. The checksum covers the exact UTF-8 payload
	 * bytes persisted in [EncodedSignal.payloadJson].
	 */
	fun encode(signal: TrackingSignal): EncodedSignal {
		val payload = serialize(signal)
		val envelope = buildString(payload.length + 48) {
			append("{\"")
			append(TYPE_FIELD)
			append("\":\"")
			append(TRACKING_SIGNAL_TYPE_CODE)
			append("\",\"")
			append(PAYLOAD_FIELD)
			append("\":")
			append(payload)
			append('}')
		}
		return EncodedSignal(
			envelopeVersion = CURRENT_ENVELOPE_VERSION,
			payloadJson = envelope,
			payloadChecksum = payloadChecksum(envelope),
		)
	}

	/** SHA-256 of the exact persisted UTF-8 payload JSON, encoded as lowercase hex. */
	fun payloadChecksum(payloadJson: String): String {
		val digest = MessageDigest.getInstance("SHA-256")
		return buildString(64) {
			digest.digest(payloadJson.toByteArray(Charsets.UTF_8)).forEach { byte ->
				val value = byte.toInt() and 0xff
				append(HEX_DIGITS[value ushr 4])
				append(HEX_DIGITS[value and 0x0f])
			}
		}
	}

	// region Serialization — StringBuilder for minimal allocation
	fun serialize(signal: TrackingSignal): String = buildString(256) {
		append("{\"ts\":")
		append(signal.timestampMs.raw)
		append(",\"ern\":")
		append(signal.elapsedRealtimeNanos)
		signal.clockDomainId?.takeIf(String::isNotBlank)?.let { domainId ->
			append(",\"cd\":\"")
			appendJsonEscaped(domainId)
			append('"')
		}

		signal.locationObservation?.let { observation ->
			append(",\"obs\":{\"disp\":\"")
			appendJsonEscaped(observation.ingressDisposition)
			append('"')
			observation.rawFixTimeMs?.let { rawTime ->
				append(",\"rawTs\":")
				append(rawTime)
			}
			observation.coordinate?.let { coordinate ->
				append(",\"lat\":")
				append(coordinate.lat.raw)
				append(",\"lon\":")
				append(coordinate.lon.raw)
			}
			observation.horizontalAccuracyM?.let { append(",\"hAcc\":"); append(it) }
			observation.altitudeM?.let { append(",\"alt\":"); append(it) }
			observation.verticalAccuracyM?.let { append(",\"vAcc\":"); append(it) }
			observation.speedMps?.let { append(",\"spd\":"); append(it) }
			observation.speedAccuracyMps?.let { append(",\"sAcc\":"); append(it) }
			append(",\"prov\":\"")
			appendJsonEscaped(observation.provider)
			append('"')
			if (observation.receivedAtMs > 0L) {
				append(",\"recvAt\":")
				append(observation.receivedAtMs)
			}
			if (observation.receivedElapsedRealtimeNanos > 0L) {
				append(",\"recv\":")
				append(observation.receivedElapsedRealtimeNanos)
			}
			if (observation.acquisitionMode != "UNKNOWN") {
				append(",\"acq\":\"")
				appendJsonEscaped(observation.acquisitionMode)
				append('"')
			}
			if (observation.requestPriority != "UNKNOWN") {
				append(",\"pri\":\"")
				appendJsonEscaped(observation.requestPriority)
				append('"')
			}
			if (observation.permissionPrecision != "UNKNOWN") {
				append(",\"perm\":\"")
				appendJsonEscaped(observation.permissionPrecision)
				append('"')
			}
			if (observation.batchIndex != 0) {
				append(",\"bi\":")
				append(observation.batchIndex)
			}
			if (observation.batchSize != 1) {
				append(",\"bs\":")
				append(observation.batchSize)
			}
			if (observation.isMock) append(",\"mock\":true")
			observation.callbackId?.takeIf(String::isNotBlank)?.let { callbackId ->
				append(",\"cb\":\"")
				appendJsonEscaped(callbackId)
				append('"')
			}
			observation.sourceEventId?.takeIf(String::isNotBlank)?.let { eventId ->
				append(",\"eid\":\"")
				appendJsonEscaped(eventId)
				append('"')
			}
			append('}')
		}

		signal.location?.let { loc ->
			append(",\"loc\":{\"lat\":")
			append(loc.coordinate.lat.raw)
			append(",\"lon\":")
			append(loc.coordinate.lon.raw)
			append(",\"hAcc\":")
			append(loc.horizontalAccuracyM)
			loc.speed?.let { append(",\"spd\":"); append(it.raw) }
			loc.altitudeM?.let { append(",\"alt\":"); append(it) }
			loc.rawGpsAltitudeM?.let { append(",\"rAlt\":"); append(it) }
			loc.verticalAccuracyM?.let { append(",\"vAcc\":"); append(it) }
			loc.speedAccuracyMps?.let { append(",\"sAcc\":"); append(it) }
			if (loc.receivedElapsedRealtimeNanos > 0L) {
				append(",\"recv\":")
				append(loc.receivedElapsedRealtimeNanos)
			}
			if (loc.acquisitionMode != "UNKNOWN") {
				append(",\"acq\":\"")
				appendJsonEscaped(loc.acquisitionMode)
				append('"')
			}
			if (loc.requestPriority != "UNKNOWN") {
				append(",\"pri\":\"")
				appendJsonEscaped(loc.requestPriority)
				append('"')
			}
			if (loc.permissionPrecision != "UNKNOWN") {
				append(",\"perm\":\"")
				appendJsonEscaped(loc.permissionPrecision)
				append('"')
			}
			if (loc.batchIndex != 0) {
				append(",\"bi\":")
				append(loc.batchIndex)
			}
			if (loc.batchSize != 1) {
				append(",\"bs\":")
				append(loc.batchSize)
			}
			if (loc.isMock) append(",\"mock\":true")
			loc.sourceEventId?.takeIf(String::isNotBlank)?.let { eventId ->
				append(",\"eid\":\"")
				appendJsonEscaped(eventId)
				append('"')
			}
			append(",\"prov\":\"")
			appendJsonEscaped(loc.provider)
			append("\"}")
		}

			signal.locationDecision?.let { decision ->
				append(",\"dec\":{\"eid\":\"")
				appendJsonEscaped(decision.sourceEventId)
				append("\",\"out\":\"")
				append(decision.decision.stableCode())
			append('"')
			decision.reason?.let { reason ->
				append(",\"reason\":\"")
				appendJsonEscaped(reason)
				append('"')
			}
			append('}')
		}

		signal.activity?.let { act ->
			append(",\"act\":{\"t\":\"")
			append(act.type.stableCode())
			append("\"")
			append(",\"c\":")
			append(act.confidence.raw)
			append('}')
			append(",\"af\":")
			append(signal.activityFresh)
		}

		signal.steps?.let { s ->
			append(",\"stp\":{\"d\":")
			append(s.stepDelta.raw)
			append(",\"tot\":")
			append(s.totalStepsSinceBoot)
			append(",\"vs\":")
			append(s.sensorValueStart)
			append(",\"ve\":")
			append(s.sensorValueEnd)
			append(",\"r\":")
			append(s.sensorReset)
			append('}')
		}

		signal.cells?.let { c ->
			append(",\"cel\":[")
			c.towers.forEachIndexed { i, t ->
				if (i > 0) append(',')
				append("{\"id\":")
				append(t.cellId)
				append(",\"mcc\":\"")
				appendJsonEscaped(t.mcc)
				append("\",\"mnc\":\"")
				appendJsonEscaped(t.mnc)
				append("\",\"net\":")
				append(t.networkType)
				append(",\"sig\":")
				append(t.signalStrength)
				append(",\"lac\":")
				append(t.areaCode)
				append('}')
			}
			append(']')
		}

		signal.wifi?.let { w ->
			append(",\"wfi\":[")
			w.networks.forEachIndexed { i, n ->
				if (i > 0) append(',')
				append("{\"b\":\"")
				appendJsonEscaped(n.bssid)
				append("\",\"s\":\"")
				appendJsonEscaped(n.ssid)
				append("\",\"cap\":\"")
				appendJsonEscaped(n.capabilities)
				append("\",\"f\":")
				append(n.frequency)
				append(",\"l\":")
				append(n.level)
				append('}')
			}
			append(']')
			w.timestampMs?.let {
				append(",\"wft\":")
				append(it.raw)
			}
			w.coordinate?.let { coordinate ->
				append(",\"wfc\":{\"lat\":")
				append(coordinate.lat.raw)
				append(",\"lon\":")
				append(coordinate.lon.raw)
				append('}')
			}
			if (w.coordinateProvenance != ObservationCoordinateProvenance.UNKNOWN) {
				append(",\"wfp\":\"")
				append(w.coordinateProvenance.stableCode())
				append('"')
			}
		}

		signal.pressure?.let { p ->
			append(",\"prs\":{\"hPa\":")
			append(p.pressureHpa)
			append(",\"alt\":")
			append(p.altitudeM)
			append('}')
		}

		signal.policy?.let { pol ->
			append(",\"pol\":{\"tier\":\"")
			append(pol.tier.stableCode())
			append('"')
			pol.policyName?.let {
				append(",\"name\":\"")
				appendJsonEscaped(it)
				append('"')
			}
			append('}')
		}

		append('}')
	}
	// endregion

	// region Deserialization — JSONObject for simplicity (cold path only)
	/**
	 * Decode a row using its persisted envelope metadata.
	 *
	 * Version zero is the pre-envelope format. It deliberately has no checksum
	 * because old rows did not record one; all new writes use version one.
	 */
	fun decode(
		envelopeVersion: Int,
		payloadJson: String,
		payloadChecksum: String?,
	): PendingSignalDecodeResult = when (envelopeVersion) {
		LEGACY_ENVELOPE_VERSION -> decodeLegacyPayload(payloadJson)
		CURRENT_ENVELOPE_VERSION -> {
			if (payloadChecksum.isNullOrBlank()) {
				PendingSignalDecodeResult.Malformed(
					PendingSignalDecodeFailure.MISSING_PAYLOAD_CHECKSUM,
				)
			} else if (payloadChecksum(payloadJson) != payloadChecksum) {
				PendingSignalDecodeResult.Malformed(
					PendingSignalDecodeFailure.PAYLOAD_CHECKSUM_MISMATCH,
				)
			} else {
				decodeCurrentEnvelope(payloadJson)
			}
		}
		else -> PendingSignalDecodeResult.Unsupported(
			PendingSignalDecodeFailure.UNSUPPORTED_ENVELOPE_VERSION,
		)
	}

	/**
	 * Compatibility convenience for callers that only have JSON. Production WAL
	 * recovery must call [decode] so it verifies the checksum and version.
	 */
	fun deserialize(json: String): TrackingSignal? = when (val result = decodeUnverifiedJson(json)) {
		is PendingSignalDecodeResult.Valid -> result.signal
		else -> null
	}

	private fun decodeLegacyPayload(payloadJson: String): PendingSignalDecodeResult = try {
		PendingSignalDecodeResult.Valid(
			JSONObject(payloadJson).toTrackingSignal(strictCodes = false),
		)
	} catch (@Suppress("TooGenericExceptionCaught") e: UnsupportedSignalEncodingException) {
		PendingSignalDecodeResult.Unsupported(e.reason)
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		PendingSignalDecodeResult.Malformed(PendingSignalDecodeFailure.MALFORMED_PAYLOAD)
	}

	private fun decodeUnverifiedJson(payloadJson: String): PendingSignalDecodeResult = try {
		val objectValue = JSONObject(payloadJson)
		if (objectValue.has(TYPE_FIELD) || objectValue.has(PAYLOAD_FIELD)) {
			decodeEnvelopeObject(objectValue)
		} else {
			PendingSignalDecodeResult.Valid(objectValue.toTrackingSignal(strictCodes = false))
		}
	} catch (@Suppress("TooGenericExceptionCaught") e: UnsupportedSignalEncodingException) {
		PendingSignalDecodeResult.Unsupported(e.reason)
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		PendingSignalDecodeResult.Malformed(PendingSignalDecodeFailure.MALFORMED_PAYLOAD)
	}

	private fun decodeCurrentEnvelope(payloadJson: String): PendingSignalDecodeResult = try {
		decodeEnvelopeObject(JSONObject(payloadJson))
	} catch (@Suppress("TooGenericExceptionCaught") e: UnsupportedSignalEncodingException) {
		PendingSignalDecodeResult.Unsupported(e.reason)
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		PendingSignalDecodeResult.Malformed(PendingSignalDecodeFailure.MALFORMED_ENVELOPE)
	}

	private fun decodeEnvelopeObject(envelope: JSONObject): PendingSignalDecodeResult {
		val typeCode = envelope.opt(TYPE_FIELD) as? String
			?: return PendingSignalDecodeResult.Malformed(PendingSignalDecodeFailure.MALFORMED_ENVELOPE)
		if (typeCode != TRACKING_SIGNAL_TYPE_CODE) {
			return PendingSignalDecodeResult.Unsupported(
				PendingSignalDecodeFailure.UNSUPPORTED_SIGNAL_TYPE,
			)
		}
		val payload = envelope.opt(PAYLOAD_FIELD) as? JSONObject
			?: return PendingSignalDecodeResult.Malformed(PendingSignalDecodeFailure.MALFORMED_ENVELOPE)
		return try {
			PendingSignalDecodeResult.Valid(payload.toTrackingSignal(strictCodes = true))
		} catch (e: UnsupportedSignalEncodingException) {
			PendingSignalDecodeResult.Unsupported(e.reason)
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			PendingSignalDecodeResult.Malformed(PendingSignalDecodeFailure.MALFORMED_PAYLOAD)
		}
	}

	private fun JSONObject.toTrackingSignal(strictCodes: Boolean) = TrackingSignal(
		timestampMs = EpochMs(getLong("ts")),
		elapsedRealtimeNanos = optLong("ern", 0L),
		clockDomainId = optString("cd", "").takeIf(String::isNotBlank),
		locationObservation = optJSONObject("obs")?.toLocationObservationSignal(),
		location = optJSONObject("loc")?.toLocationSignal(),
			locationDecision = optJSONObject("dec")?.toLocationDecisionSignal(strictCodes),
		activity = optJSONObject("act")?.toActivitySignal(strictCodes),
		activityFresh = optBoolean("af", has("act")),
		steps = optJSONObject("stp")?.toStepSignal(),
		cells = optJSONArray("cel")?.toCellSignal(),
		wifi = toWifiSignal(strictCodes),
		pressure = optJSONObject("prs")?.toPressureSignal(),
		policy = optJSONObject("pol")?.toPolicySignal(strictCodes),
	)
	// endregion

	// region JSONObject → Signal helpers
	private fun JSONObject.toLocationSignal() = LocationSignal(
		coordinate = CoordinateE7(
			lat = LatE7(getInt("lat")),
			lon = LonE7(getInt("lon")),
		),
		horizontalAccuracyM = getDouble("hAcc").toFloat(),
		speed = if (has("spd")) SpeedMps(getDouble("spd").toFloat()) else null,
		altitudeM = if (has("alt")) getDouble("alt").toFloat() else null,
		rawGpsAltitudeM = if (has("rAlt")) getDouble("rAlt").toFloat() else null,
		verticalAccuracyM = if (has("vAcc")) getDouble("vAcc").toFloat() else null,
		speedAccuracyMps = if (has("sAcc")) getDouble("sAcc").toFloat() else null,
		provider = optString("prov", "fused"),
		receivedElapsedRealtimeNanos = optLong("recv", 0L),
		acquisitionMode = optString("acq", "UNKNOWN"),
		requestPriority = optString("pri", "UNKNOWN"),
		permissionPrecision = optString("perm", "UNKNOWN"),
		batchIndex = optInt("bi", 0),
		batchSize = optInt("bs", 1).coerceAtLeast(1),
		isMock = optBoolean("mock", false),
		sourceEventId = optString("eid", "").takeIf(String::isNotBlank),
	)

	private fun JSONObject.toLocationObservationSignal() = LocationObservationSignal(
		rawFixTimeMs = if (has("rawTs")) getLong("rawTs") else null,
		coordinate = if (has("lat") && has("lon")) {
			CoordinateE7(
				lat = LatE7(getInt("lat")),
				lon = LonE7(getInt("lon")),
			)
		} else {
			null
		},
		horizontalAccuracyM = if (has("hAcc")) getDouble("hAcc").toFloat() else null,
		altitudeM = if (has("alt")) getDouble("alt").toFloat() else null,
		verticalAccuracyM = if (has("vAcc")) getDouble("vAcc").toFloat() else null,
		speedMps = if (has("spd")) getDouble("spd").toFloat() else null,
		speedAccuracyMps = if (has("sAcc")) getDouble("sAcc").toFloat() else null,
		provider = optString("prov", "unknown"),
		receivedAtMs = optLong("recvAt", 0L),
		receivedElapsedRealtimeNanos = optLong("recv", 0L),
		acquisitionMode = optString("acq", "UNKNOWN"),
		requestPriority = optString("pri", "UNKNOWN"),
		permissionPrecision = optString("perm", "UNKNOWN"),
		batchIndex = optInt("bi", 0),
		batchSize = optInt("bs", 1).coerceAtLeast(1),
		isMock = optBoolean("mock", false),
		ingressDisposition = optString("disp", "DELIVERED_VALID"),
		callbackId = optString("cb", "").takeIf(String::isNotBlank),
		sourceEventId = optString("eid", "").takeIf(String::isNotBlank),
	)

	private fun JSONObject.toLocationDecisionSignal(strictCodes: Boolean): LocationDecisionSignal {
		val sourceEventId = getString("eid").takeIf(String::isNotBlank)
			?: throw IllegalArgumentException("Location decision is missing source event id")
		val decision = getString("out").toLocationDecision(strictCodes)
		return LocationDecisionSignal(
			sourceEventId = sourceEventId,
			decision = decision,
			reason = optString("reason", "").takeIf(String::isNotBlank),
		)
	}

	private fun JSONObject.toActivitySignal(strictCodes: Boolean): ActivitySignal {
		val encodedType = get("t")
		val type = when {
			encodedType is String -> encodedType.toActivityType(strictCodes)
			!strictCodes && encodedType is Number -> {
				DetectedActivityType.entries.getOrElse(encodedType.toInt()) {
					DetectedActivityType.UNKNOWN
				}
			}
			else -> throw IllegalArgumentException("Activity type must use a stable string code")
		}
		return ActivitySignal(
			type = type,
			confidence = ActivityConfidence(getInt("c")),
		)
	}

	private fun JSONObject.toStepSignal() = StepSignal(
		stepDelta = StepCount(getInt("d")),
		totalStepsSinceBoot = getLong("tot"),
		sensorValueStart = optInt("vs", 0),
		sensorValueEnd = optInt("ve", 0),
		sensorReset = optBoolean("r", false),
	)

	private fun JSONArray.toCellSignal(): CellSignal {
		val towers = (0 until length()).map { i ->
			val t = getJSONObject(i)
			CellTowerReading(
				cellId = t.getLong("id"),
				mcc = t.getString("mcc"),
				mnc = t.getString("mnc"),
				networkType = t.getInt("net"),
				signalStrength = t.getInt("sig"),
				areaCode = t.optInt("lac", 0),
			)
		}
		return CellSignal(towers)
	}

	private fun JSONObject.toWifiSignal(strictCodes: Boolean): WifiSignal? {
		val array = optJSONArray("wfi") ?: return null
		val networks = (0 until array.length()).map { i ->
			val n = array.getJSONObject(i)
			WifiNetworkReading(
				bssid = n.getString("b"),
				ssid = n.getString("s"),
				capabilities = n.getString("cap"),
				frequency = n.getInt("f"),
				level = n.getInt("l"),
			)
		}
		val provenance = when (val encodedProvenance = opt("wfp")) {
			null, JSONObject.NULL -> ObservationCoordinateProvenance.UNKNOWN
			is String -> encodedProvenance.toCoordinateProvenance(strictCodes)
			is Number -> {
				if (!strictCodes) {
					ObservationCoordinateProvenance.entries.getOrElse(encodedProvenance.toInt()) {
						ObservationCoordinateProvenance.UNKNOWN
					}
				} else {
					throw IllegalArgumentException(
						"Wi-Fi coordinate provenance must use a stable string code",
					)
				}
			}
			else -> throw IllegalArgumentException(
				"Wi-Fi coordinate provenance must use a stable string code",
			)
		}
		val coordinate = optJSONObject("wfc")?.let {
			CoordinateE7(
				lat = LatE7(it.getInt("lat")),
				lon = LonE7(it.getInt("lon")),
			)
		}
		return WifiSignal(
			networks = networks,
			timestampMs = if (has("wft")) EpochMs(getLong("wft")) else null,
			coordinate = coordinate,
			coordinateProvenance = provenance,
		)
	}

	private fun JSONObject.toPressureSignal() = PressureSignal(
		pressureHpa = getDouble("hPa").toFloat(),
		altitudeM = getDouble("alt").toFloat(),
	)

	private fun JSONObject.toPolicySignal(strictCodes: Boolean): PolicySignal {
		val encodedTier = get("tier")
		val tier = when {
			encodedTier is String -> encodedTier.toPolicyTier(strictCodes)
			!strictCodes && encodedTier is Number -> {
				PolicyTier.entries.getOrElse(encodedTier.toInt()) { PolicyTier.OFF }
			}
			else -> throw IllegalArgumentException("Policy tier must use a stable string code")
		}
		return PolicySignal(
			tier = tier,
			policyName = if (has("name")) getString("name") else null,
		)
	}
	// endregion

	// region Stable codes
	private fun DetectedActivityType.stableCode(): String = when (this) {
		DetectedActivityType.STILL -> "still"
		DetectedActivityType.WALKING -> "walking"
		DetectedActivityType.RUNNING -> "running"
		DetectedActivityType.ON_BICYCLE -> "on_bicycle"
		DetectedActivityType.IN_VEHICLE -> "in_vehicle"
		DetectedActivityType.ON_FOOT -> "on_foot"
		DetectedActivityType.TILTING -> "tilting"
		DetectedActivityType.UNKNOWN -> "unknown"
	}

	private fun String.toActivityType(strictCodes: Boolean): DetectedActivityType = when (this) {
		"still" -> DetectedActivityType.STILL
		"walking" -> DetectedActivityType.WALKING
		"running" -> DetectedActivityType.RUNNING
		"on_bicycle" -> DetectedActivityType.ON_BICYCLE
		"in_vehicle" -> DetectedActivityType.IN_VEHICLE
		"on_foot" -> DetectedActivityType.ON_FOOT
		"tilting" -> DetectedActivityType.TILTING
		"unknown" -> DetectedActivityType.UNKNOWN
		else -> {
			if (strictCodes) {
				throw UnsupportedSignalEncodingException(
					PendingSignalDecodeFailure.UNSUPPORTED_ACTIVITY_TYPE,
				)
			}
			DetectedActivityType.UNKNOWN
		}
	}

	private fun PolicyTier.stableCode(): String = when (this) {
		PolicyTier.OFF -> "off"
		PolicyTier.AMBIENT -> "ambient"
		PolicyTier.ACTIVE -> "active"
		PolicyTier.PRECISION -> "precision"
	}

	private fun String.toPolicyTier(strictCodes: Boolean): PolicyTier = when (this) {
		"off" -> PolicyTier.OFF
		"ambient" -> PolicyTier.AMBIENT
		"active" -> PolicyTier.ACTIVE
		"precision" -> PolicyTier.PRECISION
		else -> {
			if (strictCodes) {
				throw UnsupportedSignalEncodingException(
					PendingSignalDecodeFailure.UNSUPPORTED_POLICY_TIER,
				)
			}
			PolicyTier.OFF
		}
	}

	private fun LocationDecision.stableCode(): String = when (this) {
		LocationDecision.ACCEPTED -> "accepted"
		LocationDecision.REJECTED -> "rejected"
	}

	private fun String.toLocationDecision(strictCodes: Boolean): LocationDecision = when (this) {
		"accepted" -> LocationDecision.ACCEPTED
		"rejected" -> LocationDecision.REJECTED
		// Version-zero rows used enum names. Keep them readable but never allow
		// a versioned envelope to depend on Kotlin enum spelling.
		"ACCEPTED" if !strictCodes -> LocationDecision.ACCEPTED
		"REJECTED" if !strictCodes -> LocationDecision.REJECTED
		else -> throw UnsupportedSignalEncodingException(
			PendingSignalDecodeFailure.UNSUPPORTED_LOCATION_DECISION,
		)
	}

	private fun ObservationCoordinateProvenance.stableCode(): String = when (this) {
		ObservationCoordinateProvenance.UNKNOWN -> "unknown"
		ObservationCoordinateProvenance.DIRECT -> "direct"
		ObservationCoordinateProvenance.INTERPOLATED -> "interpolated"
	}

	private fun String.toCoordinateProvenance(
		strictCodes: Boolean,
	): ObservationCoordinateProvenance = when (this) {
		"unknown" -> ObservationCoordinateProvenance.UNKNOWN
		"direct" -> ObservationCoordinateProvenance.DIRECT
		"interpolated" -> ObservationCoordinateProvenance.INTERPOLATED
		else -> {
			if (strictCodes) {
				throw UnsupportedSignalEncodingException(
					PendingSignalDecodeFailure.UNSUPPORTED_WIFI_COORDINATE_PROVENANCE,
				)
			}
			ObservationCoordinateProvenance.UNKNOWN
		}
	}

	private class UnsupportedSignalEncodingException(
		val reason: PendingSignalDecodeFailure,
	) : IllegalArgumentException()
	// endregion

	// region String escaping
	private fun StringBuilder.appendJsonEscaped(value: String) {
		for (ch in value) {
			when (ch) {
				'"' -> append("\\\"")
				'\\' -> append("\\\\")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				'\t' -> append("\\t")
				else -> append(ch)
			}
		}
	}
	// endregion
}
