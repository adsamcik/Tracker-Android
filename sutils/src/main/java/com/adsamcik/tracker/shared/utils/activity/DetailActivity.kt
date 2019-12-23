package com.adsamcik.tracker.shared.utils.activity

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.updateLayoutParams
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.shared.utils.style.SystemBarStyle
import com.adsamcik.tracker.shared.utils.style.SystemBarStyleView


/**
 * Special abstract helper activity which provides custom AppBar and some other assist functions.
 * Custom AppBar was implemented to provide complete control over that piece of layout.
 */
abstract class DetailActivity : CoreUIActivity() {
	open fun onConfigure(configuration: Configuration) = Unit

	private val topPanelRoot: ViewGroup by lazy { findViewById<ViewGroup>(R.id.top_panel_root) }
	private val contentDetailRoot: ViewGroup by lazy { findViewById<ViewGroup>(R.id.content_detail_root) }

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		val configuration = Configuration()
		onConfigure(configuration)

		styleController.watchNotificationBar(
				SystemBarStyleView(
						window,
						layer = configuration.titleBarLayer,
						style = SystemBarStyle.LayerColor
				)
		)

		val navigationBarStyleView = configuration.navigationBarStyleView ?: SystemBarStyleView(
				window,
				layer = configuration.titleBarLayer,
				style = if (configuration.fitSystemWindows) {
					SystemBarStyle.LayerColor
				} else {
					SystemBarStyle.Translucent
				}
		)

