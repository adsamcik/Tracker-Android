package com.adsamcik.tracker.shared.utils.extension

import androidx.work.Data
import com.adsamcik.tracker.shared.base.logging.ReporterFacade
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WorkExtensions")
class WorkExtensionsTest {

	@BeforeEach
	fun setUp() {
		mockkObject(ReporterFacade)
		every { ReporterFacade.report(any<Throwable>()) } just Runs
	}

	@AfterEach
	fun tearDown() {
		unmockkObject(ReporterFacade)
	}

	@Nested
	@DisplayName("getPositiveLongReportNull")
	inner class GetPositiveLongReportNull {

		@Test
		fun `returns positive value when present`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns 42L

			val result = data.getPositiveLongReportNull("key")

			result shouldBe 42L
		}

		@Test
		fun `returns zero when value is zero`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns 0L

			val result = data.getPositiveLongReportNull("key")

			result shouldBe 0L
		}

		@Test
		fun `returns large positive value`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns Long.MAX_VALUE

			val result = data.getPositiveLongReportNull("key")

			result shouldBe Long.MAX_VALUE
		}

		@Test
		fun `returns null and reports negative value when key exists`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns -5L
			every { data.keyValueMap } returns mapOf("key" to -5L)

			val result = data.getPositiveLongReportNull("key")

			result.shouldBeNull()
			verify {
				ReporterFacade.report(match<IllegalArgumentException> {
					it.message!!.contains("invalid negative value")
				})
			}
		}

		@Test
		fun `returns null and reports missing key`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns -1L
			every { data.keyValueMap } returns emptyMap()

			val result = data.getPositiveLongReportNull("key")

			result.shouldBeNull()
			verify {
				ReporterFacade.report(match<IllegalArgumentException> {
					it.message!!.contains("was not specified")
				})
			}
		}

		@Test
		fun `does not report on valid positive value`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns 100L

			data.getPositiveLongReportNull("key")

			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `does not report on zero value`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns 0L

			data.getPositiveLongReportNull("key")

			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `error message includes key name for missing key`() {
			val data = mockk<Data>()
			every { data.getLong("mySpecialKey", -1) } returns -1L
			every { data.keyValueMap } returns emptyMap()

			data.getPositiveLongReportNull("mySpecialKey")

			verify {
				ReporterFacade.report(match<IllegalArgumentException> {
					it.message!!.contains("mySpecialKey")
				})
			}
		}

		@Test
		fun `error message includes key name and value for negative value`() {
			val data = mockk<Data>()
			every { data.getLong("sessionId", -1) } returns -99L
			every { data.keyValueMap } returns mapOf("sessionId" to -99L)

			data.getPositiveLongReportNull("sessionId")

			verify {
				ReporterFacade.report(match<IllegalArgumentException> {
					it.message!!.contains("sessionId") && it.message!!.contains("-99")
				})
			}
		}
	}

	@Nested
	@DisplayName("getLongReportNull")
	inner class GetLongReportNull {

		@Test
		fun `returns value when key exists`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns mapOf("key" to 42L)
			every { data.getLong("key", 0) } returns 42L

			val result = data.getLongReportNull("key")

			result shouldBe 42L
		}

		@Test
		fun `returns null and reports when key is missing`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns emptyMap()

			val result = data.getLongReportNull("key")

			result.shouldBeNull()
			verify {
				ReporterFacade.report(match<IllegalArgumentException> {
					it.message!!.contains("was not specified")
				})
			}
		}

		@Test
		fun `returns negative value when present`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns mapOf("key" to -10L)
			every { data.getLong("key", 0) } returns -10L

			val result = data.getLongReportNull("key")

			result shouldBe -10L
		}

		@Test
		fun `returns zero when value is zero and key exists`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns mapOf("key" to 0L)
			every { data.getLong("key", 0) } returns 0L

			val result = data.getLongReportNull("key")

			result shouldBe 0L
		}

		@Test
		fun `does not report when key exists`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns mapOf("key" to 1L)
			every { data.getLong("key", 0) } returns 1L

			data.getLongReportNull("key")

			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `error message includes key name`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns emptyMap()

			data.getLongReportNull("tripId")

			verify {
				ReporterFacade.report(match<IllegalArgumentException> {
					it.message!!.contains("tripId")
				})
			}
		}
	}
}
