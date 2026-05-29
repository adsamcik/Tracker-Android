package com.adsamcik.tracker.game.challenge.catalog

import android.content.Context
import com.adsamcik.tracker.shared.base.database.data.ChallengeEntity
import com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor

/**
 * Adapts a [ChallengeDefinition] to the slimmed [ChallengeProcessor] interface so
 * UI code that reads `instance.processor.titleRes` / `formatDescription` keeps
 * working without changes while the engine-driven path is in place.
 *
 * Pure read shim — [processEntity] is inherited as a no-op because catalog-driven
 * challenges recompute progress from the `WindowedMetricsProvider` window inside
 * [com.adsamcik.tracker.game.challenge.engine.ChallengeEngine].
 */
internal class CatalogBackedProcessor(
	private val def: ChallengeDefinition,
) : ChallengeProcessor {
	override val type get() = def.type
	override val titleRes get() = def.titleRes
	override fun formatDescription(context: Context, entity: ChallengeEntity): String =
		context.getString(def.descriptionTemplateRes, entity.requiredValue.toInt().toString())
}
