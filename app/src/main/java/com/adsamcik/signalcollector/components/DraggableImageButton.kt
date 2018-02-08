package com.adsamcik.signalcollector.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import com.adsamcik.signalcollector.utility.Assist


class DraggableImageButton : ImageButton {
    val TAG = "DraggableImageButton"

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    private val deadZone = Assist.dpToPx(context, 16)

    private var initialPosition: PointF = PointF()
    private var initialTranslation: PointF = PointF()
    private var targetPosition: PointF = PointF()

    private var stateInitial = true
    private var touchInitialPosition: PointF = PointF()


    private var dragAxis = DragAxis.None

    private var targetView: View? = null
    private var anchor = DragTargetAnchor.TopLeft
    private var marginDp = 0

    fun setDrag(axis: DragAxis) {
        dragAxis = axis
    }

    fun setTarget(target: View, anchor: DragTargetAnchor, marginDp: Int) {
        this.targetView = target
        this.anchor = anchor
        this.marginDp = marginDp

        val position = getLocationOnScreen(this)
        initialPosition.x = position[0].toFloat()
        initialPosition.y = position[1].toFloat()

        initialTranslation.x = translationX
        initialTranslation.y = translationY
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
            moveToState(!stateInitial)
        }

        return true
    }

    private fun moveToState(state: Boolean) {
        var target: Float
        if (this.dragAxis == DragAxis.X || this.dragAxis == DragAxis.XY) {
            target = if (stateInitial) targetPosition.x else initialTranslation.x
            animate(ValueAnimator.AnimatorUpdateListener { translationX = it.animatedValue as Float }, translationX, target)
        }

        if (this.dragAxis == DragAxis.Y || this.dragAxis == DragAxis.XY) {
            target = if (stateInitial) targetPosition.y else initialTranslation.y
            animate(ValueAnimator.AnimatorUpdateListener { translationY = it.animatedValue as Float }, translationY, target)
        }
        stateInitial = state
    }

    private fun calculateRelativePosition(target: View, targetAnchor: DragTargetAnchor): PointF {
        return when (targetAnchor) {
            DragTargetAnchor.Top -> PointF(target.x + target.width / 2, 0f)
            DragTargetAnchor.TopRight -> PointF(target.x + target.width, 0f)
            DragTargetAnchor.Right -> PointF(target.x + target.width, target.y + target.height / 2)
            DragTargetAnchor.BottomRight -> PointF(target.x + target.width, target.y + target.height)
            DragTargetAnchor.Bottom -> PointF(target.x + target.width / 2, target.y + target.height)
            DragTargetAnchor.BottomLeft -> PointF(0f, target.y + target.height)
            DragTargetAnchor.Left -> PointF(0f, target.y + target.height / 2)
            DragTargetAnchor.TopLeft -> PointF(0f, 0f)
            DragTargetAnchor.Middle -> PointF(target.x + target.width / 2, target.y + target.height / 2)
        }
    }

    private fun animate(updateListener: ValueAnimator.AnimatorUpdateListener, thisPosition: Float, targetPosition: Float) {
        val valueAnimator = ValueAnimator.ofFloat(thisPosition, targetPosition)

        valueAnimator.addUpdateListener(updateListener)

        valueAnimator.interpolator = LinearInterpolator()
        valueAnimator.duration = 200
        valueAnimator.start()
    }

    private fun between(firstConstraint: Float, secondConstraint: Float, number: Float): Boolean {
        return if (firstConstraint > secondConstraint)
            number in secondConstraint..firstConstraint
        else
            number in firstConstraint..secondConstraint
    }

    private fun calculateTargetPosition() {
        val thisOnScreen = getLocationOnScreen(this)
        val targetOnScreen = getLocationOnScreen(targetView!!)
        val targetRelPos = calculateRelativePosition(targetView!!, anchor)
        val marginPx = Assist.dpToPx(context, marginDp)

        targetPosition.x = translationX + (targetOnScreen[0] - thisOnScreen[0] + marginPx).toFloat() - targetRelPos.x
        targetPosition.y = translationY + (targetOnScreen[1] - thisOnScreen[1] + marginPx).toFloat() - targetRelPos.y
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val x = event!!.x
        val y = event.y
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                touchInitialPosition.x = event.rawX
                touchInitialPosition.y = event.rawY

                calculateTargetPosition()
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

                    calculateTargetPosition()

                    if (dragAxis.isHorizontal() && dragAxis.isVertical()) {

                    } else if (dragAxis.isVertical()) {
                        move = (Math.abs(changeY - initialPosition.y) < Math.abs(changeY - targetPosition.y)) xor stateInitial
                    } else if (dragAxis.isHorizontal()) {
                        move = (Math.abs(changeX - initialPosition.x) < Math.abs(changeX - targetPosition.x)) xor stateInitial
                    }

                    if (move)
                        moveToState(!stateInitial)
                    else
                        moveToState(stateInitial)
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
                        if (between(targetPosition.x, initialPosition.x, desire))
                            translationX = desire

                    } else
                        translationX = desire
                }

                if (this.dragAxis == DragAxis.Y || this.dragAxis == DragAxis.XY) {
                    val desire = translationY + y
                    if (targetView != null) {
                        if (between(targetPosition.y, initialPosition.y, desire))
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