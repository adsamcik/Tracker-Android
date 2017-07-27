package com.adsamcik.signalcollector.data;

import android.content.Context;
import android.content.res.Resources;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.R;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.vimeo.stag.UseStag;

@UseStag(UseStag.FieldOption.SERIALIZED_NAME)
public class Challenge {
	/**
	 * Type of challenge
	 */
	@SerializedName("type")
	private ChallengeType type;

	/**
	 * Is challenge done?
	 */
	@SerializedName("isDone")
	private boolean done;

	/**
	 * Description variables used to format description strings
	 */
	@SerializedName("descVars")
	private String[] descVars;

	@Expose(serialize = false, deserialize = false)
	private String title;

	@Expose(serialize = false, deserialize = false)
	private String description;

	public Challenge(ChallengeType type, String title, String description, boolean done) {
		this.type = type;
		this.title = title;
		this.description = description;
		this.descVars = null;
		this.done = done;
	}

	public Challenge(ChallengeType type, String title, String[] descVars, boolean done) {
		this.type = type;
		this.title = title;
		this.descVars = descVars;
		this.description = null;
		this.done = done;
	}

	public ChallengeType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public boolean isDone() {
		return done;
	}

	public void setDone(boolean done) {
		this.done = done;
	}

	public void generateTexts(@NonNull Context context) {
		Resources resources = context.getResources();
		assert descVars != null;
		switch (type) {
			case LawfulExplorer:
				title = resources.getString(R.string.challenge_lawful_explorer_title);
				description = resources.getString(R.string.challenge_lawful_explorer_description, descVars[0], descVars[1]);
				break;
			case AwfulExplorer:
				title = resources.getString(R.string.challenge_awful_explorer_title);
				description = resources.getString(R.string.challenge_awful_explorer_description, descVars[0]);
				break;
			case ScatteredData:
				title = resources.getString(R.string.challenge_scattered_data_title);
				description = resources.getString(R.string.challenge_scattered_data_description, descVars[0]);
				break;
			case LongJourney:
				title = resources.getString(R.string.challenge_long_journey_title);
				description = resources.getString(R.string.challenge_long_journey_description, descVars[0]);
				break;
			case Crowded:
				title = resources.getString(R.string.challenge_crowded_title);
				description = resources.getString(R.string.challenge_crowded_description, descVars[0]);
				break;
			case PeacefulForest:
				title = resources.getString(R.string.challenge_peaceful_forest_title);
				description = resources.getString(R.string.challenge_peaceful_forest_description, descVars[0]);
				break;
			case CrossCountry:
				title = resources.getString(R.string.challenge_cross_country_title);
				description = resources.getString(R.string.challenge_cross_country_description, descVars[0]);
				break;
			case TripDownMemoryLane:
				title = resources.getString(R.string.challenge_memory_lane_title);
				description = resources.getString(R.string.challenge_memory_lane_description, descVars[0]);
				break;
			case AIsForAlphabet:
				title = resources.getString(R.string.challenge_alphabet_title);
				description = resources.getString(R.string.challenge_alphabet_description, descVars[0], descVars[1]);
				break;
			case StayInRange:
				title = resources.getString(R.string.challenge_cell_range_title);
				description = resources.getString(R.string.challenge_cell_range_description, descVars[0]);
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

	//STAG CONSTRUCTOR GETTERS SETTERS//

	Challenge() {}

	String[] getDescVars() {
		return descVars;
	}

	boolean getDone() {
		return done;
	}

	void setType(ChallengeType type) {
		this.type = type;
	}

	void setDescVars(String[] descVars) {
		this.descVars = descVars;
	}
}
