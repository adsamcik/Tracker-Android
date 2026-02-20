package com.adsamcik.tracker.stats.engine.exploration

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExplorationBloomFilterTest {

	@Test
	fun `added elements should be found`() {
		val bf = ExplorationBloomFilter.create(1000)
		bf.add("cell-abc")
		bf.add("cell-def")
		bf.mightContain("cell-abc").shouldBeTrue()
		bf.mightContain("cell-def").shouldBeTrue()
	}

	@Test
	fun `non-added elements should usually not be found`() {
		val bf = ExplorationBloomFilter.create(1000)
		bf.add("cell-abc")
		// A single negative check is not guaranteed, but with 1000 capacity
		// and 1 element, the probability of false positive is vanishingly small.
		bf.mightContain("cell-xyz").shouldBeFalse()
	}

	@Test
	fun `false positive rate should be near configured rate`() {
		val n = 10_000
		val fpr = 0.01
		val bf = ExplorationBloomFilter.create(n, fpr)
		for (i in 0 until n) {
			bf.add("item-$i")
		}
		// Test with elements that were NOT added
		var falsePositives = 0
		val testCount = 100_000
		for (i in n until n + testCount) {
			if (bf.mightContain("item-$i")) {
				falsePositives++
			}
		}
		val observedFpr = falsePositives.toDouble() / testCount
		// Allow some tolerance: observed FPR should be < 3x configured rate
		observedFpr shouldBeLessThan (fpr * 3.0)
	}

	@Test
	fun `count tracks insertions`() {
		val bf = ExplorationBloomFilter.create(100)
		bf.count shouldBe 0
		bf.add("a")
		bf.count shouldBe 1
		bf.add("b")
		bf.count shouldBe 2
	}

	@Test
	fun `serialization round-trip preserves state`() {
		val bf = ExplorationBloomFilter.create(1000)
		bf.add("cell-1")
		bf.add("cell-2")
		bf.add("cell-3")

		val bytes = bf.serialize()
		val restored = ExplorationBloomFilter.deserialize(bytes)

		restored.mightContain("cell-1").shouldBeTrue()
		restored.mightContain("cell-2").shouldBeTrue()
		restored.mightContain("cell-3").shouldBeTrue()
		restored.mightContain("cell-4").shouldBeFalse()
	}

	@Test
	fun `serialization preserves parameters`() {
		val bf = ExplorationBloomFilter.create(5000, 0.001)
		val bytes = bf.serialize()
		val restored = ExplorationBloomFilter.deserialize(bytes)
		restored.numBits shouldBe bf.numBits
		restored.numHashFunctions shouldBe bf.numHashFunctions
	}

	@Test
	fun `optimal parameters are reasonable`() {
		val m = ExplorationBloomFilter.optimalNumBits(10_000, 0.01)
		val k = ExplorationBloomFilter.optimalNumHashFunctions(10_000, m)
		m shouldBeGreaterThan 0
		k shouldBeGreaterThan 0
		// For n=10000, p=0.01: m ~ 95851, k ~ 7
		(k in 5..10) shouldBe true
	}

	@Test
	fun `create with zero insertions throws`() {
		assertThrows<IllegalArgumentException> {
			ExplorationBloomFilter.create(0)
		}
	}

	@Test
	fun `create with invalid FPR throws`() {
		assertThrows<IllegalArgumentException> {
			ExplorationBloomFilter.create(100, 0.0)
		}
		assertThrows<IllegalArgumentException> {
			ExplorationBloomFilter.create(100, 1.0)
		}
	}

	@Test
	fun `empty filter returns false for any query`() {
		val bf = ExplorationBloomFilter.create(100)
		bf.mightContain("anything").shouldBeFalse()
		bf.mightContain("").shouldBeFalse()
	}
}
