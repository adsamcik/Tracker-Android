package com.adsamcik.tracker.tracker.source.runtime

import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellSourceBackendTest {
	@Test
	fun `partial registration failure retains completed handle for runtime ordered cleanup`() {
		val baseManager = mockk<TelephonyManager>(relaxed = true)
		val firstManager = mockk<TelephonyManager>(relaxed = true)
		val secondManager = mockk<TelephonyManager>(relaxed = true)
		every { baseManager.createForSubscriptionId(1) } returns firstManager
		every { baseManager.createForSubscriptionId(2) } returns secondManager
		val removalAttempts = mutableListOf<TelephonyCallback>()
		var firstCallback: TelephonyCallback? = null
		val backend = backend(baseManager) { subscriptionId, manager ->
			if (subscriptionId == 2) error("injected second registration failure")
			val callback = mockk<TelephonyCallback>(relaxed = true).also { firstCallback = it }
			CellProviderRegistration(manager, callback, null) { removalAttempts += callback }
		}

		assertFalse(backend.start(linkedSetOf(1, 2)) { })
		assertTrue(removalAttempts.isEmpty())

		assertTrue(backend.stop())
		assertEquals(listOf(requireNotNull(firstCallback)), removalAttempts)
	}

	@Test
	fun `partial multi sim unregister retains only failed callback and blocks replacement`() {
		val baseManager = mockk<TelephonyManager>(relaxed = true)
		val firstManager = mockk<TelephonyManager>(relaxed = true)
		val secondManager = mockk<TelephonyManager>(relaxed = true)
		every { baseManager.createForSubscriptionId(1) } returns firstManager
		every { baseManager.createForSubscriptionId(2) } returns secondManager
		val registered = mutableListOf<Pair<Int?, CellProviderRegistration>>()
		val removalAttempts = mutableListOf<Pair<Int?, TelephonyCallback>>()
		var rejectSecondRemoval = true
		val backend = backend(baseManager) { subscriptionId, manager ->
			val callback = mockk<TelephonyCallback>(relaxed = true)
			CellProviderRegistration(manager, callback, null) {
				removalAttempts += subscriptionId to callback
				if (subscriptionId == 2 && rejectSecondRemoval) {
					error("injected subscription removal failure")
				}
			}.also { registered += subscriptionId to it }
		}

		assertTrue(backend.start(linkedSetOf(1, 2)) { })
		val firstCallback = requireNotNull(registered.single { it.first == 1 }.second.callback)
		val secondCallback = requireNotNull(registered.single { it.first == 2 }.second.callback)

		assertFalse(backend.stop())
		assertEquals(
			listOf<Pair<Int?, TelephonyCallback>>(1 to firstCallback, 2 to secondCallback),
			removalAttempts,
		)

		assertFalse(backend.start(setOf(1)) { })
		assertEquals(2, registered.size)
		assertEquals(Pair<Int?, TelephonyCallback>(2, secondCallback), removalAttempts.last())
		assertEquals(1, removalAttempts.count { it.second === firstCallback })
		assertEquals(1, removalAttempts.count { it.second === secondCallback })

		rejectSecondRemoval = false
		assertTrue(backend.stop())
		assertEquals(Pair<Int?, TelephonyCallback>(2, secondCallback), removalAttempts.last())
		assertTrue(backend.start(setOf(1)) { })
		val replacement = requireNotNull(registered.last().second.callback)
		assertNotSame(firstCallback, replacement)
		assertEquals(3, registered.size)
	}

	@Test
	@Suppress("DEPRECATION")
	fun `failed legacy removal retries the exact PhoneStateListener`() {
		val baseManager = mockk<TelephonyManager>(relaxed = true)
		val subscriptionManager = mockk<TelephonyManager>(relaxed = true)
		every { baseManager.createForSubscriptionId(7) } returns subscriptionManager
		val registered = mutableListOf<PhoneStateListener>()
		val removalAttempts = mutableListOf<PhoneStateListener>()
		var rejectRemoval = true
		val backend = backend(baseManager) { _, manager ->
			val listener = mockk<PhoneStateListener>(relaxed = true)
			registered += listener
			CellProviderRegistration(manager, null, listener) {
				removalAttempts += listener
				if (rejectRemoval) error("injected legacy removal failure")
			}
		}

		assertTrue(backend.start(setOf(7)) { })
		val first = registered.single()
		assertFalse(backend.stop())
		assertFalse(backend.start(setOf(7)) { })
		assertEquals(1, registered.size)
		assertEquals(1, removalAttempts.size)
		removalAttempts.forEach { assertSame(first, it) }

		rejectRemoval = false
		assertTrue(backend.stop())
		assertSame(first, removalAttempts.last())
		assertTrue(backend.start(setOf(7)) { })
		assertNotSame(first, registered.last())
	}

	@Test
	fun `one shot refresh callback is not a retained provider registration`() {
		val baseManager = mockk<TelephonyManager>(relaxed = true)
		val subscriptionManager = mockk<TelephonyManager>(relaxed = true)
		every { baseManager.createForSubscriptionId(3) } returns subscriptionManager
		val retainedCallbacks = mutableListOf<TelephonyCallback>()
		val removedCallbacks = mutableListOf<TelephonyCallback>()
		val backend = backend(baseManager) { _, manager ->
			val callback = mockk<TelephonyCallback>(relaxed = true)
			retainedCallbacks += callback
			CellProviderRegistration(manager, callback, null) { removedCallbacks += callback }
		}

		assertTrue(backend.start(setOf(3)) { })
		assertEquals(CellRefreshRequestOutcome.REQUESTED, backend.requestRefresh { })
		assertTrue(backend.stop())
		assertEquals(retainedCallbacks, removedCallbacks)
	}

	@Test
	fun `stop cannot mutate retained handles while a refresh request is in flight`() {
		val baseManager = mockk<TelephonyManager>(relaxed = true)
		val subscriptionManager = mockk<TelephonyManager>(relaxed = true)
		every { baseManager.createForSubscriptionId(4) } returns subscriptionManager
		val refreshEntered = CountDownLatch(1)
		val releaseRefresh = CountDownLatch(1)
		val stopTaskStarted = CountDownLatch(1)
		val removalAttempted = CountDownLatch(1)
		every {
			subscriptionManager.requestCellInfoUpdate(
				any<Executor>(),
				any<TelephonyManager.CellInfoCallback>(),
			)
		} answers {
			refreshEntered.countDown()
			check(releaseRefresh.await(5, TimeUnit.SECONDS))
		}
		val backend = backend(baseManager) { _, manager ->
			val callback = mockk<TelephonyCallback>(relaxed = true)
			CellProviderRegistration(manager, callback, null) { removalAttempted.countDown() }
		}
		assertTrue(backend.start(setOf(4)) { })
		val workers = Executors.newFixedThreadPool(2)

		try {
			val refresh = workers.submit<CellRefreshRequestOutcome> { backend.requestRefresh { } }
			assertTrue(refreshEntered.await(5, TimeUnit.SECONDS))
			val stop = workers.submit<Boolean> {
				stopTaskStarted.countDown()
				backend.stop()
			}
			assertTrue(stopTaskStarted.await(5, TimeUnit.SECONDS))
			assertFalse(removalAttempted.await(100, TimeUnit.MILLISECONDS))

			releaseRefresh.countDown()
			assertEquals(CellRefreshRequestOutcome.REQUESTED, refresh.get(5, TimeUnit.SECONDS))
			assertTrue(stop.get(5, TimeUnit.SECONDS))
			assertTrue(removalAttempted.await(5, TimeUnit.SECONDS))
		} finally {
			releaseRefresh.countDown()
			workers.shutdownNow()
		}
	}

	private fun backend(
		baseManager: TelephonyManager,
		factory: (Int?, TelephonyManager) -> CellProviderRegistration,
	) = AndroidCellSourceBackend(
		baseManager = baseManager,
		subscriptionManager = null,
		executor = Executor(Runnable::run),
		registrationFactory = { subscriptionId, manager, _, _ -> factory(subscriptionId, manager) },
	)
}
