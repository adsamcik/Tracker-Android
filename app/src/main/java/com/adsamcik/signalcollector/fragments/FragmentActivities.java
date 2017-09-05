package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.data.Challenge;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.ChallengeManager;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.utility.SnackMaker;

public class FragmentActivities extends Fragment implements ITabFragment {
	private ListView listViewChallenges;
	private SwipeRefreshLayout refreshLayout;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fragment_activities, container, false);
		final Activity activity = getActivity();

		listViewChallenges = rootView.findViewById(R.id.listview_challenges);

		refreshLayout = (SwipeRefreshLayout) rootView;
		refreshLayout.setColorSchemeResources(R.color.color_primary);
		refreshLayout.setProgressViewOffset(true, 0, Assist.dpToPx(activity, 40));
		refreshLayout.setOnRefreshListener(this::updateData);

		updateData();

		return rootView;
	}

	private void updateData() {
		final boolean isRefresh = refreshLayout != null && refreshLayout.isRefreshing();
		final Activity activity = getActivity();
		final Context context = activity.getApplicationContext();
		ChallengeManager.getChallenges(activity, isRefresh, (source, challenges) -> {
			if (!source.isSuccess())
				new SnackMaker(activity).showSnackbar(R.string.error_connection_failed);
			else {
				activity.runOnUiThread(() -> listViewChallenges.setAdapter(new ChallengesAdapter(context, challenges)));
			}
			activity.runOnUiThread(() -> refreshLayout.setRefreshing(false));
		});
	}

	@NonNull
	@Override
	public Failure<String> onEnter(@NonNull FragmentActivity activity, @NonNull FloatingActionButton fabOne, @NonNull FloatingActionButton fabTwo) {
		return new Failure<>();
	}

	@Override
	public void onLeave(@NonNull FragmentActivity activity) {

	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {

	}

	@Override
	public void onHomeAction() {

	}

	private class ChallengesAdapter extends BaseAdapter {
		private final Context mContext;
		private final LayoutInflater mInflater;
		private final Challenge[] mDataSource;

		public ChallengesAdapter(Context context, Challenge[] items) {
			mContext = context;
			mDataSource = items;
			mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		}

		@Override
		public int getCount() {
			return mDataSource.length;
		}

		@Override
		public Object getItem(int i) {
			return mDataSource[i];
		}

		@Override
		public long getItemId(int i) {
			return i;
		}

		@Override
		public View getView(int i, View view, ViewGroup viewGroup) {
			if (view == null)
				view = mInflater.inflate(R.layout.layout_challenge_small, viewGroup, false);

			Challenge challenge = mDataSource[i];
			((TextView) view.findViewById(R.id.challenge_title)).setText(challenge.getTitle());
			((TextView) view.findViewById(R.id.challenge_description)).setText(challenge.getDescription());

			TextView textViewDifficulty = view.findViewById(R.id.challenge_difficulty);
			if(challenge.getDifficultyString() == null)
				textViewDifficulty.setVisibility(View.GONE);
			else
				textViewDifficulty.setText(challenge.getDifficultyString());


			Context context = getContext();

			int color;
			if(challenge.isDone()) {
				color = ContextCompat.getColor(getContext(), R.color.background_success);
			} else {
				TypedValue typedValue = new TypedValue();
				context.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
				color = typedValue.data;
			}
			view.setBackgroundColor(color);
			return view;
		}
	}
}
