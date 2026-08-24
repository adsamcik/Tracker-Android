package com.adsamcik.tracker.activity.api.registration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityRegistrationCleanupStoreTest {
	private lateinit var marker: File
	private lateinit var store: ActivityRegistrationCleanupStore

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		marker = File(application.cacheDir, "activity-cleanup-store-test")
		deleteJournalFiles()
		store = ActivityRegistrationCleanupStore(marker)
	}

	@After
	fun tearDown() = deleteJournalFiles()

	@Test
	fun `missing journal requires the released v27 cleanup check`() {
		store.read() shouldBe ActivityRegistrationCleanupState.INITIAL
	}

	@Test
	fun `journal round trips only exact pending intent identities`() {
		val brokered = ActivityRegistrationCleanupKey(
			kind = ActivityRegistrationCleanupKind.BROKERED,
			sourceInstanceId = "random-instance",
			registrationGeneration = 7L,
		)
		val state = ActivityRegistrationCleanupState(
			releasedV27Checked = true,
			pending = setOf(brokered, ActivityRegistrationCleanupKey.RELEASED_V27),
		)

		store.write(state)

		store.read() shouldBe state
	}

	@Test
	fun `adding a cleanup identity is durable and idempotent`() {
		val key = ActivityRegistrationCleanupKey(
			kind = ActivityRegistrationCleanupKind.BROKERED,
			sourceInstanceId = "random-instance",
			registrationGeneration = 2L,
		)

		store.addPending(listOf(key, key))
		val reloaded = ActivityRegistrationCleanupStore(marker)

		reloaded.read().pending shouldBe setOf(key)
	}

	@Test
	fun `corrupt journal fails closed instead of becoming an empty obligation`() {
		marker.writeBytes(byteArrayOf(1, 2, 3, 4))

		val error = runCatching { store.read() }.exceptionOrNull()

		error.shouldBeInstanceOf<ActivityRegistrationCleanupStoreException>()
		error.corrupt shouldBe true
		marker.exists() shouldBe true
	}

	private fun deleteJournalFiles() {
		marker.delete()
		File("${marker.path}.bak").delete()
		File("${marker.path}.new").delete()
	}
}
