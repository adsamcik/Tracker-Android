package com.adsamcik.signalcollector.activities

import android.app.Activity
import android.os.Bundle
import android.support.v4.app.NavUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

abstract class DetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Preferences.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_detail)
        findViewById<View>(R.id.back_button).setOnClickListener({ _ -> NavUtils.navigateUpFromSameTask(this) })
    }

    fun setTitle(title: String) {
        val titleView = findViewById<TextView>(R.id.content_detail_title)
        titleView.text = title
    }

    override fun setTitle(title: CharSequence) {
        super.setTitle(title)
        setTitle(title as String)
    }

    override fun setTitle(titleId: Int) {
        super.setTitle(titleId)
        setTitle(getString(titleId))
    }

    private fun createContentLayout(scrollbable: Boolean, addContentPadding: Boolean): LinearLayout {
        val linearLayout = LinearLayout(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (scrollbable) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT)
        if (addContentPadding) {
            val padding = resources.getDimension(R.dimen.activity_horizontal_margin).toInt()
            linearLayout.setPadding(padding, padding, padding, padding)
        }
        linearLayout.layoutParams = lp

        linearLayout.orientation = LinearLayout.VERTICAL
        return linearLayout
    }

    protected fun createContentParent(addContentPadding: Boolean): LinearLayout {
        val root = findViewById<LinearLayout>(R.id.content_detail_root)
        val contentParent = createContentLayout(false, addContentPadding)
        root.addView(contentParent)
        return contentParent
    }

    protected fun createScrollableContentParent(addContentPadding: Boolean): LinearLayout {
        val root = findViewById<LinearLayout>(R.id.content_detail_root)
        val scrollView = ScrollView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        scrollView.layoutParams = lp

        val contentParent = createContentLayout(false, addContentPadding)

        scrollView.addView(contentParent)

        root.addView(scrollView)
        return contentParent
    }
}
