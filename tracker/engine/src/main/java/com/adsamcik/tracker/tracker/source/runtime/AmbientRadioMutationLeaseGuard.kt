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

	/**
	 * Allows only authority-reducing work for the exact retained lease after its activation
	 * operation has completed. Implementations must reject a replaced lease.
	 */
	suspend fun <T> mutateReductionIfRetained(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = mutateIfCurrent(identity, mutation)
}

interface TrackingPurposeMutationLeaseGuard : AmbientRadioMutationLeaseGuard {
	suspend fun <T> mutateAutomaticIfCurrent(
		identity: com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T>

	suspend fun <T> mutateAmbientIfCurrent(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T>
}

sealed interface AmbientRadioLeaseMutation<out T> {
	data class Applied<T>(val value: T) : AmbientRadioLeaseMutation<T>
	data object Stale : AmbientRadioLeaseMutation<Nothing>
}

/** Safe until the parent purpose-availability store binds its atomic lease bridge. */
object RejectingAmbientRadioMutationLeaseGuard : TrackingPurposeMutationLeaseGuard {
	override suspend fun <T> mutateIfCurrent(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Stale

	override suspend fun <T> mutateAutomaticIfCurrent(
		identity: com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Stale

	override suspend fun <T> mutateAmbientIfCurrent(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Stale

	override suspend fun <T> mutateReductionIfRetained(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Stale
}
