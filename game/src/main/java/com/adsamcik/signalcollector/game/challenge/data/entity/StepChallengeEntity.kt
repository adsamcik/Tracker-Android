package com.adsamcik.signalcollector.game.challenge.data.entity

import androidx.room.Entity
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntryExtra

@Entity(tableName = "challenge_step", inheritSuperIndices = true)
class StepChallengeEntity(entryId: Long, isCompleted: Boolean, val requiredStepCount: Int, var stepCount: Int) :
		ChallengeEntryExtra(entryId, isCompleted)
