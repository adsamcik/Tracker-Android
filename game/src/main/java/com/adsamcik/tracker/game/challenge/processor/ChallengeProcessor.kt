package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import androidx.annotation.StringRes
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession

/**
 * UI-facing read shim for an active challenge.
 *
 * Historically this interface drove the imperative challenge pipeline (one impl per
 * [ChallengeType], multi-bound via Hilt). After p2-3 the [com.adsamcik.tracker.game.challenge.engine.ChallengeEngine]
 * is catalog-driven — defaults, metrics and progress live on
 * [com.adsamcik.tracker.game.challenge.catalog.ChallengeDefinition].
 *
 * One implementation remains:
 *  - [com.adsamcik.tracker.game.challenge.catalog.CatalogBackedProcessor] — generic
 *    catalog adapter consumed by UI code that reads `instance.processor.titleRes`
 *    and `instance.processor.formatDescription`.
 *
 * Do not add new implementations. New challenge types belong in `ChallengeCatalog`.
 * Phase 6 will collapse this interface into the UI layer directly.
 */
interface ChallengeProcessor {
	/** The challenge type this processor handles. */
	val type: ChallengeType

	/** String resource for the challenge title. */
	@get:StringRes
	val titleRes: Int

	/** Format the challenge description using entity data. */
	fun formatDescription(context: Context, entity: ChallengeEntity): String

	/**
	 * Mutate the entity in response to a finished session.
	 *
	 * Default no-op. [com.adsamcik.tracker.game.challenge.catalog.CatalogBackedProcessor]
	 * does not override this — catalog-driven progress is recomputed from the
	 * `WindowedMetricsProvider` over the challenge's full interval window inside
	 * [com.adsamcik.tracker.game.challenge.engine.ChallengeEngine].
	 */
	suspend fun processEntity(
		context: Context,
		entity: ChallengeEntity,
		session: TrackerSession,
	): ChallengeEntity = entity
}
