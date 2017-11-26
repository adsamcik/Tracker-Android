package com.adsamcik.signalcollector.utility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewAnimationUtils

object Animate {
    /**
     * Circular reveal animation
     * Center is automatically set to the middle of the view
     * Initial radius is 0
     *
     * @param view view
     */
    fun revealShow(view: View) {
        val cx = view.width / 2
        val cy = view.height / 2
        revealShow(view, cx, cy, 0)
    }

    /**
     * Circular reveal animation
     *
     * @param view          view
     * @param cx            animation center x
     * @param cy            animation center y
     * @param initialRadius initial radius
     */
    fun revealShow(view: View, cx: Int, cy: Int, initialRadius: Int) {
        val finalRadius = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

        val anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, initialRadius.toFloat(), finalRadius)

        view.visibility = View.VISIBLE
        anim.start()
    }

    /**
     * Circular hide animation
     * Center is automatically set to the middle of the view
     * End radius is 0
     *
     * @param view           view
     * @param onDoneCallback callback when animation is done
     */
    fun revealHide(view: View, onDoneCallback: (() -> Unit)?) {
        val cx = view.width / 2
        val cy = view.height / 2
        revealHide(view, cx, cy, 0, onDoneCallback)
    }

    /**
     * Circular hide animation
     *
     * @param view           view
     * @param cx             animation center x
     * @param cy             animation center y
     * @param endRadius      end radius
     * @param onDoneCallback callback when animation is done
     */
    fun revealHide(view: View, cx: Int, cy: Int, endRadius: Int, onDoneCallback: (() -> Unit)?) {
        val initialRadius = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

        val anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, initialRadius, endRadius.toFloat())
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                view.visibility = View.INVISIBLE
                onDoneCallback?.invoke()
            }
        })
        anim.start()
    }

}
