package com.adsamcik.tracker.game.challenge.processor

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import dagger.MapKey

/**
 * Hilt map key for [ChallengeProcessor] multibinding.
 */
@MapKey
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ChallengeTypeKey(val value: ChallengeType)
