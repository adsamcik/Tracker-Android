package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationSignal
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight JSON serializer for [TrackingSignal].
 *
 * Uses manual [StringBuilder] for serialization (hot-path friendly, zero
 * reflection) and [JSONObject] for deserialization (cold-path only, runs
 * during flush/recovery).
 */
internal object SignalSerializer {

	// region Serialization — StringBuilder for minimal allocation
	fun serialize(signal: TrackingSignal): String = buildString(256) {
		append("{\"ts\":")
		append(signal.timestampMs.raw)
		append(",\"ern\":")
		append(signal.elapsedRealtimeNanos)

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
			append(",\"prov\":\"")
			appendJsonEscaped(loc.provider)
			append("\"}")
		}

		signal.activity?.let { act ->
			append(",\"act\":{\"t\":")
			append(act.type.ordinal)
			append(",\"c\":")
			append(act.confidence.raw)
			append('}')
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
		}

		signal.pressure?.let { p ->
			append(",\"prs\":{\"hPa\":")
			append(p.pressureHpa)
			append(",\"alt\":")
			append(p.altitudeM)
			append('}')
		}

		signal.policy?.let { pol ->
			append(",\"pol\":{\"tier\":")
			append(pol.tier.ordinal)
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
	fun deserialize(json: String): TrackingSignal? = try {
		val obj = JSONObject(json)
		TrackingSignal(
			timestampMs = EpochMs(obj.getLong("ts")),
			elapsedRealtimeNanos = obj.optLong("ern", 0L),
			location = obj.optJSONObject("loc")?.toLocationSignal(),
			activity = obj.optJSONObject("act")?.toActivitySignal(),
			steps = obj.optJSONObject("stp")?.toStepSignal(),
			cells = obj.optJSONArray("cel")?.toCellSignal(),
			wifi = obj.optJSONArray("wfi")?.toWifiSignal(),
			pressure = obj.optJSONObject("prs")?.toPressureSignal(),
			policy = obj.optJSONObject("pol")?.toPolicySignal(),
		)
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		// Skip corrupted entries — logged by caller
		null
	}
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
	)

	private fun JSONObject.toActivitySignal(): ActivitySignal {
		val ordinal = getInt("t")
		val entries = DetectedActivityType.entries
		val type = if (ordinal in entries.indices) entries[ordinal] else DetectedActivityType.UNKNOWN
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
			)
		}
		return CellSignal(towers)
	}

	private fun JSONArray.toWifiSignal(): WifiSignal {
		val networks = (0 until length()).map { i ->
			val n = getJSONObject(i)
			WifiNetworkReading(
				bssid = n.getString("b"),
				ssid = n.getString("s"),
				capabilities = n.getString("cap"),
				frequency = n.getInt("f"),
				level = n.getInt("l"),
			)
		}
		return WifiSignal(networks)
	}

	private fun JSONObject.toPressureSignal() = PressureSignal(
		pressureHpa = getDouble("hPa").toFloat(),
		altitudeM = getDouble("alt").toFloat(),
	)

	private fun JSONObject.toPolicySignal(): PolicySignal {
		val ordinal = getInt("tier")
		val entries = PolicyTier.entries
		val tier = if (ordinal in entries.indices) entries[ordinal] else PolicyTier.OFF
		return PolicySignal(
			tier = tier,
			policyName = if (has("name")) getString("name") else null,
		)
	}
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
