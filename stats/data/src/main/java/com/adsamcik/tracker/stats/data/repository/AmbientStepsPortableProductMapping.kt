package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause

/** Converts retained portable coverage limitations into the shared Ambient day product causes. */
internal fun PortableAmbientStepsDayV1.toAmbientStepsProductCauses(): Set<AmbientStepsDayCause> =
	buildSet {
	if (coverage == PortableAmbientStepsCoverage.PARTIAL) {
		add(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL)
	}
	partialCauses.forEach { cause ->
		when (cause) {
			PortableAmbientStepsPartialCause.EXPLICIT_GAP ->
				add(AmbientStepsDayCause.AMBIENT_GAP)
			PortableAmbientStepsPartialCause.RETENTION,
			PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY,
			-> add(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL)
		}
	}
}
