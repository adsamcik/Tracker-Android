package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity

/**
 * Parent-owned CAS bridge for ambient radio mutations.
 *
 * An implementation must keep [identity] current for the complete [mutation] call, including the
 * broker Room transaction, physical-provider reconciliation, and exact-attempt compensation.
 * Lease replacement or cancellation must wait, or cause [Stale] before [mutation] starts. A
 * one-shot check that releases its store lock before invoking [mutation] does not satisfy this
 * contract.
 */
interface AmbientRadioMutationLeaseGuard {
	suspend fun <T> mutateIfCurrent(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T>
}

sealed interface AmbientRadioLeaseMutation<out T> {
	data class Applied<T>(val value: T) : AmbientRadioLeaseMutation<T>
	data object Stale : AmbientRadioLeaseMutation<Nothing>
}

/** Safe until the parent purpose-availability store binds its atomic lease bridge. */
object RejectingAmbientRadioMutationLeaseGuard : AmbientRadioMutationLeaseGuard {
	override suspend fun <T> mutateIfCurrent(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Stale
}
