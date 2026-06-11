package com.adsamcik.tracker.stats.api.rule

interface RuleRegistry {
	suspend fun allInstances(): List<RuleInstance>
	suspend fun instancesAffectedByTables(dirtyTables: Set<String>): List<RuleInstance>
}
