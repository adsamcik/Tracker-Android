package com.adsamcik.tracker.impexp.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class DataImporterTest {
	@Test
	fun `clear cancels old asynchronous admission and rejects imports until reopened`() {
		val fence = ImportAdmissionFence()
		val old = fence.capture()!!
		var enqueued = false
		fence.pause { Unit }
		fence.enqueueIfCurrent(old) { enqueued = true } shouldBe false
		fence.capture() shouldBe null
		fence.resume()
		fence.enqueueIfCurrent(old) { enqueued = true } shouldBe false
		val fresh = fence.capture()!!
		fence.enqueueIfCurrent(fresh) { enqueued = true } shouldBe true
		enqueued shouldBe true
	}
	@Test
	fun `queued import retains its original data epoch across worker retries`() {
		val input = importJobInput("content://tracker/session", 17L)
		input.getString(com.adsamcik.tracker.impexp.importer.worker.ImportWorker.ARG_FILE_URI) shouldBe
			"content://tracker/session"
		input.getLong(com.adsamcik.tracker.impexp.importer.worker.ImportWorker.ARG_ORIGINAL_DATA_EPOCH,
			-1L) shouldBe 17L
	}

	@Test
	fun `persistReadPermission takes persistable permission for content uri`() {
		val context = mockk<Context>(relaxed = true)
		val resolver = mockk<ContentResolver>(relaxed = true)
		val uri = mockk<Uri>(relaxed = true)
		every { context.contentResolver } returns resolver
		every { uri.scheme } returns ContentResolver.SCHEME_CONTENT
		justRun {
			resolver.takePersistableUriPermission(
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION
			)
		}

		DataImporter.persistReadPermission(context, uri) shouldBe true

		verify(exactly = 1) {
			resolver.takePersistableUriPermission(
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION
			)
		}
	}

	@Test
	fun `persistReadPermission ignores file uri`() {
		val context = mockk<Context>(relaxed = true)
		val resolver = mockk<ContentResolver>(relaxed = true)
		val uri = mockk<Uri>(relaxed = true)
		every { context.contentResolver } returns resolver
		every { uri.scheme } returns ContentResolver.SCHEME_FILE

		DataImporter.persistReadPermission(context, uri) shouldBe false

		verify(exactly = 0) {
			resolver.takePersistableUriPermission(any(), any())
		}
	}
}
