package com.adsamcik.tracker.map.ui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeExactly
import org.junit.jupiter.api.Test

class AppendOnlyPathMapperTest {

	@Test
	fun `maps only newly appended values`() {
		var transformations = 0
		val mapper = AppendOnlyPathMapper<Int, String>()
		val first = listOf(1, 2)

		mapper.update(first) {
			transformations++
			it.toString()
		}
		val mapped = mapper.update(first + 3) {
			transformations++
			it.toString()
		}

		mapped.shouldContainExactly("1", "2", "3")
		transformations.shouldBeExactly(3)
	}

	@Test
	fun `rebuilds when the source path is replaced`() {
		var transformations = 0
		val mapper = AppendOnlyPathMapper<Int, String>()
		mapper.update(listOf(1, 2, 3)) {
			transformations++
			it.toString()
		}

		val mapped = mapper.update(listOf(4, 5)) {
			transformations++
			it.toString()
		}

		mapped.shouldContainExactly("4", "5")
		transformations.shouldBeExactly(5)
	}
}
