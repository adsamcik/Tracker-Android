package com.adsamcik.tracker.tracker.source.runtime

import android.hardware.Sensor
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import java.security.MessageDigest

/**
 * Issues a non-stable token only from physical provider counter-epoch evidence.
 *
 * Registration generation, QoS, batching, report latency, and configuration fingerprint are
 * deliberately absent, so reconfiguration cannot split one physical counter. Source instance is
 * included because it is the durable boundary for a new boot/deletion-scoped provider identity.
 */
internal object StepsCounterDomainTokenIssuer {
	fun directSensor(
		sensor: Sensor,
		bootClockDomainId: String,
		sourceInstanceId: String,
		counterEpochGeneration: Long,
	): StepsCounterDomainToken? = directSensor(
		bootClockDomainId = bootClockDomainId,
		sourceInstanceId = sourceInstanceId,
		counterEpochGeneration = counterEpochGeneration,
		sensorType = sensor.type,
		sensorStringType = sensor.stringType,
		sensorVendor = sensor.vendor,
		sensorName = sensor.name,
		sensorVersion = sensor.version,
		sensorId = sensor.id,
	)

	internal fun directSensor(
		bootClockDomainId: String,
		sourceInstanceId: String,
		counterEpochGeneration: Long,
		sensorType: Int,
		sensorStringType: String,
		sensorVendor: String,
		sensorName: String,
		sensorVersion: Int,
		sensorId: Int,
	): StepsCounterDomainToken? {
		require(counterEpochGeneration > 0L)
		if (bootClockDomainId.isBlank() ||
			sourceInstanceId.isBlank() ||
			sensorType != Sensor.TYPE_STEP_COUNTER ||
			sensorStringType.isBlank() ||
			sensorVendor.isBlank() ||
			sensorName.isBlank()
		) return null
		return StepsCounterDomainToken.opaque(
			opaqueDigest(
				"android-step-counter-epoch-v1",
				bootClockDomainId,
				sourceInstanceId,
				counterEpochGeneration,
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
