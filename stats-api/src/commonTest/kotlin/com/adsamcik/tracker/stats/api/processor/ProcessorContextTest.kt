package com.adsamcik.tracker.stats.api.processor

import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class ProcessorContextTest {

	private fun context(
		checkpoint: ByteArray? = null,
		startTimestamp: EpochMs = EpochMs(1_000L),
		isResuming: Boolean = false,
		sessionId: Long = 0L,
	) = ProcessorContext(
		checkpoint = checkpoint,
		startTimestamp = startTimestamp,
		isResuming = isResuming,
		sessionId = sessionId,
	)

	// ── equals ───────────────────────────────────────────────────

	@Test
	fun `same checkpoint content produces equal instances`() {
		val a = context(checkpoint = byteArrayOf(1, 2, 3))
		val b = context(checkpoint = byteArrayOf(1, 2, 3))
		a shouldBe b
	}

	@Test
	fun `different checkpoint content produces unequal instances`() {
		val a = context(checkpoint = byteArrayOf(1, 2, 3))
		val b = context(checkpoint = byteArrayOf(4, 5, 6))
		a shouldNotBe b
	}

	@Test
	fun `null checkpoint vs non-null checkpoint are not equal`() {
		val a = context(checkpoint = null)
		val b = context(checkpoint = byteArrayOf(1))
		a shouldNotBe b
	}

	@Test
	fun `both null checkpoints are equal`() {
		val a = context(checkpoint = null)
		val b = context(checkpoint = null)
		a shouldBe b
	}

	@Test
	fun `reflexive equality - same instance`() {
		val a = context(checkpoint = byteArrayOf(9, 8))
		@Suppress("ReplaceCallWithBinaryOperator")
		a.equals(a) shouldBe true
	}

	@Test
	fun `symmetric equality`() {
		val a = context(checkpoint = byteArrayOf(10, 20))
		val b = context(checkpoint = byteArrayOf(10, 20))
		(a == b) shouldBe true
		(b == a) shouldBe true
	}

	@Test
	fun `not equal to object of different type`() {
		val a = context()
		@Suppress("ReplaceCallWithBinaryOperator")
		a.equals("not a ProcessorContext") shouldBe false
	}

	@Test
	fun `not equal to null`() {
		val a = context()
		@Suppress("ReplaceCallWithBinaryOperator")
		a.equals(null) shouldBe false
	}

	// ── hashCode ─────────────────────────────────────────────────

	@Test
	fun `equal objects have same hashCode`() {
		val a = context(checkpoint = byteArrayOf(1, 2, 3))
		val b = context(checkpoint = byteArrayOf(1, 2, 3))
		a.hashCode() shouldBe b.hashCode()
	}

	@Test
	fun `hashCode is stable across multiple calls`() {
		val a = context(checkpoint = byteArrayOf(7))
		val first = a.hashCode()
		val second = a.hashCode()
		first shouldBe second
	}

	@Test
	fun `different checkpoints likely produce different hashCodes`() {
		val a = context(checkpoint = byteArrayOf(1, 2, 3))
		val b = context(checkpoint = byteArrayOf(4, 5, 6))
		a.hashCode() shouldNotBe b.hashCode()
	}

	@Test
	fun `null checkpoint hashCode is deterministic`() {
		val a = context(checkpoint = null)
		val b = context(checkpoint = null)
		a.hashCode() shouldBe b.hashCode()
	}

	// ── field discrimination ─────────────────────────────────────

	@Test
	fun `different sessionId makes instances unequal`() {
		val a = context(sessionId = 1L)
		val b = context(sessionId = 2L)
		a shouldNotBe b
	}

	@Test
	fun `different startTimestamp makes instances unequal`() {
		val a = context(startTimestamp = EpochMs(100L))
		val b = context(startTimestamp = EpochMs(200L))
		a shouldNotBe b
	}

	@Test
	fun `different isResuming makes instances unequal`() {
		val a = context(isResuming = false)
		val b = context(isResuming = true)
		a shouldNotBe b
	}

	@Test
	fun `copy with modified field is not equal to original`() {
		val original = context(checkpoint = byteArrayOf(1, 2), sessionId = 5L)
		val modified = original.copy(sessionId = 99L)
		original shouldNotBe modified
	}

	@Test
	fun `copy preserves unmodified fields`() {
		val original = context(
			checkpoint = byteArrayOf(1, 2),
			startTimestamp = EpochMs(500L),
			isResuming = true,
			sessionId = 42L,
		)
		val modified = original.copy(sessionId = 99L)
		modified.startTimestamp shouldBe original.startTimestamp
		modified.isResuming shouldBe original.isResuming
		modified.checkpoint shouldBe original.checkpoint
	}
}
