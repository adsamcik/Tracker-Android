package com.adsamcik.tracker.tracker.component.producer

import android.hardware.Sensor

internal object SensorBatching {
	internal const val MAX_REPORT_LATENCY_US = 30_000_000

	fun stepCounterMaxReportLatencyUs(sensor: Sensor): Int =
		if (sensor.fifoMaxEventCount > 0) MAX_REPORT_LATENCY_US else 0
}
