package com.adsamcik.signalcollector.data;

import android.content.Context;
import android.content.res.Resources;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.R;

public class Challenge {
	private final ChallengeType type;
	public String title;
	public boolean isDone;
	private String description;
	private String[] descVars;

	public Challenge(ChallengeType type, String title, String description, boolean isDone) {
		this.type = type;
		this.title = title;
		this.description = description;
		this.isDone = isDone;
		this.descVars = null;
	}

	public Challenge(ChallengeType type, String title, String[] descVars, boolean isDone) {
		this.type = type;
		this.title = title;
		this.descVars = descVars;
		this.description = null;
		this.isDone = false;
	}
	
	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public void generateTexts(@NonNull Context context) {
		Resources resources = context.getResources();
		switch (type) {
			case LawfulExplorer:
				title = resources.getString(R.string.challenge_lawful_explorer_title);
				description = resources.getString(R.string.challenge_lawful_explorer_description);
				break;
			case AwfulExplorer:
				title = resources.getString(R.string.challenge_awful_explorer_title);
				description = resources.getString(R.string.challenge_awful_explorer_description);
				break;
			case ScatteredData:
				title = resources.getString(R.string.challenge_scattered_data_title);
				description = resources.getString(R.string.challenge_scattered_data_description);
				break;
			case LongJourney:
				title = resources.getString(R.string.challenge_long_journey_title);
				description = resources.getString(R.string.challenge_long_journey_description);
				break;
			case Crowded:
				title = resources.getString(R.string.challenge_crowded_title);
				description = resources.getString(R.string.challenge_crowded_description);
				break;
			case PeacefulForest:
				title = resources.getString(R.string.challenge_peaceful_forest_title);
				description = resources.getString(R.string.challenge_peaceful_forest_description);
				break;
			case CrossCountry:
				title = resources.getString(R.string.challenge_cross_country_title);
				description = resources.getString(R.string.challenge_cross_country_description);
				break;
			case TripDownMemoryLane:
				title = resources.getString(R.string.challenge_memory_lane_title);
				description = resources.getString(R.string.challenge_memory_lane_description);
				break;
			case AIsForAlphabet:
				title = resources.getString(R.string.challenge_alphabet_title);
				description = resources.getString(R.string.challenge_alphabet_description);
				break;
			case StayInRange:
				title = resources.getString(R.string.challenge_cell_range_title);
				description = resources.getString(R.string.challenge_cell_range_description);
				break;
			default:
				description = resources.getString(R.string.challenge_error_name);
				break;
		}
	}

	public enum ChallengeType {
		LawfulExplorer,
		AwfulExplorer,
		ScatteredData,
		LongJourney,
		Crowded,
		PeacefulForest,
		CrossCountry,
		TripDownMemoryLane,
		AIsForAlphabet,
		StayInRange
	}
}
