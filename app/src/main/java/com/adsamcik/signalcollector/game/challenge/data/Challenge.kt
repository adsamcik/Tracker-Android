package com.adsamcik.signalcollector.game.challenge.data

import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.tracker.data.TrackerSession

/**
 * Challenge object that holds information about challenge including it's text localization
 */
interface Challenge {
	/**
	 * Difficulty of the challenge
	 */
	val difficulty: ChallengeDifficulty

	/**
	 * Name of the challenge
	 */
	val name: String

	/**
	 * Description of the challenge
	 */
	fun generateDescription(progressData: ChallengeProgressData): String

	/**
	 * Runs a batch process on a specified session
	 */
	fun batchProcess(session: TrackerSession)
}
