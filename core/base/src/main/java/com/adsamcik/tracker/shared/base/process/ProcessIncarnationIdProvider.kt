package com.adsamcik.tracker.shared.base.process

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies one non-persistent identity for the lifetime of the current application process. */
@Singleton
class ProcessIncarnationIdProvider internal constructor(
	identityFactory: () -> UUID,
) {
	@Inject
	constructor() : this(UUID::randomUUID)

	private val identity = identityFactory().toString()

	fun current(): String = identity
}
