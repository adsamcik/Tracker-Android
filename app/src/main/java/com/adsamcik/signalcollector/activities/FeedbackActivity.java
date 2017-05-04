package com.adsamcik.signalcollector.activities;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TextInputEditText;
import android.support.v4.content.res.ResourcesCompat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.utility.NetworkLoader;
import com.adsamcik.signalcollector.utility.SnackMaker;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;

public class FeedbackActivity extends DetailActivity {
	private Integer currentType = null;
	private View selected = null;

	private ColorStateList mSelectedState;
	private ColorStateList mDefaultState;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(R.string.feedback_title);
		LinearLayout parent = createScrollableContentParent(true);
		ViewGroup groupRoot = (ViewGroup) getLayoutInflater().inflate(R.layout.layout_feedback, parent);

		LinearLayout feedbackLayout = (LinearLayout) groupRoot.findViewById(R.id.feedback_type_layout);

		ColorStateList[] csl = Assist.getSelectionStateLists(getResources(), getTheme());
		mSelectedState = csl[1];
		mDefaultState = csl[0];

		for (int i = 0; i < feedbackLayout.getChildCount(); i++)
			feedbackLayout.getChildAt(i).setOnClickListener((v) -> updateType(v, feedbackLayout.indexOfChild(v)));


		feedbackLayout.findViewById(R.id.feedback_cancel_button).setOnClickListener(v -> finish());
		feedbackLayout.findViewById(R.id.feedback_send_button).setOnClickListener(v ->  {
			if(currentType == null) {
				new SnackMaker(parent).showSnackbar(R.string.feedback_error_type);
				return;
			}

			TextInputEditText summaryText = (TextInputEditText) parent.findViewById(R.id.feedback_summary);
			if(summaryText.getText().length() <= 8) {
				new SnackMaker(parent).showSnackbar(R.string.feedback_error_short_summary);
			}

			RequestBody formBody = new MultipartBody.Builder()
					.setType(MultipartBody.FORM)
					.addFormDataPart("token", token)
					.addFormDataPart("file", Network.generateVerificationString(userID, file.length()), RequestBody.create(MEDIA_TYPE_ZIP, file))
					.build();

			Network.request(Network.URL_FEEDBACK, ne )
		});
	}

	private void updateType(View v, int select) {
		currentType = select;

		if (selected != null)
			((TextView) selected).setTextColor(mDefaultState);

		((TextView) v).setTextColor(mSelectedState);
		selected = v;
	}
}
