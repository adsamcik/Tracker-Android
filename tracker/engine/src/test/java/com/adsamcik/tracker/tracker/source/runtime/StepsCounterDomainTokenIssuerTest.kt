package com.adsamcik.tracker.tracker.source.runtime

import android.hardware.Sensor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.Test

class StepsCounterDomainTokenIssuerTest {
	@Test
	fun `registration configuration and report latency do not enter one sensor counter epoch`() {
		val first = token()
		val afterRegistrationAndQosChange = token()

		afterRegistrationAndQosChange shouldBe first
	}

	@Test
	fun `boot counter epoch and physical provider changes rotate the token`() {
		token(boot = "boot-b") shouldNotBe token()
		token(vendor = "other-vendor") shouldNotBe token()
		token(name = "other-counter") shouldNotBe token()
	}

	@Test
	fun `provider that cannot prove a physical epoch returns no token`() {
		StepsCounterDomainTokenIssuer.directSensor(
			bootClockDomainId = "boot-a",
			sensorType = Sensor.TYPE_STEP_COUNTER,
			sensorStringType = "",
			sensorVendor = "vendor",
			sensorName = "counter",
			sensorVersion = 1,
			sensorId = 7,
		).shouldBeNull()
	}

	private fun token(
		boot: String = "boot-a",
		vendor: String = "vendor",
		name: String = "counter",
	) = requireNotNull(StepsCounterDomainTokenIssuer.directSensor(
		bootClockDomainId = boot,
		sensorType = Sensor.TYPE_STEP_COUNTER,
		sensorStringType = "android.sensor.step_counter",
		sensorVendor = vendor,
		sensorName = name,
		sensorVersion = 1,
		sensorId = 7,
	))
}
