package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.MapKey

@MapKey
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class SourceRuntimeKey(val value: SourceKind)
