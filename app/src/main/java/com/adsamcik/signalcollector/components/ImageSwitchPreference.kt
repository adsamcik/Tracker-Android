package com.adsamcik.signalcollector.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.support.annotation.DrawableRes
import android.support.v7.preference.Preference
import android.support.v7.preference.PreferenceViewHolder
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.view.setMargins
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.utility.Assist


class ImageSwitchPreference : Preference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        initAttributes(context, attrs!!)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initAttributes(context, attrs!!)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initAttributes(context, attrs!!)
    }

    constructor(context: Context) : super(context)

    private fun initAttributes(context: Context, attrs: AttributeSet) {
        val resources = context.resources
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.ImageSwitchPreference)
        val titlesResource = attributes.getResourceId(R.styleable.ImageSwitchPreference_titles, 0)
        val drawablesResource = attributes.getResourceId(R.styleable.ImageSwitchPreference_drawables, 0)

        foregroundColors = attributes.getColorStateList(R.styleable.ImageSwitchPreference_foregroundStateColors) ?: ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected)), intArrayOf(Color.GRAY, Color.BLUE))

        attributes.recycle()

        val titles = resources.getStringArray(titlesResource)
        val drawables = resources.obtainTypedArray(drawablesResource)


        if (titles.size != drawables.length())
            throw RuntimeException("Drawables and titles are not equal in size")


        for (i in 0..titles.lastIndex) {
            addItem(titles[i], drawables.getResourceId(i, -1))
        }

        drawables.recycle()
    }

    private var foregroundColors: ColorStateList? = null

    private var items = ArrayList<SwitchItem>()
    private var textView: TextView? = null
    private var imageRoot: ViewGroup? = null

    private var initialized = false
    private val imageSizePx = Assist.dpToPx(context, 50)
    private val marginPx = Assist.dpToPx(context, 10)

    private var selected: Int = -1

    init {
        layoutResource = R.layout.layout_image_switch
    }

    fun addItem(title: String, @DrawableRes image: Int) {
        val item = SwitchItem(title, image)
        items.add(item)

        if (initialized)
            initializeItem(item, items.lastIndex)
    }

    private fun onClick(index: Int) {
        if (selected >= 0) {
            items[selected].imageView!!.isSelected = false
        }
        val newItem = items[index]
        newItem.imageView!!.isSelected = true
        textView!!.text = newItem.title

        selected = index
    }

    private fun initializeItem(item: SwitchItem, index: Int) {
        val view = ImageView(context)
        view.contentDescription = item.title
        view.setImageResource(item.drawable)
        view.imageTintList = foregroundColors
        val layoutParams = LinearLayout.LayoutParams(imageSizePx, imageSizePx)
        layoutParams.setMargins(marginPx)
        view.layoutParams = layoutParams
        view.setOnClickListener { onClick(index) }

        imageRoot!!.addView(view)
        item.imageView = view
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isClickable = false
        textView = holder.findViewById(R.id.title) as TextView
        imageRoot = holder.findViewById(R.id.option_root) as ViewGroup
        initialized = true
        items.forEachIndexed { index, switchItem ->
            initializeItem(switchItem, index)
        }
    }

    class SwitchItem(val title: String, @DrawableRes val drawable: Int, var imageView: ImageView? = null)
}