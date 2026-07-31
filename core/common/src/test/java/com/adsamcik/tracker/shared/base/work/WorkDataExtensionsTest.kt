package com.adsamcik.tracker.shared.base.work

import androidx.work.Data
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WorkDataExtensions")
class WorkDataExtensionsTest {
	@Nested
	@DisplayName("getNonNegativeLongOrNull")
	inner class GetNonNegativeLongOrNull {

		@Test
		fun `returns positive value when present`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns 42L

			val result = data.getNonNegativeLongOrNull("key")

			result shouldBe 42L
		}

		@Test
		fun `returns zero when value is zero`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns 0L

			val result = data.getNonNegativeLongOrNull("key")

			result shouldBe 0L
		}

		@Test
		fun `returns large positive value`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns Long.MAX_VALUE

			val result = data.getNonNegativeLongOrNull("key")

			result shouldBe Long.MAX_VALUE
		}

		@Test
		fun `returns null for a negative value`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns -5L

			val result = data.getNonNegativeLongOrNull("key")

			result.shouldBeNull()
		}

		@Test
		fun `returns null for a missing key`() {
			val data = mockk<Data>()
			every { data.getLong("key", -1) } returns -1L

			val result = data.getNonNegativeLongOrNull("key")

			result.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("tryGetLong")
	inner class TryGetLong {

		@Test
		fun `returns value when key exists`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns mapOf("key" to 42L)
			every { data.getLong("key", 0) } returns 42L

			val result = data.tryGetLong("key")

			result shouldBe 42L
		}

		@Test
		fun `returns null when key is missing`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns emptyMap()

			val result = data.tryGetLong("key")

			result.shouldBeNull()
		}

		@Test
		fun `returns negative value when present`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns mapOf("key" to -10L)
			every { data.getLong("key", 0) } returns -10L

			val result = data.tryGetLong("key")

			result shouldBe -10L
		}

		@Test
		fun `returns zero when value is zero and key exists`() {
			val data = mockk<Data>()
			every { data.keyValueMap } returns mapOf("key" to 0L)
			every { data.getLong("key", 0) } returns 0L

			val result = data.tryGetLong("key")

			result shouldBe 0L
		}

	}
}
