package com.adsamcik.tracker.shared.preferences.flow

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PreferenceFlowsTest {

	private lateinit var mockContext: Context
	private lateinit var mockResources: Resources

	@BeforeEach
	fun setUp() {
		mockkObject(LegacyPreferenceStore)
		mockResources = mockk(relaxed = true)
		mockContext = mockk {
			every { applicationContext } returns this@mockk
			every { resources } returns mockResources
		}
	}

	@AfterEach
	fun tearDown() {
		unmockkObject(LegacyPreferenceStore)
	}

	@Nested
	inner class `boolean with string key` {
		@Test
		fun `delegates to LegacyPreferenceStore booleanFlow`() = runTest {
			val expected = flowOf(true)
			every { LegacyPreferenceStore.booleanFlow(any(), "myKey", false) } returns expected

			val result = PreferenceFlows.boolean(mockContext, "myKey", false)
			result shouldBe expected
		}
	}

	@Nested
	inner class `boolean with resource key` {
		@Test
		fun `resolves key and default from resources`() = runTest {
			every { mockContext.getString(101) } returns "resolvedKey"
			every { mockContext.getString(102) } returns "true"
			val expected = flowOf(true)
			every { LegacyPreferenceStore.booleanFlow(any(), "resolvedKey", true) } returns expected

			val result = PreferenceFlows.boolean(mockContext, 101, 102)
			result shouldBe expected
		}
	}

	@Nested
	inner class `int with string key` {
		@Test
		fun `delegates to LegacyPreferenceStore intFlow`() = runTest {
			val expected = flowOf(42)
			every { LegacyPreferenceStore.intFlow(any(), "intKey", 0) } returns expected

			val result = PreferenceFlows.int(mockContext, "intKey", 0)
			result shouldBe expected
		}
	}

	@Nested
	inner class `int with resource key` {
		@Test
		fun `resolves key and default from resources`() = runTest {
			every { mockContext.getString(201) } returns "resolvedIntKey"
			every { mockResources.getInteger(202) } returns 7
			val expected = flowOf(7)
			every { LegacyPreferenceStore.intFlow(any(), "resolvedIntKey", 7) } returns expected

			val result = PreferenceFlows.int(mockContext, 201, 202)
			result shouldBe expected
		}
	}

	@Nested
	inner class `intFromString with string key` {
		@Test
		fun `converts string flow to int`() = runTest {
			every { LegacyPreferenceStore.stringOrIntFlow(any(), "isKey", "5") } returns flowOf("5")

			val result = PreferenceFlows.intFromString(mockContext, "isKey", 5)
			result.first() shouldBe 5
		}

		@Test
		fun `returns default for non-numeric string`() = runTest {
			every { LegacyPreferenceStore.stringOrIntFlow(any(), "isKey", "10") } returns flowOf("abc")

			val result = PreferenceFlows.intFromString(mockContext, "isKey", 10)
			result.first() shouldBe 10
		}
	}

	@Nested
	inner class `intFromString with resource key` {
		@Test
		fun `resolves key and default from resources`() = runTest {
			every { mockContext.getString(301) } returns "resIntStrKey"
			every { mockContext.getString(302) } returns "15"
			every { LegacyPreferenceStore.stringOrIntFlow(any(), "resIntStrKey", "15") } returns flowOf("15")

			val result = PreferenceFlows.intFromString(mockContext, 301, 302)
			result.first() shouldBe 15
		}

		@Test
		fun `handles non-numeric default resource string`() = runTest {
			every { mockContext.getString(301) } returns "resIntStrKey"
			every { mockContext.getString(302) } returns "invalid"
			every { LegacyPreferenceStore.stringOrIntFlow(any(), "resIntStrKey", "invalid") } returns flowOf("invalid")

			val result = PreferenceFlows.intFromString(mockContext, 301, 302)
			result.first() shouldBe 0
		}
	}

	@Nested
	inner class `string with resource key` {
		@Test
		fun `resolves key and default from resources`() = runTest {
			every { mockContext.getString(401) } returns "strKey"
			every { mockContext.getString(402) } returns "defaultVal"
			val expected = flowOf("defaultVal")
			every { LegacyPreferenceStore.stringFlow(any(), "strKey", "defaultVal") } returns expected

			val result = PreferenceFlows.string(mockContext, 401, 402)
			result shouldBe expected
		}
	}

	@Nested
	inner class `object identity` {
		@Test
		fun `PreferenceFlows is a singleton`() {
			val ref1 = PreferenceFlows
			val ref2 = PreferenceFlows
			(ref1 === ref2) shouldBe true
		}
	}
}
