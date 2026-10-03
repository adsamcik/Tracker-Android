package com.adsamcik.tracker.impexp.format

import com.adsamcik.tracker.impexp.exporter.Exporter
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Exercises the same public reflection path used after the selected format reaches Export. */
class RegisteredExporterReconstructionTest {

	@ParameterizedTest(name = "{0} can be reconstructed by the export activity")
	@ValueSource(strings = [
		"gpx", "kml", "json", "db", "portable-steps-v1", "portable-ambient-steps-v1",
		"portable-activity-v1", "portable-pressure-v1", "tracker-location",
	])
	fun `registered exporter has a usable public no-argument constructor`(formatId: String) {
		val registered = FormatRegistry.exporterFor(formatId).shouldNotBeNull()
		val reconstructed = registered.javaClass.getConstructor().newInstance() as Exporter

		reconstructed.javaClass shouldBe registered.javaClass
		reconstructed.extension shouldBe registered.extension
		reconstructed.mimeType shouldBe registered.mimeType
		reconstructed.canSelectDateRange shouldBe registered.canSelectDateRange
		reconstructed.requiresLocationData shouldBe registered.requiresLocationData
		reconstructed.containsSensitiveLocationData shouldBe registered.containsSensitiveLocationData
		reconstructed.sensitivityTitleRes shouldBe registered.sensitivityTitleRes
		reconstructed.sensitivityMessageRes shouldBe registered.sensitivityMessageRes
	}
}
