package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reuses the bounded ZIP extractor; no child reaches an importer before the whole wrapper checks. */
internal class SessionBundleStager(
	private val extractor: ArchiveExtractor = ZipArchiveExtractor(),
) {
	/** Copy a picked document once; its private bytes supply both the job hash and child dispatch. */
	suspend fun stageSource(context: Context, source: DocumentFile): StagedSessionSource {
		val directory = ownedDirectory(context)
		val destination = File.createTempFile("session-source-", ".trackersession", directory)
		try {
			val input = source.openInputStream(context)
				?: throw java.io.IOException("Cannot open session file")
			input.use { stream -> destination.outputStream().use { output ->
				val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
				var count = 0L
				while (true) {
					currentCoroutineContext().ensureActive()
					val read = stream.read(buffer)
					if (read < 0) break
					count += read
					if (count > SessionBundleManifestCodecV1.MAX_ARCHIVE_BYTES)
						throw InvalidSessionBundle("Session file exceeds the size limit")
					output.write(buffer, 0, read)
				}
			} }
			return StagedSessionSource(destination)
		} catch (failure: Throwable) {
			if (destination.exists() && !destination.delete())
				failure.addSuppressed(IOException("Session import copy could not be removed"))
			throw failure
		}
	}

	suspend fun stage(context: Context, file: DocumentFile): StagedSessionBundle {
		val directory = ownedDirectory(context)
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
							if (destination.exists() && !destination.delete())
								failure.addSuppressed(IOException("Session import data could not be removed"))
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
			staged.values.forEach { child ->
				if (child.file.exists() && !child.file.delete())
					failure.addSuppressed(IOException("Session import data could not be removed"))
			}
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
		private const val STALE_CHILD_AGE_MS = 24L * 60L * 60L * 1_000L
		private val OWNED_FILE = Regex("(?:session-child-[-0-9]+\\.tmp|session-source-[-0-9]+\\.trackersession)")
		val TYPED_SOURCE_FORMAT_IDS = setOf(
			"tracker-portable-location", "portable-steps-v1", "portable-ambient-steps-v1",
			"portable-activity-v1", "portable-pressure-v1", "portable-wifi-v1",
			"portable-cell-v1",
		)

		/** Full clear calls this after WorkManager has acknowledged import cancellation. */
		fun clearOwnedStaging(context: Context) {
			val cache = context.cacheDir.canonicalFile
			val directory = File(cache, CACHE_DIRECTORY)
			if (!directory.exists()) return
			if (directory.canonicalFile.parentFile != cache || !directory.isDirectory)
				throw IOException("Session import cache cannot be cleaned safely")
			directory.listFiles()?.forEach { candidate ->
				if (!OWNED_FILE.matches(candidate.name) || !candidate.isFile ||
					candidate.canonicalFile.parentFile != directory.canonicalFile || !candidate.delete())
					throw IOException("Session import cache could not be cleaned")
			} ?: throw IOException("Session import cache could not be inspected")
			if (!directory.delete()) throw IOException("Session import cache could not be cleaned")
		}

		private fun ownedDirectory(context: Context): File {
			val cache = context.cacheDir.canonicalFile
			val directory = File(cache, CACHE_DIRECTORY).apply {
				if (!isDirectory && !mkdirs()) throw IOException("Cannot prepare session import")
			}
			if (directory.canonicalFile.parentFile != cache)
				throw IOException("Session import cache is outside app storage")
			cleanupStaleChildren(directory, System.currentTimeMillis())
			return directory
		}

		private fun cleanupStaleChildren(directory: File, nowMs: Long) {
			directory.listFiles()?.forEach { candidate ->
				if (OWNED_FILE.matches(candidate.name) && candidate.isFile &&
					candidate.canonicalFile.parentFile == directory.canonicalFile &&
					candidate.lastModified() < nowMs - STALE_CHILD_AGE_MS && !candidate.delete())
					throw IOException("Old session import cache could not be cleaned")
			} ?: throw IOException("Session import cache could not be inspected")
		}
	}
}

internal class StagedSessionSource(val file: File) : Closeable {
	val document: DocumentFile get() = DocumentFile.fromFile(file)
	override fun close() {
		if (file.exists() && !file.delete()) throw IOException("Session import copy could not be removed")
	}
}

internal class StagedSessionBundle(
	val manifest: SessionBundleManifestV1,
	val manifestBytes: ByteArray,
	val children: List<StagedSessionChild>,
) : Closeable {
	override fun close() {
		var failed = false
		children.forEach { child ->
			if (child.file.exists() && !child.file.delete()) failed = true
		}
		if (failed) throw IOException("Session import data could not be removed")
	}

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
