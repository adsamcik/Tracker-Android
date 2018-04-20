package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.support.constraint.ConstraintLayout
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import com.adsamcik.signalcollector.extensions.dpAsPx

class StatusActivity : DetailActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = createScrollableContentParent(true, ConstraintLayout::class.java)
        var lastId = createPair("Test2", "true", layout, null)
        lastId = createPair("Test1", "true", layout, lastId)
        lastId = createPair("Test3", "true", layout, lastId)
    }

    fun createPair(titleString: String, valueString: String, parent: ViewGroup, aboveId: Int?): Int {
        val title = TextView(this)
        val titleParams = ConstraintLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

        if (aboveId != null)
            titleParams.topToBottom = aboveId

        title.layoutParams = titleParams
        title.text = titleString
        title.id = View.generateViewId()

        parent.addView(title)

        val value = TextView(this)
        val valueParams = ConstraintLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

        valueParams.leftToRight = title.id
        valueParams.topToTop = title.id
        valueParams.marginStart = 16.dpAsPx

        value.layoutParams = valueParams
        value.text = valueString


        parent.addView(value)


        return title.id
    }
}
