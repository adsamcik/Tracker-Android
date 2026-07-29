package com.adsamcik.tracker.osm.intake

/**
 * Every independently bounded resource charged by safe PBF intake.
 *
 * A future strict reader and persistence coordinator must reserve from the
 * same [PbfResourceLedger] before an attacker-controlled length can allocate,
 * decompress, retain, bind, or write that resource. The foundation itself only
 * charges the fixed snapshot buffer plus source/snapshot bytes.
 */
enum class PbfResource {
	SOURCE_BYTES,
	PRIVATE_SNAPSHOT_DISK_BYTES,
	MANAGED_MEMORY_BYTES,
	RETAINED_GRAPH_BYTES,
	OUTPUT_BYTES,
	WORK_UNITS,
}

/**
 * Explicit resource bounds for one intake job.
 *
 * The values intentionally have no production defaults: choosing them requires
 * the agreed lowest-device and ABI benchmark matrix. All dimensions are
 * required so a future caller cannot accidentally leave a resource unbounded.
 */
data class PbfResourceLimits(
	val sourceBytes: Long,
	val privateSnapshotDiskBytes: Long,
	val managedMemoryBytes: Long,
	val retainedGraphBytes: Long,
	val outputBytes: Long,
	val workUnits: Long,
) {
	init {
		listOf(
			sourceBytes,
			privateSnapshotDiskBytes,
			managedMemoryBytes,
			retainedGraphBytes,
			outputBytes,
			workUnits,
		).forEach { limit ->
			require(limit >= 0L) { "PBF resource limits must be non-negative" }
		}
	}

	fun limitFor(resource: PbfResource): Long = when (resource) {
		PbfResource.SOURCE_BYTES -> sourceBytes
		PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES -> privateSnapshotDiskBytes
		PbfResource.MANAGED_MEMORY_BYTES -> managedMemoryBytes
		PbfResource.RETAINED_GRAPH_BYTES -> retainedGraphBytes
		PbfResource.OUTPUT_BYTES -> outputBytes
		PbfResource.WORK_UNITS -> workUnits
	}
}

/**
 * Thread-safe, all-or-nothing reservation accounting for one PBF intake job.
 *
 * Reservations are held by a [PbfResourceLease]. A request touching multiple
 * resources either succeeds for every dimension or leaves the ledger unchanged;
 * callers can therefore reserve output/disk/work together before a write.
 */
class PbfResourceLedger(
	private val limits: PbfResourceLimits,
) {
	private val lock = Any()
	private val used = PbfResource.entries.associateWith { 0L }.toMutableMap()

	fun openLease(): PbfResourceLease = PbfResourceLease(this)

	fun limitFor(resource: PbfResource): Long = limits.limitFor(resource)

	fun used(resource: PbfResource): Long = synchronized(lock) { used.getValue(resource) }

	fun remaining(resource: PbfResource): Long = synchronized(lock) {
		limits.limitFor(resource) - used.getValue(resource)
	}

	internal fun reserve(lease: PbfResourceLease, requests: Map<PbfResource, Long>) {
		synchronized(lock) {
			checkLeaseOpen(lease)
			requests.forEach { (resource, amount) ->
				require(amount >= 0L) { "PBF reservation amounts must be non-negative" }
				if (amount == 0L) return@forEach
				val next = checkedAdd(used.getValue(resource), amount, resource)
				if (next > limits.limitFor(resource)) {
					throw PbfIntakeFailure.ResourceLimitExceeded(
						resource = resource,
						limit = limits.limitFor(resource),
						requested = amount,
					)
				}
			}

			// Mutate only after every dimension has passed. This is the atomic
			// admission point for a combined memory/disk/output/work reservation.
			requests.forEach { (resource, amount) ->
				if (amount == 0L) return@forEach
				used[resource] = used.getValue(resource) + amount
				lease.held[resource] = lease.held.getValue(resource) + amount
			}
		}
	}

	internal fun release(lease: PbfResourceLease, resource: PbfResource, amount: Long) {
		require(amount >= 0L) { "PBF release amounts must be non-negative" }
		synchronized(lock) {
			checkLeaseOpen(lease)
			val held = lease.held.getValue(resource)
			require(amount <= held) { "PBF lease cannot release more than it reserved" }
			lease.held[resource] = held - amount
			used[resource] = used.getValue(resource) - amount
		}
	}

	internal fun reserved(lease: PbfResourceLease, resource: PbfResource): Long = synchronized(lock) {
		checkLeaseOpen(lease)
		lease.held.getValue(resource)
	}

	internal fun close(lease: PbfResourceLease) {
		synchronized(lock) {
			if (lease.closed) return
			check(lease.owner === this) { "PBF lease belongs to a different ledger" }
			PbfResource.entries.forEach { resource ->
				used[resource] = used.getValue(resource) - lease.held.getValue(resource)
				lease.held[resource] = 0L
			}
			lease.closed = true
		}
	}

	private fun checkLeaseOpen(lease: PbfResourceLease) {
		check(lease.owner === this) { "PBF lease belongs to a different ledger" }
		check(!lease.closed) { "PBF resource lease is already closed" }
	}

	private fun checkedAdd(current: Long, amount: Long, resource: PbfResource): Long = try {
		Math.addExact(current, amount)
	} catch (_: ArithmeticException) {
		throw PbfIntakeFailure.ResourceLimitExceeded(
			resource = resource,
			limit = limits.limitFor(resource),
			requested = amount,
		)
	}
}

/**
 * A releasable share of one [PbfResourceLedger].
 *
 * The lease is intentionally small and has no implicit allocations. Consumers
 * call [reserveAll] before the operation that consumes the charged resource,
 * then retain the lease for exactly as long as those resources remain live.
 */
class PbfResourceLease internal constructor(
	internal val owner: PbfResourceLedger,
) : AutoCloseable {
	internal val held = PbfResource.entries.associateWith { 0L }.toMutableMap()
	internal var closed: Boolean = false

	fun reserve(resource: PbfResource, amount: Long) {
		reserveAll(mapOf(resource to amount))
	}

	fun reserveAll(requests: Map<PbfResource, Long>) {
		owner.reserve(this, requests)
	}

	fun release(resource: PbfResource, amount: Long) {
		owner.release(this, resource, amount)
	}

	fun reserved(resource: PbfResource): Long = owner.reserved(this, resource)

	override fun close() {
		owner.close(this)
	}
}
