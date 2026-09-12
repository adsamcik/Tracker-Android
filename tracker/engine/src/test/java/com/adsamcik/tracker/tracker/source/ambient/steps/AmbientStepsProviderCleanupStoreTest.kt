package com.adsamcik.tracker.tracker.source.ambient.steps

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
class AmbientStepsProviderCleanupStoreTest {
	private lateinit var journalFile: File
	private lateinit var subject: AmbientStepsProviderCleanupStore

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		journalFile = File(application.cacheDir, "ambient-steps-provider-cleanup-test")
		deleteJournalFiles()
		subject = AmbientStepsProviderCleanupStore(journalFile)
	}

	@After
	fun tearDown() = deleteJournalFiles()

	@Test
	fun `missing journal has no provider cleanup debt`() {
		subject.read() shouldBe AmbientStepsProviderCleanupState.INITIAL
	}

	@Test
	fun `journal round trips only provider-global cleanup identities`() {
		val state = AmbientStepsProviderCleanupState(AmbientStepsProvider.entries.toSet())

		subject.write(state)

		AmbientStepsProviderCleanupStore(journalFile).read() shouldBe state
	}

	@Test
	fun `adding and removing provider debt is durable and idempotent`() {
		subject.addPending(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
		subject.addPending(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
		subject.addPending(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS)

		val reloaded = AmbientStepsProviderCleanupStore(journalFile)
		reloaded.read().pending shouldBe AmbientStepsProvider.entries.toSet()
		reloaded.removePending(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
		reloaded.removePending(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
		AmbientStepsProviderCleanupStore(journalFile).read().pending shouldBe
			setOf(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS)
	}

	@Test
	fun `corrupt journal fails closed without erasing cleanup debt`() {
		journalFile.writeBytes(byteArrayOf(1, 2, 3, 4))

		val error = runCatching { subject.read() }.exceptionOrNull()

		error.shouldBeInstanceOf<AmbientStepsProviderCleanupStoreException>()
		error.corrupt shouldBe true
		journalFile.exists() shouldBe true
	}

	private fun deleteJournalFiles() {
		journalFile.delete()
		File("${journalFile.path}.bak").delete()
		File("${journalFile.path}.new").delete()
	}
}
