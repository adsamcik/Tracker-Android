package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileBackedExportSpoolTest {
	private lateinit var directory: File

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		directory = File(context.cacheDir, "portable-export-spool")
		directory.listFiles().orEmpty().filter {
			it.name.startsWith(OWNED_PREFIX) ||
				it.name.startsWith("portable-steps-v1-") ||
				it.name.startsWith("portable-ambient-steps-v1-")
		}.forEach {
			check(it.delete())
		}
	}

	@After
	fun tearDown() {
		directory.listFiles().orEmpty().forEach { check(it.delete()) }
		directory.delete()
	}

	@Test
	fun `create sweeps aged owned files without touching unknown cache files`() {
		val now = 10_000L
		val expired = abandoned("portable-steps-v2", 1L, 'a', 4)
		val legacy = File(directory, "portable-steps-v1-123.tmp").apply {
			writeBytes(ByteArray(4))
			check(setLastModified(1L))
		}
		val unknown = File(directory, "other-component.tmp").apply { writeText("keep") }

		FileBackedExportSpool(
			ApplicationProvider.getApplicationContext(),
			"portable-steps-v2-",
			8L,
			FileBackedExportSpoolLimits(4, 32L, 100L),
			{ now },
			{ "b".repeat(32) },
		).use { active ->
			expired.exists() shouldBe false
			legacy.exists() shouldBe false
			unknown.exists() shouldBe true
			active.backingFile.exists() shouldBe true
		}
	}

	@Test
	fun `create evicts oldest abandoned files to enforce count and byte reservations`() {
		val first = abandoned("portable-steps-v1", 100L, 'a', 6)
		val second = abandoned("portable-steps-v1", 200L, 'b', 6)

		FileBackedExportSpool(
			ApplicationProvider.getApplicationContext(),
			"portable-steps-v2-",
			6L,
			FileBackedExportSpoolLimits(3, 12L, 10_000L),
			{ 300L },
			{ "c".repeat(32) },
		).use { active ->
			first.exists() shouldBe false
			second.exists() shouldBe true
			directory.listFiles().orEmpty().filter { it.name.startsWith(OWNED_PREFIX) }.size shouldBe 2
			active.backingFile.exists() shouldBe true
		}
	}

	@Test
	fun `concurrent active spool is protected while abandoned file is reclaimed`() = runTest {
		val limits = FileBackedExportSpoolLimits(2, 16L, 10_000L)
		val first = FileBackedExportSpool(
			ApplicationProvider.getApplicationContext(),
			"portable-steps-v2-",
			8L,
			limits,
			{ 500L },
			{ "d".repeat(32) },
		)
		try {
			first.write { output ->
				output.write(byteArrayOf(1, 2, 3))
			}
			val abandoned = abandoned("portable-steps-v1", 400L, 'e', 1)
			FileBackedExportSpool(
				ApplicationProvider.getApplicationContext(),
				"portable-steps-v1-",
				8L,
				limits,
				{ 600L },
				{ "f".repeat(32) },
			).use { second ->
				first.backingFile.exists() shouldBe true
				second.backingFile.exists() shouldBe true
				abandoned.exists() shouldBe false
			}
			first.backingFile.exists() shouldBe true
		} finally {
			first.close()
		}
	}

	@Test
	fun `bounded write failure is staged and close removes the incomplete file`() = runTest {
		val spool = FileBackedExportSpool(
			ApplicationProvider.getApplicationContext(),
			"portable-steps-v2-",
			3L,
			FileBackedExportSpoolLimits(2, 8L, 10_000L),
			{ 700L },
			{ "1".repeat(32) },
		)
		val file = spool.backingFile
		try {
			shouldThrow<IOException> {
				spool.write { output -> output.write(byteArrayOf(1, 2, 3, 4)) }
			}
		} finally {
			spool.close()
		}
		file.exists() shouldBe false
	}

	private fun abandoned(
		purpose: String,
		createdAtMs: Long,
		id: Char,
		size: Int,
	): File {
		if (!directory.isDirectory) check(directory.mkdirs())
		return File(
			directory,
			"$OWNED_PREFIX$purpose-$createdAtMs-${id.toString().repeat(32)}.tmp",
		).apply {
			writeBytes(ByteArray(size))
			check(setLastModified(createdAtMs))
		}
	}

	private companion object {
		const val OWNED_PREFIX = "tracker-portable-export-spool-v1-"
	}
}
