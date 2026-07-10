package com.adsamcik.tracker.map.ui

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

internal class AppendOnlyPathMapper<T, R> {
	private var previousSource: List<T>? = null
	private var mapped: PersistentList<R> = persistentListOf()

	fun update(source: List<T>, transform: (T) -> R): PersistentList<R> {
		if (source.isEmpty()) {
			previousSource = null
			mapped = persistentListOf()
			return mapped
		}

		val previous = previousSource
		val isAppendOnly = previous != null &&
			mapped.size == previous.size &&
			source.size >= previous.size &&
			source.first() == previous.first() &&
			source[previous.lastIndex] == previous.last()

		mapped = if (isAppendOnly) {
			var updated = mapped
			for (index in previous.size until source.size) {
				updated = updated.adding(transform(source[index]))
			}
			updated
		} else {
			source.map(transform).toPersistentList()
		}
		previousSource = source
		return mapped
	}
}
