package com.adsamcik.tracker.shared.base.io

import android.content.Context
import java.io.File

/**
 * Production implementation of [FileResolver] backed by Android Context.
 */
class DefaultFileResolver(private val context: Context) : FileResolver {
    override val filesDir: File
        get() = context.filesDir

    override val cacheDir: File
        get() = context.cacheDir

    override fun getExternalFilesDir(type: String?): File? = context.getExternalFilesDir(type)
}
