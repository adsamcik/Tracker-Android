package com.adsamcik.tracker.impexp.exporter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ExportLog")
class ExportLogTest {

	@Test
	fun `EXPORT_LOG_SOURCE is export`() {
		EXPORT_LOG_SOURCE shouldBe "export"
	}

	@Test
	fun `EXPORT_LOG_SOURCE is not blank`() {
		EXPORT_LOG_SOURCE.shouldNotBeBlank()
	}
}
