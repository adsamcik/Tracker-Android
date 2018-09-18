package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
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
        content_detail_title.text = title
    }

    override fun setTitle(titleId: Int) {
        title = getString(titleId)
    }

    private fun <T : ViewGroup> initContentLayout(layout: T, scrollable: Boolean, addContentPadding: Boolean) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (scrollable) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT)
        if (addContentPadding) {
            val padding = resources.getDimension(R.dimen.activity_horizontal_margin).toInt()
            layout.setPadding(padding, padding, padding, padding)
        }
        layout.layoutParams = lp
        layout.id = CONTENT_ID
    }

    private fun createLinearContentLayout(scrollable: Boolean, addContentPadding: Boolean): LinearLayout {
        val linearLayout = LinearLayout(this)
        initContentLayout(linearLayout, scrollable, addContentPadding)
        linearLayout.orientation = LinearLayout.VERTICAL
        return linearLayout
    }

    private fun createConstraintContentLayout(scrollable: Boolean, addContentPadding: Boolean): androidx.constraintlayout.widget.ConstraintLayout {
        val constraintLayout = androidx.constraintlayout.widget.ConstraintLayout(this)
        initContentLayout(constraintLayout, scrollable, addContentPadding)
        return constraintLayout
    }

    @Suppress("UNCHECKED_CAST")
    protected fun<T : ViewGroup> createContentLayout(scrollable: Boolean, addContentPadding: Boolean, tClass: Class<T>) : T {
        //Casts are safe and due to limitations it was done this way. Can be revisited in the future for improvements.
        return when(tClass) {
            LinearLayout::class.java -> createLinearContentLayout(scrollable, addContentPadding) as T
            androidx.constraintlayout.widget.ConstraintLayout::class.java -> createConstraintContentLayout(scrollable, addContentPadding) as T
            else -> throw RuntimeException("Support for ${tClass.name} is not implemented")
        }
    }


    /**
     * Creates basic content parent
     *
     * @param addContentPadding Should default content padding be set?
     */
    protected fun createLinearContentParent(addContentPadding: Boolean): LinearLayout {
        val root = findViewById<LinearLayout>(R.id.content_detail_root)
        val contentParent = createLinearContentLayout(false, addContentPadding)
        root.addView(contentParent)
        return contentParent
    }

    /**
     * Creates content parent which can be scrolled
     *
     * @param addContentPadding Should default content padding be set?
     */
    protected fun createLinearScrollableContentParent(addContentPadding: Boolean): LinearLayout {
        val root = findViewById<LinearLayout>(R.id.content_detail_root)
        val scrollView = ScrollView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        scrollView.layoutParams = lp

        val contentParent = createLinearContentLayout(false, addContentPadding)

        scrollView.addView(contentParent)

        root.addView(scrollView)
        return contentParent
    }

    /**
     * Creates content parent which can be scrolled
     *
     * @param addContentPadding Should default content padding be set?
     */
    protected fun <T : ViewGroup> createScrollableContentParent(addContentPadding: Boolean, tClass: Class<T>): T {
        val root = findViewById<LinearLayout>(R.id.content_detail_root)
        val scrollView = ScrollView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        scrollView.layoutParams = lp

        val contentParent = createContentLayout(false, addContentPadding, tClass)

        scrollView.addView(contentParent)

        root.addView(scrollView)
        return contentParent
    }

    override fun onDestroy() {
        if (colorManager != null)
            ColorSupervisor.recycleColorManager(colorManager!!)
        colorManager = null
        super.onDestroy()
    }

    companion object {
        const val CONTENT_ID = 2668368
    }
}
