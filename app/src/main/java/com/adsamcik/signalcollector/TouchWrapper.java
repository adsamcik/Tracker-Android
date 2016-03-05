package com.adsamcik.signalcollector;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

public class TouchWrapper extends FrameLayout {
	public boolean mMapIsTouched;

	public TouchWrapper(Context context) {
		super(context);
	}

	public TouchWrapper(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public TouchWrapper(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	public TouchWrapper(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		switch(ev.getAction()) {
			case MotionEvent.ACTION_DOWN:
				mMapIsTouched = true;
				break;

			case MotionEvent.ACTION_UP:
				mMapIsTouched = false;
				break;
		}

		return super.dispatchTouchEvent(ev);

	}
}
