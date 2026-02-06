package com.adsamcik.tracker.shared.base.io

import java.io.File

/**
 * Abstraction over file system access, allowing injection of test fakes.
 *
 * This interface abstracts away direct `Context.filesDir`, `Context.cacheDir`,
 * and `Context.getExternalFilesDir()` calls, making code testable without
 * Android or Robolectric.
 *
 * Usage in production:
 * ```kotlin
 * class DefaultFileResolver(private val context: Context) : FileResolver {
 *     override val filesDir: File get() = context.filesDir
 *     override val cacheDir: File get() = context.cacheDir
 *     override fun getExternalFilesDir(type: String?): File? = context.getExternalFilesDir(type)
 * }
 * ```
 *
 * Usage in tests:
 * ```kotlin
 * val fakeResolver = FakeFileResolver(tempDir)
 * val exporter = CrashExporter(fakeResolver)
 * ```
 */
interface FileResolver {
    /** Internal files directory (equivalent to Context.filesDir). */
    val filesDir: File

    /** Cache directory (equivalent to Context.cacheDir). */
    val cacheDir: File

    /**
     * External files directory (equivalent to Context.getExternalFilesDir).
     * @param type The type of files directory to return (e.g., Environment.DIRECTORY_DOCUMENTS)
     * @return The path of the directory, or null if external storage is unavailable.
     */
    fun getExternalFilesDir(type: String?): File?

    /**
     * Resolve a file relative to filesDir.
     * Convenience method for `File(filesDir, name)`.
     */
    fun resolveInternal(name: String): File = File(filesDir, name)

    /**
     * Resolve a file relative to cacheDir.
     * Convenience method for `File(cacheDir, name)`.
     */
    fun resolveCache(name: String): File = File(cacheDir, name)
}
