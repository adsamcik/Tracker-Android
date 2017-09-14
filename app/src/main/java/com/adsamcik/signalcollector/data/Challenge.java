package com.adsamcik.signalcollector.data;

import android.content.Context;
import android.content.res.Resources;
import android.support.annotation.NonNull;
import android.util.Log;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.enums.ChallengeDifficulty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.vimeo.stag.UseStag;

public class Challenge {
	/**
	 * Type of challenge
	 */
	private ChallengeType type;

	/**
	 * Challenge progress
	 */
	private float progress;

	/**
	 * Description variables used to format description strings
	 */
	private String[] descVars;

	/**
	 * Difficulty of the challenge
	 */
	private @ChallengeDifficulty int difficulty;

	private String title;
	private String description;
	private String difficultyString;

	public Challenge(ChallengeType type, String title, String description, float progress, @ChallengeDifficulty int difficulty) {
		this.type = type;
		this.title = title;
		this.description = description;
		this.descVars = null;
		this.progress = progress;
		this.difficulty = difficulty;
	}

	public Challenge(ChallengeType type, String title, String[] descVars, float progress, @ChallengeDifficulty int difficulty) {
		this.type = type;
		this.title = title;
		this.descVars = descVars;
		this.description = null;
		this.progress = progress;
		this.difficulty = difficulty;
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

	public String getDifficultyString() {
		return difficultyString;
	}

	public float getProgress() {
		return progress;
	}

	public void setDone(boolean done) {
		this.progress = 1;
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

		switch (difficulty) {
			case ChallengeDifficulty.EASY:
				difficultyString = resources.getString(R.string.challenge_easy);
				break;
			case ChallengeDifficulty.HARD:
				difficultyString = resources.getString(R.string.challenge_hard);
				break;
			case ChallengeDifficulty.MEDIUM:
				difficultyString = resources.getString(R.string.challenge_medium);
				break;
			case ChallengeDifficulty.VERY_EASY:
				difficultyString = resources.getString(R.string.challenge_very_easy);
				break;
			case ChallengeDifficulty.VERY_HARD:
				difficultyString = resources.getString(R.string.challenge_very_hard);
				break;
			default:
				difficultyString = null;
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
