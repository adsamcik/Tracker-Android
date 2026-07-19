package com.adsamcik.tracker.impexp.exporter

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class DatabaseBackupArchiveTest {
	@Test
	fun `writes every application database and records its checksum in manifest`() {
		val directory = File("build/database-backup-archive-test").apply {
			deleteRecursively()
			mkdirs()
		}
		val databases = mapOf(
			"main_database" to "main content".toByteArray(),
			"preference_database" to "preferences".toByteArray(),
			"points_database" to "points".toByteArray(),
			"debug_database" to "logs".toByteArray(),
		)
		databases.forEach { (name, content) -> File(directory, name).writeBytes(content) }

		val output = ByteArrayOutputStream()
		DatabaseBackupArchive.write(directory.listFiles()!!.toList(), output)

		val archiveEntries = unzip(output.toByteArray())
		archiveEntries.keys shouldContainExactlyInAnyOrder listOf(
			"databases/main_database",
			"databases/preference_database",
			"databases/points_database",
			"databases/debug_database",
			"manifest.json",
		)
		databases.forEach { (name, content) ->
			archiveEntries["databases/$name"] shouldBe content
			val checksum = MessageDigest.getInstance("SHA-256")
				.digest(content)
				.joinToString("") { "%02x".format(it) }
			archiveEntries.getValue("manifest.json").decodeToString()
				.contains(""""file":"$name","sizeBytes":${content.size},"sha256":"$checksum"""") shouldBe true
		}
	}

	private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
		buildMap {
			ZipInputStream(bytes.inputStream()).use { archive ->
				while (true) {
					val entry = archive.nextEntry ?: break
					put(entry.name, archive.readBytes())
					archive.closeEntry()
				}
			}
		}
}
