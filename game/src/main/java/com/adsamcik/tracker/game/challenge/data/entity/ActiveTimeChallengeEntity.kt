package com.adsamcik.tracker.game.challenge.data.entity

import androidx.room.Entity
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntryExtra

@Entity(tableName = "challenge_active_time", inheritSuperIndices = true)
class ActiveTimeChallengeEntity(
    entryId: Long,
    isCompleted: Boolean,
    var activeTimeInMinutes: Int,
    val requiredActiveTimeInMinutes: Int
) : ChallengeEntryExtra(entryId, isCompleted)