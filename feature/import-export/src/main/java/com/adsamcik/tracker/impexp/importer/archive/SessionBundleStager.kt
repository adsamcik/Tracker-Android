package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reuses the bounded ZIP extractor; no child reaches an importer before the whole wrapper checks. */
internal class SessionBundleStager(
	private val extractor: ArchiveExtractor? = null,
) {
	/** Copy a picked document once; its private bytes supply both the job hash and child dispatch. */
	suspend fun stageSource(context: Context, source: DocumentFile): StagedSessionSource {
		currentCoroutineContext().ensureActive()
		val directory = ownedDirectory(context)
		val destination = File.createTempFile("session-source-", ".trackersession", directory)
		try {
			val input = source.openInputStream(context)
				?: throw IOException("Cannot open session file")
			input.use { stream ->
				destination.outputStream().use { output ->
					copyBounded(stream, output, SessionBundleManifestCodecV1.MAX_ARCHIVE_BYTES,
						sizeLimitMessage = "Session file exceeds the size limit")
				}
			}
			currentCoroutineContext().ensureActive()
			return StagedSessionSource(destination)
		} catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
			// Remove the picked-file copy even on cancellation or Error, then rethrow the original failure.
			if (destination.exists() && !destination.delete()) {
				failure.addSuppressed(IOException("Session import copy could not be removed"))
			}
			throw failure
		}
	}

	suspend fun stage(context: Context, file: DocumentFile): StagedSessionBundle {
		currentCoroutineContext().ensureActive()
		val directory = ownedDirectory(context)
		val staged = mutableMapOf<String, StagedSessionChild>()
		var manifestBytes: ByteArray? = null
		var totalBytes = 0L
		val boundedExtractor = extractor ?: ZipArchiveExtractor(
			maximumEntryBytes = { name ->
				if (name == SessionBundleManifestCodecV1.MANIFEST_PATH) {
					SessionBundleManifestCodecV1.MAX_MANIFEST_BYTES.toLong()
				} else {
					minOf(SessionBundleManifestCodecV1.MAX_CHILD_BYTES,
						SessionBundleManifestCodecV1.MAX_TOTAL_BYTES - totalBytes)
				}
			},
			maximumTotalBytes = SessionBundleManifestCodecV1.MAX_TOTAL_BYTES +
				SessionBundleManifestCodecV1.MAX_MANIFEST_BYTES,
			maximumCompressedInputBytes = SessionBundleManifestCodecV1.MAX_ARCHIVE_BYTES,
		)
		try {
			val opened = boundedExtractor.extract(context, file, shouldExtract = { entry ->
				currentCoroutineContext().ensureActive()
				requireUniqueMember(entry.fileName, manifestBytes != null, staged)
				true
			}) { stream ->
				stream.use { input ->
					val name = input.fileName
					requireUniqueMember(name, manifestBytes != null, staged)
					if (name == SessionBundleManifestCodecV1.MANIFEST_PATH) {
						val bytes = ByteArrayOutputStream()
						copyBounded(input, bytes, SessionBundleManifestCodecV1.MAX_MANIFEST_BYTES.toLong())
						manifestBytes = bytes.toByteArray()
					} else {
						totalBytes += stageChild(input, directory, totalBytes, staged)
					}
				}
			}
			currentCoroutineContext().ensureActive()
			if (!opened) {
				throw InvalidSessionBundle("Cannot open session file")
			}
			return finishBundle(manifestBytes, staged)
		} catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
			// Remove previously staged children on any extraction or validation failure, including cancellation or Error.
			staged.values.forEach { child ->
				if (child.file.exists() && !child.file.delete()) {
					failure.addSuppressed(IOException("Session import data could not be removed"))
				}
			}
			throw failure
		}
	}

	private fun requireUniqueMember(
		name: String,
		manifestPresent: Boolean,
		staged: Map<String, StagedSessionChild>,
	) {
		if (name == SessionBundleManifestCodecV1.MANIFEST_PATH) {
			if (manifestPresent) {
				throw InvalidSessionBundle("Duplicate session details")
			}
		} else if (staged.size >= SessionBundleManifestCodecV1.MAX_CHILDREN || staged.containsKey(name)) {
			throw InvalidSessionBundle("Duplicate or excessive session data files")
		}
	}

	private suspend fun stageChild(
		input: FileImportStream,
		directory: File,
		previousTotalBytes: Long,
		staged: MutableMap<String, StagedSessionChild>,
	): Long {
		val destination = File.createTempFile("session-child-", ".tmp", directory)
		try {
			val digest = MessageDigest.getInstance("SHA-256")
			val size = destination.outputStream().use { output ->
				copyBounded(input, output, SessionBundleManifestCodecV1.MAX_CHILD_BYTES, digest)
			}
			if (previousTotalBytes + size > SessionBundleManifestCodecV1.MAX_TOTAL_BYTES) {
				throw InvalidSessionBundle("Session data exceeds the file limit")
			}
			staged[input.fileName] =
				StagedSessionChild(input.fileName, input.receiptKey, destination, size, digest.digest().toHex())
			return size
		} catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
			// Remove the current child if copying, hashing or publication fails, including cancellation or Error.
			if (destination.exists() && !destination.delete()) {
				failure.addSuppressed(IOException("Session import data could not be removed"))
			}
			throw failure
		}
	}

	private suspend fun finishBundle(
		manifestBytes: ByteArray?,
		staged: Map<String, StagedSessionChild>,
	): StagedSessionBundle {
		val encodedManifest = manifestBytes ?: throw InvalidSessionBundle("Session details are missing")
		val manifest = SessionBundleManifestCodecV1.decode(encodedManifest)
		if (manifest.children.map { it.path }.toSet() != staged.keys) {
			throw InvalidSessionBundle("Session data files do not match the details")
		}
		manifest.children.forEach { declared ->
			currentCoroutineContext().ensureActive()
			validateDeclaredChild(declared, staged.getValue(declared.path), manifest.selectedSources)
		}
		currentCoroutineContext().ensureActive()
		return StagedSessionBundle(manifest, encodedManifest, manifest.children.map { staged.getValue(it.path) })
	}

	private fun validateDeclaredChild(
		declared: SessionBundleChildV1,
		actual: StagedSessionChild,
		selectedSources: List<String>,
	) {
		if (actual.sizeBytes != declared.sizeBytes || actual.sha256 != declared.sha256) {
			throw InvalidSessionBundle("Session data file was changed or damaged")
		}
		requireRegisteredChildFormat(declared)
		if (sourceForFormat(declared.formatId) !in selectedSources) {
			throw InvalidSessionBundle("Session contains an unselected source")
		}
	}

	private fun requireRegisteredChildFormat(declared: SessionBundleChildV1) {
		val extension = declared.path.substringAfterLast('.', "")
		val registered = FormatRegistry.allEntries().singleOrNull { entry ->
			entry.descriptor.id == declared.formatId && entry.importer != null &&
				entry.importer.supportedExtensions.any { it.equals(extension, ignoreCase = true) }
		}
		if (declared.formatId !in TYPED_SOURCE_FORMAT_IDS || registered == null) {
			throw InvalidSessionBundle("Unsupported session data file")
		}
	}

	private fun sourceForFormat(formatId: String): String = when (formatId) {
		LocationFileJsonV1.FORMAT -> "location"
		"portable-steps-v1", "portable-ambient-steps-v1" -> "steps"
		"portable-activity-v1" -> "activity"
		"portable-pressure-v1" -> "pressure"
		RadioSourceFileV2.WIFI_FORMAT -> "wifi"
		RadioSourceFileV2.CELL_FORMAT -> "cell"
		else -> throw InvalidSessionBundle("Unsupported session data file")
	}

	private suspend fun copyBounded(
		input: InputStream,
		output: java.io.OutputStream,
		limit: Long,
		digest: MessageDigest? = null,
		sizeLimitMessage: String = "Session data file is too large",
	): Long {
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var count = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val read = input.read(buffer)
			currentCoroutineContext().ensureActive()
			if (read < 0) {
				break
			}
			count += read
			if (count > limit) {
				throw InvalidSessionBundle(sizeLimitMessage)
			}
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
			LocationFileJsonV1.FORMAT, "portable-steps-v1", "portable-ambient-steps-v1",
			"portable-activity-v1", "portable-pressure-v1", RadioSourceFileV2.WIFI_FORMAT,
			RadioSourceFileV2.CELL_FORMAT,
		)

		/** Full clear calls this after WorkManager has acknowledged import cancellation. */
		fun clearOwnedStaging(context: Context) {
			val cache = context.cacheDir.canonicalFile
			val directory = File(cache, CACHE_DIRECTORY)
			if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
				return
			}
			if (directory.canonicalFile != directory.absoluteFile ||
				directory.canonicalFile.parentFile != cache || !directory.isDirectory) {
				throw IOException("Session import cache cannot be cleaned safely")
			}
			clearStagedChildren(directory)
			if (!directory.delete()) {
				throw IOException("Session import cache could not be cleaned")
			}
		}

		private fun clearStagedChildren(directory: File) {
			directory.listFiles()?.forEach { candidate ->
				if (!candidate.isFile || candidate.canonicalFile != candidate.absoluteFile ||
					candidate.canonicalFile.parentFile != directory.canonicalFile || !candidate.delete()) {
					throw IOException("Session import cache could not be cleaned")
				}
			} ?: throw IOException("Session import cache could not be inspected")
		}

		private fun ownedDirectory(context: Context): File {
			val cache = context.cacheDir.canonicalFile
			val directory = File(cache, CACHE_DIRECTORY).apply {
				if (!isDirectory && !mkdirs()) {
					throw IOException("Cannot prepare session import")
				}
			}
			if (directory.canonicalFile != directory.absoluteFile ||
				directory.canonicalFile.parentFile != cache) {
				throw IOException("Session import cache is outside app storage")
			}
			cleanupStaleChildren(directory, System.currentTimeMillis())
			return directory
		}

		private fun cleanupStaleChildren(directory: File, nowMs: Long) {
			directory.listFiles()?.forEach { candidate ->
				if (isOwnedChild(candidate, directory) &&
					candidate.lastModified() < nowMs - STALE_CHILD_AGE_MS && !candidate.delete()) {
					throw IOException("Old session import cache could not be cleaned")
				}
			} ?: throw IOException("Session import cache could not be inspected")
		}

		private fun isOwnedChild(candidate: File, directory: File): Boolean =
			OWNED_FILE.matches(candidate.name) && candidate.isFile &&
				candidate.canonicalFile.parentFile == directory.canonicalFile
	}
}

internal class StagedSessionSource(val file: File) : Closeable {
	val document: DocumentFile get() = DocumentFile.fromFile(file)
	override fun close() {
		if (file.exists() && !file.delete()) {
			throw IOException("Session import copy could not be removed")
		}
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
			if (child.file.exists() && !child.file.delete()) {
				failed = true
			}
		}
		if (failed) {
			throw IOException("Session import data could not be removed")
		}
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
	(byte.toInt() and UNSIGNED_BYTE_MASK).toString(HEXADECIMAL_RADIX).padStart(2, '0')
}

private const val UNSIGNED_BYTE_MASK = 0xff
private const val HEXADECIMAL_RADIX = 16
