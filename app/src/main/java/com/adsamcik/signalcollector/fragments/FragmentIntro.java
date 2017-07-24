package com.adsamcik.signalcollector.fragments;


import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Window;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.github.paolorotolo.appintro.AppIntroBaseFragment;
import com.github.paolorotolo.appintro.ISlidePolicy;

public final class FragmentIntro extends AppIntroBaseFragment implements ISlidePolicy {
	private Window window;
	private ICallback onLeaveCallback;

	public static FragmentIntro newInstance(CharSequence title, CharSequence description,
	                                        @DrawableRes int imageDrawable,
	                                        @ColorInt int bgColor,
	                                        Window window, @Nullable ICallback onLeaveCallback) {
		return newInstance(title, description, imageDrawable, bgColor, 0, 0, window, onLeaveCallback);
	}

	public static FragmentIntro newInstance(CharSequence title, CharSequence description,
	                                        @DrawableRes int imageDrawable, @ColorInt int bgColor,
	                                        @ColorInt int titleColor, @ColorInt int descColor,
	                                        Window window, @Nullable ICallback onLeaveCallback) {
		FragmentIntro slide = new FragmentIntro();
		Bundle args = new Bundle();
		args.putString(ARG_TITLE, title.toString());
		args.putString(ARG_TITLE_TYPEFACE, null);
		args.putString(ARG_DESC, description.toString());
		args.putString(ARG_DESC_TYPEFACE, null);
		args.putInt(ARG_DRAWABLE, imageDrawable);
		args.putInt(ARG_BG_COLOR, bgColor);
		args.putInt(ARG_TITLE_COLOR, titleColor);
		args.putInt(ARG_DESC_COLOR, descColor);
		slide.setArguments(args);

		slide.window = window;
		slide.onLeaveCallback = onLeaveCallback;

		return slide;
	}

	public static FragmentIntro newInstance(CharSequence title, String titleTypeface,
	                                        CharSequence description, String descTypeface,
	                                        @DrawableRes int imageDrawable,
	                                        @ColorInt int bgColor,
	                                        Window window, @Nullable ICallback onLeaveCallback) {
		return newInstance(title, titleTypeface, description, descTypeface, imageDrawable, bgColor,
				0, 0, window, onLeaveCallback);
	}

	public static FragmentIntro newInstance(CharSequence title, String titleTypeface,
	                                        CharSequence description, String descTypeface,
	                                        @DrawableRes int imageDrawable, @ColorInt int bgColor,
	                                        @ColorInt int titleColor, @ColorInt int descColor,
	                                        Window window, @Nullable ICallback onLeaveCallback) {
		FragmentIntro slide = new FragmentIntro();
		Bundle args = new Bundle();
		args.putString(ARG_TITLE, title.toString());
		args.putString(ARG_TITLE_TYPEFACE, titleTypeface);
		args.putString(ARG_DESC, description.toString());
		args.putString(ARG_DESC_TYPEFACE, descTypeface);
		args.putInt(ARG_DRAWABLE, imageDrawable);
		args.putInt(ARG_BG_COLOR, bgColor);
		args.putInt(ARG_TITLE_COLOR, titleColor);
		args.putInt(ARG_DESC_COLOR, descColor);
		slide.setArguments(args);

		slide.window = window;
		slide.onLeaveCallback = onLeaveCallback;

		return slide;
	}

	@Override
	public void onSlideSelected() {
		int color = getArguments().getInt(ARG_BG_COLOR);
		int r = color >> 16 & 0xFF;
		int g = color >> 8 & 0xFF;
		int b = color & 0xFF;

		int max = Math.max(Math.max(r, g), b);
		double percToIncrease = 255.0 / max;
		double percNew = (max / 255.0) - 0.1;
		r = (int) (percToIncrease * r * percNew);
		g = (int) (percToIncrease * g * percNew);
		b = (int) (percToIncrease * b * percNew);
		color = Color.argb(255, r, g, b);

		window.setNavigationBarColor(color);
		window.setStatusBarColor(color);
		super.onSlideSelected();
	}

	@Override
	protected int getLayoutId() {
		return R.layout.fragment_intro2;
	}

	@Override
	public boolean isPolicyRespected() {
		return onLeaveCallback == null;
	}

	@Override
	public void onUserIllegallyRequestedNextPage() {
		if(onLeaveCallback != null) {
			onLeaveCallback.callback();
			onLeaveCallback = null;
		}
	}
}