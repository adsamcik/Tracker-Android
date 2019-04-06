package com.adsamcik.signalcollector.game.challenge.data.instance

import androidx.room.ColumnInfo
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.progress.ChallengeProgressData

abstract class ChallengeInstance<ProgressData : ChallengeProgressData>(difficulty: ChallengeDifficulty,
                                                                       name: String,
                                                                       descriptionTemplate: String,
                                                                       startTime: Long,
                                                                       endTime: Long,
                                                                       @ColumnInfo(name = "progress_data")
                                                                       val progressData: ProgressData) : Challenge(difficulty, name, descriptionTemplate, startTime, endTime)