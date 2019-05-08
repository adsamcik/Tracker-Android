package com.adsamcik.signalcollector.common.activity

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.CallSuper
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import com.adsamcik.signalcollector.common.R
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.misc.extension.dpAsPx
import kotlinx.android.synthetic.main.activity_content_detail.*

/**
 * Special abstract helper activity which provides custom AppBar and some other assist functions.
 * Custom AppBar was implemented to provide complete control over that piece of layout.
 */
abstract class DetailActivity : AppCompatActivity() {
	//todo this control over bar layer is kinda awkward, improve it
	protected var titleBarLayer = 1
	protected lateinit var colorController: ColorController

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_content_detail)

		back_button.setOnClickListener { onBackPressed() }

		val titleBarRoot = back_button.parent as View
		titleBarRoot.elevation = if (titleBarLayer <= 0) 0f else (titleBarLayer * 4.dpAsPx).toFloat()

		colorController = ColorManager.createController().also {
			it.watchView(ColorView(titleBarRoot, titleBarLayer, recursive = true, rootIsBackground = true))
		}
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
		val lp = LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.MATCH_PARENT, if (scrollable) LinearLayoutCompat.LayoutParams.WRAP_CONTENT else LinearLayoutCompat.LayoutParams.MATCH_PARENT)
		if (addContentPadding) {
			val padding = resources.getDimension(R.dimen.content_padding).toInt()
			layout.setPadding(padding, padding, padding, padding)
		}
		layout.layoutParams = lp
		layout.id = CONTENT_ID
	}

	private fun createLinearContentLayout(scrollable: Boolean, addContentPadding: Boolean): LinearLayoutCompat {
		val linearLayout = LinearLayoutCompat(this)
		initContentLayout(linearLayout, scrollable, addContentPadding)
		linearLayout.orientation = LinearLayoutCompat.VERTICAL
		return linearLayout
	}

	private fun createConstraintContentLayout(scrollable: Boolean, addContentPadding: Boolean): androidx.constraintlayout.widget.ConstraintLayout {
		val constraintLayout = androidx.constraintlayout.widget.ConstraintLayout(this)
		initContentLayout(constraintLayout, scrollable, addContentPadding)
		return constraintLayout
	}


	@Suppress("UNCHECKED_CAST")
	private fun <T : ViewGroup> createContentLayout(scrollable: Boolean, addContentPadding: Boolean, tClass: Class<T>): T {
		//Casts are safe and due to limitations it was done this way. Can be revisited in the future for improvements.
		return when (tClass) {
			LinearLayout::class.java -> createLinearContentLayout(scrollable, addContentPadding) as T
			androidx.constraintlayout.widget.ConstraintLayout::class.java -> createConstraintContentLayout(scrollable, addContentPadding) as T
			FrameLayout::class.java -> createFrameContentLayout(addContentPadding) as T
			else -> throw NotImplementedError("Support for ${tClass.name} is not implemented")
		}
	}


	/**
	 * Creates basic content parent
	 *
	 * @param addContentPadding Should default content padding be set?
	 */
	protected fun createLinearContentParent(addContentPadding: Boolean): LinearLayoutCompat {
		val contentParent = createLinearContentLayout(false, addContentPadding)
		content_detail_root.addView(contentParent)
		return contentParent
	}

	protected fun createFrameContentLayout(addContentPadding: Boolean): FrameLayout {
		val frameLayout = FrameLayout(this)
		initContentLayout(frameLayout, false, addContentPadding)
		content_detail_root.addView(frameLayout)
		return frameLayout
	}

	/**
	 * Creates content parent which can be scrolled
	 *
	 * @param addContentPadding Should default content padding be set?
	 */
	protected fun createLinearScrollableContentParent(addContentPadding: Boolean): LinearLayoutCompat {
		val scrollView = ScrollView(this)
		val lp = LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.MATCH_PARENT, LinearLayoutCompat.LayoutParams.MATCH_PARENT)
		scrollView.layoutParams = lp

		val contentParent = createLinearContentLayout(false, addContentPadding)

		scrollView.addView(contentParent)

		content_detail_root.addView(scrollView)
		return contentParent
	}

	/**
	 * Creates content parent which can be scrolled
	 *
	 * @param addContentPadding Should default content padding be set?
	 */
	protected fun <T : ViewGroup> createScrollableContentParent(addContentPadding: Boolean, tClass: Class<T>): T {
		val scrollView = ScrollView(this)
		val lp = LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.MATCH_PARENT, LinearLayoutCompat.LayoutParams.MATCH_PARENT)
		scrollView.layoutParams = lp

		val contentParent = createContentLayout(false, addContentPadding, tClass)

		scrollView.addView(contentParent)

		content_detail_root.addView(scrollView)
		return contentParent
	}

	protected fun inflateContent(@LayoutRes resource: Int) {
		layoutInflater.inflate(resource, content_detail_root, true)
	}

	override fun onDestroy() {
		ColorManager.recycleController(colorController)
		super.onDestroy()
	}

	companion object {
		const val CONTENT_ID: Int = 2668368
	}
}
