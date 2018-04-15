package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import kotlinx.android.synthetic.main.activity_content_detail.*

/**
 * Special abstract helper activity which provides custom AppBar and some other assist functions.
 * Custom AppBar was implemented to provide complete control over that piece of layout.
 */
abstract class DetailActivity : AppCompatActivity() {
    protected var colorManager: ColorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_detail)
        back_button.setOnClickListener({ _ -> onBackPressed() })

        colorManager = ColorSupervisor.createColorManager(this)
        colorManager!!.watchView(ColorView(back_button.parent as View, 1, true, false))
    }

    override fun onBackPressed() {
        finish()
    }

    override fun setTitle(title: CharSequence) {
        super.setTitle(title)
        setTitle(title as String)
    }

    override fun setTitle(titleId: Int) {
        super.setTitle(titleId)
        title = getString(titleId)
    }

    private fun createContentLayout(scrollable: Boolean, addContentPadding: Boolean): LinearLayout {
        val linearLayout = LinearLayout(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (scrollable) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT)
        if (addContentPadding) {
            val padding = resources.getDimension(R.dimen.activity_horizontal_margin).toInt()
            linearLayout.setPadding(padding, padding, padding, padding)
        }
        linearLayout.layoutParams = lp
        linearLayout.id = CONTENT_ID

        linearLayout.orientation = LinearLayout.VERTICAL
        return linearLayout
    }

    /**
     * Creates basic content parent
     *
     * @param addContentPadding Should default content padding be set?
     */
    protected fun createContentParent(addContentPadding: Boolean): LinearLayout {
        val root = findViewById<LinearLayout>(R.id.content_detail_root)
        val contentParent = createContentLayout(false, addContentPadding)
        root.addView(contentParent)
        return contentParent
    }

    /**
     * Creates content parent which can be scrolled
     *
     * @param addContentPadding Should default content padding be set?
     */
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

    override fun onDestroy() {
        if(colorManager != null)
            ColorSupervisor.recycleColorManager(colorManager!!)
        colorManager = null
        super.onDestroy()
    }

    companion object {
        const val CONTENT_ID = 2668368
    }
}
