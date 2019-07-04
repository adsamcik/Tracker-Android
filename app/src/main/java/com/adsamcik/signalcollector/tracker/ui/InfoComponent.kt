package com.adsamcik.signalcollector.tracker.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.appcompat.widget.LinearLayoutCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.color.StyleController
import com.adsamcik.signalcollector.common.color.StyleView
import com.adsamcik.signalcollector.common.extension.dp
import com.adsamcik.signalcollector.common.extension.layoutInflater

/**
 * Component that shows custom data with title and items.
 * Supports StyleController
 *
 */
class InfoComponent : FrameLayout {
	private lateinit var titleTextView: TextView

	private lateinit var root: ViewGroup

	private var items: MutableMap<String, TextView> = mutableMapOf()

	private var styleController: StyleController? = null

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
		val inflater = context.layoutInflater

		root = inflater.inflate(R.layout.layout_tracker_card, this, false)
				.also { (this as ViewGroup).addView(it) }
				.let { it as ViewGroup }

		titleTextView = findViewById(R.id.tracker_item_title)
	}

	private fun initializeAttrs(context: Context, attrs: AttributeSet?) {
		val a = context.obtainStyledAttributes(attrs, R.styleable.InfoComponent)
		val drawable = a.getDrawable(R.styleable.InfoComponent_titleImage)
		val title = a.getString(R.styleable.InfoComponent_title)

		setTitle(drawable, title)

		a.recycle()
	}

	/**
	 * Sets title of an [InfoComponent]
	 */
	fun setTitle(drawable: Drawable?, title: String?) {
		if (title != null) {
			titleTextView.text = title
		}

		if (drawable != null) {
			titleTextView.setCompoundDrawables(drawable, null, null, null)
		}
	}

	private fun createTextView(text: String, @StyleRes style: Int): TextView {
		val textView = TextView(context, null, style)

		textView.layoutParams = LinearLayoutCompat.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
			setMargins(0, 4.dp, 0, 4.dp)
		}

		textView.text = text

		root.addView(textView)

		return textView
	}

	/**
	 * Sets a [StyleController] that should manage colors for this [InfoComponent].
	 * Add and remove watch is handled automatically.
	 */
	internal fun setColorManager(styleController: StyleController) {
		this.styleController = styleController
		styleController.watchView(StyleView(this, 1))
	}

	/**
	 * Adds primary text to the [InfoComponent].
	 *
	 * @param identifier id of the text
	 * @param text text
	 */
	fun addPrimaryText(identifier: String, text: String) {
		val textView = createTextView(text, com.google.android.material.R.style.TextAppearance_AppCompat_Body1)
		items[identifier] = textView
	}

	/**
	 * Adds secondary text to the [InfoComponent].
	 *
	 * @param identifier id of the text
	 * @param text text
	 */
	fun addSecondaryText(identifier: String, text: String) {
		val textView = createTextView(text, com.google.android.material.R.style.TextAppearance_AppCompat_Body2)
		items[identifier] = textView
	}

	private fun getItem(identifier: String) = items[identifier]
			?: throw IndexOutOfBoundsException("identifier $identifier is not present in the map")

	/**
	 * Updates text with passed [identifier] to the value of [text]
	 *
	 * @param identifier id of the text to be update
	 * @param text text
	 */
	fun setText(identifier: String, text: String) {
		val item = getItem(identifier)
		item.text = text
	}

	/**
	 * Updates visibility of the text with [identifier] to the value of [visibility]
	 *
	 * @param identifier id of the text to be update
	 * @param visibility One of [View.VISIBLE], [View.INVISIBLE], or [View.GONE].
	 */
	fun setVisibility(identifier: String, visibility: Int) {
		val item = getItem(identifier)
		item.visibility = visibility
	}

	/**
	 * Detach the [InfoComponent] from the view. Automatically removes it from [StyleController] if it was set.
	 */
	fun detach() {
		(parent as ViewGroup).removeView(this)
		styleController?.stopWatchingView(this)
		styleController = null
	}
}