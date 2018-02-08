package com.adsamcik.signalcollector.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.support.v7.widget.AppCompatImageButton
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.adsamcik.signalcollector.utility.Assist
import kotlin.math.sign


class DraggableImageButton : AppCompatImageButton {
    val TAG = "DraggableImageButton"

    private val deadZone = Assist.dpToPx(context, 16)

    private var initialPosition: Point = Point()
    private var initialTranslation: PointF = PointF()
    private var targetTranslation: Point = Point()

    private var currentState = true
    private var touchInitialPosition: PointF = PointF()


    private var dragAxis = DragAxis.None

    private var targetView: View? = null
    private var anchor = DragTargetAnchor.TopLeft
    private var marginDp = 0

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setDrag(axis: DragAxis) {
        dragAxis = axis
    }

    fun setTarget(target: View, anchor: DragTargetAnchor, marginDp: Int) {
        this.targetView = target
        this.anchor = anchor
        this.marginDp = marginDp

        initialTranslation.x = translationX
        initialTranslation.y = translationY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val position = getLocationOnScreen(this)
        initialPosition.x = position[0]
        initialPosition.y = position[1]
    }

    private fun getLocationOnScreen(view: View): IntArray {
        val array = IntArray(2)
        view.getLocationOnScreen(array)
        return array
    }

    override fun performClick(): Boolean {
        super.performClick()

        if (targetView != null && this.dragAxis != DragAxis.None) {
            Log.d(TAG, "Click")
            moveToState(!currentState)
        }

        return true
    }

    private fun moveToState(state: Boolean) {
        var target: Float
        if (this.dragAxis == DragAxis.X || this.dragAxis == DragAxis.XY) {
            target = if (state) targetTranslation.x.toFloat() else initialTranslation.x
            animate(ValueAnimator.AnimatorUpdateListener { translationX = it.animatedValue as Float }, translationX, target)
        }

        if (this.dragAxis == DragAxis.Y || this.dragAxis == DragAxis.XY) {
            target = if (state) targetTranslation.y.toFloat() else initialTranslation.y
            animate(ValueAnimator.AnimatorUpdateListener { translationY = it.animatedValue as Float }, translationY, target)
        }

        currentState = state
    }

    private fun calculateEdgeOffset(target: View, targetAnchor: DragTargetAnchor): Point {
        return when (targetAnchor) {
            DragTargetAnchor.Top -> Point(target.width / 2 - width / 2, 0)
            DragTargetAnchor.TopRight -> Point(target.width - width, 0)
            DragTargetAnchor.Right -> Point(target.width - width, target.height / 2 - height / 2)
            DragTargetAnchor.BottomRight -> Point(target.width - width, target.height - height)
            DragTargetAnchor.Bottom -> Point(target.width / 2 - height / 2, target.height - height)
            DragTargetAnchor.BottomLeft -> Point(0, target.height - height)
            DragTargetAnchor.Left -> Point(0, target.height / 2 - height / 2)
            DragTargetAnchor.TopLeft -> Point(0, 0)
            DragTargetAnchor.Middle -> Point(target.width / 2 - height / 2, target.height / 2 - height / 2)
        }
    }

    private fun animate(updateListener: ValueAnimator.AnimatorUpdateListener, thisPosition: Float, targetPosition: Float) {
        val valueAnimator = ValueAnimator.ofFloat(thisPosition, targetPosition)

        valueAnimator.addUpdateListener(updateListener)

        valueAnimator.interpolator = LinearInterpolator()
        valueAnimator.duration = 200
        valueAnimator.start()
    }

    private fun between(firstConstraint: Int, secondConstraint: Int, number: Int): Boolean {
        return if (firstConstraint > secondConstraint)
            number in secondConstraint..firstConstraint
        else
            number in firstConstraint..secondConstraint
    }

    private fun between(firstConstraint: Int, secondConstraint: Int, number: Float): Boolean {
        return if (firstConstraint > secondConstraint)
            number in secondConstraint..firstConstraint
        else
            number in firstConstraint..secondConstraint
    }

    private fun calculateTargetTranslation() {
        val thisOnScreen = getLocationOnScreen(this)
        val targetOnScreen = getLocationOnScreen(targetView!!)
        val targetRelPos = calculateEdgeOffset(targetView!!, anchor)
        val targetX = ((targetOnScreen[0] - thisOnScreen[0]) + targetRelPos.x + translationX).toInt()
        val targetY = ((targetOnScreen[1] - thisOnScreen[1]) + targetRelPos.y + translationY).toInt()
        val marginPx = Assist.dpToPx(context, marginDp)

        targetTranslation.x = targetX - targetX.sign * marginPx
        targetTranslation.y = targetY - targetY.sign * marginPx
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val x = event!!.x
        val y = event.y
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                touchInitialPosition.x = event.rawX
                touchInitialPosition.y = event.rawY

                calculateTargetTranslation()
            }
            MotionEvent.ACTION_UP -> {
                val changeX = event.rawX - touchInitialPosition.x
                val changeY = event.rawY - touchInitialPosition.y
                val distanceX = Math.abs(changeX)
                val distanceY = Math.abs(changeY)

                if (distanceX < deadZone && distanceY < deadZone)
                    performClick()
                else if (targetView != null) {
                    var move = false

                    calculateTargetTranslation()

                    if (dragAxis.isHorizontal() && dragAxis.isVertical()) {

                    } else if (dragAxis.isVertical()) {
                        move = (Math.abs(changeY - initialPosition.y) > Math.abs(changeY - targetTranslation.y)) xor currentState
                    } else if (dragAxis.isHorizontal()) {
                        move = (Math.abs(changeX - initialPosition.x) > Math.abs(changeX - targetTranslation.x)) xor currentState
                    }

                    if (move)
                        moveToState(!currentState)
                    else
                        moveToState(currentState)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
            }
            MotionEvent.ACTION_POINTER_UP -> {
            }
            MotionEvent.ACTION_MOVE -> {
                if (this.dragAxis == DragAxis.X || this.dragAxis == DragAxis.XY) {
                    val desire = translationX + x
                    if (targetView != null) {
                        if (between(targetTranslation.x, initialPosition.x, desire))
                            translationX = desire

                    } else
                        translationX = desire
                }

                if (this.dragAxis == DragAxis.Y || this.dragAxis == DragAxis.XY) {
                    val desire = translationY + y
                    if (targetView != null) {
                        if (between(targetTranslation.y, initialPosition.y, desire))
                            translationY = desire

                    } else
                        translationY = desire

                }
            }
        }
        return true
    }

    enum class DragAxis {
        None {
            override fun isHorizontal(): Boolean = false
            override fun isVertical(): Boolean = false
        },
        X {
            override fun isHorizontal(): Boolean = true
            override fun isVertical(): Boolean = false
        },
        Y {
            override fun isHorizontal(): Boolean = false
            override fun isVertical(): Boolean = true
        },
        XY {
            override fun isHorizontal(): Boolean = true
            override fun isVertical(): Boolean = true
        };

        abstract fun isVertical(): Boolean
        abstract fun isHorizontal(): Boolean
    }

    enum class DragTargetAnchor {
        Top,
        TopRight,
        Right,
        BottomRight,
        Bottom,
        BottomLeft,
        Left,
        TopLeft,
        Middle
    }
}