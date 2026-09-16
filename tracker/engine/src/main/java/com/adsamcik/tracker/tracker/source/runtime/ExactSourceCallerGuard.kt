package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.api.SourceCallerGuard
import com.adsamcik.tracker.tracker.api.SourceCallerGuardInput
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.evaluateSourceCallerGuard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime/broker integration point. The caller supplies one transactionally read current authority
 * snapshot and, for FGS/restart/recovery, the exact durably restored prior acceptance. This class
 * performs no provider or demand mutation.
 */
@Singleton
class ExactSourceCallerGuard @Inject constructor() : SourceCallerGuard {
	override fun accept(input: SourceCallerGuardInput): SourceCallerGuardResult =
		evaluateSourceCallerGuard(input)
}
