package com.adsamcik.signalcollector.activities;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.res.ResourcesCompat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Assist;

public class FeedbackActivity extends DetailActivity {
	private int currentType = -1;
	private View selected = null;

	private ColorStateList mSelectedState;
	private ColorStateList mDefaultState;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		LinearLayout parent = createContentParent(false);
		ViewGroup groupRoot = (ViewGroup) getLayoutInflater().inflate(R.layout.layout_feedback, parent);

		LinearLayout feedbackLayout = (LinearLayout) groupRoot.findViewById(R.id.feedback_type_layout);

		ColorStateList[] csl = Assist.getSelectionStateLists(getResources(), getTheme());
		mSelectedState = csl[1];
		mDefaultState = csl[0];

		for (int i = 0; i < feedbackLayout.getChildCount(); i++) {
			feedbackLayout.getChildAt(i).setOnClickListener((v) -> updateType(v, feedbackLayout.indexOfChild(v)));
		}
	}

	private void updateType(View v, int select) {
		currentType = select;

		if (selected != null)
			((TextView) selected).setTextColor(mDefaultState);

		((TextView) v).setTextColor(mSelectedState);
		selected = v;
	}
}
