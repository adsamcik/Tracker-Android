package com.adsamcik.signalcollector.game.challenge.data.instance

import androidx.room.ColumnInfo
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.progress.ChallengeProgressData
import com.adsamcik.signalcollector.tracker.data.TrackerSession

/**
 * Challenge object that holds information about challenge including it's text localization
 */
abstract class Challenge<ProgressData>(
		val difficulty: ChallengeDifficulty,
		val name: String,
		protected val descriptionTemplate: String,
		@ColumnInfo(name = "start_time")
		val startTime: Long,
		@ColumnInfo(name = "end_time")
		val endTime: Long,
		@ColumnInfo(name = "progress_data")
		val progressData: ProgressData) where ProgressData : ChallengeProgressData {

	/**
	 * Duration of the challenge
	 */
	val duration: Long get() = endTime - startTime

	abstract val description: String

	/**
	 * Runs a batch process on a specified session
	 */
	abstract fun batchProcess(session: TrackerSession)
}
