package com.adsamcik.tracker.tracker.source.runtime

import android.hardware.Sensor
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import java.security.MessageDigest

/**
 * Issues a non-stable token only from physical provider counter-epoch evidence.
 *
 * Registration generation, source instance, QoS, batching, report latency, and configuration
 * fingerprint are deliberately absent, so reconfiguration cannot split one physical counter.
 */
internal object StepsCounterDomainTokenIssuer {
	fun directSensor(
		sensor: Sensor,
		bootClockDomainId: String,
	): StepsCounterDomainToken? = directSensor(
		bootClockDomainId = bootClockDomainId,
		sensorType = sensor.type,
		sensorStringType = sensor.stringType,
		sensorVendor = sensor.vendor,
		sensorName = sensor.name,
		sensorVersion = sensor.version,
		sensorId = sensor.id,
	)

	internal fun directSensor(
		bootClockDomainId: String,
		sensorType: Int,
		sensorStringType: String,
		sensorVendor: String,
		sensorName: String,
		sensorVersion: Int,
		sensorId: Int,
	): StepsCounterDomainToken? {
		if (bootClockDomainId.isBlank() ||
			sensorType != Sensor.TYPE_STEP_COUNTER ||
			sensorStringType.isBlank() ||
			sensorVendor.isBlank() ||
			sensorName.isBlank()
		) return null
		return StepsCounterDomainToken.opaque(
			opaqueDigest(
				"android-step-counter-epoch-v1",
				bootClockDomainId,
				sensorType,
				sensorStringType,
				sensorVendor,
				sensorName,
				sensorVersion,
				sensorId,
			),
		)
	}

	private fun opaqueDigest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return "sha256:" + MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
}
