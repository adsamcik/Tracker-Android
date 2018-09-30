package com.adsamcik.signalcollector.activities

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.components.BottomSheetMenu
import com.adsamcik.signalcollector.exports.IExport
import com.adsamcik.signalcollector.exports.file.IReadableFile
import com.adsamcik.signalcollector.exports.file.ReadableArchivedFile
import com.adsamcik.signalcollector.exports.file.ReadableFile
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.file.LongTermStore
import com.adsamcik.signalcollector.uitools.ColorView
import java.io.File
import java.util.zip.ZipFile

/**
 * Activity that allows user to share his collected data to other apps that support zip files
 */
class FileSharingActivity : DetailActivity() {
    private var shareableDir: File? = null
    private lateinit var root: ViewGroup

    private val files = ArrayList<IReadableFile>()


    private fun addAllFiles() {
        val temp = DataStore.getDir(this).listFiles { _, name -> name.startsWith(DataStore.DATA_FILE) || name.startsWith(DataStore.TMP_NAME) }
        files.addAll(temp.map { ReadableFile(it) })
        val storedFiles = LongTermStore.listFiles(this)

        storedFiles.forEach {
            val zipFile = ZipFile(it)
            val entryEnumeration = zipFile.entries()

            while (entryEnumeration.hasMoreElements()) {
                files.add(ReadableArchivedFile(zipFile, entryEnumeration.nextElement()))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addAllFiles()

        if (files.isEmpty()) {
            val tv = TextView(this)
            tv.setText(R.string.share_nothing_to_share)
            tv.gravity = Gravity.CENTER_HORIZONTAL
            root = createLinearContentParent(true)
            root.addView(tv)
        } else {

            val fileNames = files.map { it.name }
            root = createLinearContentParent(false)
            val layout = (layoutInflater.inflate(R.layout.layout_file_share, root) as ViewGroup).getChildAt(root.childCount - 1) as androidx.coordinatorlayout.widget.CoordinatorLayout
            val listView = layout.findViewById<ListView>(R.id.share_list_view)
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, fileNames)
            listView.adapter = adapter
            listView.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

            val shareOnClickListener = View.OnClickListener { _ ->
                val sba = listView.checkedItemPositions

                val selectedFiles = files.filterIndexed { i, _ -> sba[i] }


                if (selectedFiles.isEmpty())
                    Toast.makeText(this, R.string.share_nothing_to_share, Toast.LENGTH_SHORT).show()
                else {
                    val shareableDir = File(DataStore.getDir(this), SHAREABLE_DIR_NAME)
                    if (shareableDir.exists() || shareableDir.mkdir()) {
                        this.shareableDir = shareableDir
                        val exporterType = intent.extras!![EXPORTER_KEY] as Class<*>
                        val exporter = exporterType.newInstance() as IExport
                        val result = exporter.export(selectedFiles, shareableDir)

                        if (result.isSuccessful) {
                            val fileUri = FileProvider.getUriForFile(
                                    this@FileSharingActivity,
                                    "com.adsamcik.signalcollector.fileprovider",
                                    result.file!!)
                            val shareIntent = Intent()
                            shareIntent.action = Intent.ACTION_SEND
                            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
                            shareIntent.type = result.mime
                            startActivityForResult(Intent.createChooser(shareIntent, resources.getText(R.string.export_share_button)), SHARE_RESULT)

                            result.file.deleteOnExit()
                        } else {
                            Toast.makeText(this, R.string.error_general, Toast.LENGTH_SHORT).show()
                        }

                        shareableDir.deleteOnExit()
                    }
                }
            }

            val bottomSheetMenu = BottomSheetMenu(layout)
            bottomSheetMenu.addItem(R.string.export_share_button, shareOnClickListener)

            bottomSheetMenu.addItem(R.string.select_all, View.OnClickListener { _ ->
                for (i in fileNames.indices)
                    listView.setItemChecked(i, true)
            })

            bottomSheetMenu.addItem(R.string.deselect_all, View.OnClickListener { _ ->
                for (i in fileNames.indices)
                    listView.setItemChecked(i, false)
            })


            root.post {
                bottomSheetMenu.showHide(1000)
            }

            colorManager!!.watchView(ColorView(bottomSheetMenu.menuRoot, 0))
        }

        setTitle(R.string.export_share_button)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SHARE_RESULT)
            DataStore.recursiveDelete(shareableDir!!)

        finish()
    }

    companion object {
        private const val SHARE_RESULT = 1
        private const val SHAREABLE_DIR_NAME = "shareable"
        const val EXPORTER_KEY = "exporter"
    }
}
