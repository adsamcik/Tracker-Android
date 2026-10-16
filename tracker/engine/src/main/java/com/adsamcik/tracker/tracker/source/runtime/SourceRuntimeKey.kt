package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.MapKey

/** Hilt map key selecting the single registered runtime owner for a source kind. */
@MapKey
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class SourceRuntimeKey(val value: SourceKind)
