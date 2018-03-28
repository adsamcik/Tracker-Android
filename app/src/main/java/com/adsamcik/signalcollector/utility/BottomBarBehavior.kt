package com.adsamcik.signalcollector.utility

import android.support.constraint.ConstraintLayout
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.Snackbar
import android.view.View
import com.adsamcik.signalcollector.uitools.dpAsPx

class BottomBarBehavior(private val targetView: View) : CoordinatorLayout.Behavior<ConstraintLayout>() {
    private val dp16 = 16.dpAsPx

    private var initial: Float? = null
    private var last: Float = 0f

    private var ignore = false
    private var navbarHeight = 0

    private var change = 0f

    override fun layoutDependsOn(parent: CoordinatorLayout?, child: ConstraintLayout?, dependency: View?): Boolean =
            dependency is Snackbar.SnackbarLayout

    override fun onDependentViewChanged(parent: CoordinatorLayout?, child: ConstraintLayout?, dependency: View?): Boolean {
        if (dependency is Snackbar.SnackbarLayout) {
            if (initial == null) {
                initial = targetView.translationY
                last = targetView.translationY
                ignore = false
            }

            if (ignore)
                return false

            if (last != targetView.translationY) {
                ignore = true
                return false
            }

            val diff = dependency.y - (targetView.y + targetView.height + dp16)
            if (diff < 0) {
                change += diff
                targetView.translationY += diff
            } else if (change < 0) {
                if (diff > change) {
                    targetView.translationY -= change
                    change = 0f
                } else {
                    change += diff
                    targetView.translationY += diff
                }
            }

            last = targetView.translationY

        }
        return true
    }

    override fun onDependentViewRemoved(parent: CoordinatorLayout?, child: ConstraintLayout?, dependency: View?) {
        if (!ignore)
            targetView.translationY = initial!!
        initial = null
        super.onDependentViewRemoved(parent, child, dependency)
    }
}