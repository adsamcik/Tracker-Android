package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Reuses the bounded ZIP extractor; no child reaches an importer before the whole wrapper checks. */
internal class SessionBundleStager(
	private val extractor: ArchiveExtractor = ZipArchiveExtractor(),
) {
	suspend fun stage(context: Context, file: DocumentFile): StagedSessionBundle {
		val directory = File(context.cacheDir, CACHE_DIRECTORY).apply {
			if (!isDirectory && !mkdirs()) throw InvalidSessionBundle("Cannot prepare session import")
		}
		val staged = mutableMapOf<String, StagedSessionChild>()
		var manifestBytes: ByteArray? = null
		var totalBytes = 0L
		try {
			val opened = extractor.extract(context, file, shouldExtract = { true }) { stream ->
				stream.use { input ->
					val name = input.fileName
					if (name == SessionBundleManifestCodecV1.MANIFEST_PATH) {
						if (manifestBytes != null) throw InvalidSessionBundle("Duplicate session details")
						val bytes = ByteArrayOutputStream()
						copyBounded(input, bytes, SessionBundleManifestCodecV1.MAX_MANIFEST_BYTES.toLong())
						manifestBytes = bytes.toByteArray()
					} else {
						if (staged.size >= SessionBundleManifestCodecV1.MAX_CHILDREN || staged.containsKey(name))
							throw InvalidSessionBundle("Duplicate or excessive session data files")
						val destination = File.createTempFile("session-child-", ".tmp", directory)
						try {
							val digest = MessageDigest.getInstance("SHA-256")
							val size = destination.outputStream().use { output ->
								copyBounded(input, output, SessionBundleManifestCodecV1.MAX_CHILD_BYTES, digest)
							}
							totalBytes += size
							if (totalBytes > SessionBundleManifestCodecV1.MAX_TOTAL_BYTES)
								throw InvalidSessionBundle("Session data exceeds the file limit")
							staged[name] = StagedSessionChild(name, input.receiptKey, destination, size,
								digest.digest().toHex())
						} catch (failure: Throwable) {
							destination.delete()
							throw failure
						}
					}
				}
			}
			if (!opened) throw InvalidSessionBundle("Cannot open session file")
			val encodedManifest = manifestBytes
				?: throw InvalidSessionBundle("Session details are missing")
			val manifest = SessionBundleManifestCodecV1.decode(encodedManifest)
			if (manifest.children.map { it.path }.toSet() != staged.keys)
				throw InvalidSessionBundle("Session data files do not match the details")
			manifest.children.forEach { declared ->
				val actual = staged.getValue(declared.path)
				if (actual.sizeBytes != declared.sizeBytes || actual.sha256 != declared.sha256)
					throw InvalidSessionBundle("Session data file was changed or damaged")
				val extension = declared.path.substringAfterLast('.', "")
				val registered = FormatRegistry.allEntries().singleOrNull { entry ->
					entry.descriptor.id == declared.formatId && entry.importer != null &&
					entry.importer.supportedExtensions.any { it.equals(extension, ignoreCase = true) }
				}
				if (declared.formatId !in TYPED_SOURCE_FORMAT_IDS || registered == null)
					throw InvalidSessionBundle("Unsupported session data file")
			}
			return StagedSessionBundle(manifest, encodedManifest,
				manifest.children.map { staged.getValue(it.path) })
		} catch (failure: Throwable) {
			staged.values.forEach { it.file.delete() }
			throw failure
		}
	}

	private fun copyBounded(
		input: FileImportStream,
		output: java.io.OutputStream,
		limit: Long,
		digest: MessageDigest? = null,
	): Long {
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var count = 0L
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			count += read
			if (count > limit) throw InvalidSessionBundle("Session data file is too large")
			output.write(buffer, 0, read)
			digest?.update(buffer, 0, read)
		}
		return count
	}

	internal companion object {
		const val CACHE_DIRECTORY = "session-bundle-import"
		val TYPED_SOURCE_FORMAT_IDS = setOf(
			"tracker-portable-location", "portable-steps-v1", "portable-ambient-steps-v1",
			"portable-activity-v1", "portable-pressure-v1", "portable-wifi-v1",
			"portable-cell-v1",
		)
	}
}

internal class StagedSessionBundle(
	val manifest: SessionBundleManifestV1,
	val manifestBytes: ByteArray,
	val children: List<StagedSessionChild>,
) : Closeable {
	override fun close() { children.forEach { it.file.delete() } }

	fun metadataStream(): FileImportStream = FileImportStream(
		fileName = SessionBundleManifestCodecV1.MANIFEST_PATH,
		receiptKey = "session-bundle-metadata-v1",
		streamProvider = { ByteArrayInputStream(manifestBytes) },
	)
}

internal data class StagedSessionChild(
	val path: String,
	val receiptKey: String,
	val file: File,
	val sizeBytes: Long,
	val sha256: String,
) {
	fun importStream(): FileImportStream = FileImportStream(
		fileName = path,
		receiptKey = receiptKey,
		streamProvider = file::inputStream,
	)
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
	(byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
