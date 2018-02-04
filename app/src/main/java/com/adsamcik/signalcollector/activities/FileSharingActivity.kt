package com.adsamcik.signalcollector.activities

import android.content.Intent
import android.os.Bundle
import android.support.design.widget.CoordinatorLayout
import android.support.v4.content.FileProvider
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.adsamcik.signals.utilities.components.BottomSheetMenu
import com.adsamcik.signals.utilities.components.SnackMaker
import com.adsamcik.signals.utilities.storage.Compress
import com.crashlytics.android.Crashlytics
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*

class FileSharingActivity : DetailActivity() {
    private var shareableDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val files = DataStore.getDir(this).listFiles { _, name -> name.startsWith(DataStore.DATA_FILE) || name.startsWith(DataStore.DATA_CACHE_FILE) }
        if (files.isEmpty()) {
            val tv = TextView(this)
            tv.setText(R.string.share_nothing_to_share)
            tv.gravity = Gravity.CENTER_HORIZONTAL
            createContentParent(true).addView(tv)
        } else {
            val fileNames = files.map { file -> file.name }

            val parent = createContentParent(false)
            val layout = (layoutInflater.inflate(R.layout.layout_file_share, parent) as ViewGroup).getChildAt(parent.childCount - 1) as CoordinatorLayout
            val listView = layout.findViewById<ListView>(R.id.share_list_view)
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, fileNames)
            listView.adapter = adapter
            listView.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

            val shareOnClickListener = View.OnClickListener { v ->
                val sba = listView.checkedItemPositions
                val temp = ArrayList<String>()
                for (i in fileNames.indices)
                    if (sba.get(i)) {
                        temp.add(fileNames[i])
                        DataFile(DataStore.file(this, fileNames[i]), null, Signin.getUserID(this), DataFile.STANDARD).close()
                    }

                if (temp.size == 0)
                    Toast.makeText(this, R.string.share_nothing_to_share, Toast.LENGTH_SHORT).show()
                else {
                    val compress: Compress
                    try {
                        compress = Compress(DataStore.file(this, System.currentTimeMillis().toString()))
                    } catch (e: FileNotFoundException) {
                        Crashlytics.logException(e)
                        SnackMaker(v).showSnackbar(R.string.error_general)
                        return@OnClickListener
                    }

                    for (fileName in temp)
                        if (!compress.add(DataStore.file(this, fileName))) {
                            SnackMaker(v).showSnackbar(R.string.error_general)
                            return@OnClickListener
                        }

                    val c: File
                    try {
                        c = compress.finish()
                    } catch (e: IOException) {
                        Crashlytics.logException(e)
                        return@OnClickListener
                    }

                    val target = File(c.parent + File.separatorChar + SHAREABLE_DIR_NAME + File.separatorChar + c.name + ".zip")
                    shareableDir = File(c.parent + File.separatorChar + SHAREABLE_DIR_NAME)
                    if (shareableDir!!.exists() || shareableDir!!.mkdir()) {
                        if (c.renameTo(target)) {
                            val fileUri = FileProvider.getUriForFile(
                                    this@FileSharingActivity,
                                    "com.asdamcik.signalcollector.fileprovider",
                                    target)
                            val shareIntent = Intent()
                            shareIntent.action = Intent.ACTION_SEND
                            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
                            shareIntent.type = "application/zip"
                            startActivityForResult(Intent.createChooser(shareIntent, resources.getText(R.string.export_share_button)), SHARE_RESULT)

                        }
                    }

                    target.deleteOnExit()
                    shareableDir!!.deleteOnExit()
                }
            }

            val bottomSheetMenu = BottomSheetMenu(layout)
            bottomSheetMenu.addItem(R.string.export_share_button, shareOnClickListener)

            bottomSheetMenu.addItem(R.string.select_all, View.OnClickListener{ _ ->
                for (i in fileNames.indices)
                    listView.setItemChecked(i, true)
            })

            bottomSheetMenu.addItem(R.string.deselect_all, View.OnClickListener { _ ->
                for (i in fileNames.indices)
                    listView.setItemChecked(i, false)
            })

            bottomSheetMenu.showHide(750)
        }

        setTitle(R.string.export_share_button)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SHARE_RESULT)
            DataStore.recursiveDelete(shareableDir!!)

        finish()
    }

    companion object {
        private const val SHARE_RESULT = 1
        private const val SHAREABLE_DIR_NAME = "shareable"
    }
}
