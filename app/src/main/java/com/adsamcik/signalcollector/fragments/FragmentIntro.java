package com.adsamcik.signalcollector.fragments;


import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.annotation.DrawableRes;
import android.view.Window;

import com.adsamcik.signalcollector.R;
import com.github.paolorotolo.appintro.AppIntroBaseFragment;
import com.github.paolorotolo.appintro.AppIntroFragment;

public final class FragmentIntro extends AppIntroBaseFragment {
	private Window window;

	public static FragmentIntro newInstance(CharSequence title, CharSequence description,
	                                        @DrawableRes int imageDrawable,
	                                        @ColorInt int bgColor,
	                                        Window window) {
		return newInstance(title, description, imageDrawable, bgColor, 0, 0, window);
	}

	public static FragmentIntro newInstance(CharSequence title, CharSequence description,
	                                        @DrawableRes int imageDrawable, @ColorInt int bgColor,
	                                        @ColorInt int titleColor, @ColorInt int descColor,
	                                        Window window) {
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

		return slide;
	}

	public static FragmentIntro newInstance(CharSequence title, String titleTypeface,
	                                        CharSequence description, String descTypeface,
	                                        @DrawableRes int imageDrawable,
	                                        @ColorInt int bgColor,
	                                        Window window) {
		return newInstance(title, titleTypeface, description, descTypeface, imageDrawable, bgColor,
				0, 0, window);
	}

	public static FragmentIntro newInstance(CharSequence title, String titleTypeface,
	                                        CharSequence description, String descTypeface,
	                                        @DrawableRes int imageDrawable, @ColorInt int bgColor,
	                                        @ColorInt int titleColor, @ColorInt int descColor,
	                                        Window window) {
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
}