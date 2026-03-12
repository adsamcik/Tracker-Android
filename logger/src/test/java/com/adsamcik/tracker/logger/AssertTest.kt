package com.adsamcik.tracker.logger

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for Assert.kt utility functions.
 *
 * Reporter.report() logs via Log.e() and does not throw. These tests mock
 * Reporter and verify that the correct message is reported on assertion failure.
 */
@DisplayName("Assert")
class AssertTest {

	@BeforeEach
	fun setUp() {
		mockkObject(Reporter)
		every { Reporter.report(any<String>()) } just runs
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Nested
	@DisplayName("assertTrue")
	inner class AssertTrueTests {

		@Test
		fun `does not throw when value is true`() {
			assertTrue(true)
		}

		@Test
		fun `reports when value is false`() {
			assertTrue(false)
			verify { Reporter.report("Assertion failed. Expected true but got false.") }
		}

		@Test
		fun `includes custom message when value is false`() {
			val msgSlot = slot<String>()
			assertTrue(false) { "custom context" }
			verify { Reporter.report(capture(msgSlot)) }
			msgSlot.captured.shouldContain("Expected true but got false.")
			msgSlot.captured.shouldContain("custom context")
		}

		@Test
		fun `does not evaluate lazy message when value is true`() {
			var evaluated = false
			assertTrue(true) {
				evaluated = true
				"should not evaluate"
			}
			evaluated shouldBe false
		}
	}

	@Nested
	@DisplayName("assertFalse")
	inner class AssertFalseTests {

		@Test
		fun `does not throw when value is false`() {
			assertFalse(false)
		}

		@Test
		fun `reports when value is true`() {
			assertFalse(true)
			verify { Reporter.report("Assertion failed. Expected false but got true.") }
		}

		@Test
		fun `includes custom message when value is true`() {
			val msgSlot = slot<String>()
			assertFalse(true) { "detail info" }
			verify { Reporter.report(capture(msgSlot)) }
			msgSlot.captured.shouldContain("Expected false but got true.")
			msgSlot.captured.shouldContain("detail info")
		}

		@Test
		fun `does not evaluate lazy message when value is false`() {
			var evaluated = false
			assertFalse(false) {
				evaluated = true
				"should not evaluate"
			}
			evaluated shouldBe false
		}
	}

	@Nested
	@DisplayName("assertEqual")
	inner class AssertEqualTests {

		@Test
		fun `does not throw when values are equal`() {
			assertEqual(42, 42)
		}

		@Test
		fun `does not throw for equal strings`() {
			assertEqual("hello", "hello")
		}

		@Test
		fun `reports when values differ`() {
			val msgSlot = slot<String>()
			assertEqual(1, 2)
			verify { Reporter.report(capture(msgSlot)) }
			msgSlot.captured.shouldContain("Expected: 1")
			msgSlot.captured.shouldContain("Actual: 2")
		}

		@Test
		fun `includes custom message when values differ`() {
			val msgSlot = slot<String>()
			assertEqual("a", "b") { "string comparison" }
			verify { Reporter.report(capture(msgSlot)) }
			msgSlot.captured.shouldContain("Expected: a")
			msgSlot.captured.shouldContain("Actual: b")
			msgSlot.captured.shouldContain("string comparison")
		}
	}

	@Nested
	@DisplayName("assertMore")
	inner class AssertMoreTests {

		@Test
		fun `does not throw when Long value exceeds threshold`() {
			assertMore(10L, 5L)
		}

		@Test
		fun `reports when Long value equals threshold`() {
			assertMore(5L, 5L)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `reports when Long value is below threshold`() {
			val msgSlot = slot<String>()
			assertMore(3L, 5L)
			verify { Reporter.report(capture(msgSlot)) }
			msgSlot.captured.shouldContain("3")
			msgSlot.captured.shouldContain("5")
		}

		@Test
		fun `does not throw when Int value exceeds threshold`() {
			assertMore(10, 5)
		}

		@Test
		fun `reports when Int value equals threshold`() {
			assertMore(5, 5)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Double value exceeds threshold`() {
			assertMore(10.5, 5.0)
		}

		@Test
		fun `reports when Double value is at threshold`() {
			assertMore(5.0, 5.0)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Float value exceeds threshold`() {
			assertMore(10.5f, 5.0f)
		}

		@Test
		fun `reports when Float value is at threshold`() {
			assertMore(5.0f, 5.0f)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `Long overload includes custom message`() {
			val msgSlot = slot<String>()
			assertMore(1L, 5L) { "custom msg" }
			verify { Reporter.report(capture(msgSlot)) }
			msgSlot.captured.shouldContain("custom msg")
		}
	}

	@Nested
	@DisplayName("assertMoreOrEqual")
	inner class AssertMoreOrEqualTests {

		@Test
		fun `does not throw when Int value equals threshold`() {
			assertMoreOrEqual(5, 5)
		}

		@Test
		fun `does not throw when Int value exceeds threshold`() {
			assertMoreOrEqual(10, 5)
		}

		@Test
		fun `reports when Int value is below threshold`() {
			assertMoreOrEqual(3, 5)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Long value equals threshold`() {
			assertMoreOrEqual(5L, 5L)
		}

		@Test
		fun `reports when Long value is below threshold`() {
			assertMoreOrEqual(3L, 5L)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Double value equals threshold`() {
			assertMoreOrEqual(5.0, 5.0)
		}

		@Test
		fun `does not throw when Float value equals threshold`() {
			assertMoreOrEqual(5.0f, 5.0f)
		}
	}

	@Nested
	@DisplayName("assertLess")
	inner class AssertLessTests {

		@Test
		fun `does not throw when Long value is below threshold`() {
			assertLess(3L, 5L)
		}

		@Test
		fun `reports when Long value equals threshold`() {
			assertLess(5L, 5L)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `reports when Long value exceeds threshold`() {
			assertLess(10L, 5L)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Int value is below threshold`() {
			assertLess(3, 5)
		}

		@Test
		fun `reports when Int value equals threshold`() {
			assertLess(5, 5)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Double value is below threshold`() {
			assertLess(3.0, 5.0)
		}

		@Test
		fun `does not throw when Float value is below threshold`() {
			assertLess(3.0f, 5.0f)
		}
	}

	@Nested
	@DisplayName("assertLessOrEqual")
	inner class AssertLessOrEqualTests {

		@Test
		fun `does not throw when Int value equals threshold`() {
			assertLessOrEqual(5, 5)
		}

		@Test
		fun `does not throw when Int value is below threshold`() {
			assertLessOrEqual(3, 5)
		}

		@Test
		fun `reports when Int value exceeds threshold`() {
			assertLessOrEqual(10, 5)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Long value equals threshold`() {
			assertLessOrEqual(5L, 5L)
		}

		@Test
		fun `reports when Long value exceeds threshold`() {
			assertLessOrEqual(10L, 5L)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Double value equals threshold`() {
			assertLessOrEqual(5.0, 5.0)
		}

		@Test
		fun `does not throw when Float value equals threshold`() {
			assertLessOrEqual(5.0f, 5.0f)
		}

		@Test
		fun `reports when Float value exceeds threshold`() {
			assertLessOrEqual(10.0f, 5.0f)
			verify { Reporter.report(any<String>()) }
		}
	}

	@Nested
	@DisplayName("assertWithin")
	inner class AssertWithinTests {

		@Test
		fun `does not throw when Int value is within bounds`() {
			assertWithin(5, 1, 10)
		}

		@Test
		fun `does not throw when Int value is at lower bound`() {
			assertWithin(1, 1, 10)
		}

		@Test
		fun `does not throw when Int value is at upper bound`() {
			assertWithin(10, 1, 10)
		}

		@Test
		fun `reports when Int value is below lower bound`() {
			val msgSlot = slot<String>()
			assertWithin(0, 1, 10)
			verify { Reporter.report(capture(msgSlot)) }
			msgSlot.captured.shouldContain("not within bounds")
			msgSlot.captured.shouldContain("1..10")
		}

		@Test
		fun `reports when Int value exceeds upper bound`() {
			assertWithin(11, 1, 10)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Long value is within bounds`() {
			assertWithin(5L, 1L, 10L)
		}

		@Test
		fun `reports when Long value is outside bounds`() {
			assertWithin(0L, 1L, 10L)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Double value is within bounds`() {
			assertWithin(5.0, 1.0, 10.0)
		}

		@Test
		fun `reports when Double value is outside bounds`() {
			assertWithin(0.5, 1.0, 10.0)
			verify { Reporter.report(any<String>()) }
		}

		@Test
		fun `does not throw when Float value is within bounds`() {
			assertWithin(5.0f, 1.0f, 10.0f)
		}

		@Test
		fun `reports when Float value is outside bounds`() {
			assertWithin(0.5f, 1.0f, 10.0f)
			verify { Reporter.report(any<String>()) }
		}
	}

	@Nested
	@DisplayName("assertNotNull")
	inner class AssertNotNullTests {

		@Test
		fun `does not throw for non-null value`() {
			assertNotNull("value")
		}

		@Test
		fun `does not throw for non-null object`() {
			assertNotNull(Any())
		}

		@Test
		fun `reports for null value`() {
			assertNotNull(null)
			verify { Reporter.report("Assertion failed. Value is null.") }
		}
	}

	@Nested
	@DisplayName("Message formatting")
	inner class MessageFormatting {

		@Test
		fun `assertEqual formats expected and actual in message`() {
			assertEqual("expected_val", "actual_val")
			verify {
				Reporter.report(
					"Assertion failed. Expected not equal to actual. Expected: expected_val. Actual: actual_val."
				)
			}
		}

		@Test
		fun `assertMore formats value and threshold with lte symbol`() {
			assertMore(3L, 5L)
			verify { Reporter.report("Assertion failed. 3 ≤ 5.") }
		}

		@Test
		fun `assertLess formats value and threshold with gte symbol`() {
			assertLess(5L, 5L)
			verify { Reporter.report("Assertion failed. 5 ≥ 5.") }
		}

		@Test
		fun `assertMoreOrEqual formats value and threshold with lt symbol`() {
			assertMoreOrEqual(3, 5)
			verify { Reporter.report("Assertion failed. 3 < 5.") }
		}

		@Test
		fun `assertLessOrEqual formats value and threshold with gt symbol`() {
			assertLessOrEqual(10, 5)
			verify { Reporter.report("Assertion failed. 10 > 5.") }
		}

		@Test
		fun `assertWithin formats bounds in message`() {
			assertWithin(100, 0, 50)
			verify {
				Reporter.report("Assertion failed. 100 is not within bounds (0..50).")
			}
		}
	}
}
