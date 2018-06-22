package com.adsamcik.signalcollector.utility

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.Snackbar
import android.view.View
import com.adsamcik.signalcollector.extensions.dpAsPx

/**
 * Custom behavior that allows specific view to be modified instead of direct child of coordinator layout
 */
class BottomBarBehavior(private val targetView: View) : androidx.coordinatorlayout.widget.CoordinatorLayout.Behavior<androidx.constraintlayout.widget.ConstraintLayout>() {
    private val dp16 = 16.dpAsPx

    /**
     * Used to determine whether the target view moved
     */
    private var last: Float = Float.MIN_VALUE

    private var layoutChange = 0f
    private var ignore = false

    override fun layoutDependsOn(parent: androidx.coordinatorlayout.widget.CoordinatorLayout?, child: androidx.constraintlayout.widget.ConstraintLayout?, dependency: View?): Boolean =
            dependency is com.google.android.material.snackbar.Snackbar.SnackbarLayout

    override fun onDependentViewChanged(parent: androidx.coordinatorlayout.widget.CoordinatorLayout?, child: androidx.constraintlayout.widget.ConstraintLayout?, dependency: View?): Boolean {
        var changed = false
        if (dependency is com.google.android.material.snackbar.Snackbar.SnackbarLayout) {
            if (last == Float.MIN_VALUE) {
                ignore = false
                last = targetView.translationY
            }

            if (last != targetView.translationY) {
                ignore = false
                layoutChange = 0.0f
                return false
            }

            val diff = dependency.y - (targetView.y + targetView.height + dp16)

            if (diff < 0) {
                layoutChange += diff
                targetView.translationY += diff
                changed = true
            } else if (layoutChange < 0) {
                if (diff > layoutChange) {
                    targetView.translationY -= layoutChange
                    layoutChange = 0f
                } else {
                    layoutChange += diff
                    targetView.translationY += diff
                }
                changed = true
            }

            last = targetView.translationY
        }
        return changed
    }

    override fun onDependentViewRemoved(parent: androidx.coordinatorlayout.widget.CoordinatorLayout?, child: androidx.constraintlayout.widget.ConstraintLayout?, dependency: View?) {
        //Ensures that the view does not get stuck in out of the way position
        targetView.translationY += layoutChange
        last = Float.MIN_VALUE
        super.onDependentViewRemoved(parent, child, dependency)
    }
}