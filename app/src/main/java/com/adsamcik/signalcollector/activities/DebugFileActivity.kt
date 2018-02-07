package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import com.adsamcik.signals.base.storage.FileStore

class DebugFileActivity : DetailActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileName = intent.getStringExtra("fileName")!!
        val directory = intent.getStringExtra("directory")!!
        setTitle(fileName)
        val tv = TextView(this)
        val layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tv.layoutParams = layoutParams
        val content = FileStore.loadString(FileStore.file(directory, fileName))
        tv.text = content
        createScrollableContentParent(true).addView(tv)
    }
}
