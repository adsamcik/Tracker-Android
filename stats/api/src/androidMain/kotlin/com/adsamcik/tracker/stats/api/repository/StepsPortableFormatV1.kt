package com.adsamcik.tracker.stats.api.repository

/** Source-compatible API names for the shared, source-specific portable Steps wire representation. */
typealias StepsPortableFormatV1 = com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1
typealias PortableStepsIdentityKind = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsIdentityKind
typealias PortableStepsOpaqueIdentity = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
typealias PortableStepsDigest = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDigest
typealias PortableStepsDeletionScopeDigest =
	com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDeletionScopeDigest
typealias PortableStepsSessionMode = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSessionMode
typealias PortableStepsSource = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSource
typealias PortableStepsPurpose = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsPurpose
typealias PortableStepsCaptureCoverage = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
typealias PortableStepsProviderCoverage = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
typealias PortableStepsFactCoverage = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
typealias PortableStepsManifestV1 = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsManifestV1
typealias PortableStepsCompletenessV1 = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
typealias PortableStepsFactV1 = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
typealias PortableStepsRunV1 = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
typealias PortableStepsEntryV1 = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
typealias PortableStepsIntegrity = com.adsamcik.tracker.shared.model.steps.portable.PortableStepsIntegrity

/** Existing API ordering; shares the canonical portable Steps implementation. */
val PORTABLE_STEPS_FACT_ORDER = com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_FACT_ORDER

/** Existing API ordering; shares the canonical portable Steps implementation. */
val PORTABLE_STEPS_RUN_ORDER = com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_RUN_ORDER

/** Existing API ordering; shares the canonical portable Steps implementation. */
val PORTABLE_STEPS_ENTRY_ORDER = com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_ENTRY_ORDER
