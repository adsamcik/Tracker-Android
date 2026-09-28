package com.adsamcik.tracker.tracker.source.location

import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProtectedLocationLiveWriterRegistryTest {
	@Test fun `stale teardown cannot detach a replacement writer`() {
		val registry = ProtectedLocationLiveWriterRegistry()
		val first = ProtectedLocationCanonicalWriter { _, _ -> ProtectedLocationCanonicalWriteResult.Committed }
		val next = ProtectedLocationCanonicalWriter { _, _ -> ProtectedLocationCanonicalWriteResult.Committed }
		registry.attach(first)
		registry.attach(first)
		assertFailsWith<IllegalStateException> { registry.attach(next) }
		registry.detach(first)
		registry.attach(next)
		registry.detach(first)
		assertSame(next, registry.current())
		registry.detach(next)
		assertNull(registry.current())
	}
}
