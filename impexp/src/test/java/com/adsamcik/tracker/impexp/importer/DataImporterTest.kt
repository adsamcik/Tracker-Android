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
