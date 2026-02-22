package com.adsamcik.tracker.statistics.data.source.producer

import android.util.Log
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.statistics.data.source.ConcurrentCacheData
import com.adsamcik.tracker.statistics.data.source.RawDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.StatDataSource
import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.locks.ReentrantLock

@DisplayName("TrackerSessionProducer")
class TrackerSessionProducerTest {

	private val producer = TrackerSessionProducer()

	@BeforeEach
	fun setUp() {
		mockkStatic(Log::class)
		every { Log.w(any<String>(), any<String>()) } returns 0
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(Log::class)
	}

	private fun buildRawDataMap(
		sessionData: Any? = null,
		includeSessionKey: Boolean = true
	): RawDataMap {
		if (!includeSessionKey) return emptyMap()

		val rawProducer = mockk<RawDataProducer>(relaxed = true)
		every { rawProducer.type } returns StatDataSource.SESSION
		return mapOf(
			StatDataSource.SESSION to ConcurrentCacheData(
				ReentrantLock(),
				rawProducer,
				sessionData
			)
		)
	}

	private val emptyStatDataMap: StatDataMap = emptyMap()

	@Nested
	@DisplayName("produce")
	inner class Produce {

		@Test
		fun `returns session data when SESSION key is present`() {
			val sessions = listOf(mockk<TrackerSession>(relaxed = true))
			val rawDataMap = buildRawDataMap(sessionData = sessions)

			val result = producer.produce(rawDataMap, emptyStatDataMap)

			result shouldBe sessions
		}

		@Test
		fun `returns empty list when SESSION key is missing from RawDataMap`() {
			val rawDataMap = buildRawDataMap(includeSessionKey = false)

			val result = producer.produce(rawDataMap, emptyStatDataMap)

			result.shouldBeInstanceOf<List<*>>()
			@Suppress("UNCHECKED_CAST")
			(result as List<TrackerSession>).shouldBeEmpty()
		}

		@Test
		fun `logs warning when SESSION key is missing`() {
			val rawDataMap = buildRawDataMap(includeSessionKey = false)

			producer.produce(rawDataMap, emptyStatDataMap)

			verify { Log.w("TrackerSessionProducer", match<String> { it.contains("SESSION") }) }
		}

		@Test
		fun `returns empty list when SESSION data is null`() {
			val rawDataMap = buildRawDataMap(sessionData = null)

			val result = producer.produce(rawDataMap, emptyStatDataMap)

			result.shouldBeInstanceOf<List<*>>()
			@Suppress("UNCHECKED_CAST")
			(result as List<TrackerSession>).shouldBeEmpty()
		}

		@Test
		fun `logs warning when SESSION data is null`() {
			val rawDataMap = buildRawDataMap(sessionData = null)

			producer.produce(rawDataMap, emptyStatDataMap)

			verify { Log.w("TrackerSessionProducer", match<String> { it.contains("SESSION") }) }
		}
	}

	@Nested
	@DisplayName("metadata")
	inner class Metadata {

		@Test
		fun `requires SESSION raw data source`() {
			producer.requiredRawData shouldBe listOf(StatDataSource.SESSION)
		}

		@Test
		fun `has no producer dependencies`() {
			producer.dependsOn.shouldBeEmpty()
		}
	}
}
