package com.adsamcik.tracker.game.challenge.data.entity

import androidx.room.Entity
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntryExtra

@Entity(tableName = "challenge_step", inheritSuperIndices = true)
class StepChallengeEntity(entryId: Long, isCompleted: Boolean, val requiredStepCount: Int, var stepCount: Int) :
		ChallengeEntryExtra(entryId, isCompleted)

