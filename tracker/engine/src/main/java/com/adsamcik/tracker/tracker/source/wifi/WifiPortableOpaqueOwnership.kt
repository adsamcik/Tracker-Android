package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind

internal data class WifiPortableOpaqueOwner(
	val kind: PortableWifiIdentityKind,
	val entryIdentity: String,
	val runIdentity: String? = null,
	val deletionScopeDigest: String? = null,
)

/** Complete entry/run/observation/scope ownership for one authenticated portable hierarchy. */
internal class WifiPortableOpaqueOwnership(entry: PortableCapturedWifiEntryV1) {
	val identityOwners: Map<String, WifiPortableOpaqueOwner> = buildMap {
		val entryIdentity = entry.identity.value
		put(
			entryIdentity,
			WifiPortableOpaqueOwner(PortableWifiIdentityKind.LOGICAL_ENTRY, entryIdentity),
		)
		entry.runs.forEach { run ->
			val runIdentity = run.identity.value
			put(
				runIdentity,
				WifiPortableOpaqueOwner(
					PortableWifiIdentityKind.PHYSICAL_RUN,
					entryIdentity,
					runIdentity,
					run.deletionScopeDigest.value,
				),
			)
			run.observations.forEach { observation ->
				put(
					observation.identity.value,
					WifiPortableOpaqueOwner(
						PortableWifiIdentityKind.OBSERVATION,
						entryIdentity,
						runIdentity,
					),
				)
			}
		}
	}

	val scopeOwners: Map<String, WifiPortableOpaqueOwner> = entry.runs.associate { run ->
		run.deletionScopeDigest.value to WifiPortableOpaqueOwner(
			PortableWifiIdentityKind.PHYSICAL_RUN,
			entry.identity.value,
			run.identity.value,
			run.deletionScopeDigest.value,
		)
	}

	val allValues: Set<String> = identityOwners.keys + scopeOwners.keys

	init {
		require(identityOwners.size == 1 + entry.runs.size + entry.runs.sumOf { it.observations.size })
		require(scopeOwners.size == entry.runs.size)
		require(identityOwners.keys.none { it in scopeOwners })
	}
}

internal class WifiPortableOpaqueOwnershipSet private constructor(
	val identityOwners: Map<String, WifiPortableOpaqueOwner>,
	val scopeOwners: Map<String, WifiPortableOpaqueOwner>,
	val valuesByEntry: Map<String, Set<String>>,
) {
	val allValues: Set<String> = identityOwners.keys + scopeOwners.keys

	companion object {
		fun from(entries: Collection<PortableCapturedWifiEntryV1>): WifiPortableOpaqueOwnershipSet? {
			val identities = linkedMapOf<String, WifiPortableOpaqueOwner>()
			val scopes = linkedMapOf<String, WifiPortableOpaqueOwner>()
			val valuesByEntry = linkedMapOf<String, Set<String>>()
			for (entry in entries) {
				val candidate = runCatching { WifiPortableOpaqueOwnership(entry) }.getOrNull()
					?: return null
				if (candidate.identityOwners.keys.any { it in scopes } ||
					candidate.scopeOwners.keys.any { it in identities } ||
					candidate.identityOwners.any { (identity, owner) ->
						identities[identity]?.let { it != owner } == true
					} || candidate.scopeOwners.any { (scope, owner) ->
						scopes[scope]?.let { it != owner } == true
					}
				) return null
				identities.putAll(candidate.identityOwners)
				scopes.putAll(candidate.scopeOwners)
				valuesByEntry[entry.identity.value] = candidate.allValues
			}
			return WifiPortableOpaqueOwnershipSet(identities, scopes, valuesByEntry)
		}
	}
}
