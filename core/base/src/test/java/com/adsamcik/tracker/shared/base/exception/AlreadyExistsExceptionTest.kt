package com.adsamcik.tracker.shared.base.exception

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("AlreadyExistsException - duplicate entity errors")
class AlreadyExistsExceptionTest {

	@Nested
	@DisplayName("Constructors")
	inner class Constructors {
		@Test
		fun `no-arg constructor creates exception with null message`() {
			val ex = AlreadyExistsException()
			ex.message.shouldBeNull()
			ex.cause.shouldBeNull()
		}

		@Test
		fun `message constructor stores message`() {
			val ex = AlreadyExistsException("Item X exists")
			ex.message shouldBe "Item X exists"
			ex.cause.shouldBeNull()
		}

		@Test
		fun `null message constructor is allowed`() {
			val ex = AlreadyExistsException(null as String?)
			ex.message.shouldBeNull()
		}

		@Test
		fun `message and cause constructor stores both`() {
			val cause = RuntimeException("root cause")
			val ex = AlreadyExistsException("duplicate", cause)
			ex.message shouldBe "duplicate"
			ex.cause shouldBe cause
		}

		@Test
		fun `cause-only constructor wraps cause`() {
			val cause = IllegalStateException("inner")
			val ex = AlreadyExistsException(cause)
			ex.cause shouldBe cause
		}

		@Test
		fun `full constructor passes all parameters`() {
			val cause = RuntimeException("root")
			val ex = AlreadyExistsException("msg", cause, true, true)
			ex.message shouldBe "msg"
			ex.cause shouldBe cause
		}
	}

	@Nested
	@DisplayName("Type hierarchy")
	inner class TypeHierarchy {
		@Test
		fun `is a RuntimeException`() {
			AlreadyExistsException().shouldBeInstanceOf<RuntimeException>()
		}

		@Test
		fun `can be thrown and caught`() {
			val ex = assertThrows<AlreadyExistsException> {
				throw AlreadyExistsException("test throw")
			}
			ex.message shouldBe "test throw"
		}

		@Test
		fun `can be caught as RuntimeException`() {
			val ex = assertThrows<RuntimeException> {
				throw AlreadyExistsException("caught as runtime")
			}
			ex.shouldBeInstanceOf<AlreadyExistsException>()
		}
	}
}
