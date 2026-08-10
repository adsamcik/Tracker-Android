package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.projection.LateCorrectionPolicy

data class ConsumerMigration(
	val id: String,
	val currentInput: String,
	val targetInput: String,
	val canonicalWriter: CanonicalWriter,
	val stableOutputIdentity: String,
	val checkpointStore: String,
	val lateCorrectionPolicy: LateCorrectionPolicy,
	val retirementGate: String,
)

enum class CanonicalWriter { LEGACY, EVENT_PROJECTION }

class ConsumerMigrationRegistry(entries: List<ConsumerMigration>) {
	private val byId = entries.associateBy(ConsumerMigration::id)

	init {
		require(byId.size == entries.size) { "Consumer migration IDs must be unique" }
		require(entries.all { it.id.isNotBlank() && it.stableOutputIdentity.isNotBlank() })
	}

	fun entry(id: String): ConsumerMigration = requireNotNull(byId[id]) {
		"Consumer $id is not registered for source-event migration"
	}

	fun entries(): Collection<ConsumerMigration> = byId.values
}

