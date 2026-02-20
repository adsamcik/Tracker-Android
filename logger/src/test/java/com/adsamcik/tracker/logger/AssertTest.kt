package com.adsamcik.tracker.logger

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for Assert.kt utility functions.
 *
 * In DEBUG builds, Reporter.report() throws an Exception, so assertion failures
 * propagate as exceptions. These tests verify that behavior.
 */
@DisplayName("Assert")
class AssertTest {

	@Nested
	@DisplayName("assertTrue")
	inner class AssertTrueTests {

		@Test
		fun `does not throw when value is true`() {
			assertTrue(true)
		}

		@Test
		fun `reports when value is false`() {
			val exception = assertThrows<Exception> {
				assertTrue(false)
			}
			exception.message shouldBe "Assertion failed. Expected true but got false."
		}

		@Test
		fun `includes custom message when value is false`() {
			val exception = assertThrows<Exception> {
				assertTrue(false) { "custom context" }
			}
			exception.message!!.shouldContain("Expected true but got false.")
			exception.message!!.shouldContain("custom context")
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
			val exception = assertThrows<Exception> {
				assertFalse(true)
			}
			exception.message shouldBe "Assertion failed. Expected false but got true."
		}

		@Test
		fun `includes custom message when value is true`() {
			val exception = assertThrows<Exception> {
				assertFalse(true) { "detail info" }
			}
			exception.message!!.shouldContain("Expected false but got true.")
			exception.message!!.shouldContain("detail info")
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
			val exception = assertThrows<Exception> {
				assertEqual(1, 2)
			}
			exception.message!!.shouldContain("Expected: 1")
			exception.message!!.shouldContain("Actual: 2")
		}

		@Test
		fun `includes custom message when values differ`() {
			val exception = assertThrows<Exception> {
				assertEqual("a", "b") { "string comparison" }
			}
			exception.message!!.shouldContain("Expected: a")
			exception.message!!.shouldContain("Actual: b")
			exception.message!!.shouldContain("string comparison")
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
			assertThrows<Exception> {
				assertMore(5L, 5L)
			}
		}

		@Test
		fun `reports when Long value is below threshold`() {
			val exception = assertThrows<Exception> {
				assertMore(3L, 5L)
			}
			exception.message!!.shouldContain("3")
			exception.message!!.shouldContain("5")
		}

		@Test
		fun `does not throw when Int value exceeds threshold`() {
			assertMore(10, 5)
		}

		@Test
		fun `reports when Int value equals threshold`() {
			assertThrows<Exception> {
				assertMore(5, 5)
			}
		}

		@Test
		fun `does not throw when Double value exceeds threshold`() {
			assertMore(10.5, 5.0)
		}

		@Test
		fun `reports when Double value is at threshold`() {
			assertThrows<Exception> {
				assertMore(5.0, 5.0)
			}
		}

		@Test
		fun `does not throw when Float value exceeds threshold`() {
			assertMore(10.5f, 5.0f)
		}

		@Test
		fun `reports when Float value is at threshold`() {
			assertThrows<Exception> {
				assertMore(5.0f, 5.0f)
			}
		}

		@Test
		fun `Long overload includes custom message`() {
			val exception = assertThrows<Exception> {
				assertMore(1L, 5L) { "custom msg" }
			}
			exception.message!!.shouldContain("custom msg")
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
			assertThrows<Exception> {
				assertMoreOrEqual(3, 5)
			}
		}

		@Test
		fun `does not throw when Long value equals threshold`() {
			assertMoreOrEqual(5L, 5L)
		}

		@Test
		fun `reports when Long value is below threshold`() {
			assertThrows<Exception> {
				assertMoreOrEqual(3L, 5L)
			}
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
			assertThrows<Exception> {
				assertLess(5L, 5L)
			}
		}

		@Test
		fun `reports when Long value exceeds threshold`() {
			assertThrows<Exception> {
				assertLess(10L, 5L)
			}
		}

		@Test
		fun `does not throw when Int value is below threshold`() {
			assertLess(3, 5)
		}

		@Test
		fun `reports when Int value equals threshold`() {
			assertThrows<Exception> {
				assertLess(5, 5)
			}
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
			assertThrows<Exception> {
				assertLessOrEqual(10, 5)
			}
		}

		@Test
		fun `does not throw when Long value equals threshold`() {
			assertLessOrEqual(5L, 5L)
		}

		@Test
		fun `reports when Long value exceeds threshold`() {
			assertThrows<Exception> {
				assertLessOrEqual(10L, 5L)
			}
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
			assertThrows<Exception> {
				assertLessOrEqual(10.0f, 5.0f)
			}
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
			val exception = assertThrows<Exception> {
				assertWithin(0, 1, 10)
			}
			exception.message!!.shouldContain("not within bounds")
			exception.message!!.shouldContain("1..10")
		}

		@Test
		fun `reports when Int value exceeds upper bound`() {
			assertThrows<Exception> {
				assertWithin(11, 1, 10)
			}
		}

		@Test
		fun `does not throw when Long value is within bounds`() {
			assertWithin(5L, 1L, 10L)
		}

		@Test
		fun `reports when Long value is outside bounds`() {
			assertThrows<Exception> {
				assertWithin(0L, 1L, 10L)
			}
		}

		@Test
		fun `does not throw when Double value is within bounds`() {
			assertWithin(5.0, 1.0, 10.0)
		}

		@Test
		fun `reports when Double value is outside bounds`() {
			assertThrows<Exception> {
				assertWithin(0.5, 1.0, 10.0)
			}
		}

		@Test
		fun `does not throw when Float value is within bounds`() {
			assertWithin(5.0f, 1.0f, 10.0f)
		}

		@Test
		fun `reports when Float value is outside bounds`() {
			assertThrows<Exception> {
				assertWithin(0.5f, 1.0f, 10.0f)
			}
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
			val exception = assertThrows<Exception> {
				assertNotNull(null)
			}
			exception.message shouldBe "Assertion failed. Value is null."
		}
	}

	@Nested
	@DisplayName("Message formatting")
	inner class MessageFormatting {

		@Test
		fun `assertEqual formats expected and actual in message`() {
			val exception = assertThrows<Exception> {
				assertEqual("expected_val", "actual_val")
			}
			exception.message shouldBe
				"Assertion failed. Expected not equal to actual. Expected: expected_val. Actual: actual_val."
		}

		@Test
		fun `assertMore formats value and threshold with lte symbol`() {
			val exception = assertThrows<Exception> {
				assertMore(3L, 5L)
			}
			exception.message shouldBe "Assertion failed. 3 ≤ 5."
		}

		@Test
		fun `assertLess formats value and threshold with gte symbol`() {
			val exception = assertThrows<Exception> {
				assertLess(5L, 5L)
			}
			exception.message shouldBe "Assertion failed. 5 ≥ 5."
		}

		@Test
		fun `assertMoreOrEqual formats value and threshold with lt symbol`() {
			val exception = assertThrows<Exception> {
				assertMoreOrEqual(3, 5)
			}
			exception.message shouldBe "Assertion failed. 3 < 5."
		}

		@Test
		fun `assertLessOrEqual formats value and threshold with gt symbol`() {
			val exception = assertThrows<Exception> {
				assertLessOrEqual(10, 5)
			}
			exception.message shouldBe "Assertion failed. 10 > 5."
		}

		@Test
		fun `assertWithin formats bounds in message`() {
			val exception = assertThrows<Exception> {
				assertWithin(100, 0, 50)
			}
			exception.message shouldBe
				"Assertion failed. 100 is not within bounds (0..50)."
		}
	}
}