		styleController.watchNavigationBar(navigationBarStyleView)


		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_content_detail)

		findViewById<Button>(R.id.back_button).setOnClickListener { onBackPressed() }


		val desiredElevation = configuration.elevation
				?: DEFAULT_TITLE_BAR_ELEVATION.dp * configuration.titleBarLayer
		topPanelRoot.elevation = kotlin.math.max(0, desiredElevation).toFloat()

		val topBarStyleView = StyleView(topPanelRoot, configuration.titleBarLayer)
		styleController.watchView(topBarStyleView)

		if (configuration.useColorControllerForContent) {
			styleController.watchView(StyleView(contentDetailRoot, 0))
		}

		if (configuration.fitSystemWindows) {
			findViewById<ViewGroup>(R.id.detail_root).fitsSystemWindows = true
			styleController.watchView(
					StyleView(
							findViewById<View>(R.id.detail_root),
							0,
							maxDepth = 0
					)
			)
		} else {
			topPanelRoot.updateLayoutParams<LinearLayoutCompat.LayoutParams> {
				height += DisplayAssist.getStatusBarHeight(this@DetailActivity)
			}
		}
	}

	override fun onBackPressed() {
		finish()
	}

	@MainThread
	override fun setTitle(title: CharSequence) {
		super.setTitle(title)
		findViewById<TextView>(R.id.content_detail_title).text = title
	}

	@MainThread
	override fun setTitle(titleId: Int) {
		title = getString(titleId)
	}

	@MainThread
	protected fun addAction(
			@DrawableRes iconRes: Int,
			@StringRes description: Int,
			onClickListener: View.OnClickListener
	) {
		val button = AppCompatImageButton(this).apply {
			layoutParams = ViewGroup.LayoutParams(48.dp, 48.dp)
			contentDescription = getString(description)
			setImageResource(iconRes)
			scaleType = ImageView.ScaleType.CENTER_INSIDE

			background = Assist.getBackgroundDrawable(Color.BLACK)

			setOnClickListener(onClickListener)
		}

		topPanelRoot.addView(button)
	}

	private fun <T : ViewGroup> initContentLayout(
			layout: T,
			scrollable: Boolean,
			addContentPadding: Boolean
	) {
		val lp = LinearLayoutCompat.LayoutParams(
				LinearLayoutCompat.LayoutParams.MATCH_PARENT,
				if (scrollable) LinearLayoutCompat.LayoutParams.WRAP_CONTENT else LinearLayoutCompat.LayoutParams.MATCH_PARENT
		)
		if (addContentPadding) {
			val padding = resources.getDimension(R.dimen.content_padding).toInt()
			layout.setPadding(padding, padding, padding, padding)
		}
		layout.layoutParams = lp
		layout.id = CONTENT_ID
	}

	private fun createLinearContentLayout(
			scrollable: Boolean,
			addContentPadding: Boolean
	): LinearLayoutCompat {
		val linearLayout = LinearLayoutCompat(this)
		initContentLayout(linearLayout, scrollable, addContentPadding)
		linearLayout.orientation = LinearLayoutCompat.VERTICAL
		return linearLayout
	}

	private fun createConstraintContentLayout(
			scrollable: Boolean,
			addContentPadding: Boolean
	): androidx.constraintlayout.widget.ConstraintLayout {
		val constraintLayout = androidx.constraintlayout.widget.ConstraintLayout(this)
		initContentLayout(constraintLayout, scrollable, addContentPadding)
		return constraintLayout
	}


	@Suppress("UNCHECKED_CAST")
	private fun <T : ViewGroup> createContentLayout(
			isScrollable: Boolean,
			addContentPadding: Boolean,
			tClass: Class<T>
	): T {
		//Casts are safe and due to limitations it was done this way. Can be revisited in the future for improvements.
		return when (tClass) {
			LinearLayout::class.java -> createLinearContentLayout(
					isScrollable,
					addContentPadding
			) as T
			androidx.constraintlayout.widget.ConstraintLayout::class.java -> createConstraintContentLayout(
					isScrollable,
					addContentPadding
			) as T
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
		contentDetailRoot.addView(contentParent)
		return contentParent
	}

	protected fun createFrameContentLayout(addContentPadding: Boolean): FrameLayout {
		val frameLayout = FrameLayout(this)
		initContentLayout(frameLayout, false, addContentPadding)
		contentDetailRoot.addView(frameLayout)
		return frameLayout
	}

	/**
	 * Creates content parent which can be scrolled
	 *
	 * @param addContentPadding Should default content padding be set?
	 */
	protected fun createLinearScrollableContentParent(addContentPadding: Boolean): LinearLayoutCompat {
		val scrollView = ScrollView(this)
		val lp = LinearLayoutCompat.LayoutParams(
				LinearLayoutCompat.LayoutParams.MATCH_PARENT,
				LinearLayoutCompat.LayoutParams.MATCH_PARENT
		)
		scrollView.layoutParams = lp

		val contentParent = createLinearContentLayout(false, addContentPadding)

		scrollView.addView(contentParent)

		contentDetailRoot.addView(scrollView)
		return contentParent
	}

	/**
	 * Creates content parent which can be scrolled
	 *
	 * @param addContentPadding Should default content padding be set?
	 */
	protected fun <T : ViewGroup> createScrollableContentParent(
			addContentPadding: Boolean,
			tClass: Class<T>
	): T {
		val scrollView = ScrollView(this)
		val lp = LinearLayoutCompat.LayoutParams(
				LinearLayoutCompat.LayoutParams.MATCH_PARENT,
				LinearLayoutCompat.LayoutParams.MATCH_PARENT
		)
		scrollView.layoutParams = lp

		val contentParent = createContentLayout(false, addContentPadding, tClass)

		scrollView.addView(contentParent)

		contentDetailRoot.addView(scrollView)
		return contentParent
	}

	protected fun <RootView : View> inflateContent(@LayoutRes resource: Int): RootView {
		val rootContentView = layoutInflater.inflate(resource, contentDetailRoot, false)
		contentDetailRoot.addView(rootContentView)
		@Suppress("unchecked_cast")
		return rootContentView as RootView
	}

	companion object {
		const val CONTENT_ID: Int = 2668368
		const val DEFAULT_TITLE_BAR_ELEVATION = 4
	}

	data class Configuration(
			var titleBarLayer: Int = 0,
			var elevation: Int? = null,
			var useColorControllerForContent: Boolean = false,
			var fitSystemWindows: Boolean = true,
			var navigationBarStyleView: SystemBarStyleView? = null
	)
}

