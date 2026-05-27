package com.adsamcik.tracker.game.challenge.data

import android.content.Context
import com.adsamcik.tracker.game.challenge.catalog.CatalogBackedProcessor
import com.adsamcik.tracker.game.challenge.catalog.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor
import com.adsamcik.tracker.shared.base.Time

/**
 * Runtime representation of an active challenge.
 * Pairs the database entity with its processor for type-specific logic.
 * Replaces the old generic ChallengeInstance<ExtraData, Instance> hierarchy.
 */
data class ChallengeInstanceNew(
	val entity: ChallengeEntity,
	val processor: ChallengeProcessor,
) {
	val progress: Double
		get() = if (entity.requiredValue > 0.0) {
			(entity.currentValue / entity.requiredValue).coerceIn(0.0, 1.0)
		} else {
			0.0
		}

	val isCompleted: Boolean get() = entity.isCompleted

	val isExpired: Boolean get() = entity.endTime <= Time.nowMillis

	fun getTitle(context: Context): String = context.getString(processor.titleRes)

	fun getDescription(context: Context): String = processor.formatDescription(context, entity)

	companion object {
		/**
		 * Build an instance from an entity + catalog definition. Used by
		 * [com.adsamcik.tracker.game.challenge.engine.ChallengeEngine] which is
		 * catalog-driven and doesn't need the legacy `ChallengeProcessor` map.
		 *
		 * Internally pairs the entity with a no-op shim processor
		 * ([CatalogBackedProcessor]) so existing UI code that reads
		 * `instance.processor.titleRes` keeps working.
		 */
		fun fromDefinition(
			entity: ChallengeEntity,
			def: ChallengeDefinition,
		): ChallengeInstanceNew {
			return ChallengeInstanceNew(entity, CatalogBackedProcessor(def))
		}
	}
}
