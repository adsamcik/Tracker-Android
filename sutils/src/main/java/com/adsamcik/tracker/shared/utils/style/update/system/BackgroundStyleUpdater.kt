package com.adsamcik.tracker.shared.utils.style.update.system

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.utils.style.color.ColorFunctions

/**
 * Background style updater
 */
@Suppress("UNUSED_PARAMETER")
internal class BackgroundStyleUpdater {
	companion object {
		private const val COLOR_DIST_ANIMATION_THRESHOLD = 50
		private const val ANIMATION_DURATION = 1000L
	}

	@MainThread
	private fun updateColorDrawable(
			drawable: ColorDrawable,
			@ColorInt backgroundColor: Int,
			updateStyleData: StyleUpdater.UpdateStyleData
	) {
		val originalColor = drawable.color
		if (updateStyleData.isAnimationAllowed &&
				ColorFunctions.distance(
						originalColor,
						backgroundColor
				) > COLOR_DIST_ANIMATION_THRESHOLD) {
			val colorAnimation = ValueAnimator.ofObject(
					ArgbEvaluator(),
					originalColor,
					backgroundColor
			)
			colorAnimation.duration = ANIMATION_DURATION
			colorAnimation.addUpdateListener {
				drawable.color = it.animatedValue as Int
			}
			colorAnimation.start()
		} else {
			drawable.color = backgroundColor
		}
	}

	private fun updateRippleDrawable(
			drawable: RippleDrawable,
			@ColorInt backgroundColor: Int,
			updateStyleData: StyleUpdater.UpdateStyleData
	) {
		val nextLevel = ColorFunctions.getBackgroundLayerColor(
				backgroundColor,
				updateStyleData.backgroundLuminance,
				1
		)
		drawable.setColor(Assist.getPressedState(nextLevel))
		drawable.setTint(backgroundColor)
	}

	private fun updateGenericDrawable(
			drawable: Drawable,
			@ColorInt backgroundColor: Int,
			updateStyleData: StyleUpdater.UpdateStyleData
	) {
		drawable.setTint(backgroundColor)
		drawable.colorFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			BlendModeColorFilter(backgroundColor, BlendMode.SRC_IN)
		} else {
			@Suppress("DEPRECATION")
			(PorterDuffColorFilter(backgroundColor, PorterDuff.Mode.SRC_IN))
		}
	}

	/**
	 * Update style (colors) of background drawable
	 */
	fun updateDrawable(
			drawable: Drawable,
			@ColorInt backgroundColor: Int,
			updateStyleData: StyleUpdater.UpdateStyleData
	): Boolean {
		if (drawable.alpha == 0) return false

		drawable.mutate()
		when (drawable) {
			is ColorDrawable -> updateColorDrawable(drawable, backgroundColor, updateStyleData)
			is RippleDrawable -> updateRippleDrawable(drawable, backgroundColor, updateStyleData)
			else -> updateGenericDrawable(drawable, backgroundColor, updateStyleData)
		}
		return true
	}
}
