package com.adsamcik.signalcollector.Fragments;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.R;

public class FragmentMain extends Fragment {
	TextView textTime, textPosition, textAccuracy, textWifiCount, textCurrentCell, textCellCount, textPressure, textActivity;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_main, container, false);
		textAccuracy = (TextView) view.findViewById(R.id.textAccuracy);
		textPosition = (TextView) view.findViewById(R.id.textPosition);
		textCellCount = (TextView) view.findViewById(R.id.textCells);
		textCurrentCell = (TextView) view.findViewById(R.id.textCurrentCell);
		textWifiCount = (TextView) view.findViewById(R.id.textWifiCount);
		textTime = (TextView) view.findViewById(R.id.textTime);
		textPressure = (TextView) view.findViewById(R.id.textPressure);
		textActivity = (TextView) view.findViewById(R.id.textActivity);
		if(getActivity().getClass().getSimpleName().equals("MainActivity")) {
			((MainActivity) getActivity()).setupUpdateReceiver(textTime, textPosition, textWifiCount, textCurrentCell, textCellCount, textAccuracy, textPressure, textActivity);
		}
		return view;
	}
}
