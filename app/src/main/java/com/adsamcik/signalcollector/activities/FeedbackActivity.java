package com.adsamcik.signalcollector.activities;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TextInputEditText;
import android.support.design.widget.TextInputLayout;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.utility.Signin;
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
		Signin.getTokenAsync(this, value -> {
			LinearLayout parent = createScrollableContentParent(true);
			ViewGroup groupRoot = (ViewGroup) getLayoutInflater().inflate(R.layout.layout_feedback, parent);

			LinearLayout feedbackLayout = (LinearLayout) groupRoot.findViewById(R.id.feedback_type_layout);

			ColorStateList[] csl = Assist.getSelectionStateLists(getResources(), getTheme());
			mSelectedState = csl[1];
			mDefaultState = csl[0];

			for (int i = 0; i < feedbackLayout.getChildCount(); i++)
				feedbackLayout.getChildAt(i).setOnClickListener((v) -> updateType(v, feedbackLayout.indexOfChild(v)));

			groupRoot.findViewById(R.id.feedback_cancel_button).setOnClickListener(v -> finish());
			groupRoot.findViewById(R.id.feedback_send_button).setOnClickListener(v -> {
				if (currentType == null) {
					new SnackMaker(parent).showSnackbar(R.string.feedback_error_type);
					Assist.hideSoftKeyboard(this, parent);
					return;
				}

				TextInputLayout summaryTextLayout = (TextInputLayout) parent.findViewById(R.id.feedback_summary_wrap);
				EditText summaryText = summaryTextLayout.getEditText();

				assert summaryText != null;

				final Editable sumText = summaryText.getText();
				int textLength = sumText.length();
				final int MIN_TEXT_LENGTH = 8;
				final int MAX_TEXT_LENGTH = summaryTextLayout.getCounterMaxLength();

				final TextWatcher textWatcher = new TextWatcher() {
					@Override
					public void beforeTextChanged(CharSequence s, int start, int count, int after) {

					}

					@Override
					public void onTextChanged(CharSequence s, int start, int before, int count) {

					}

					@Override
					public void afterTextChanged(Editable s) {
						if (s.length() >= MIN_TEXT_LENGTH && s.length() <= MAX_TEXT_LENGTH) {
							summaryTextLayout.setError(null);
							summaryText.removeTextChangedListener(this);
						}
					}
				};

				if (textLength < MIN_TEXT_LENGTH) {
					summaryTextLayout.setError(getString(R.string.feedback_error_short_summary));
					summaryText.addTextChangedListener(textWatcher);
				} else if (MAX_TEXT_LENGTH < textLength) {
					summaryTextLayout.setError(getString(R.string.feedback_error_long_summary));
					summaryText.addTextChangedListener(textWatcher);
				} else {
					String result = summaryText.getText().toString().trim().replaceAll("\\s+", " ");
					if (result.length() <= MIN_TEXT_LENGTH)
						summaryTextLayout.setError(getString(R.string.feedback_error_spaces_summary));
					else {
						MultipartBody.Builder builder = Network.generateAuthBody(value).addFormDataPart("summary", result).addFormDataPart("type", Integer.toString(currentType));

						TextInputLayout descriptionTextLayout = (TextInputLayout) parent.findViewById(R.id.feedback_description_wrap);
						EditText descriptionText = descriptionTextLayout.getEditText();

						assert descriptionText != null;

						String description = descriptionText.getText().toString().trim();
						if (description.length() > 0)
							builder.addFormDataPart("description", description);

						Network.request(Network.URL_FEEDBACK, builder.build());
					}
				}
			});
		});
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
	}

	private void updateType(View v, int select) {
		currentType = select;

		if (selected != null)
			((TextView) selected).setTextColor(mDefaultState);

		((TextView) v).setTextColor(mSelectedState);
		selected = v;
	}
}
