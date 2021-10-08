package com.adsamcik.tracker.game.fragment.recycler.data

import com.adsamcik.tracker.game.challenge.adapter.ChallengeAdapter
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.ListRecyclerData

/**
 * Data for challenges in Game recycler
 */
class ChallengeRecyclerData(
		title: Int,
		dataAdapter: ChallengeAdapter
) : ListRecyclerData<ChallengeAdapter>(
		title,
		dataAdapter
)

