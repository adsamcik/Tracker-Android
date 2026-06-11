package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.shared.base.io.FileResolver
import java.io.File

/**
 * In-memory/temporary directory fake implementation of [FileResolver] for unit tests.
 *
 * This allows testing file-based operations without requiring Android Context or
 * Robolectric. Files are written to a configurable root directory (typically a
 * temporary directory created by the test framework).
 *
 * Usage:
 * ```kotlin
 * @TempDir
 * lateinit var tempDir: File
 *
 * @Test
 * fun `test file operations`() {
 *     val fakeResolver = FakeFileResolver(tempDir)
 *     val exporter = CrashExporter(fakeResolver)
 *
 *     exporter.export(crashData)
 *
 *     // Verify file was created
 *     val crashFile = fakeResolver.resolveInternal("crashes/crash.log")
 *     crashFile.exists() shouldBe true
 * }
 * ```
 *
 * Alternative usage with auto-cleanup:
 * ```kotlin
 * @Test
 * fun `test with auto temp dir`() {
 *     FakeFileResolver.withTempDir { resolver ->
 *         val exporter = CrashExporter(resolver)
 *         exporter.export(crashData)
 *         resolver.resolveInternal("crashes").exists() shouldBe true
 *     }
 *     // tempDir automatically cleaned up
 * }
 * ```
 */
class FakeFileResolver(
    private val rootDir: File
) : FileResolver {

    override val filesDir: File
        get() = File(rootDir, "files").also { it.mkdirs() }

    override val cacheDir: File
        get() = File(rootDir, "cache").also { it.mkdirs() }

    private val externalFilesDir: File
        get() = File(rootDir, "external").also { it.mkdirs() }

    override fun getExternalFilesDir(type: String?): File? {
        val dir = if (type != null) {
            File(externalFilesDir, type)
        } else {
            externalFilesDir
        }
        dir.mkdirs()
        return dir
    }

    /** Clear all files (useful between tests). */
    fun clear() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
        externalFilesDir.deleteRecursively()
    }

    companion object {
        /**
         * Create a FakeFileResolver with a temporary directory that is
         * automatically cleaned up after the block completes.
         */
        inline fun <T> withTempDir(block: (FakeFileResolver) -> T): T {
            val tempDir = kotlin.io.path.createTempDirectory("fake_file_resolver").toFile()
            return try {
                block(FakeFileResolver(tempDir))
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
