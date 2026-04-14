package com.adsamcik.tracker.shared.base.extension

import com.adsamcik.tracker.shared.base.graph.Vertex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.floats.shouldBeNaN
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CollectionExtensionsTest {

	@Nested
	@DisplayName("contains with predicate")
	inner class ContainsPredicate {
		@Test
		fun `returns true when predicate matches`() {
			listOf(1, 2, 3).contains { it == 2 } shouldBe true
		}

		@Test
		fun `returns false when no match`() {
			listOf(1, 2, 3).contains { it == 4 } shouldBe false
		}

		@Test
		fun `returns false for empty collection`() {
			emptyList<Int>().contains { true } shouldBe false
		}

		@Test
		fun `matches on first element`() {
			listOf(10, 20, 30).contains { it == 10 } shouldBe true
		}

		@Test
		fun `matches on last element`() {
			listOf(10, 20, 30).contains { it == 30 } shouldBe true
		}
	}

	@Nested
	@DisplayName("averageDouble")
	inner class AverageDouble {
		@Test
		fun `average of single element`() {
			listOf(5).averageDouble { it.toDouble() } shouldBe 5.0
		}

		@Test
		fun `average of multiple elements`() {
			listOf(2, 4, 6).averageDouble { it.toDouble() } shouldBe 4.0
		}

		@Test
		fun `average with transform`() {
			listOf("ab", "abcd").averageDouble { it.length.toDouble() } shouldBe 3.0
		}

		@Test
		fun `average of empty collection returns NaN`() {
			emptyList<Int>().averageDouble { it.toDouble() }.shouldBeNaN()
		}
	}

	@Nested
	@DisplayName("averageFloat")
	inner class AverageFloat {
		@Test
		fun `average of multiple elements`() {
			listOf(2, 4, 6).averageFloat { it.toFloat() } shouldBe 4.0f
		}

		@Test
		fun `average of single element`() {
			listOf(7).averageFloat { it.toFloat() } shouldBe 7.0f
		}

		@Test
		fun `average of empty collection returns NaN`() {
			emptyList<Int>().averageFloat { it.toFloat() }.shouldBeNaN()
		}
	}

	@Nested
	@DisplayName("averageIfFloat")
	inner class AverageIfFloat {
		@Test
		fun `average with condition filters items`() {
			listOf(1, 2, 3, 4, 5).averageIfFloat({ it > 2 }, { it.toFloat() }) shouldBe 4.0f
		}

		@Test
		fun `all items pass condition`() {
			listOf(1, 2, 3).averageIfFloat({ true }, { it.toFloat() }) shouldBe 2.0f
		}

		@Test
		fun `no items pass condition returns NaN`() {
			listOf(1, 2, 3).averageIfFloat({ false }, { it.toFloat() }).shouldBeNaN()
		}

		@Test
		fun `single item passes`() {
			listOf(10, 20, 30).averageIfFloat({ it == 20 }, { it.toFloat() }) shouldBe 20.0f
		}
	}

	@Nested
	@DisplayName("averageIfDouble")
	inner class AverageIfDouble {
		@Test
		fun `average with condition filters items`() {
			listOf(1, 2, 3, 4, 5).averageIfDouble({ it > 2 }, { it.toDouble() }) shouldBe 4.0
		}

		@Test
		fun `all items pass condition`() {
			listOf(10, 20, 30).averageIfDouble({ true }, { it.toDouble() }) shouldBe 20.0
		}

		@Test
		fun `no items pass condition returns NaN`() {
			listOf(1, 2, 3).averageIfDouble({ false }, { it.toDouble() }).shouldBeNaN()
		}
	}

	@Nested
	@DisplayName("nearestDouble")
	inner class NearestDouble {
		@Test
		fun `finds nearest item`() {
			listOf(10, 20, 30).nearestDouble {
				kotlin.math.abs(it - 22).toDouble()
			} shouldBe 20
		}

		@Test
		fun `returns null for empty collection`() {
			emptyList<Int>().nearestDouble { it.toDouble() }.shouldBeNull()
		}

		@Test
		fun `returns first when all distances equal`() {
			listOf("a", "b", "c").nearestDouble { 5.0 } shouldBe "a"
		}

		@Test
		fun `finds nearest at start`() {
			listOf(1, 10, 100).nearestDouble { it.toDouble() } shouldBe 1
		}

		@Test
		fun `finds nearest at end`() {
			listOf(100, 50, 1).nearestDouble { it.toDouble() } shouldBe 1
		}
	}

	@Nested
	@DisplayName("nearestLong")
	inner class NearestLong {
		@Test
		fun `finds nearest item`() {
			listOf(10L, 20L, 30L).nearestLong { kotlin.math.abs(it - 22L) } shouldBe 20L
		}

		@Test
		fun `returns null for empty collection`() {
			emptyList<Long>().nearestLong { it }.shouldBeNull()
		}

		@Test
		fun `finds nearest at end`() {
			listOf(100L, 50L, 1L).nearestLong { it } shouldBe 1L
		}
	}

	@Nested
	@DisplayName("remove with condition")
	inner class RemoveWithCondition {
		@Test
		fun `removes first matching item and returns true`() {
			val list = mutableListOf(1, 2, 3, 2)
			list.remove { it == 2 } shouldBe true
			list shouldContainExactly listOf(1, 3, 2)
		}

		@Test
		fun `returns false when no match`() {
			val list = mutableListOf(1, 2, 3)
			list.remove { it == 4 } shouldBe false
			list shouldContainExactly listOf(1, 2, 3)
		}

		@Test
		fun `empty list returns false`() {
			val list = mutableListOf<Int>()
			list.remove { true } shouldBe false
		}

		@Test
		fun `removes single element`() {
			val list = mutableListOf(42)
			list.remove { it == 42 } shouldBe true
			list.shouldBeEmpty()
		}
	}

	@Nested
	@DisplayName("removeAllByIndexes")
	inner class RemoveAllByIndexes {
		@Test
		fun `removes items at given indexes`() {
			val list = mutableListOf("a", "b", "c", "d", "e")
			list.removeAllByIndexes(listOf(1, 3))
			list shouldContainExactly listOf("a", "c", "e")
		}

		@Test
		fun `handles duplicate indexes safely`() {
			val list = mutableListOf("a", "b", "c")
			list.removeAllByIndexes(listOf(1, 1))
			list shouldContainExactly listOf("a", "c")
		}

		@Test
		fun `empty index list does nothing`() {
			val list = mutableListOf("a", "b")
			list.removeAllByIndexes(emptyList())
			list shouldContainExactly listOf("a", "b")
		}

		@Test
		fun `remove all indexes`() {
			val list = mutableListOf("a", "b", "c")
			list.removeAllByIndexes(listOf(0, 1, 2))
			list.shouldBeEmpty()
		}
	}

	@Nested
	@DisplayName("DoubleArray.toIntArray")
	inner class DoubleArrayToIntArray {
		@Test
		fun `converts doubles to ints by truncation`() {
			doubleArrayOf(1.9, 2.1, 3.5).toIntArray() shouldBe intArrayOf(1, 2, 3)
		}

		@Test
		fun `empty array returns empty`() {
			doubleArrayOf().toIntArray() shouldBe intArrayOf()
		}

		@Test
		fun `negative values truncate toward zero`() {
			doubleArrayOf(-1.9, -2.1).toIntArray() shouldBe intArrayOf(-1, -2)
		}
	}

	@Nested
	@DisplayName("DoubleArray.roundToIntArray")
	inner class DoubleArrayRoundToIntArray {
		@Test
		fun `rounds doubles to nearest int`() {
			doubleArrayOf(1.4, 2.6, 3.5).roundToIntArray() shouldBe intArrayOf(1, 3, 4)
		}

		@Test
		fun `empty array returns empty`() {
			doubleArrayOf().roundToIntArray() shouldBe intArrayOf()
		}

		@Test
		fun `negative values round correctly`() {
			doubleArrayOf(-1.4, -2.6).roundToIntArray() shouldBe intArrayOf(-1, -3)
		}
	}

	@Nested
	@DisplayName("sortByIndexes")
	inner class SortByIndexes {
		@Test
		fun `sorts by given index order`() {
			val list = listOf("a", "b", "c")
			list.sortByIndexes(listOf(2, 0, 1)) shouldContainExactly listOf("c", "a", "b")
		}

		@Test
		fun `identity order preserves list`() {
			val list = listOf("a", "b", "c")
			list.sortByIndexes(listOf(0, 1, 2)) shouldContainExactly listOf("a", "b", "c")
		}

		@Test
		fun `reverse order reverses list`() {
			val list = listOf("a", "b", "c")
			list.sortByIndexes(listOf(2, 1, 0)) shouldContainExactly listOf("c", "b", "a")
		}

		@Test
		fun `mismatched sizes throws IllegalArgumentException`() {
			shouldThrow<IllegalArgumentException> {
				listOf("a", "b").sortByIndexes(listOf(0))
			}
		}
	}

	@Nested
	@DisplayName("sortByVertexes")
	inner class SortByVertexes {
		@Test
		fun `sorts by vertex values`() {
			val list = listOf("a", "b", "c")
			list.sortByVertexes(
				listOf(Vertex(2), Vertex(0), Vertex(1))
			) shouldContainExactly listOf("c", "a", "b")
		}

		@Test
		fun `identity vertex order preserves list`() {
			val list = listOf("x", "y")
			list.sortByVertexes(
				listOf(Vertex(0), Vertex(1))
			) shouldContainExactly listOf("x", "y")
		}
	}

	@Nested
	@DisplayName("filterConsecutive Float with similarity")
	inner class FilterConsecutiveFloat {
		@Test
		fun `filters similar consecutive floats`() {
			listOf(1.0f, 1.01f, 2.0f, 2.01f, 3.0f)
				.filterConsecutive(0.1f) shouldContainExactly listOf(1.0f, 2.0f, 3.0f)
		}

		@Test
		fun `empty list returns empty`() {
			emptyList<Float>().filterConsecutive(0.1f).shouldBeEmpty()
		}

		@Test
		fun `single element returns that element`() {
			listOf(5.0f).filterConsecutive(0.1f) shouldContainExactly listOf(5.0f)
		}

		@Test
		fun `all similar keeps only first`() {
			listOf(1.0f, 1.05f, 1.08f).filterConsecutive(0.1f) shouldContainExactly listOf(1.0f)
		}

		@Test
		fun `all different keeps all`() {
			listOf(1.0f, 3.0f, 5.0f).filterConsecutive(0.5f) shouldContainExactly listOf(1.0f, 3.0f, 5.0f)
		}
	}

	@Nested
	@DisplayName("filterConsecutive generic with similarity function")
	inner class FilterConsecutiveGeneric {
		@Test
		fun `filters equal consecutive values`() {
			listOf(1, 1, 2, 2, 3).filterConsecutive { last, value ->
				last != value
			} shouldContainExactly listOf(1, 2, 3)
		}

		@Test
		fun `all different keeps all`() {
			listOf(1, 2, 3).filterConsecutive { last, value ->
				last != value
			} shouldContainExactly listOf(1, 2, 3)
		}

		@Test
		fun `all same keeps only first`() {
			listOf(5, 5, 5).filterConsecutive { last, value ->
				last != value
			} shouldContainExactly listOf(5)
		}

		@Test
		fun `empty list returns empty`() {
			emptyList<Int>().filterConsecutive { _, _ -> true }.shouldBeEmpty()
		}

		@Test
		fun `single element returns single element`() {
			listOf(42).filterConsecutive { _, _ -> true } shouldContainExactly listOf(42)
		}
	}

	@Nested
	@DisplayName("filterConsecutive with transform and similarity")
	inner class FilterConsecutiveWithTransform {
		@Test
		fun `filters based on transformed value`() {
			data class Item(val value: Int, val name: String)

			val items = listOf(Item(1, "a"), Item(1, "b"), Item(2, "c"))
			items.filterConsecutive(
				{ it.value },
				{ last, value -> last != value }
			) shouldContainExactly listOf(Item(1, "a"), Item(2, "c"))
		}

		@Test
		fun `keeps all when transform values all differ`() {
			val items = listOf("a", "bb", "ccc")
			items.filterConsecutive(
				{ it.length },
				{ last, value -> last != value }
			) shouldContainExactly listOf("a", "bb", "ccc")
		}
	}

	@Nested
	@DisplayName("forEachParallel and forEachParallelAwait")
	inner class ParallelOperations {
		@Test
		fun `forEachParallelAwait processes all items`() = runTest {
			val result = listOf(1, 2, 3).forEachParallelAwait { it * 2 }
			result shouldContainExactly listOf(2, 4, 6)
		}

		@Test
		fun `forEachParallel returns deferred results`() = runTest {
			val deferreds = listOf(1, 2, 3).forEachParallel { it * 2 }
			deferreds.map { it.await() } shouldContainExactly listOf(2, 4, 6)
		}

		@Test
		fun `empty collection returns empty list`() = runTest {
			emptyList<Int>().forEachParallelAwait { it }.shouldBeEmpty()
		}
	}

	@Nested
	@DisplayName("Map.require")
	inner class MapRequire {
		@Test
		fun `returns value when key exists`() {
			mapOf("a" to 1).require("a") shouldBe 1
		}

		@Test
		fun `throws IllegalArgumentException when key missing`() {
			shouldThrow<IllegalArgumentException> {
				mapOf("a" to 1).require("b")
			}
		}

		@Test
		fun `works with multiple entries`() {
			val map = mapOf("x" to 10, "y" to 20, "z" to 30)
			map.require("y") shouldBe 20
		}
	}

	@Nested
	@DisplayName("standardDeviation")
	inner class StandardDeviation {
		@Test
		fun `identical values have zero deviation`() {
			listOf(5.0, 5.0, 5.0).standardDeviation() shouldBe 0.0
		}

		@Test
		fun `known dataset produces correct population deviation`() {
			// mean=5, variance=4, sd=2
			listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0).standardDeviation() shouldBe 2.0
		}

		@Test
		fun `single element has zero deviation`() {
			listOf(42.0).standardDeviation() shouldBe 0.0
		}

		@Test
		fun `two values`() {
			// mean=5, deviations: -5 and 5, variance=25, sd=5
			listOf(0.0, 10.0).standardDeviation() shouldBe 5.0
		}
	}
}
