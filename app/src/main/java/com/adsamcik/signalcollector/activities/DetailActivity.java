package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.NavUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Preferences;

public abstract class DetailActivity extends Activity {

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		Preferences.setTheme(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_content_detail);
		findViewById(R.id.back_button).setOnClickListener(view -> NavUtils.navigateUpFromSameTask(this));
	}

	public void setTitle(String title) {
		TextView titleView = findViewById(R.id.content_detail_title);
		titleView.setText(title);
	}

	@Override
	public void setTitle(CharSequence title) {
		super.setTitle(title);
		setTitle((String) title);
	}

	@Override
	public void setTitle(int titleId) {
		super.setTitle(titleId);
		setTitle(getString(titleId));
	}

	private LinearLayout createContentLayout(boolean scrollbable, boolean addContentPadding) {
		LinearLayout linearLayout = new LinearLayout(this);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, scrollbable ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT);
		if (addContentPadding) {
			int padding = (int) getResources().getDimension(R.dimen.activity_horizontal_margin);
			linearLayout.setPadding(padding, padding, padding, padding);
		}
		linearLayout.setLayoutParams(lp);

		linearLayout.setOrientation(LinearLayout.VERTICAL);
		return linearLayout;
	}

	protected LinearLayout createContentParent(boolean addContentPadding) {
		LinearLayout root = findViewById(R.id.content_detail_root);
		LinearLayout contentParent = createContentLayout(false, addContentPadding);
		root.addView(contentParent);
		return contentParent;
	}

	protected LinearLayout createScrollableContentParent(boolean addContentPadding) {
		LinearLayout root = findViewById(R.id.content_detail_root);
		ScrollView scrollView = new ScrollView(this);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
		scrollView.setLayoutParams(lp);

		LinearLayout contentParent = createContentLayout(false, addContentPadding);

		scrollView.addView(contentParent);

		root.addView(scrollView);
		return contentParent;
	}
}
