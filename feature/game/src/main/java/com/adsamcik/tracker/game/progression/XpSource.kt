package com.adsamcik.tracker.game.progression

/**
 * Source category for [com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity]
 * rows. Combined with a per-event id it forms the ledger's unique key so the
 * same event can never be double-credited.
 */
internal enum class XpSource {
	SESSION,
	MINI_GAME,
	GOAL,
}
