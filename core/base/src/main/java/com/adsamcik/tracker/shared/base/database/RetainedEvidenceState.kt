package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState

/** Historical-row authority for transaction-owned maintenance; never accepts an old operation. */
internal fun SupportSQLiteDatabase.retainedEvidenceState(expectedCurrentEpoch: Long): SourceEvidenceState =
    query("SELECT * FROM source_evidence_state WHERE id = 1").use { cursor ->
        check(cursor.moveToFirst()) { "Saved-data maintenance requires source evidence" }
        val epoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch"))
        check(epoch == expectedCurrentEpoch) { "Saved-data maintenance belongs to an old dataset" }
        val aliases = cursor.getColumnIndex("restored_data_epochs").takeIf { it >= 0 }
            ?.let(cursor::getString).orEmpty()
        SourceEvidenceState(collectedDataEpoch = epoch, restoredDataEpochs = aliases)
    }

internal fun SupportSQLiteDatabase.acceptsRetainedEpoch(epoch: Long, expectedCurrentEpoch: Long): Boolean =
    retainedEvidenceState(expectedCurrentEpoch).acceptsRetainedEpoch(epoch)
