package com.adsamcik.tracker.shared.utils.style.implementations

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.drawable.DrawableCompat
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable


/**
 * Drawable for legend color. Implements [StyleableForegroundDrawable].
 */
@Suppress("TooManyFunctions")
abstract class StyleableDrawableWrapper(drawable: GradientDrawable) : Drawable(), Drawable.Callback,
		StyleableForegroundDrawable {

	var drawable: GradientDrawable = drawable
		private set


	override fun draw(canvas: Canvas) {
		drawable.draw(canvas)
	}

	override fun onBoundsChange(bounds: Rect) {
		drawable.bounds = bounds
	}

	override fun setChangingConfigurations(configs: Int) {
		drawable.changingConfigurations = configs
	}

	override fun getChangingConfigurations(): Int {
		return drawable.changingConfigurations
	}

	override fun setDither(dither: Boolean) {
		drawable.setDither(dither)
	}

	override fun setFilterBitmap(filter: Boolean) {
		drawable.isFilterBitmap = filter
	}

	override fun setAlpha(alpha: Int) {
		drawable.alpha = alpha
	}

	override fun setColorFilter(cf: ColorFilter?) {
		drawable.colorFilter = cf
	}

	override fun isStateful(): Boolean {
		return drawable.isStateful
	}

	override fun setState(stateSet: IntArray): Boolean {
		return drawable.setState(stateSet)
	}

	override fun getState(): IntArray {
		return drawable.state
	}

	override fun jumpToCurrentState() {
		drawable.jumpToCurrentState()
	}

	override fun getCurrent(): Drawable {
		return drawable.current
	}

	override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
		return super.setVisible(visible, restart) || drawable.setVisible(visible, restart)
	}

	override fun getOpacity(): Int {
		return drawable.opacity
	}

	override fun getTransparentRegion(): Region? {
		return drawable.transparentRegion
	}

	override fun getIntrinsicWidth(): Int {
		return drawable.intrinsicWidth
	}

	override fun getIntrinsicHeight(): Int {
		return drawable.intrinsicHeight
	}

	override fun getMinimumWidth(): Int {
		return drawable.minimumWidth
	}

	override fun getMinimumHeight(): Int {
		return drawable.minimumHeight
	}

	override fun getPadding(padding: Rect): Boolean {
		return drawable.getPadding(padding)
	}

	override fun invalidateDrawable(who: Drawable) {
		invalidateSelf()
	}

	/**
	 * {@inheritDoc}
	 */
	override fun scheduleDrawable(
			who: Drawable,
			what: Runnable,
			`when`: Long
	) {
		scheduleSelf(what, `when`)
	}

	/**
	 * {@inheritDoc}
	 */
	override fun unscheduleDrawable(who: Drawable, what: Runnable) {
		unscheduleSelf(what)
	}

	override fun onLevelChange(level: Int): Boolean {
		return drawable.setLevel(level)
	}

	override fun setAutoMirrored(mirrored: Boolean) {
		DrawableCompat.setAutoMirrored(drawable, mirrored)
	}

	override fun isAutoMirrored(): Boolean {
		return DrawableCompat.isAutoMirrored(drawable)
	}

	override fun setTint(tint: Int) {
		DrawableCompat.setTint(drawable, tint)
	}

	override fun setTintList(tint: ColorStateList?) {
		DrawableCompat.setTintList(drawable, tint)
	}

	override fun setTintMode(tintMode: PorterDuff.Mode?) {
		DrawableCompat.setTintMode(drawable, tintMode!!)
	}

	override fun setHotspot(x: Float, y: Float) {
		DrawableCompat.setHotspot(drawable, x, y)
	}

	override fun setHotspotBounds(left: Int, top: Int, right: Int, bottom: Int) {
		DrawableCompat.setHotspotBounds(drawable, left, top, right, bottom)
	}

	/**
	 * Gets wrapped drawable
	 */
	fun getWrappedDrawable(): Drawable {
		return drawable
	}

	/**
	 * Sets wrapped drawable
	 */
	fun setWrappedDrawable(drawable: GradientDrawable) {
		this.drawable.callback = null
		this.drawable = drawable
		drawable.callback = this
	}
}
