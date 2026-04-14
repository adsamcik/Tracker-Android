package com.adsamcik.tracker.shared.base.exception

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("CircularDependencyException - cycle detection errors")
class CircularDependencyExceptionTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `message is stored and retrievable`() {
			val ex = CircularDependencyException("A -> B -> A")
			ex.message shouldBe "A -> B -> A"
		}

		@Test
		fun `null message is allowed`() {
			val ex = CircularDependencyException(null)
			ex.message.shouldBeNull()
		}

		@Test
		fun `cause is null by default`() {
			val ex = CircularDependencyException("cycle")
			ex.cause.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("Type hierarchy")
	inner class TypeHierarchy {
		@Test
		fun `is a RuntimeException`() {
			CircularDependencyException("msg").shouldBeInstanceOf<RuntimeException>()
		}

		@Test
		fun `can be thrown and caught`() {
			val ex = assertThrows<CircularDependencyException> {
				throw CircularDependencyException("cycle detected")
			}
			ex.message shouldBe "cycle detected"
		}
	}
}
