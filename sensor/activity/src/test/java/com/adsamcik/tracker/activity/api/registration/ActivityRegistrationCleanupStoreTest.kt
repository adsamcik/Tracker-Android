package com.adsamcik.tracker.activity.api.registration

import android.app.Application
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ActivityRegistrationCleanupStoreTest.HostAtomicFile::class])
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
	fun `removing one cleanup identity preserves other pending work and recovery state`() {
		val key = ActivityRegistrationCleanupKey(
			kind = ActivityRegistrationCleanupKind.BROKERED,
			sourceInstanceId = "completed-instance",
			registrationGeneration = 3L,
		)
		store.addPending(listOf(key, ActivityRegistrationCleanupKey.RELEASED_V27))

		store.removePending(listOf(key, key))

		ActivityRegistrationCleanupStore(marker).read().pending shouldBe setOf(ActivityRegistrationCleanupKey.RELEASED_V27)
		store.read().releasedV27Checked shouldBe false
	}

	@Test
	fun `corrupt journal fails closed instead of becoming an empty obligation`() {
		marker.writeBytes(byteArrayOf(1, 2, 3, 4))

		val error = runCatching { store.read() }.exceptionOrNull()

		error.shouldBeInstanceOf<ActivityRegistrationCleanupStoreException>()
		error.corrupt shouldBe true
		marker.exists() shouldBe true
	}

	/** Host File.renameTo cannot replace an existing Windows file; Android rename can. */
	@Implements(AtomicFile::class)
	class HostAtomicFile {
		@RealObject
		private lateinit var atomicFile: AtomicFile

		@Implementation
		fun finishWrite(output: FileOutputStream?) {
			if (output == null) {
				return
			}
			output.fd.sync()
			output.close()
			Files.move(File("${atomicFile.baseFile.path}.new").toPath(), atomicFile.baseFile.toPath(),
				StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun deleteJournalFiles() {
		marker.delete()
		File("${marker.path}.bak").delete()
		File("${marker.path}.new").delete()
	}
}
