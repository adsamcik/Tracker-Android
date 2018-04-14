package com.adsamcik.signalcollector.file


import com.crashlytics.android.Crashlytics
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Utility class for easier creation of zip files
 */
data class Compress
/**
 * Constructor for Compression class
 * It will keep stream active until you call finish
 *
 * @param file Zip file (will be overwritten if already exists)
 * @throws FileNotFoundException throws exception if there is issue with writting to zip file
 */
@Throws(FileNotFoundException::class)
constructor(private val file: File) {

    private val zipStream: ZipOutputStream = ZipOutputStream(BufferedOutputStream(FileOutputStream(file)))

    /**
     * Add file to zip from string data
     *
     * @param data data
     * @param name name of the file
     * @return true if successfully added
     */
    fun add(data: String, name: String): Boolean {
        val entry = ZipEntry(name)
        val buffer = data.toByteArray()
        return try {
            zipStream.putNextEntry(entry)
            zipStream.write(buffer, 0, buffer.size)
            true
        } catch (e: IOException) {
            Crashlytics.logException(e)
            false
        }

    }

    /**
     * Add file to zip
     *
     * @param file file
     * @return true if successfully added
     */
    fun add(file: File): Boolean {
        BufferedInputStream(FileInputStream(file), BUFFER).use { origin ->
            val entry = ZipEntry(file.name)
            return try {
                zipStream.putNextEntry(entry)
                origin.use {
                    it.copyTo(zipStream)
                }
                true
            } catch (e: IOException) {
                e.printStackTrace()
                Crashlytics.logException(e)
                false
            }
        }
    }

    /**
     * Adds file to the zip file using +=
     */
    operator fun plusAssign(file: File) {
        add(file)
    }

    /**
     * Adds all files from array to the zip file using +=
     */
    operator fun plusAssign(files: Array<File>) {
        files.forEach { file -> add(file) }
    }

    /**
     * Call after you're done writting into the zip file
     *
     * @return zip file
     * @throws IOException Exception is thrown if error occurred during stream closing
     */
    @Throws(IOException::class)
    fun finish(): File {
        zipStream.close()
        return file
    }

    companion object {
        private const val BUFFER = 2048
    }
}
