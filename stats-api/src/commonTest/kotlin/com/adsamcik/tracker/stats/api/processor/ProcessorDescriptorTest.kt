package com.adsamcik.tracker.stats.api.processor

import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class ProcessorDescriptorTest {

	private fun descriptor(
		id: String = "test-processor",
		requiredTier: PolicyTier = PolicyTier.AMBIENT,
		flushIntervalMs: Long = 30_000L,
		priority: Int = 0,
	) = ProcessorDescriptor(
		id = id,
		requiredTier = requiredTier,
		flushIntervalMs = flushIntervalMs,
		priority = priority,
	)

	// ── default values ───────────────────────────────────────────

	@Test
	fun `default requiredTier is AMBIENT`() {
		val desc = ProcessorDescriptor(id = "p1")
		desc.requiredTier shouldBe PolicyTier.AMBIENT
	}

	@Test
	fun `default flushIntervalMs is 30_000`() {
		val desc = ProcessorDescriptor(id = "p1")
		desc.flushIntervalMs shouldBe 30_000L
	}

	@Test
	fun `default priority is 0`() {
		val desc = ProcessorDescriptor(id = "p1")
		desc.priority shouldBe 0
	}

	// ── copy behavior ────────────────────────────────────────────

	@Test
	fun `copy with modified priority preserves other fields`() {
		val original = descriptor(id = "abc", requiredTier = PolicyTier.ACTIVE, flushIntervalMs = 5_000L, priority = 1)
		val modified = original.copy(priority = 99)
		modified.id shouldBe original.id
		modified.requiredTier shouldBe original.requiredTier
		modified.flushIntervalMs shouldBe original.flushIntervalMs
		modified.priority shouldBe 99
	}

	@Test
	fun `copy with modified id preserves other fields`() {
		val original = descriptor(id = "original", requiredTier = PolicyTier.PRECISION, flushIntervalMs = 10_000L, priority = 5)
		val modified = original.copy(id = "new-id")
		modified.id shouldBe "new-id"
		modified.requiredTier shouldBe original.requiredTier
		modified.flushIntervalMs shouldBe original.flushIntervalMs
		modified.priority shouldBe original.priority
	}

	@Test
	fun `copy with modified requiredTier preserves other fields`() {
		val original = descriptor()
		val modified = original.copy(requiredTier = PolicyTier.PRECISION)
		modified.id shouldBe original.id
		modified.flushIntervalMs shouldBe original.flushIntervalMs
		modified.priority shouldBe original.priority
		modified.requiredTier shouldBe PolicyTier.PRECISION
	}

	// ── data class contract ──────────────────────────────────────

	@Test
	fun `two instances with same values are equal`() {
		val a = descriptor()
		val b = descriptor()
		a shouldBe b
	}

	@Test
	fun `two instances with different id are not equal`() {
		val a = descriptor(id = "alpha")
		val b = descriptor(id = "beta")
		a shouldNotBe b
	}

	@Test
	fun `two instances with different requiredTier are not equal`() {
		val a = descriptor(requiredTier = PolicyTier.AMBIENT)
		val b = descriptor(requiredTier = PolicyTier.ACTIVE)
		a shouldNotBe b
	}

	@Test
	fun `two instances with different flushIntervalMs are not equal`() {
		val a = descriptor(flushIntervalMs = 1_000L)
		val b = descriptor(flushIntervalMs = 60_000L)
		a shouldNotBe b
	}

	@Test
	fun `two instances with different priority are not equal`() {
		val a = descriptor(priority = 0)
		val b = descriptor(priority = 10)
		a shouldNotBe b
	}

	@Test
	fun `equal instances have same hashCode`() {
		val a = descriptor()
		val b = descriptor()
		a.hashCode() shouldBe b.hashCode()
	}

	@Test
	fun `toString contains all field values`() {
		val desc = descriptor(id = "my-proc", requiredTier = PolicyTier.ACTIVE, flushIntervalMs = 15_000L, priority = 3)
		val str = desc.toString()
		str shouldContain "my-proc"
		str shouldContain "ACTIVE"
		str shouldContain "15000"
		str shouldContain "3"
	}
}
