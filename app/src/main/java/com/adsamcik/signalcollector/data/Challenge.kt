package com.adsamcik.signalcollector.data

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.ChallengeDifficulties

/**
 * Challenge object that holds information about challenge including it's text localization
 */

class Challenge {
    /**
     * Type of challenge
     */
    var type: ChallengeType? = null
        private set

    /**
     * Challenge progress
     */
    var progress: Float = 0.toFloat()
        private set

    /**
     * Description variables used to format description strings
     */
    private var descVars: Array<String>? = null

    /**
     * Difficulty of the challenge
     */
    @ChallengeDifficulties.ChallengeDifficulty
    private var difficulty: Int = 0

    var title: String? = null
        private set
    var description: String? = null
        private set
    var difficultyString: String? = null
        private set

    constructor()

    constructor(type: ChallengeType, title: String, description: String, progress: Float, @ChallengeDifficulties.ChallengeDifficulty difficulty: Int) {
        this.type = type
        this.title = title
        this.description = description
        this.descVars = null
        this.progress = progress
        this.difficulty = difficulty
    }

    constructor(type: ChallengeType, title: String, descVars: Array<String>, progress: Float, @ChallengeDifficulties.ChallengeDifficulty difficulty: Int) {
        this.type = type
        this.title = title
        this.descVars = descVars
        this.description = null
        this.progress = progress
        this.difficulty = difficulty
    }

    /**
     * Sets progress as done
     * This is useful when progress needs to be updated locally when challenge is completed without redownloading challenges from the server.
     */
    fun setDone() {
        this.progress = 1f
    }

    /**
     * This function loads appropriate strings from the resources for this challenge.
     * Implementing it like this allowed proper localization.
     */
    fun generateTexts(context: Context) {
        val resources = context.resources
        assert(descVars != null)
        when (type) {
            Challenge.ChallengeType.LawfulExplorer -> {
                title = resources.getString(R.string.challenge_lawful_explorer_title)
                description = resources.getString(R.string.challenge_lawful_explorer_description, descVars!![0], descVars!![1])
            }
            Challenge.ChallengeType.AwfulExplorer -> {
                title = resources.getString(R.string.challenge_awful_explorer_title)
                description = resources.getString(R.string.challenge_awful_explorer_description, descVars!![0])
            }
            Challenge.ChallengeType.ScatteredData -> {
                title = resources.getString(R.string.challenge_scattered_data_title)
                description = resources.getString(R.string.challenge_scattered_data_description, descVars!![0])
            }
            Challenge.ChallengeType.LongJourney -> {
                title = resources.getString(R.string.challenge_long_journey_title)
                description = resources.getString(R.string.challenge_long_journey_description, descVars!![0])
            }
            Challenge.ChallengeType.Crowded -> {
                title = resources.getString(R.string.challenge_crowded_title)
                description = resources.getString(R.string.challenge_crowded_description, descVars!![0])
            }
            Challenge.ChallengeType.PeacefulForest -> {
                title = resources.getString(R.string.challenge_peaceful_forest_title)
                description = resources.getString(R.string.challenge_peaceful_forest_description, descVars!![0])
            }
            Challenge.ChallengeType.CrossCountry -> {
                title = resources.getString(R.string.challenge_cross_country_title)
                description = resources.getString(R.string.challenge_cross_country_description, descVars!![0])
            }
            Challenge.ChallengeType.TripDownMemoryLane -> {
                title = resources.getString(R.string.challenge_memory_lane_title)
                description = resources.getString(R.string.challenge_memory_lane_description, descVars!![0])
            }
            Challenge.ChallengeType.AIsForAlphabet -> {
                title = resources.getString(R.string.challenge_alphabet_title)
                description = resources.getString(R.string.challenge_alphabet_description, descVars!![0], descVars!![1])
            }
            Challenge.ChallengeType.StayInRange -> {
                title = resources.getString(R.string.challenge_cell_range_title)
                description = resources.getString(R.string.challenge_cell_range_description, descVars!![0])
            }
            else -> description = resources.getString(R.string.challenge_error_name)
        }

        difficultyString = when (difficulty) {
            ChallengeDifficulties.EASY -> resources.getString(R.string.challenge_easy)
            ChallengeDifficulties.HARD -> resources.getString(R.string.challenge_hard)
            ChallengeDifficulties.MEDIUM -> resources.getString(R.string.challenge_medium)
            ChallengeDifficulties.VERY_EASY -> resources.getString(R.string.challenge_very_easy)
            ChallengeDifficulties.VERY_HARD -> resources.getString(R.string.challenge_very_hard)
            ChallengeDifficulties.UNKNOWN -> null
            else -> null
        }
    }

    /**
     * Challenge types that are currently supported
     */
    enum class ChallengeType {
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
