package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.NavUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;

public abstract class DetailActivity extends Activity {

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_content_detail);
		findViewById(R.id.back_button).setOnClickListener(view -> NavUtils.navigateUpFromSameTask(this));
	}

	public void setTitle(String title) {
		TextView titleView = (TextView) findViewById(R.id.content_detail_title);
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

	public LinearLayout getLayout() {
		return (LinearLayout) findViewById(R.id.content_detail_layout);
	}
}
