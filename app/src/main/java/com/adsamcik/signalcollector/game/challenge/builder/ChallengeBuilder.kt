package com.adsamcik.signalcollector.game.challenge.builder

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.definition.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.misc.Probability
import com.adsamcik.signalcollector.misc.extension.rescale

abstract class ChallengeBuilder<ChallengeType : ChallengeInstance<*>>(private val definition: ChallengeDefinition<ChallengeType>) {
	protected var difficultyMultiplier: Double = 1.0
	protected var duration: Long = 0L

	protected lateinit var description: String
	protected lateinit var title: String

	protected open val difficulty: ChallengeDifficulty
		get() = when {
			difficultyMultiplier < 0.5 -> ChallengeDifficulty.VERY_EASY
			difficultyMultiplier < 0.9 -> ChallengeDifficulty.EASY
			difficultyMultiplier < 1.1 -> ChallengeDifficulty.MEDIUM
			difficultyMultiplier < 1.5 -> ChallengeDifficulty.HARD
			else -> ChallengeDifficulty.VERY_HARD
		}

	open fun selectLength() {
		val max = 1.6
		val min = 0.4
		val (durationMultiplier, _) = Probability.normal(min, max)
		duration = (definition.defaultDuration * durationMultiplier).toLong()
		difficultyMultiplier *= durationMultiplier.rescale(min..max, 0.25..2.0)
	}

	private fun loadResources(context: Context) {
		val resources = context.resources
		title = resources.getString(definition.titleRes)
		description = resources.getString(definition.descriptionRes)
	}

	abstract fun selectChallengeSpecificParameters()

	private fun createEntry(context: Context, startAt: Long): ChallengeEntry {
		val entryDao = ChallengeDatabase.getAppDatabase(context).entryDao
		val entry = ChallengeEntry(definition.name, startAt, startAt + duration, difficulty)
		entryDao.insertSetId(entry)

		if(entry.id == 0L)
			throw Error("Id was 0 after insertion. Something is wrong.")

		return entry
	}

	fun build(context: Context, startAt: Long): ChallengeType {
		selectChallengeSpecificParameters()
		selectLength()
		loadResources(context)

		val entry = createEntry(context, startAt)

		return buildChallenge(context, entry)
	}

	protected abstract fun buildChallenge(context: Context, entry: ChallengeEntry): ChallengeType
}