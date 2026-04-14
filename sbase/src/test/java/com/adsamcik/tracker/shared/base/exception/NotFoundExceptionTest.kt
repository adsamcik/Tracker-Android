package com.adsamcik.tracker.shared.base.exception

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("NotFoundException - missing entity errors")
class NotFoundExceptionTest {

	@Nested
	@DisplayName("Constructors")
	inner class Constructors {
		@Test
		fun `no-arg constructor creates exception with null message`() {
			val ex = NotFoundException()
			ex.message.shouldBeNull()
			ex.cause.shouldBeNull()
		}

		@Test
		fun `message constructor stores message`() {
			val ex = NotFoundException("Entity not found")
			ex.message shouldBe "Entity not found"
			ex.cause.shouldBeNull()
		}

		@Test
		fun `null message constructor is allowed`() {
			val ex = NotFoundException(null as String?)
			ex.message.shouldBeNull()
		}

		@Test
		fun `message and cause constructor stores both`() {
			val cause = RuntimeException("root cause")
			val ex = NotFoundException("missing", cause)
			ex.message shouldBe "missing"
			ex.cause shouldBe cause
		}

		@Test
		fun `cause-only constructor wraps cause`() {
			val cause = IllegalStateException("inner")
			val ex = NotFoundException(cause)
			ex.cause shouldBe cause
		}

		@Test
		fun `full constructor passes all parameters`() {
			val cause = RuntimeException("root")
			val ex = NotFoundException("msg", cause, true, true)
			ex.message shouldBe "msg"
			ex.cause shouldBe cause
		}
	}

	@Nested
	@DisplayName("Type hierarchy")
	inner class TypeHierarchy {
		@Test
		fun `is a RuntimeException`() {
			NotFoundException().shouldBeInstanceOf<RuntimeException>()
		}

		@Test
		fun `can be thrown and caught`() {
			val ex = assertThrows<NotFoundException> {
				throw NotFoundException("not found")
			}
			ex.message shouldBe "not found"
		}

		@Test
		fun `can be caught as RuntimeException`() {
			val ex = assertThrows<RuntimeException> {
				throw NotFoundException("caught as runtime")
			}
			ex.shouldBeInstanceOf<NotFoundException>()
		}
	}
}
