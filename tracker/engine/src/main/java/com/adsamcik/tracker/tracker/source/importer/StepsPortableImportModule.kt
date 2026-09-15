package com.adsamcik.tracker.tracker.source.importer

import com.adsamcik.tracker.stats.api.repository.ImportPortableSteps
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Inactive until a product file importer requests the source-local command. */
@Module
@InstallIn(SingletonComponent::class)
internal interface StepsPortableImportModule {
	@Binds
	@Singleton
	fun bindImportPortableSteps(implementation: RoomImportPortableSteps): ImportPortableSteps
}
