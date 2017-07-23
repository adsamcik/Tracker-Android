package com.adsamcik.signalcollector.fragments;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.data.Challenge;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.utility.Failure;

import java.util.ArrayList;

public class FragmentActivities extends Fragment implements ITabFragment {
	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		final View rootView = inflater.inflate(R.layout.fragment_activities, container, false);

		ListView listViewChallenges = rootView.findViewById(R.id.listview_challenges);
		ArrayList<Challenge> challenges = new ArrayList<>();
		challenges.add(new Challenge("Lawful explorer", "lorem dolor amet", true));
		challenges.add(new Challenge("Lawful explorer", "lorem dolor amet", false));
		challenges.add(new Challenge("Lawful explorer", "lorem dolor amet", false));
		listViewChallenges.setAdapter(new ChallengesAdapter(getContext(), challenges));
		return rootView;
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
		private Context mContext;
		private LayoutInflater mInflater;
		private ArrayList<Challenge> mDataSource;

		public ChallengesAdapter(Context context, ArrayList<Challenge> items) {
			mContext = context;
			mDataSource = items;
			mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		}

		@Override
		public int getCount() {
			return mDataSource.size();
		}

		@Override
		public Object getItem(int i) {
			return mDataSource.get(i);
		}

		@Override
		public long getItemId(int i) {
			return i;
		}

		@Override
		public View getView(int i, View view, ViewGroup viewGroup) {
			if(view == null)
				view = mInflater.inflate(R.layout.layout_challenge_small, viewGroup, false);

			Challenge challenge = mDataSource.get(i);
			((TextView)view.findViewById(R.id.challenge_title)).setText(challenge.title);
			((TextView)view.findViewById(R.id.challenge_description)).setText(challenge.description);
			Resources resources = getResources();
			view.setBackgroundColor(challenge.isDone ? resources.getColor(R.color.background_success) : resources.getColor(R.color.card_background));
			return view;
		}
	}
}
