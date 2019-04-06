package com.adsamcik.signalcollector.game.challenge.data.instance

import androidx.room.ColumnInfo
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.tracker.data.TrackerSession

/**
 * Challenge object that holds information about challenge including it's text localization
 */
abstract class Challenge(
		val difficulty: ChallengeDifficulty,
		val name: String,
		protected val descriptionTemplate: String,
		@ColumnInfo(name = "start_time")
		val startTime: Long,
		@ColumnInfo(name = "end_time")
		val endTime: Long) {

	/**
	 * Duration of the challenge
	 */
	val duration: Long get() = endTime - startTime

	abstract val description: String

	abstract val progress: Int

	/**
	 * Runs a batch process on a specified session
	 */
	abstract fun batchProcess(session: TrackerSession)
}
