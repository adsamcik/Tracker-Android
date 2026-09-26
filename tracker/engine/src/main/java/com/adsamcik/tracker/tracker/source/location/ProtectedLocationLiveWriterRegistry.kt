package com.adsamcik.tracker.tracker.source.location

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** The service owns the writer lifecycle; stale teardown cannot detach a replacement. */
@Singleton
internal class ProtectedLocationLiveWriterRegistry @Inject constructor() {
	private val writer = AtomicReference<ProtectedLocationCanonicalWriter?>()
	fun current(): ProtectedLocationCanonicalWriter? = writer.get()
	fun attach(value: ProtectedLocationCanonicalWriter) {
		check(writer.compareAndSet(null, value) || writer.get() === value) {
			"A protected Location live writer is already attached"
		}
	}
	fun detach(value: ProtectedLocationCanonicalWriter) { writer.compareAndSet(value, null) }
}
