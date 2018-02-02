package com.adsamcik.signals.utilities.storage

import android.util.MalformedJsonException
import com.crashlytics.android.Crashlytics
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.*
import java.security.InvalidParameterException

object FileStore {
    private const val BUFFER = 1024

    /**
     * Checks if file exists
     *
     * @param fileName file name
     * @return existence of file
     */
    fun file(parent: String, fileName: String): File = File(parent, fileName)

    /**
     * Checks if file exists
     *
     * @param fileName file name
     * @return existence of file
     */
    fun file(parent: File, fileName: String): File = File(parent, fileName)

    fun dataFile(parent: File, dataFileName: String): File {
        if (!parent.isDirectory)
            throw InvalidParameterException("Parent must be directory")

        val files = parent.listFiles { _, s -> s.startsWith(dataFileName) }
        return if (files.size == 1) files[0] else File(parent, dataFileName)
    }

    /**
     * Saves string to file
     *
     * @param file file
     * @param data string data
     */
    fun saveString(file: File, data: String, append: Boolean): Boolean {
        try {
            FileOutputStream(file, append).use { outputStream ->
                outputStream.channel.lock()
                val osw = OutputStreamWriter(outputStream)
                osw.write(data)
                osw.close()
            }
        } catch (e: Exception) {
            Crashlytics.logException(e)
            e.printStackTrace()
            return false
        }

        return true
    }

    /**
     * Appends string to file. If file does not exists, one is created. Should not be combined with other methods.
     * Allows file overriding and custom empty array detection.
     *
     * @param file   file
     * @param data   Json array to append
     * @param append Should existing file be overriden with current data
     * @return Failure
     * @throws MalformedJsonException Thrown when json array is in incorrect format
     */
    @Throws(MalformedJsonException::class)
    fun saveAppendableJsonArray(file: File, data: String, append: Boolean, firstArrayItem: Boolean = false): Boolean {
        val sb = StringBuilder(data)
        if (sb[0] == ',')
            throw MalformedJsonException("Json starts with ','. That is not right.")
        val firstChar = if (!append || firstArrayItem || !file.exists() || file.length() == 0L) '[' else ','
        when (firstChar) {
            ',' -> if (sb[0] == '[')
                sb.setCharAt(0, ',')
            else
                sb.insert(0, ',')
            '[' -> if (sb[0] == '{')
                sb.insert(0, '[')
        }

        if (sb[sb.length - 1] == ']')
            sb.deleteCharAt(sb.length - 1)
        
        return saveString(file, sb.toString(), append)
    }

    /**
     * Converts loadStringAsBuilder to string and handles nulls
     *
     * @param file file to load
     * @return content of file (empty if file has no content or does not exists)
     */
    fun loadString(file: File): String? {
        if (!file.exists())
            return null

        return try {
            return FileInputStream(file).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Crashlytics.logException(e)
            null
        }
    }

    /**
     * Loads json array that was saved with append method
     *
     * @param file file to load
     * @return proper json array
     */
    fun loadAppendableJsonArray(file: File): String? {
        val str = loadString(file)
        if (str != null) {
            val sb = StringBuilder(str)
            if (sb.isNotEmpty()) {
                if (sb[sb.length - 1] != ']')
                    sb.append(']')
                return sb.toString()
            }
        }
        return null
    }

    /**
     * Loads whole json array and than finds last object and converts it to java object
     *
     * @param file file to load
     * @param tClass   class of the resulting object
     * @return last object of json array or null
     */
    fun <T> loadLastFromAppendableJsonArray(file: File, tClass: Class<T>): T? {
        val str = loadString(file) ?: return null
        (str.length - 1 downTo 0)
                .filter { str[it] == '{' }
                .forEach {
                    return try {
                        Gson().fromJson(str.substring(it), tClass)
                    } catch (e: JsonSyntaxException) {
                        Crashlytics.logException(e)
                        null
                    }
                }
        return null
    }

    @Throws(InvalidParameterException::class, FileNotFoundException::class)
    fun loadLastAscii(file: File, n: Int): String? {
        val bytes = loadLastBytes(file, n)
        return if (bytes != null) String(bytes) else null
    }

    @Throws(InvalidParameterException::class, FileNotFoundException::class)
    fun loadLastBytes(file: File, n: Int): ByteArray? {
        if (n > file.length())
            throw InvalidParameterException()

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - n)
                val arr = ByteArray(n)
                raf.read(arr, 0, n)
                return arr
            }
        } catch (e: IOException) {
            Crashlytics.logException(e)
            return null
        }

    }

    /**
     * Tries to delete file multiple times based on `maxRetryCount`.
     * After every unsuccessfull try there is 50ms sleep so you should ensure that this function does not run on UI thread.
     * If it is unsuccessfull after all tries it will attempt to mark the file for deletion but will return false at this time because it the deletation can't be guranteed
     *
     * @param file          file to delete
     * @param maxRetryCount maximum retry count
     * @return true if file was deleted, false otherwise
     */
    fun delete(file: File?, maxRetryCount: Int = 3): Boolean {
        if (file == null)
            throw InvalidParameterException("file is null")

        var retryCount = 0
        while (true) {
            if (!file.exists() || file.delete())
                return true

            if (++retryCount < maxRetryCount)
                break

            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                // Restore the interrupted done
                Thread.currentThread().interrupt()
            }

        }

        file.deleteOnExit()
        return false
    }

    /**
     * Recursively deletes all files in a directory
     *
     * @param file File or directory
     * @return True if successfull
     */
    fun recursiveDelete(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()
                    .filterNot { recursiveDelete(it) }
                    .forEach { return false }
        }
        return delete(file)
    }

    fun clearFolder(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()
                    .filterNot { delete(it) }
                    .forEach { return false }
        } else
            return false
        return true
    }

    /**
     * Rename file
     *
     * @param file        file to rename
     * @param newFileName new file name
     * @return success
     */
    fun rename(file: File, newFileName: String): Boolean =
            file.renameTo(File(file.parentFile, newFileName))
}
