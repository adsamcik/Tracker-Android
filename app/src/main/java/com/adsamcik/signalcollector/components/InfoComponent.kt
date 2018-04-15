package com.adsamcik.signalcollector.components

import android.content.Context
import android.graphics.drawable.Drawable
import android.support.v4.content.ContextCompat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.extensions.dpAsPx


class InfoComponent : FrameLayout {
    private var titleTextView: TextView? = null

    private var root: ViewGroup? = null

    private var items: MutableMap<String, TextView> = mutableMapOf()

    private var colorManager: ColorManager? = null

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        initialize(context)
        initializeAttrs(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initialize(context)
        initializeAttrs(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initialize(context)
        initializeAttrs(context, attrs)
    }

    constructor(context: Context) : super(context) {
        initialize(context)
    }

    private fun initialize(context: Context) {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        inflater.inflate(R.layout.layout_component_info, this)
        root = this.getChildAt(0) as ViewGroup

        titleTextView = findViewById(R.id.tracker_item_title)
    }

    private fun initializeAttrs(context: Context, attrs: AttributeSet?) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.InfoComponent)
        val drawable = a.getDrawable(R.styleable.InfoComponent_titleImage)
        val title = a.getString(R.styleable.InfoComponent_title)

        setTitle(drawable, title)

        a.recycle()
    }

    fun setTitle(drawable: Drawable?, title: String?) {
        if (title != null)
            titleTextView!!.text = title

        if (drawable != null)
            titleTextView!!.setCompoundDrawables(drawable, null, null, null)
    }

    private fun createTextView(text: String): TextView {
        val textView = TextView(context)
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 8.dpAsPx)
        textView.layoutParams = lp
        textView.text = text

        root!!.addView(textView)

        return textView
    }

    internal fun setColorManager(colorManager: ColorManager) {
        this.colorManager = colorManager
        colorManager.watchElement(ColorView(this, 1, true, false, true))
    }

    private fun setTextViewTheme(textView: TextView, textSizeResource: Int, textColorResource: Int) {
        val resources = context.resources
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(textSizeResource))
        textView.setTextColor(ContextCompat.getColor(context, textColorResource))
    }

    fun addPrimaryText(identifier: String, text: String) {
        val textView = createTextView(text)
        setTextViewTheme(textView, R.dimen.primary_text_size, R.color.text_primary)
        items[identifier] = textView
    }

    fun addSecondaryText(identifier: String, text: String) {
        val textView = createTextView(text)
        setTextViewTheme(textView, R.dimen.secondary_text_size, R.color.text_secondary)
        items[identifier] = textView
    }

    fun setText(identifier: String, text: String) {
        items[identifier]?.text = text
    }

    fun setVisibility(identifier: String, visiblity: Int) {
        items[identifier]?.visibility = visiblity
    }

    fun detach() {
        (parent as ViewGroup).removeView(this)
        colorManager?.stopWatchingElement(this)
        colorManager = null
    }
}