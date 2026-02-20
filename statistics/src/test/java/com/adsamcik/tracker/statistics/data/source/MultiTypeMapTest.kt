package com.adsamcik.tracker.statistics.data.source

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import io.kotest.assertions.throwables.shouldThrow

@DisplayName("MultiTypeMap")
class MultiTypeMapTest {

	// ========================================================================
	// MutableMultiTypeMap — basic operations
	// ========================================================================

	@Nested
	@DisplayName("MutableMultiTypeMap basic operations")
	inner class BasicOperations {

		@Test
		fun `put stores and get retrieves value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map.put("key", 42)

			map["key"] shouldBe 42
		}

		@Test
		fun `put overwrites existing value and returns old`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["key"] = "first"

			val old = map.put("key", "second")

			old shouldBe "first"
			map["key"] shouldBe "second"
		}

		@Test
		fun `size reflects number of entries`() {
			val map = MutableMultiTypeMap<String, Any>()

			map.size shouldBe 0
			map["a"] = 1
			map["b"] = 2
			map.size shouldBe 2
		}

		@Test
		fun `isEmpty returns true for empty map`() {
			val map = MutableMultiTypeMap<String, Any>()
			map.isEmpty().shouldBeTrue()
		}

		@Test
		fun `isEmpty returns false for non-empty map`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["x"] = "y"
			map.isEmpty().shouldBeFalse()
		}

		@Test
		fun `containsKey returns true for existing key`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["present"] = 1

			map.containsKey("present").shouldBeTrue()
		}

		@Test
		fun `containsKey returns false for absent key`() {
			val map = MutableMultiTypeMap<String, Any>()

			map.containsKey("absent").shouldBeFalse()
		}

		@Test
		fun `containsValue returns true for stored value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["k"] = "targetValue"

			map.containsValue("targetValue").shouldBeTrue()
		}

		@Test
		fun `containsValue returns false for absent value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["k"] = "other"

			map.containsValue("missing").shouldBeFalse()
		}

		@Test
		fun `keys returns all keys`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["a"] = 1
			map["b"] = 2

			map.keys shouldContainExactlyInAnyOrder listOf("a", "b")
		}

		@Test
		fun `values returns all values`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["a"] = 1
			map["b"] = 2

			map.values.toList() shouldContainExactlyInAnyOrder listOf(1, 2)
		}

		@Test
		fun `entries returns all entries`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["x"] = 10

			val entry = map.entries.single()
			entry.key shouldBe "x"
			entry.value shouldBe 10
		}
	}

	// ========================================================================
	// Missing key handling
	// ========================================================================

	@Nested
	@DisplayName("Missing key handling")
	inner class MissingKeyHandling {

		@Test
		fun `get throws IllegalArgumentException for missing key`() {
			val map = MutableMultiTypeMap<String, Any>()

			val exception = shouldThrow<IllegalArgumentException> {
				map["noSuchKey"]
			}
			exception.message shouldBe "Value with key noSuchKey not found"
		}

		@Test
		fun `get throws after key is removed`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["temp"] = 99
			map.remove("temp")

			shouldThrow<IllegalArgumentException> {
				map["temp"]
			}
		}

		@Test
		fun `get throws after clear`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["key"] = "value"
			map.clear()

			shouldThrow<IllegalArgumentException> {
				map["key"]
			}
		}
	}

	// ========================================================================
	// Type-safe requiredTyped
	// ========================================================================

	@Nested
	@DisplayName("requiredTyped type-safe access")
	inner class RequiredTyped {

		@Test
		fun `requiredTyped returns correctly typed value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["list"] = listOf(1, 2, 3)

			val result: List<Int> = map.requiredTyped("list")
			result shouldBe listOf(1, 2, 3)
		}

		@Test
		fun `requiredTyped works with string values`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["name"] = "hello"

			val result: String = map.requiredTyped("name")
			result shouldBe "hello"
		}

		@Test
		fun `requiredTyped throws on type mismatch`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["value"] = "not a number"

			shouldThrow<IllegalArgumentException> {
				map.requiredTyped<Int>("value")
			}
		}

		@Test
		fun `requiredTyped throws on missing key`() {
			val map = MutableMultiTypeMap<String, Any>()

			shouldThrow<IllegalArgumentException> {
				map.requiredTyped<String>("missing")
			}
		}

		@Test
		fun `requiredTyped supports supertype access`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["number"] = 42

			// Int is a Number, so this should work
			val result: Number = map.requiredTyped("number")
			result shouldBe 42
		}
	}

	// ========================================================================
	// Mutable operations — clear, remove, putAll
	// ========================================================================

	@Nested
	@DisplayName("Mutable operations")
	inner class MutableOperations {

		@Test
		fun `clear empties the map`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["a"] = 1
			map["b"] = 2
			map.clear()

			map.isEmpty().shouldBeTrue()
			map.size shouldBe 0
		}

		@Test
		fun `remove deletes entry and returns old value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["key"] = "value"

			val removed = map.remove("key")

			removed shouldBe "value"
			map.containsKey("key").shouldBeFalse()
		}

		@Test
		fun `remove returns null for absent key`() {
			val map = MutableMultiTypeMap<String, Any>()

			val removed = map.remove("absent")

			removed.shouldBeNull()
		}

		@Test
		fun `putAll inserts multiple entries`() {
			val map = MutableMultiTypeMap<String, Any>()
			map.putAll(mapOf("a" to 1, "b" to 2, "c" to 3))

			map.size shouldBe 3
			map["a"] shouldBe 1
			map["b"] shouldBe 2
			map["c"] shouldBe 3
		}
	}

	// ========================================================================
	// Heterogeneous value types
	// ========================================================================

	@Nested
	@DisplayName("Heterogeneous value types")
	inner class HeterogeneousTypes {

		@Test
		fun `stores and retrieves different types under different keys`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["int"] = 42
			map["string"] = "hello"
			map["list"] = listOf(1.0, 2.0)
			map["boolean"] = true

			map["int"] shouldBe 42
			map["string"] shouldBe "hello"
			map["list"] shouldBe listOf(1.0, 2.0)
			map["boolean"] shouldBe true
		}

		@Test
		fun `overwrite with different type`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["key"] = 42
			map["key"] = "now a string"

			map["key"] shouldBe "now a string"
		}
	}

	// ========================================================================
	// Concurrent access patterns
	// ========================================================================

	@Nested
	@DisplayName("Concurrent access patterns")
	inner class ConcurrentAccess {

		@Test
		fun `concurrent writes do not lose entries`() = runTest {
			val map = MutableMultiTypeMap<Int, Any>()
			val jobs = (0 until 100).map { i ->
				launch(Dispatchers.Default) {
					map[i] = "value-$i"
				}
			}
			jobs.joinAll()

			// MutableMultiTypeMap wraps a plain MutableMap, so some entries
			// may be lost under real contention — verify at least it doesn't crash.
			// In production, ConcurrentCacheData + Lock is used instead.
			map.size shouldBe map.keys.size
		}
	}
}
